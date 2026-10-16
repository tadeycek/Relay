import json
from pathlib import Path
import secrets
import tempfile
import threading
import unittest
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from owner_app import DIAGNOSTIC_TTL, OwnerDatabase, OwnerServer


class OwnerServiceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.database = OwnerDatabase(Path(self.directory.name) / "owner.sqlite3")
        self.invite = secrets.token_urlsafe(32)
        self.server = OwnerServer(("127.0.0.1", 0), self.database, self.invite)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.device = str(uuid4())
        self.token = secrets.token_urlsafe(32)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.directory.cleanup()

    def request(self, path, token, body=None, headers=None):
        data = json.dumps(body).encode() if body is not None else None
        request = Request(f"http://127.0.0.1:{self.server.server_port}{path}", data=data,
                          headers={"Authorization": "Bearer " + token, "Content-Type": "application/json", **(headers or {})})
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            try: return error.code, json.load(error)
            finally: error.close()

    def enroll(self):
        status, _ = self.request("/v1/enroll", self.invite, {"device_id": self.device, "device_token": self.token})
        self.assertEqual(200, status)

    def events(self, values):
        return self.request(f"/v1/devices/{self.device}/events", self.token, {"events": values})

    def test_enrollment_and_device_tokens_are_required(self):
        body = {"device_id": self.device, "device_token": self.token}
        self.assertEqual(401, self.request("/v1/enroll", "wrong", body)[0])
        self.enroll()
        self.assertEqual(401, self.request(f"/v1/devices/{self.device}/state", "wrong")[0])
        self.assertEqual(200, self.request(f"/v1/devices/{self.device}/state", self.token)[0])

    def test_registration_cannot_replace_an_installation_token(self):
        self.enroll()
        self.assertEqual(403, self.request("/v1/enroll", self.invite,
            {"device_id": self.device, "device_token": secrets.token_urlsafe(32)})[0])
        self.assertTrue(self.database.authenticated(self.device, self.token))

    def test_lockout_owner_recovery_and_acknowledgment(self):
        self.enroll()
        events = [{"seq": 1, "kind": "enrolled", "failures": 0},
                  {"seq": 2, "kind": "failed", "failures": 1},
                  {"seq": 3, "kind": "failed", "failures": 2},
                  {"seq": 4, "kind": "locked", "failures": 3}]
        self.assertEqual(200, self.events(events)[0])
        self.assertEqual(1, self.database.devices()[0]["locked"])
        self.database.clear_lockout(self.device)
        status, state = self.request(f"/v1/devices/{self.device}/state", self.token)
        self.assertEqual(200, status)
        self.assertEqual(4, state["unlock_through_sequence"])
        self.assertEqual(1, self.database.devices()[0]["recovery_pending"])
        self.events([{"seq": 5, "kind": "recovered", "failures": 0}])
        self.assertEqual(0, self.database.devices()[0]["recovery_pending"])

    def test_replayed_events_cannot_undo_owner_recovery(self):
        self.enroll()
        locked = [{"seq": 4, "kind": "locked", "failures": 3}]
        self.events(locked)
        self.database.clear_lockout(self.device)
        self.events(locked)
        self.assertEqual(0, self.database.devices()[0]["locked"])
        self.events([{"seq": 8, "kind": "locked", "failures": 3}])
        self.assertEqual(1, self.database.devices()[0]["locked"])
        self.assertLess(self.database.state(self.device)["unlock_through_sequence"], 8)

    def test_another_device_cannot_read_status(self):
        self.enroll()
        other = str(uuid4())
        self.database.enroll(other, secrets.token_urlsafe(32))
        self.assertEqual(401, self.request(f"/v1/devices/{other}/state", self.token)[0])

    def test_only_access_fields_are_accepted(self):
        self.enroll()
        response = self.events([{"seq": 1, "kind": "unlocked", "failures": 0, "message": "private"}])
        self.assertEqual(400, response[0])
        self.assertEqual(0, self.database.state(self.device)["last_sequence"])

    def test_no_raw_tokens_or_network_addresses_in_database(self):
        self.enroll()
        self.events([{"seq": 1, "kind": "enrolled", "failures": 0}])
        with self.database.connect() as db:
            row = dict(db.execute("SELECT * FROM devices").fetchone())
        self.assertNotEqual(self.token, row["token_hash"])
        self.assertFalse(any("ip" in key or "address" in key or "message" in key or "pin" in key for key in row))
        self.assertFalse(self.database.path.stat().st_mode & 0o077)
        self.assertIsNone(self.database.diagnostic(self.device))

    def report(self, **changes):
        return {"app_version": "1.0", "manufacturer": "Test", "model": "Test phone", "android_version": "15",
                "android_api": 35, "network": "wifi", "route": "tor",
                "permissions": {"camera": False, "location": False, "notifications": True}, **changes}

    def diagnostics(self, body, **kwargs):
        return self.request(f"/v1/devices/{self.device}/diagnostics", self.token, body, **kwargs)

    def test_diagnostics_require_authentication_and_explicit_choice(self):
        self.enroll()
        path = f"/v1/devices/{self.device}/diagnostics"
        body = {"enabled": True, "report": self.report()}
        self.assertEqual(401, self.request(path, "wrong", body)[0])
        self.assertEqual(400, self.diagnostics({"report": self.report()})[0])
        self.assertEqual(400, self.diagnostics({"enabled": 1, "report": self.report()})[0])
        self.assertIsNone(self.database.diagnostic(self.device))
        other = str(uuid4())
        self.database.enroll(other, secrets.token_urlsafe(32))
        self.assertEqual(401, self.request(f"/v1/devices/{other}/diagnostics", self.token, body)[0])

    def test_diagnostics_allowlist_and_type_validation(self):
        self.enroll()
        reports = [self.report(contacts=["private"]), self.report(android_api=True), self.report(network="ssid"),
                   self.report(model="spoof\nnew log entry"), self.report(model="x" * 97),
                   self.report(permissions={"camera": True, "location": False, "notifications": True, "contacts": True}),
                   self.report(permissions={"camera": "yes", "location": False, "notifications": True})]
        for report in reports:
            self.assertEqual(400, self.diagnostics({"enabled": True, "report": report})[0])
        self.assertEqual(400, self.diagnostics({"enabled": False, "report": self.report()})[0])
        self.assertIsNone(self.database.diagnostic(self.device))

    def test_report_stores_only_latest_and_uses_tcp_peer_not_forwarded_ip(self):
        self.enroll()
        self.assertEqual(200, self.diagnostics({"enabled": True, "report": self.report()},
            headers={"X-Forwarded-For": "203.0.113.25"})[0])
        self.assertEqual("127.0.0.1", self.database.diagnostic(self.device)["peer_address"])
        self.assertEqual(200, self.diagnostics({"enabled": True, "report": self.report(network="cellular")})[0])
        self.assertEqual("cellular", self.database.diagnostic(self.device)["report"]["network"])
        with self.database.connect() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM diagnostics").fetchone()[0])
        self.assertEqual(1, len(self.database.main_logs()))  # consent, not every report
        self.assertNotIn("127.0.0.1", json.dumps(self.database.main_logs()))

    def test_revocation_deletes_report_and_address_without_affecting_recovery(self):
        self.enroll()
        self.diagnostics({"enabled": True, "report": self.report()})
        self.assertEqual(200, self.diagnostics({"enabled": False})[0])
        self.assertIsNone(self.database.diagnostic(self.device))
        self.events([{"seq": 1, "kind": "locked", "failures": 3}])
        self.database.clear_lockout(self.device)
        self.assertEqual(1, self.database.state(self.device)["unlock_through_sequence"])
        kinds = {e["kind"] for e in self.database.main_logs()}
        self.assertTrue({"locked", "unblock_requested", "diagnostics_deleted"}.issubset(kinds))
        self.assertNotIn("Test phone", json.dumps(self.database.main_logs()))

    def test_expired_diagnostics_are_purged_and_access_history_is_preserved(self):
        self.enroll()
        self.events([{"seq": 1, "kind": "enrolled", "failures": 0}])
        self.diagnostics({"enabled": True, "report": self.report()})
        with self.database.connect() as db:
            db.execute("UPDATE diagnostics SET received_at=?", (time.time() - DIAGNOSTIC_TTL - 1,))
        self.server.last_maintenance = 0
        self.server.service_actions()
        self.assertIsNone(self.database.diagnostic(self.device))
        self.assertEqual("enrolled", self.database.history(self.device)[0]["kind"])

    def test_existing_database_is_migrated_without_losing_devices_or_events(self):
        self.enroll()
        self.events([{"seq": 1, "kind": "enrolled", "failures": 0}])
        with self.database.lock, self.database.connect() as db:
            db.execute("DROP TABLE diagnostics")
            db.execute("DROP TABLE owner_events")
        migrated = OwnerDatabase(self.database.path)
        self.assertTrue(migrated.authenticated(self.device, self.token))
        self.assertEqual(1, migrated.state(self.device)["last_sequence"])
        self.assertIsNone(migrated.diagnostic(self.device))


if __name__ == "__main__": unittest.main()
