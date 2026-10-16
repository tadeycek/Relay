"""Encrypted recovery channel. The desktop holds the owner signing key; no web server is needed."""
from __future__ import annotations

import asyncio
from datetime import timedelta
import json
import os
import threading
import time
from uuid import uuid4

PROTOCOL = "calculator_owner_v1"
RELAYS = ["wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net"]


def process_report(database, sender: str, body: dict) -> dict:
    if not isinstance(body, dict) or set(body) != {"protocol", "type", "device_id", "device_token", "events", "diagnostics"}:
        raise ValueError("Invalid recovery report")
    if body["protocol"] != PROTOCOL or body["type"] != "report":
        raise ValueError("Unknown recovery protocol")
    if len(sender) != 64 or any(c not in "0123456789abcdef" for c in sender):
        raise ValueError("Invalid recovery sender")
    diagnostic = body["diagnostics"]
    if not isinstance(diagnostic, dict) or type(diagnostic.get("revision")) is not int or not 0 <= diagnostic["revision"] <= 2**63 - 1:
        raise ValueError("Invalid diagnostic revision")
    events = body["events"]
    if not isinstance(events, list) or len(events) > 100:
        raise ValueError("Invalid access events")
    device_id = body["device_id"]
    # The private installation token also preserves the binding when upgrading an existing local DB.
    with database.lock:
        database.enroll(device_id, body["device_token"])
        database.bind_relay(device_id, sender)
        if events:
            database.record(device_id, events)
        database.relay_diagnostics(device_id, diagnostic)
        return state_body(database, device_id)


def state_body(database, device_id: str) -> dict:
    state = database.state(device_id)
    return {"protocol": PROTOCOL, "type": "state", "device_id": device_id,
            "acknowledged_sequence": state["last_sequence"],
            "unlock_through_sequence": state["unlock_through_sequence"],
            "diagnostics_revision": database.relay_revision(device_id)}


class OwnerRelayClient:
    def __init__(self, database, secret: dict, cursor_path):
        self.database = database
        self.secret = secret
        self.cursor_path = cursor_path
        self.status = "Encrypted recovery: connecting to relays"
        self.stopping = threading.Event()
        self.thread = None

    def start(self):
        self.thread = threading.Thread(target=lambda: asyncio.run(self._run()), daemon=True)
        self.thread.start()

    def stop(self):
        self.stopping.set()
        if self.thread:
            self.thread.join(timeout=3)

    async def _run(self):
        from nostr_sdk import ClientBuilder, EventBuilder, Filter, HandleNotification, Keys, Kind, NostrSigner, PublicKey, RelayUrl, Timestamp, UnwrappedGift
        keys = Keys.parse(self.secret["owner_secret_hex"])
        signer = NostrSigner.keys(keys)
        urls = [RelayUrl.parse(url) for url in RELAYS]
        seen = set()
        last_sent = {}
        client = None
        last_maintenance = 0.0
        def maintenance():
            nonlocal last_maintenance
            if time.monotonic() - last_maintenance >= 60:
                self.database.purge_expired()
                last_maintenance = time.monotonic()
        try:
            seen_at = min(int(time.time()), max(0, int(self.cursor_path.read_text())))
        except (OSError, ValueError):
            seen_at = int(time.time())

        async def send_state(device_id, pubkey):
            body = state_body(self.database, device_id)
            envelope = {"v": 1, "id": str(uuid4()), "ts": int(time.time() * 1000), "body": json.dumps(body, separators=(",", ":"))}
            receiver = PublicKey.parse(pubkey)
            rumor = EventBuilder.private_msg_rumor(receiver, json.dumps(envelope, separators=(",", ":"))).build(keys.public_key())
            result = await asyncio.wait_for(client.gift_wrap_to(urls, receiver, rumor, []), 15)
            if not result.success:
                raise ConnectionError("No relay accepted the recovery response")
            last_sent[device_id] = (body["unlock_through_sequence"], time.monotonic())

        class Handler(HandleNotification):
            async def handle_msg(self, relay_url, msg):
                pass

            async def handle(self, relay_url, subscription_id, event):
                nonlocal seen_at
                event_id = event.id().to_hex()
                if event_id in seen:
                    return
                seen.add(event_id)
                if len(seen) > 10_000:
                    seen.clear()
                    seen.add(event_id)
                try:
                    gift = await UnwrappedGift.from_gift_wrap(signer, event)
                    rumor = gift.rumor()
                    sender = gift.sender().to_hex()
                    if rumor.kind().as_u16() != 14 or rumor.author().to_hex() != sender:
                        return
                    content = rumor.content()
                    if len(content) > 20_000:
                        return
                    envelope = json.loads(content)
                    if envelope.get("v") != 1 or not isinstance(envelope.get("body"), str) or len(envelope["body"]) > 16_384:
                        return
                    body = json.loads(envelope["body"])
                    process_report(self_outer.database, sender, body)
                    seen_at = max(seen_at, int(time.time()))
                    cursor_temp = self_outer.cursor_path.with_name(f"relay-cursor.{os.getpid()}.tmp")
                    cursor_temp.write_text(str(seen_at))
                    cursor_temp.chmod(0o600)
                    cursor_temp.replace(self_outer.cursor_path)
                    await send_state(body["device_id"], sender)
                except Exception:
                    # Never print report content, installation tokens or network addresses.
                    return

        self_outer = self
        while not self.stopping.is_set():
            maintenance()
            notifications = None
            try:
                client = ClientBuilder().signer(signer).build()
                for url in urls:
                    await client.add_relay(url)
                await client.connect()
                await client.wait_for_connection(timedelta(seconds=10))
                await client.subscribe(Filter().kind(Kind(1059)).pubkey(keys.public_key())
                                       .since(Timestamp.from_secs(max(0, seen_at - 3 * 86400))))
                notifications = asyncio.create_task(client.handle_notifications(Handler()))
                while not self.stopping.is_set() and not notifications.done():
                    maintenance()
                    relays = await client.relays()
                    count = sum(relay.is_connected() for relay in relays.values())
                    self.status = f"Encrypted recovery: {count}/{len(urls)} relays connected · no web server"
                    for row in self.database.pending_relay_recoveries():
                        previous = last_sent.get(row["id"])
                        if previous is None or previous[0] != row["unlock_through_sequence"] or time.monotonic() - previous[1] >= 5:
                            try:
                                await send_state(row["id"], row["pubkey"])
                            except Exception:
                                pass
                    await asyncio.sleep(1)
                if notifications.done():
                    await notifications
            except Exception as error:
                self.status = f"Encrypted recovery: disconnected ({type(error).__name__}); retrying"
            finally:
                if notifications:
                    notifications.cancel()
                if client:
                    await client.disconnect()
            if not self.stopping.is_set():
                await asyncio.sleep(3)
