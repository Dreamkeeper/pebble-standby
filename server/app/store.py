"""SQLite persistence (design D1/D2): stdlib sqlite3, WAL, no ORM.

One module-level ``db`` instance, path from ``CM_DATA_DIR``. Methods are
synchronous; FastAPI sync endpoints already run in a threadpool, and the
async pump wraps calls in ``asyncio.to_thread``. Every wearer-scoped
method takes ``wearer_id`` first (design D8) — nothing in here has a
notion of a "current" wearer.

Escalations persist as a snapshot blob (``Escalation.to_state()`` JSON)
plus indexed columns; per-contact detail lives inside the blob, which is
atomic by construction.
"""
from __future__ import annotations

import contextlib
import json
import os
import sqlite3
import time
import uuid

SCHEMA_VERSION = 4

_SCHEMA = """
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS wearers (
  id TEXT PRIMARY KEY, name TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  self_telegram TEXT, self_ntfy TEXT, self_email TEXT,
  created_t REAL NOT NULL);
CREATE TABLE IF NOT EXISTS tokens (
  token_hash TEXT PRIMARY KEY, wearer_id TEXT NOT NULL,
  created_t REAL NOT NULL, revoked_t REAL);
CREATE TABLE IF NOT EXISTS enroll_codes (
  code_hash TEXT PRIMARY KEY, wearer_id TEXT NOT NULL,
  expires_t REAL NOT NULL, used_t REAL);
CREATE TABLE IF NOT EXISTS tiers (
  wearer_id TEXT NOT NULL, name TEXT NOT NULL,
  position INTEGER NOT NULL,
  repeat_after_s INTEGER NOT NULL DEFAULT 1800,
  promote_after_s INTEGER NOT NULL DEFAULT 600,
  PRIMARY KEY (wearer_id, name));
CREATE TABLE IF NOT EXISTS contacts (
  id TEXT PRIMARY KEY, wearer_id TEXT NOT NULL,
  tier_name TEXT NOT NULL, name TEXT NOT NULL,
  telegram_chat_id TEXT, ntfy_topic TEXT, email TEXT,
  position INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS escalations (
  id TEXT PRIMARY KEY, wearer_id TEXT NOT NULL,
  resolved INTEGER NOT NULL DEFAULT 0,
  started_t REAL NOT NULL, state_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS ack_tokens (
  token_hash TEXT PRIMARY KEY, wearer_id TEXT NOT NULL,
  escalation_id TEXT NOT NULL, contact_id TEXT NOT NULL,
  created_t REAL NOT NULL);
CREATE TABLE IF NOT EXISTS deadman (
  wearer_id TEXT PRIMARY KEY,
  last_heartbeat_t REAL, low_battery_notice_t REAL, offline_until_t REAL);
CREATE TABLE IF NOT EXISTS events (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  wearer_id TEXT, t REAL NOT NULL, kind TEXT NOT NULL, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS idx_esc_open ON escalations (resolved, wearer_id);
CREATE INDEX IF NOT EXISTS idx_events_wearer ON events (wearer_id, seq);
"""

_SCHEMA_V2 = """
CREATE TABLE IF NOT EXISTS operators (
  username TEXT PRIMARY KEY, pw TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'responder',
  enabled INTEGER NOT NULL DEFAULT 1, created_t REAL NOT NULL);
CREATE TABLE IF NOT EXISTS sessions (
  session_hash TEXT PRIMARY KEY, username TEXT NOT NULL,
  csrf TEXT NOT NULL, created_t REAL NOT NULL, expires_t REAL NOT NULL,
  revoked_t REAL);
CREATE TABLE IF NOT EXISTS login_attempts (
  key TEXT NOT NULL, t REAL NOT NULL);
CREATE INDEX IF NOT EXISTS idx_login_attempts ON login_attempts (key, t);
CREATE TABLE IF NOT EXISTS heartbeat_trail (
  wearer_id TEXT NOT NULL, t REAL NOT NULL, battery INTEGER);
CREATE INDEX IF NOT EXISTS idx_hb_trail ON heartbeat_trail (wearer_id, t);
"""

