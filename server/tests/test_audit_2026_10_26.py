"""Regression tests for the 2026-10-26 security hunt of the server
(docs/security-audit-2026-10-26). Each test encodes the invariant a
confirmed finding or validated lead said the server must hold; the
leads' local validation plans were run against the pre-fix code first
and the outcome is recorded in the commit that added this file."""
import asyncio
import sqlite3
import threading
import time
import urllib.error

import pytest
from fastapi.testclient import TestClient

from app import telegram_poll
from conftest import UI_ADMIN, admin_headers, ui_login, wearer_headers


def _cycle(appenv, t):
    asyncio.run(appenv.main.pump_cycle(t))


def _contact(appenv, body, headers=None):
    return appenv.client.post("/api/v1/contacts", json=body,
                              headers=headers or wearer_headers())


def _alarm(appenv, detector="impact", headers=None, **extra):
    body = {"detector": detector, "kind": "watch_alarm"}
    body.update(extra)
    return appenv.client.post("/api/v1/alarm", json=body,
                              headers=headers or wearer_headers())


def _second_wearer(appenv, wid="wb"):
    r = appenv.client.post("/api/v1/admin/wearers", json={"id": wid, "name": "B"},
                           headers=admin_headers())
    assert r.status_code == 200, r.text
    tok = "tok-" + wid
    appenv.store.add_token(wid, appenv.wearers.hash_token(tok))
    return wearer_headers(tok)


# ---- confirmed: unbounded per-wearer fan-out through the shared pump ----

def test_contacts_per_wearer_are_capped(appenv):
    limit = appenv.wearers.MAX_CONTACTS_PER_WEARER
    for i in range(limit):
        r = _contact(appenv, {"name": f"c{i}", "email": f"r{i}@example.test"})
        assert r.status_code == 200, r.text
    r = _contact(appenv, {"name": "one too many", "email": "x@example.test"})
    assert r.status_code == 422
    assert "contacts" in r.json()["detail"]["fields"]


def test_open_escalations_per_wearer_are_capped_and_folded(appenv):
    _contact(appenv, {"name": "R", "telegram_chat_id": "111111"})
    limit = appenv.main.MAX_OPEN_ESCALATIONS_PER_WEARER
    ids = [_alarm(appenv, f"d{i}").json()["escalation_id"] for i in range(limit)]
    assert len(set(ids)) == limit
    folded = _alarm(appenv, "d-extra").json()["escalation_id"]
    assert folded == ids[-1]                    # folded into the newest open one
    open_now = [e for wid, e in appenv.main.escalations.values()
                if wid == "default" and not e.resolved]
    assert len(open_now) == limit
    kinds = [e["kind"] for e in appenv.store.recent_events("default", 50)]
    assert "alarm_collapsed" in kinds


def test_alarm_fields_are_bounded(appenv):
    assert _alarm(appenv, "x" * 65).status_code == 422
    assert _alarm(appenv, "ok", kind="phone_silent").status_code == 422
    assert _alarm(appenv, "ok", kind="watch_alarm").status_code == 200
    # a lone UTF-16 surrogate must never reach a message body
    r = appenv.client.post("/api/v1/alarm",
                           content=b'{"detector": "\\udcff", "kind": "watch_alarm"}',
                           headers={**wearer_headers(),
                                    "Content-Type": "application/json"})
    assert r.status_code == 422


def test_one_raising_send_does_not_stall_other_wearers(appenv):
    _contact(appenv, {"name": "R", "ntfy_topic": "cm-r1"})
    poisoned = _alarm(appenv, "poison").json()["escalation_id"]
    hb = _second_wearer(appenv)
    _contact(appenv, {"name": "RB", "telegram_chat_id": "999999"}, headers=hb)
    _alarm(appenv, "impact", headers=hb)

    def boom(address, text, ack_url, ack_token):
        raise ValueError("transport blew up")
    appenv.fakes["ntfy"].on_deliver = boom

    t = time.time()
    _cycle(appenv, t)                                  # must not raise
    assert [a for a, *_ in appenv.fakes["telegram"].sent] == ["999999"]
    esc = appenv.main.escalations[poisoned][1]
    assert all(cs.last_sent_t is not None for cs in esc._contacts), \
        "the failed send must be recorded so it retries on the repeat cadence"


