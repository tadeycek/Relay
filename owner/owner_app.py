#!/usr/bin/env python3
"""Native Control Panel and encrypted relay recovery. HTTP helpers remain for regression tests."""
from __future__ import annotations

import argparse
from contextlib import contextmanager, nullcontext
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import UUID

ROOT = Path(__file__).resolve().parent.parent
KINDS = {"enrolled", "unlocked", "failed", "locked", "recovered"}
DIAGNOSTIC_TTL = 7 * 24 * 60 * 60
REPORT_FIELDS = {"app_version", "manufacturer", "model", "android_version", "android_api", "network", "route", "permissions"}


class OwnerDatabase:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = threading.RLock()
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, token_hash TEXT NOT NULL,
                    created_at REAL NOT NULL, last_activity REAL NOT NULL,
                    last_unlock REAL, failures INTEGER NOT NULL DEFAULT 0,
                    locked INTEGER NOT NULL DEFAULT 0, last_sequence INTEGER NOT NULL DEFAULT 0,
                    unlock_through_sequence INTEGER NOT NULL DEFAULT 0,
                    recovery_pending INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS events (
                    device_id TEXT NOT NULL, sequence INTEGER NOT NULL, kind TEXT NOT NULL,
                    failures INTEGER NOT NULL, received_at REAL NOT NULL,
                    PRIMARY KEY (device_id, sequence)
                );
                CREATE TABLE IF NOT EXISTS diagnostics (
                    device_id TEXT PRIMARY KEY, report TEXT NOT NULL,
                    peer_address TEXT NOT NULL, received_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS relay_devices (
                    device_id TEXT PRIMARY KEY, pubkey TEXT NOT NULL UNIQUE,
                    diagnostics_revision INTEGER NOT NULL DEFAULT -1
                );
                CREATE TABLE IF NOT EXISTS owner_events (
                    id INTEGER PRIMARY KEY, device_id TEXT NOT NULL,
                    kind TEXT NOT NULL, received_at REAL NOT NULL
                );
            """)
        path.chmod(0o600)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA secure_delete=ON")
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def token_hash(token: str) -> str:
        return hashlib.sha256(token.encode()).hexdigest()

    def enroll(self, device_id: str, token: str):
        if str(UUID(device_id)) != device_id or not 32 <= len(token) <= 128:
            raise ValueError("Invalid installation credentials")
        with self.lock, self.connect() as db:
            row = db.execute("SELECT token_hash FROM devices WHERE id=?", (device_id,)).fetchone()
            digest = self.token_hash(token)
            if row:
                if not hmac.compare_digest(row[0], digest):
                    raise PermissionError("Installation already exists")
            else:
                if db.execute("SELECT COUNT(*) FROM devices").fetchone()[0] >= 1000:
                    raise ValueError("Installation limit reached")
                now = time.time()
                db.execute("INSERT INTO devices(id,token_hash,created_at,last_activity) VALUES(?,?,?,?)",
                           (device_id, digest, now, now))

    def authenticated(self, device_id: str, token: str) -> bool:
        with self.connect() as db:
            row = db.execute("SELECT token_hash FROM devices WHERE id=?", (device_id,)).fetchone()
        return bool(row and hmac.compare_digest(row[0], self.token_hash(token)))

    def record(self, device_id: str, events: list) -> int:
        if not isinstance(events, list) or not 1 <= len(events) <= 100:
            raise ValueError("Invalid event batch")
        previous = 0
        for event in events:
            if not isinstance(event, dict) or set(event) != {"seq", "kind", "failures"}:
                raise ValueError("Only access-status fields are accepted")
            seq, kind, failures = event["seq"], event["kind"], event["failures"]
            if type(seq) is not int or seq <= previous or type(failures) is not int or kind not in KINDS:
                raise ValueError("Invalid access event")
            if failures not in range(4) or (kind == "locked" and failures != 3):
                raise ValueError("Invalid failure count")
            if kind in {"enrolled", "unlocked", "recovered"} and failures != 0:
                raise ValueError("Invalid success event")
            if kind == "failed" and failures not in (1, 2):
                raise ValueError("Invalid failed attempt")
            previous = seq
        with self.lock, self.connect() as db:
            last = db.execute("SELECT last_sequence FROM devices WHERE id=?", (device_id,)).fetchone()[0]
            for event in events:
                seq, kind, failures = event["seq"], event["kind"], event["failures"]
                if seq <= last:
                    continue
                now = time.time()
                db.execute("INSERT INTO events VALUES(?,?,?,?,?)", (device_id, seq, kind, failures, now))
                db.execute("UPDATE devices SET last_activity=?,last_sequence=?,failures=?,locked=? WHERE id=?",
                           (now, seq, failures, int(kind == "locked"), device_id))
                if kind in {"enrolled", "unlocked"}:
                    db.execute("UPDATE devices SET last_unlock=? WHERE id=?", (now, device_id))
                if kind in {"recovered", "unlocked"}:
                    db.execute("UPDATE devices SET recovery_pending=0 WHERE id=?", (device_id,))
                last = seq
            # Bound local history; keep the newest 100 access events per installation.
            db.execute("DELETE FROM events WHERE device_id=? AND sequence NOT IN "
                       "(SELECT sequence FROM events WHERE device_id=? ORDER BY sequence DESC LIMIT 100)",
                       (device_id, device_id))
            return last

    def state(self, device_id: str) -> dict:
        with self.connect() as db:
            row = db.execute("SELECT last_sequence,unlock_through_sequence FROM devices WHERE id=?", (device_id,)).fetchone()
        return dict(row)

    def clear_lockout(self, device_id: str):
        with self.lock, self.connect() as db:
            changed = db.execute("UPDATE devices SET unlock_through_sequence=last_sequence,locked=0,"
                       "failures=0,recovery_pending=1 WHERE id=? AND locked=1", (device_id,))
            if changed.rowcount:
                self._owner_event(db, device_id, "unblock_requested")

    @staticmethod
    def _owner_event(db, device_id, kind):
        db.execute("INSERT INTO owner_events(device_id,kind,received_at) VALUES(?,?,?)",
                   (device_id, kind, time.time()))
        db.execute("DELETE FROM owner_events WHERE id NOT IN (SELECT id FROM owner_events ORDER BY id DESC LIMIT 1000)")

    def set_diagnostics(self, device_id: str, body: dict, peer_address: str | None, _db=None):
        """Accept only an explicit opt-in report; never infer consent from ordinary traffic."""
        if type(body.get("enabled")) is not bool:
            raise ValueError("Explicit diagnostic choice required")
        enabled = body["enabled"]
        if set(body) != ({"enabled", "report"} if enabled else {"enabled"}):
            raise ValueError("Invalid diagnostic request")
        if enabled:
            report = body["report"]
            if not isinstance(report, dict) or set(report) != REPORT_FIELDS:
                raise ValueError("Only disclosed diagnostic fields are accepted")
            for field, length in [("app_version", 64), ("manufacturer", 64), ("model", 96), ("android_version", 32)]:
                value = report[field]
                if not isinstance(value, str) or not 1 <= len(value) <= length or not value.isprintable():
                    raise ValueError("Invalid diagnostic text")
            if type(report["android_api"]) is not int or not 26 <= report["android_api"] <= 1000:
                raise ValueError("Invalid Android API")
            if report["network"] not in {"wifi", "cellular", "ethernet", "vpn", "other", "offline", "unknown"}:
                raise ValueError("Invalid network type")
            if report["route"] not in {"direct", "tor", "direct_fallback"}:
                raise ValueError("Invalid route")
            permissions = report["permissions"]
            if not isinstance(permissions, dict) or set(permissions) != {"camera", "location", "notifications"} or any(type(v) is not bool for v in permissions.values()):
                raise ValueError("Invalid permission summary")
            # Trust the TCP peer only. Forwarded headers are untrusted; this may be a proxy or Tor exit.
            peer_address = str(ipaddress.ip_address(peer_address)) if peer_address is not None else "Not observed (encrypted relay)"
        with self.lock, (self.connect() if _db is None else nullcontext(_db)) as db:
            existing = db.execute("SELECT device_id FROM diagnostics WHERE device_id=?", (device_id,)).fetchone()
            if enabled:
                db.execute("INSERT OR REPLACE INTO diagnostics VALUES(?,?,?,?)",
                           (device_id, json.dumps(report), peer_address, time.time()))
                if not existing: self._owner_event(db, device_id, "diagnostics_enabled")
            else:
                db.execute("DELETE FROM diagnostics WHERE device_id=?", (device_id,))
                if existing: self._owner_event(db, device_id, "diagnostics_deleted")

    def purge_expired(self):
        with self.lock, self.connect() as db:
            db.execute("DELETE FROM diagnostics WHERE received_at < ?", (time.time() - DIAGNOSTIC_TTL,))

    def diagnostic(self, device_id: str):
        self.purge_expired()
        with self.connect() as db:
            row = db.execute("SELECT report,peer_address,received_at FROM diagnostics WHERE device_id=?", (device_id,)).fetchone()
        return {"report": json.loads(row[0]), "peer_address": row[1], "received_at": row[2]} if row else None

    def main_logs(self):
        with self.connect() as db:
            return [dict(row) for row in db.execute("SELECT device_id,kind,failures,received_at FROM events "
                "UNION ALL SELECT device_id,kind,NULL,received_at FROM owner_events ORDER BY received_at DESC LIMIT 300")]

    def devices(self):
        with self.connect() as db:
            return [dict(row) for row in db.execute("SELECT id,created_at,last_activity,last_unlock,failures,"
                    "locked,last_sequence,recovery_pending FROM devices ORDER BY last_activity DESC")]

    def history(self, device_id: str):
        with self.connect() as db:
            return [dict(row) for row in db.execute("SELECT kind,failures,received_at FROM events "
                    "WHERE device_id=? ORDER BY sequence DESC LIMIT 20", (device_id,))]

    def bind_relay(self, device_id, pubkey):
        with self.lock, self.connect() as db:
            existing = db.execute("SELECT pubkey FROM relay_devices WHERE device_id=?", (device_id,)).fetchone()
            if existing and existing[0] != pubkey:
                raise PermissionError("Recovery identity changed")
            if not existing:
                db.execute("INSERT INTO relay_devices(device_id,pubkey) VALUES(?,?)", (device_id, pubkey))

    def relay_revision(self, device_id):
        with self.connect() as db:
            return db.execute("SELECT diagnostics_revision FROM relay_devices WHERE device_id=?", (device_id,)).fetchone()[0]

    def relay_diagnostics(self, device_id, diagnostic):
        revision = diagnostic["revision"]
        body = {key: value for key, value in diagnostic.items() if key != "revision"}
        # Store revocation and its revision in one transaction; a replay cannot restore an old opt-in.
        with self.lock, self.connect() as db:
            previous = db.execute("SELECT diagnostics_revision FROM relay_devices WHERE device_id=?", (device_id,)).fetchone()[0]
            if revision >= previous:
                self.set_diagnostics(device_id, body, None, _db=db)
                db.execute("UPDATE relay_devices SET diagnostics_revision=? WHERE device_id=?", (revision, device_id))

    def pending_relay_recoveries(self):
        with self.connect() as db:
            return [dict(row) for row in db.execute("SELECT devices.id,relay_devices.pubkey,devices.unlock_through_sequence "
                "FROM devices JOIN relay_devices ON devices.id=relay_devices.device_id WHERE recovery_pending=1")]


class OwnerServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, database: OwnerDatabase, enrollment_token: str):
        self.database = database
        self.enrollment_token = enrollment_token
        self.last_maintenance = 0
        super().__init__(address, OwnerHandler)

    def service_actions(self):
        if time.monotonic() - self.last_maintenance >= 60:
            self.database.purge_expired()
            self.last_maintenance = time.monotonic()

    def handle_error(self, request, client_address):
        # Do not log network addresses, authorization headers, or request payloads.
        pass


class OwnerHandler(BaseHTTPRequestHandler):
    server: OwnerServer

    def log_message(self, *args):
        pass  # No HTTP access/IP logs.

    def reply(self, status: int, body: dict):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self): self.dispatch("GET")
    def do_POST(self): self.dispatch("POST")

    def dispatch(self, method: str):
        self.connection.settimeout(5)
        auth = self.headers.get("Authorization", "")
        token = auth[7:] if auth.startswith("Bearer ") else ""
        try:
            body = None
            if method == "POST":
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 32768:
                    self.reply(400, {"error": "Invalid body size"}); return
                body = json.loads(self.rfile.read(size))
                if not isinstance(body, dict): raise ValueError("Invalid request")
            if self.path == "/v1/enroll" and method == "POST":
                if not token or not hmac.compare_digest(token, self.server.enrollment_token):
                    self.reply(401, {"error": "Unauthorized"}); return
                if set(body) != {"device_id", "device_token"}: raise ValueError("Invalid enrollment")
                self.server.database.enroll(body["device_id"], body["device_token"])
                self.reply(200, {"ok": True}); return
            route = re.fullmatch(r"/v1/devices/([a-f0-9-]{36})/(events|state|diagnostics)", self.path)
            if not route:
                self.reply(404, {"error": "Not found"}); return
            device_id, action = route.groups()
            if not token or not self.server.database.authenticated(device_id, token):
                self.reply(401, {"error": "Unauthorized"}); return
            if action == "events" and method == "POST":
                if set(body) != {"events"}: raise ValueError("Invalid event request")
                seq = self.server.database.record(device_id, body["events"])
                self.reply(200, {"last_sequence": seq}); return
            if action == "state" and method == "GET":
                self.reply(200, self.server.database.state(device_id)); return
            if action == "diagnostics" and method == "POST":
                self.server.database.set_diagnostics(device_id, body, self.client_address[0])
                self.reply(200, {"ok": True}); return
            self.reply(405, {"error": "Method not allowed"})
        except PermissionError:
            self.reply(403, {"error": "Not allowed"})
        except (ValueError, TypeError, KeyError, AttributeError):
            self.reply(400, {"error": "Invalid request"})


def prepare(root: Path = ROOT):
    directory = root / ".local" / "owner"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    secret_file = directory / "service.json"
    if secret_file.exists():
        secret = json.loads(secret_file.read_text())
    else:
        secret = {"enrollment_token": secrets.token_urlsafe(32)}
        secret_file.write_text(json.dumps(secret))
        secret_file.chmod(0o600)
    from nostr_sdk import Keys
    if "owner_secret_hex" not in secret:
        secret["owner_secret_hex"] = Keys.generate().secret_key().to_hex()
        secret_file.write_text(json.dumps(secret))
    secret_file.chmod(0o600)
    public = Keys.parse(secret["owner_secret_hex"]).public_key().to_hex()
    config = root / ".local" / "owner-config.properties"
    lines = config.read_text().splitlines() if config.exists() else []
    lines = [line for line in lines if not line.startswith("owner_pubkey=")]
    lines.append("owner_pubkey=" + public)
    config.write_text("\n".join(lines) + "\n")
    config.chmod(0o600)
    return OwnerDatabase(directory / "owner.sqlite3"), secret


def launch_gui(database: OwnerDatabase, server: OwnerServer):
    import tkinter as tk
    from tkinter import font as tkfont, ttk
    window = tk.Tk(className="ControlPanel")
    window.title("Control Panel")
    icon = tk.PhotoImage(file=str(ROOT / "owner/assets/control-panel.png"))
    window.iconphoto(True, icon)
    window.geometry("1160x810")
    window.minsize(940, 680)
    window.configure(bg="#17191b")
    style = ttk.Style(window)
    style.theme_use("clam")
    families = {name.lower(): name for name in tkfont.families(window)}
    family = next((families[name] for name in ("liberation sans", "dejavu sans", "arial", "helvetica") if name in families), "fixed")
    style.configure(".", background="#17191b", foreground="#f3f4f5", fieldbackground="#23272b", font=(family, 11))
    style.configure("Treeview", background="#23272b", foreground="#f3f4f5", rowheight=30, borderwidth=0)
    style.configure("Treeview.Heading", background="#343a40", foreground="#f3f4f5", padding=8)
    style.map("Treeview", background=[("selected", "#45613a")])
    style.configure("TButton", padding=(12, 8))
    style.configure("TNotebook.Tab", padding=(18, 10))
    style.map("TNotebook.Tab", background=[("selected", "#3d4b36"), ("active", "#343a40"), ("!selected", "#2b3035")],
              foreground=[("selected", "#ffffff"), ("!selected", "#c3cbd3")])
    frame = ttk.Frame(window, padding=20)
    frame.pack(fill="both", expand=True)
    heading = ttk.Frame(frame)
    heading.pack(fill="x")
    # Also show the image in the window: some Linux desktops omit title-bar icons.
    heading_icon = icon.subsample(6, 6)
    ttk.Label(heading, image=heading_icon).pack(side="left", padx=(0, 12))
    ttk.Label(heading, text="Control Panel", font=(family, 24, "bold")).pack(side="left")
    status = tk.StringVar()
    ttk.Label(frame, textvariable=status, foreground="#b7e481").pack(anchor="w", pady=(6, 14))
    notebook = ttk.Notebook(frame)
    notebook.pack(fill="both", expand=True)
    main_tab, individual_tab = ttk.Frame(notebook, padding=12), ttk.Frame(notebook, padding=12)
    notebook.add(main_tab, text="Main logs")
    notebook.add(individual_tab, text="Individual installations")

    def make_table(parent, columns, headings, widths, height=8):
        holder = ttk.Frame(parent)
        holder.pack(fill="both", expand=True)
        table = ttk.Treeview(holder, columns=columns, show="headings", selectmode="browse", height=height)
        for name, label, width in zip(columns, headings, widths):
            table.heading(name, text=label)
            table.column(name, width=width, minwidth=70)
        scroll = ttk.Scrollbar(holder, orient="vertical", command=table.yview)
        table.configure(yscrollcommand=scroll.set)
        table.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        return table

    ttk.Label(main_tab, text="Latest 300 access and owner actions. Times show when reports reached Control Panel.").pack(anchor="w", pady=(0, 10))
    logs = make_table(main_tab, ("time", "installation", "event", "details"),
        ("Received", "Installation ID", "Event", "Details"), (160, 315, 220, 280), height=14)
    ttk.Label(main_tab, text="Double-click a log to open that installation. No message text or diagnostic payloads are written to this log.",
              wraplength=1000, foreground="#a5adb5").pack(anchor="w", pady=(10, 0))
    table = make_table(individual_tab, ("installation", "status", "attempts", "unlock", "activity"),
        ("Installation ID", "Access state", "Failures", "Unlock reported", "Last access report"),
        (310, 140, 80, 170, 170), height=5)
    actions = ttk.Frame(individual_tab)
    actions.pack(fill="x", pady=10)
    selected_label = tk.StringVar(value="Select an installation to inspect its reports.")
    ttk.Label(actions, textvariable=selected_label).pack(side="left")

    def clear():
        selected = table.selection()
        if selected:
            database.clear_lockout(selected[0])
            refresh()

    button = ttk.Button(actions, text="Unblock selected", command=clear)
    button.pack(side="right")
    detail_holder = ttk.Frame(individual_tab)
    detail_holder.pack(fill="both", expand=True)
    details = tk.Text(detail_holder, height=15, wrap="word", bg="#23272b", fg="#f3f4f5",
                      insertbackground="#f3f4f5", relief="flat", padx=12, pady=12, font=(family, 10))
    detail_scroll = ttk.Scrollbar(detail_holder, orient="vertical", command=details.yview)
    details.configure(yscrollcommand=detail_scroll.set)
    details.pack(side="left", fill="both", expand=True)
    detail_scroll.pack(side="right", fill="y")
    details.configure(state="disabled")
    ttk.Label(frame, text="Diagnostics require the phone user's explicit opt-in. Only the latest report is kept, for up to 7 days.\n"
        "No messages, passcodes, contacts or phone identifiers. Access state does not prove a phone is online.",
        foreground="#a5adb5", wraplength=1080).pack(anchor="w", pady=(12, 0))
    event_labels = {"enrolled": "Code created", "unlocked": "Code accepted", "failed": "Wrong code", "locked": "Locked out",
        "recovered": "Recovery acknowledged", "unblock_requested": "Owner requested unblock",
        "diagnostics_enabled": "Diagnostics opted in", "diagnostics_deleted": "Diagnostics stopped and deleted"}

    def formatted(value):
        return time.strftime("%b %d %H:%M:%S", time.localtime(value)) if value else "—"

    def selection_changed(_=None):
        selected = table.selection()
        row = next((r for r in database.devices() if selected and r["id"] == selected[0]), None)
        button.state(["!disabled"] if row and row["locked"] else ["disabled"])
        if not row:
            selected_label.set("Select an installation to inspect its reports.")
            lines = ["No installation selected."]
        else:
            selected_label.set(row["id"])
            lines = [f'Installation: {row["id"]}', f'Registered: {formatted(row["created_at"])}',
                     "Recovery: original code still required after an unblock.", ""]
            diagnostic = database.diagnostic(row["id"])
            if diagnostic:
                report = diagnostic["report"]
                lines += ["OPTIONAL DIAGNOSTICS · shared by this installation's user",
                    f'Report received: {formatted(diagnostic["received_at"])}',
                    f'Device (self-reported): {report["manufacturer"]} {report["model"]}',
                    f'Android: {report["android_version"]} (API {report["android_api"]}) · App: {report["app_version"]}',
                    f'Network: {report["network"]} · Request route (self-reported): {report["route"]}',
                    ("Phone IP: not observed through encrypted relays." if diagnostic["peer_address"] == "Not observed (encrypted relay)" else f'Legacy service connection address: {diagnostic["peer_address"]}'),
                    "App permissions: " + " · ".join(f'{key}: {"allowed" if value else "not allowed"}' for key, value in report["permissions"].items()), ""]
            else:
                lines += ["Optional diagnostics: not shared, deleted, or expired. No phone/network details stored.", ""]
            lines += ["RECENT ACCESS REPORTS"] + [f'{formatted(e["received_at"])}  {event_labels.get(e["kind"], e["kind"])}  (failures {e["failures"]}/3)'
                                                       for e in database.history(row["id"])[:8]]
        value = "\n".join(lines)
        if details.get("1.0", "end-1c") != value:
            details.configure(state="normal")
            details.delete("1.0", "end")
            details.insert("1.0", value)
            details.configure(state="disabled")

    def open_installation(_=None):
        selected = logs.selection()
        if selected:
            device_id = logs.item(selected[0], "values")[1]
            if table.exists(device_id):
                table.selection_set(device_id)
                table.see(device_id)
                notebook.select(individual_tab)
                selection_changed()

    def refresh():
        rows = database.devices()
        ids = {r["id"] for r in rows}
        for item in table.get_children():
            if item not in ids: table.delete(item)
        for index, row in enumerate(rows):
            state = "Locked" if row["locked"] else "Unblock pending" if row["recovery_pending"] else "Ready"
            values = (row["id"], state, f'{row["failures"]}/3', formatted(row["last_unlock"]), formatted(row["last_activity"]))
            if table.exists(row["id"]):
                table.item(row["id"], values=values)
                table.move(row["id"], "", index)
            else: table.insert("", "end", iid=row["id"], values=values)
        entries = database.main_logs()
        values = [(formatted(e["received_at"]), e["device_id"], event_labels.get(e["kind"], e["kind"]),
                   f'Failures {e["failures"]}/3' if e["failures"] is not None else "Local owner action" if e["kind"] == "unblock_requested" else "Phone user's diagnostic choice") for e in entries]
        if [logs.item(i, "values") for i in logs.get_children()] != values:
            logs.delete(*logs.get_children()) if logs.get_children() else None
            for value in values: logs.insert("", "end", values=value)
        if hasattr(server, "status"):
            endpoint = server.status
        else:
            endpoint = f'{"https" if isinstance(server.socket, ssl.SSLSocket) else "http"}://{server.server_address[0]}:{server.server_port}'
        status.set(f'{len(rows)} installations · {sum(r["locked"] for r in rows)} locked · {endpoint}')
        selection_changed()

    table.bind("<<TreeviewSelect>>", selection_changed)
    logs.bind("<Double-1>", open_installation)
    def tick():
        database.purge_expired()
        refresh()
        window.after(2000, tick)
    window.protocol("WM_DELETE_WINDOW", window.destroy)
    tick()
    window.mainloop()


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepare", action="store_true", help="Create local config before building the Android app")
    parser.add_argument("--headless", action="store_true")
    args = parser.parse_args()
    database, secret = prepare()
    if args.prepare: return
    from owner_relay import OwnerRelayClient
    relay = OwnerRelayClient(database, secret, ROOT / ".local/owner/relay-cursor")
    relay.start()
    try:
        if args.headless:
            while True: time.sleep(1)
        else:
            launch_gui(database, relay)
    except KeyboardInterrupt:
        pass
    finally:
        relay.stop()


if __name__ == "__main__": main()