_SCHEMA_V4 = """
CREATE TABLE IF NOT EXISTS diagnostics (
  id TEXT PRIMARY KEY, wearer_id TEXT NOT NULL,
  created_t REAL NOT NULL, size INTEGER NOT NULL, days INTEGER NOT NULL,
  request_id TEXT);
CREATE INDEX IF NOT EXISTS idx_diag_wearer ON diagnostics (wearer_id, created_t);
CREATE TABLE IF NOT EXISTS diag_requests (
  id TEXT PRIMARY KEY, wearer_id TEXT NOT NULL, days INTEGER NOT NULL,
  requested_by TEXT NOT NULL, created_t REAL NOT NULL,
  state TEXT NOT NULL DEFAULT 'pending', resolved_t REAL, diagnostics_id TEXT);
CREATE INDEX IF NOT EXISTS idx_diag_req_wearer ON diag_requests (wearer_id, created_t);
"""

DEFAULT_TIER = {"name": "primary", "position": 0,
                "repeat_after_s": 1800, "promote_after_s": 600}


class Store:
    def __init__(self, data_dir: str):
        os.makedirs(data_dir, exist_ok=True)
        self.path = os.path.join(data_dir, "cryomonitor.db")
        with self._conn() as c:
            c.executescript(_SCHEMA)
            row = c.execute("SELECT version FROM schema_version").fetchone()
            if row is None:
                c.executescript(_SCHEMA_V2)
                c.execute("ALTER TABLE deadman ADD COLUMN suspended_until REAL")
                c.execute("ALTER TABLE heartbeat_trail ADD COLUMN watch_battery INTEGER")
                c.executescript(_SCHEMA_V4)
                c.execute("INSERT INTO schema_version VALUES (?)", (SCHEMA_VERSION,))
            else:
                if row["version"] < 2:
                    c.executescript(_SCHEMA_V2)
                    c.execute("ALTER TABLE deadman ADD COLUMN suspended_until REAL")
                if row["version"] < 3:  # M0 spike S6: watch battery drain trail
                    c.execute("ALTER TABLE heartbeat_trail ADD COLUMN watch_battery INTEGER")
                if row["version"] < 4:  # consented-diagnostics
                    c.executescript(_SCHEMA_V4)
                if row["version"] < SCHEMA_VERSION:
                    c.execute("UPDATE schema_version SET version=?", (SCHEMA_VERSION,))

    @contextlib.contextmanager
    def _conn(self):
        conn = sqlite3.connect(self.path, timeout=30)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        try:
            with conn:  # one transaction per method call
                yield conn
        finally:
            conn.close()

    # -- wearers --

    def create_wearer(self, wearer_id: str, name: str) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO wearers (id, name, created_t) VALUES (?,?,?)",
                      (wearer_id, name, time.time()))
            c.execute("""INSERT INTO tiers (wearer_id, name, position,
                         repeat_after_s, promote_after_s) VALUES (?,?,?,?,?)""",
                      (wearer_id, DEFAULT_TIER["name"], DEFAULT_TIER["position"],
                       DEFAULT_TIER["repeat_after_s"], DEFAULT_TIER["promote_after_s"]))

    def get_wearer(self, wearer_id: str) -> dict | None:
        with self._conn() as c:
            r = c.execute("SELECT * FROM wearers WHERE id=?", (wearer_id,)).fetchone()
            return dict(r) if r else None

    def list_wearers(self) -> list[dict]:
        with self._conn() as c:
            return [dict(r) for r in c.execute(
                "SELECT * FROM wearers ORDER BY created_t")]

    def set_wearer_enabled(self, wearer_id: str, enabled: bool) -> None:
        with self._conn() as c:
            c.execute("UPDATE wearers SET enabled=? WHERE id=?",
                      (1 if enabled else 0, wearer_id))

    def set_self_notify(self, wearer_id: str, telegram: str | None,
                        ntfy: str | None, email: str | None) -> None:
        with self._conn() as c:
            c.execute("""UPDATE wearers SET self_telegram=?, self_ntfy=?,
                         self_email=? WHERE id=?""",
                      (telegram, ntfy, email, wearer_id))

    # -- tokens --

    def add_token(self, wearer_id: str, token_hash: str) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO tokens (token_hash, wearer_id, created_t) "
                      "VALUES (?,?,?)", (token_hash, wearer_id, time.time()))

    def revoke_tokens(self, wearer_id: str) -> int:
        with self._conn() as c:
            cur = c.execute("UPDATE tokens SET revoked_t=? WHERE wearer_id=? "
                            "AND revoked_t IS NULL", (time.time(), wearer_id))
            return cur.rowcount

    def wearer_for_token(self, token_hash: str) -> str | None:
        """Returns the wearer id for a live token on an enabled wearer."""
        with self._conn() as c:
            r = c.execute(
                """SELECT t.wearer_id FROM tokens t JOIN wearers w
                   ON w.id = t.wearer_id
                   WHERE t.token_hash=? AND t.revoked_t IS NULL AND w.enabled=1""",
                (token_hash,)).fetchone()
            return r["wearer_id"] if r else None

    # -- enrollment --

    def create_enroll_code(self, wearer_id: str, code_hash: str,
                           expires_t: float) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO enroll_codes (code_hash, wearer_id, expires_t) "
                      "VALUES (?,?,?)", (code_hash, wearer_id, expires_t))

    def exchange_enroll_code(self, code_hash: str, now: float,
                             new_token_hash: str) -> str | None:
        """Atomically burn the code and mint the token. None if invalid."""
        with self._conn() as c:
            r = c.execute(
                """SELECT e.wearer_id FROM enroll_codes e JOIN wearers w
                   ON w.id = e.wearer_id
                   WHERE e.code_hash=? AND e.used_t IS NULL
                     AND e.expires_t > ? AND w.enabled=1""",
                (code_hash, now)).fetchone()
            if not r:
                return None
            c.execute("UPDATE enroll_codes SET used_t=? WHERE code_hash=?",
                      (now, code_hash))
            c.execute("INSERT INTO tokens (token_hash, wearer_id, created_t) "
                      "VALUES (?,?,?)", (new_token_hash, r["wearer_id"], now))
            return r["wearer_id"]

    # -- tiers and contacts --

    def upsert_tier(self, wearer_id: str, name: str, position: int,
                    repeat_after_s: int, promote_after_s: int) -> None:
        with self._conn() as c:
            c.execute("""INSERT INTO tiers (wearer_id, name, position,
                         repeat_after_s, promote_after_s) VALUES (?,?,?,?,?)
                         ON CONFLICT(wearer_id, name) DO UPDATE SET
                         position=excluded.position,
                         repeat_after_s=excluded.repeat_after_s,
                         promote_after_s=excluded.promote_after_s""",
                      (wearer_id, name, position, repeat_after_s, promote_after_s))

    def list_tiers(self, wearer_id: str) -> list[dict]:
        with self._conn() as c:
            return [dict(r) for r in c.execute(
                "SELECT * FROM tiers WHERE wearer_id=? ORDER BY position",
                (wearer_id,))]

    def delete_tier(self, wearer_id: str, name: str) -> bool:
        with self._conn() as c:
            used = c.execute("SELECT COUNT(*) FROM contacts WHERE wearer_id=? "
                             "AND tier_name=?", (wearer_id, name)).fetchone()[0]
            if used:
                return False
            c.execute("DELETE FROM tiers WHERE wearer_id=? AND name=?",
                      (wearer_id, name))
            return True

    def upsert_contact(self, wearer_id: str, contact: dict) -> str:
        cid = contact.get("id") or uuid.uuid4().hex[:12]
        with self._conn() as c:
            c.execute("""INSERT INTO contacts (id, wearer_id, tier_name, name,
                         telegram_chat_id, ntfy_topic, email, position)
                         VALUES (?,?,?,?,?,?,?,?)
                         ON CONFLICT(id) DO UPDATE SET
                         tier_name=excluded.tier_name, name=excluded.name,
                         telegram_chat_id=excluded.telegram_chat_id,
                         ntfy_topic=excluded.ntfy_topic, email=excluded.email,
                         position=excluded.position
                         WHERE contacts.wearer_id=excluded.wearer_id""",
                      (cid, wearer_id, contact["tier_name"], contact["name"],
                       contact.get("telegram_chat_id"), contact.get("ntfy_topic"),
                       contact.get("email"), contact.get("position", 0)))
        return cid

    def list_contacts(self, wearer_id: str) -> list[dict]:
        with self._conn() as c:
            return [dict(r) for r in c.execute(
                "SELECT * FROM contacts WHERE wearer_id=? ORDER BY position, name",
                (wearer_id,))]

    def delete_contact(self, wearer_id: str, contact_id: str) -> bool:
        with self._conn() as c:
            cur = c.execute("DELETE FROM contacts WHERE wearer_id=? AND id=?",
                            (wearer_id, contact_id))
            return cur.rowcount > 0

    # -- escalations --

    def save_escalation(self, wearer_id: str, esc_id: str, state: dict) -> None:
        with self._conn() as c:
            c.execute("""INSERT INTO escalations (id, wearer_id, resolved,
                         started_t, state_json) VALUES (?,?,?,?,?)
                         ON CONFLICT(id) DO UPDATE SET
                         resolved=excluded.resolved,
                         state_json=excluded.state_json
                         WHERE escalations.wearer_id=excluded.wearer_id""",
                      (esc_id, wearer_id, 1 if state["resolved"] else 0,
                       state["started_t"], json.dumps(state)))

    def load_unresolved_escalations(self) -> list[tuple[str, str, dict]]:
        """[(wearer_id, esc_id, state)] across all wearers, for startup."""
        with self._conn() as c:
            return [(r["wearer_id"], r["id"], json.loads(r["state_json"]))
                    for r in c.execute(
                        "SELECT * FROM escalations WHERE resolved=0 "
                        "ORDER BY started_t")]

    def get_escalation(self, wearer_id: str, esc_id: str) -> dict | None:
        with self._conn() as c:
            r = c.execute("SELECT state_json FROM escalations WHERE id=? AND "
                          "wearer_id=?", (esc_id, wearer_id)).fetchone()
            return json.loads(r["state_json"]) if r else None

    # -- ack tokens --

    def add_ack_token(self, wearer_id: str, token_hash: str,
                      escalation_id: str, contact_id: str) -> None:
        with self._conn() as c:
            c.execute("""INSERT OR IGNORE INTO ack_tokens (token_hash, wearer_id,
                         escalation_id, contact_id, created_t) VALUES (?,?,?,?,?)""",
                      (token_hash, wearer_id, escalation_id, contact_id, time.time()))

    def lookup_ack_token(self, token_hash: str) -> dict | None:
        with self._conn() as c:
            r = c.execute("SELECT * FROM ack_tokens WHERE token_hash=?",
                          (token_hash,)).fetchone()
            return dict(r) if r else None

    # -- dead-man --

    def save_deadman(self, wearer_id: str, last_heartbeat_t: float | None,
                     low_battery_notice_t: float | None,
                     offline_until_t: float | None) -> None:
        with self._conn() as c:
            c.execute("""INSERT INTO deadman (wearer_id, last_heartbeat_t,
                         low_battery_notice_t, offline_until_t) VALUES (?,?,?,?)
                         ON CONFLICT(wearer_id) DO UPDATE SET
                         last_heartbeat_t=excluded.last_heartbeat_t,
                         low_battery_notice_t=excluded.low_battery_notice_t,
                         offline_until_t=excluded.offline_until_t""",
                      (wearer_id, last_heartbeat_t, low_battery_notice_t,
                       offline_until_t))

    def load_deadman_all(self) -> dict[str, dict]:
        with self._conn() as c:
            return {r["wearer_id"]: dict(r)
                    for r in c.execute("SELECT * FROM deadman")}

    # -- events --

    def add_event(self, wearer_id: str | None, kind: str, data: dict) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO events (wearer_id, t, kind, data) "
                      "VALUES (?,?,?,?)",
                      (wearer_id, time.time(), kind, json.dumps(data)))

    def recent_events(self, wearer_id: str | None, limit: int = 50) -> list[dict]:
        q = "SELECT * FROM events {} ORDER BY seq DESC LIMIT ?"
        with self._conn() as c:
            if wearer_id is None:  # admin: everything
                rows = c.execute(q.format(""), (limit,)).fetchall()
            else:
                # Strictly this wearer's rows: NULL-wearer rows are the
                # global operator audit stream (logins, role changes) and
                # must never reach a wearer bearer token (review 2026-08-29
                # finding 13).
                rows = c.execute(q.format("WHERE wearer_id=?"),
                                 (wearer_id, limit)).fetchall()
        return [{"t": r["t"], "kind": r["kind"], "wearer_id": r["wearer_id"],
                 **json.loads(r["data"])} for r in reversed(rows)]

    # -- operators & sessions (web dashboard) --

    def create_operator(self, username: str, pw_json: str, role: str) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO operators (username, pw, role, created_t) "
                      "VALUES (?,?,?,?)", (username, pw_json, role, time.time()))

    def get_operator(self, username: str) -> dict | None:
        with self._conn() as c:
            r = c.execute("SELECT * FROM operators WHERE username=?",
                          (username,)).fetchone()
            return dict(r) if r else None

    def list_operators(self) -> list[dict]:
        with self._conn() as c:
            return [{k: v for k, v in dict(r).items() if k != "pw"}
                    for r in c.execute("SELECT * FROM operators ORDER BY created_t")]

    def update_operator(self, username: str, pw_json: str | None = None,
                        role: str | None = None,
                        enabled: bool | None = None) -> None:
        with self._conn() as c:
            if pw_json is not None:
                c.execute("UPDATE operators SET pw=? WHERE username=?",
                          (pw_json, username))
            if role is not None:
                c.execute("UPDATE operators SET role=? WHERE username=?",
                          (role, username))
            if enabled is not None:
                c.execute("UPDATE operators SET enabled=? WHERE username=?",
                          (1 if enabled else 0, username))

    def count_admins(self) -> int:
        with self._conn() as c:
            return c.execute("SELECT COUNT(*) FROM operators WHERE role='admin' "
                             "AND enabled=1").fetchone()[0]

    def create_session(self, session_hash: str, username: str, csrf: str,
                       expires_t: float) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO sessions (session_hash, username, csrf, "
                      "created_t, expires_t) VALUES (?,?,?,?,?)",
                      (session_hash, username, csrf, time.time(), expires_t))

    def lookup_session(self, session_hash: str, now: float) -> dict | None:
        """Valid session joined with an enabled operator, or None."""
        with self._conn() as c:
            r = c.execute(
                """SELECT s.username, s.csrf, s.expires_t, o.role
                   FROM sessions s JOIN operators o ON o.username = s.username
                   WHERE s.session_hash=? AND s.revoked_t IS NULL
                     AND s.expires_t > ? AND o.enabled=1""",
                (session_hash, now)).fetchone()
            return dict(r) if r else None

    def revoke_session(self, session_hash: str) -> None:
        with self._conn() as c:
            c.execute("UPDATE sessions SET revoked_t=? WHERE session_hash=?",
                      (time.time(), session_hash))

    def revoke_operator_sessions(self, username: str) -> int:
        with self._conn() as c:
            return c.execute("UPDATE sessions SET revoked_t=? WHERE username=? "
                             "AND revoked_t IS NULL",
                             (time.time(), username)).rowcount

    def record_login_attempt(self, key: str) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO login_attempts (key, t) VALUES (?,?)",
                      (key, time.time()))
            c.execute("DELETE FROM login_attempts WHERE t < ?",
                      (time.time() - 86400,))

    def login_attempts_since(self, key: str, since_t: float) -> int:
        with self._conn() as c:
            return c.execute("SELECT COUNT(*) FROM login_attempts WHERE key=? "
                             "AND t > ?", (key, since_t)).fetchone()[0]

    # -- heartbeat trail (dashboard battery graph) --

    def add_heartbeat_point(self, wearer_id: str, battery: int | None,
                            watch_battery: int | None = None) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO heartbeat_trail (wearer_id, t, battery, "
                      "watch_battery) VALUES (?,?,?,?)",
                      (wearer_id, time.time(), battery, watch_battery))
            c.execute("DELETE FROM heartbeat_trail WHERE wearer_id=? AND t < ?",
                      (wearer_id, time.time() - 48 * 3600))

    # -- phone command queue (kv-backed; one pending command per wearer) --

    def queue_command(self, wearer_id: str, command: str) -> None:
        with self._conn() as c:
            c.execute("INSERT OR REPLACE INTO kv (key, value) VALUES (?,?)",
                      (f"cmd:{wearer_id}", command))

    def peek_command(self, wearer_id: str) -> str | None:
        """Return the pending command WITHOUT clearing it: it is redelivered
        on every heartbeat until the phone acks it (review 2026-08-29
        finding 14 — deleting before the response survives the network
        loses the command)."""
        with self._conn() as c:
            row = c.execute("SELECT value FROM kv WHERE key=?",
                            (f"cmd:{wearer_id}",)).fetchone()
            return row["value"] if row else None

    def ack_command(self, wearer_id: str, value: str) -> None:
        with self._conn() as c:
            c.execute("DELETE FROM kv WHERE key=? AND value=?",
                      (f"cmd:{wearer_id}", value))

    def heartbeat_trail(self, wearer_id: str, limit: int = 288) -> list[dict]:
        with self._conn() as c:
            rows = c.execute("SELECT t, battery, watch_battery FROM heartbeat_trail WHERE "
                             "wearer_id=? ORDER BY t DESC LIMIT ?",
                             (wearer_id, limit)).fetchall()
            return [dict(r) for r in reversed(rows)]

    def set_suspended_until(self, wearer_id: str, until_t: float | None) -> None:
        with self._conn() as c:
            c.execute("""INSERT INTO deadman (wearer_id, suspended_until)
                         VALUES (?,?) ON CONFLICT(wearer_id) DO UPDATE SET
                         suspended_until=excluded.suspended_until""",
                      (wearer_id, until_t))

    def get_suspended_until(self, wearer_id: str) -> float | None:
        with self._conn() as c:
            r = c.execute("SELECT suspended_until FROM deadman WHERE wearer_id=?",
                          (wearer_id,)).fetchone()
            return r["suspended_until"] if r else None

    # -- diagnostics bundles (consented-diagnostics; files live on disk) --

    def add_diagnostics(self, wearer_id: str, diag_id: str, size: int,
                        days: int, request_id: str | None) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO diagnostics (id, wearer_id, created_t, size, "
                      "days, request_id) VALUES (?,?,?,?,?,?)",
                      (diag_id, wearer_id, time.time(), size, days, request_id))

    def list_diagnostics(self, wearer_id: str) -> list[dict]:
        with self._conn() as c:
            return [dict(r) for r in c.execute(
                "SELECT * FROM diagnostics WHERE wearer_id=? "
                "ORDER BY created_t DESC", (wearer_id,)).fetchall()]

    def get_diagnostics(self, wearer_id: str, diag_id: str) -> dict | None:
        with self._conn() as c:
            r = c.execute("SELECT * FROM diagnostics WHERE wearer_id=? AND id=?",
                          (wearer_id, diag_id)).fetchone()
            return dict(r) if r else None

    def delete_diagnostics(self, wearer_id: str, diag_id: str) -> bool:
        with self._conn() as c:
            return c.execute("DELETE FROM diagnostics WHERE wearer_id=? AND id=?",
                             (wearer_id, diag_id)).rowcount > 0

    def diagnostics_older_than(self, before_t: float) -> list[dict]:
        with self._conn() as c:
            return [dict(r) for r in c.execute(
                "SELECT id, wearer_id FROM diagnostics WHERE created_t < ?",
                (before_t,)).fetchall()]

    def create_diag_request(self, wearer_id: str, req_id: str, days: int,
                            requested_by: str) -> None:
        """A newer request supersedes any still-pending one: the phone
        keeps only the latest (design D4)."""
        now = time.time()
        with self._conn() as c:
            c.execute("UPDATE diag_requests SET state='superseded', resolved_t=? "
                      "WHERE wearer_id=? AND state='pending'", (now, wearer_id))
            c.execute("INSERT INTO diag_requests (id, wearer_id, days, requested_by, "
                      "created_t) VALUES (?,?,?,?,?)",
                      (req_id, wearer_id, days, requested_by, now))

    def list_diag_requests(self, wearer_id: str, limit: int = 5) -> list[dict]:
        with self._conn() as c:
            return [dict(r) for r in c.execute(
                "SELECT * FROM diag_requests WHERE wearer_id=? "
                "ORDER BY created_t DESC LIMIT ?", (wearer_id, limit)).fetchall()]

    def get_diag_request(self, wearer_id: str, req_id: str) -> dict | None:
        with self._conn() as c:
            r = c.execute("SELECT * FROM diag_requests WHERE wearer_id=? AND id=?",
                          (wearer_id, req_id)).fetchone()
            return dict(r) if r else None

    def resolve_diag_request(self, wearer_id: str, req_id: str, state: str,
                             diagnostics_id: str | None = None) -> bool:
        """pending -> fulfilled | declined. A fulfilled request stays
        fulfilled; an upload may still answer a superseded one."""
        with self._conn() as c:
            return c.execute(
                "UPDATE diag_requests SET state=?, resolved_t=?, diagnostics_id=? "
                "WHERE wearer_id=? AND id=? AND state IN ('pending','superseded')",
                (state, time.time(), diagnostics_id, wearer_id, req_id)).rowcount > 0

    # -- kv --

    def kv_get(self, key: str) -> str | None:
        with self._conn() as c:
            r = c.execute("SELECT value FROM kv WHERE key=?", (key,)).fetchone()
            return r["value"] if r else None

    def kv_set(self, key: str, value: str) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO kv (key, value) VALUES (?,?) "
                      "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                      (key, value))


db = Store(os.environ.get("CM_DATA_DIR", "/srv/data"))