def test_sends_per_wearer_per_cycle_are_budgeted(appenv):
    limit = appenv.wearers.MAX_CONTACTS_PER_WEARER
    for i in range(limit):
        _contact(appenv, {"name": f"c{i}", "email": f"r{i}@example.test"})
    for i in range(appenv.main.MAX_OPEN_ESCALATIONS_PER_WEARER):
        _alarm(appenv, f"d{i}")
    _cycle(appenv, time.time())
    sent = len(appenv.fakes["email"].sent)
    assert sent <= appenv.main.MAX_SENDS_PER_WEARER_PER_CYCLE
    kinds = [e["kind"] for e in appenv.store.recent_events("default", 500)]
    assert "send_suppressed" in kinds


# ---- confirmed: disable does not stop open escalations ----

def test_disable_resolves_open_escalations_and_stops_sends(appenv):
    _contact(appenv, {"name": "R", "telegram_chat_id": "111111"})
    esc_id = _alarm(appenv).json()["escalation_id"]
    t0 = time.time()
    _cycle(appenv, t0)
    assert len(appenv.fakes["telegram"].sent) == 1
    r = appenv.client.post("/api/v1/admin/wearers/default/disable",
                           headers=admin_headers())
    assert r.status_code == 200
    assert appenv.main.escalations[esc_id][1].resolved
    _cycle(appenv, t0 + 1801)
    assert len(appenv.fakes["telegram"].sent) == 1
    ev = [e for e in appenv.store.recent_events("default", 20) if e["kind"] == "resolved"]
    assert ev and ev[0]["resolution"] == "wearer_disabled"
    # a restart does not resurrect it
    assert not appenv.store.load_unresolved_escalations()


# ---- confirmed: username lockout ----

def test_a_strangers_failures_do_not_lock_the_operator_out(appenv_ui):
    stranger = TestClient(appenv_ui.main.app, client=("203.0.113.9", 50000))
    for _ in range(appenv_ui.operators.LOGIN_MAX_ATTEMPTS):
        r = stranger.post("/ui/login", data={"username": UI_ADMIN[0], "password": "wrong"},
                          follow_redirects=False)
        assert r.status_code == 401
    r = stranger.post("/ui/login", data={"username": UI_ADMIN[0], "password": "wrong"},
                      follow_redirects=False)
    assert r.status_code == 429                      # the stranger's address is blocked
    assert ui_login(appenv_ui.client)                # the operator still gets in


# ---- confirmed: enrol-code race ----

def test_enrol_code_is_exchanged_at_most_once_under_a_race(appenv, monkeypatch):
    store = appenv.store
    store.create_wearer("alice", "Alice")
    code_hash = appenv.wearers.hash_token("ABCDEFGH")
    store.create_enroll_code("alice", code_hash, time.time() + 3600)
    barrier = threading.Barrier(2, timeout=15)
    real_connect = sqlite3.connect

    class Proxy:
        def __init__(self, conn):
            object.__setattr__(self, "_c", conn)

        def __getattr__(self, name):
            return getattr(self._c, name)

        def __setattr__(self, name, value):      # row_factory etc. reach the real connection
            setattr(self._c, name, value)

        def __enter__(self):
            self._c.__enter__()
            return self

        def __exit__(self, *exc):
            return self._c.__exit__(*exc)

        def execute(self, sql, *args):
            if sql.lstrip().startswith("UPDATE enroll_codes"):
                barrier.wait()           # both SELECTs ran before either UPDATE
            return self._c.execute(sql, *args)

    monkeypatch.setattr(sqlite3, "connect", lambda *a, **k: Proxy(real_connect(*a, **k)))
    results = []
    threads = [threading.Thread(
        target=lambda i=i: results.append(
            store.exchange_enroll_code(code_hash, time.time(), f"tok{i}")))
        for i in range(2)]
    for th in threads:
        th.start()
    for th in threads:
        th.join(60)
    n = real_connect(store.path).execute(
        "SELECT COUNT(*) FROM tokens WHERE wearer_id=?", ("alice",)).fetchone()[0]
    assert results.count("alice") == 1 and n == 1, (results, n)


# ---- confirmed: unroutable operator username ----

def test_operator_usernames_must_be_routable(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    r = appenv_ui.client.post("/ui/operators/create",
                              data={"username": "ops/x", "password": "longenough1",
                                    "role": "admin", "csrf": csrf},
                              follow_redirects=False)
    assert r.status_code == 200 and "username" in r.text
    assert appenv_ui.store.get_operator("ops/x") is None
    r = appenv_ui.client.post("/ui/operators/create",
                              data={"username": "ops.x-1", "password": "longenough1",
                                    "role": "responder", "csrf": csrf},
                              follow_redirects=False)
    assert r.status_code == 303


# ---- leads validated locally ----

def test_non_finite_suspended_until_is_rejected_and_tolerated(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    for body in (b'{"suspended_until": Infinity}', b'{"suspended_until": 1e999}',
                 b'{"suspended_until": -5}'):
        r = appenv_ui.client.post("/api/v1/heartbeat", content=body,
                                  headers={**wearer_headers(),
                                           "Content-Type": "application/json"})
        assert r.status_code == 422, body
    # a bad value that somehow got stored must not take the dashboard down
    appenv_ui.store.set_suspended_until("default", float("inf"))
    assert appenv_ui.client.get("/ui/").status_code == 200
    assert appenv_ui.client.get("/ui/wearers/default").status_code == 200
    appenv_ui.store.set_suspended_until("default", time.time() + 3600)
    assert "suspended 59m" in appenv_ui.client.get("/ui/").text
    assert csrf


def test_admin_api_cookie_session_needs_csrf_on_mutations(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    r = appenv_ui.client.post("/api/v1/admin/wearers/default/disable",
                              headers={"Content-Type": "application/x-www-form-urlencoded"},
                              content=b"")
    assert r.status_code == 403
    assert appenv_ui.store.get_wearer("default")["enabled"] == 1
    r = appenv_ui.client.post("/api/v1/admin/wearers/default/disable",
                              headers={"X-CSRF": csrf})
    assert r.status_code == 200
    assert appenv_ui.store.get_wearer("default")["enabled"] == 0
    # reads keep working with the cookie alone
    assert appenv_ui.client.get("/api/v1/admin/wearers").status_code == 200


def test_poll_loop_survives_a_failing_update(appenv, monkeypatch):
    calls = []

    def fake_api(bot_token, method, params, timeout):
        calls.append((method, params))
        if method == "getUpdates":
            n = len([c for c in calls if c[0] == "getUpdates"])
            if n == 1:
                return {"result": [
                    {"update_id": 41, "message": {"text": "hi", "chat": {"id": 7},
                                                  "from": {"first_name": "x"}}},
                    {"update_id": 42,
                     "callback_query": {"id": "cb9", "data": "ack:rawtok"}}]}
            if n == 2:
                assert params["offset"] == 43     # the batch was consumed
                return {"result": []}
            raise asyncio.CancelledError
        if method == "sendMessage":
            raise urllib.error.HTTPError("https://api.telegram.org/botBOT/sendMessage",
                                         403, "Forbidden", {}, None)
        return {"ok": True}

    monkeypatch.setattr(telegram_poll, "_api", fake_api)
    acked = []

    async def on_ack(token):
        acked.append(token)
        return "Acknowledged"

    async def run():
        try:
            await telegram_poll.poll_loop("BOT", appenv.store, on_ack, idle_sleep_s=0)
        except asyncio.CancelledError:
            pass

    asyncio.run(run())
    assert acked == ["rawtok"]
    assert appenv.store.kv_get(telegram_poll.OFFSET_KEY) == "42"


def test_diagnostics_uploads_per_wearer_are_bounded(appenv, monkeypatch):
    from app import diagnostics
    monkeypatch.setattr(diagnostics, "MAX_BUNDLES_PER_WEARER", 3)
    h = {"Content-Type": "application/zip", **wearer_headers()}
    for _ in range(3):
        r = appenv.client.post("/api/v1/diagnostics?days=1", content=b"PK\x03\x04", headers=h)
        assert r.status_code == 200, r.text
    r = appenv.client.post("/api/v1/diagnostics?days=1", content=b"PK\x03\x04", headers=h)
    assert r.status_code == 429
    assert len(appenv.store.list_diagnostics("default")) == 3
