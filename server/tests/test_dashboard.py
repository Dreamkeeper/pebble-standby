"""Web dashboard: auth, roles, CSRF, fleet ordering, actions, audit,
admin-token retirement."""
import re
import time

from conftest import UI_ADMIN, ui_login, wearer_headers


# ---- pre-auth surface ----

def test_unauthenticated_ui_is_a_blank_door(appenv_ui):
    r = appenv_ui.client.get("/ui/", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/ui/login"
    login = appenv_ui.client.get("/ui/login")
    assert login.status_code == 200
    assert "default" not in login.text        # no wearer-derived bytes
    assert appenv_ui.client.get(
        "/ui/static/htmx.min.js").status_code == 200


def test_wearer_token_does_not_open_the_ui(appenv_ui):
    r = appenv_ui.client.get("/ui/", headers=wearer_headers(),
                          follow_redirects=False)
    assert r.status_code == 303               # still just the login door


# ---- login / sessions ----

def test_bootstrap_admin_can_login_and_logout(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    assert appenv_ui.client.get("/ui/").status_code == 200
    r = appenv_ui.client.post("/ui/logout", data={"csrf": csrf},
                           follow_redirects=False)
    assert r.status_code == 303
    r = appenv_ui.client.get("/ui/", follow_redirects=False)
    assert r.status_code == 303               # session revoked server-side


def test_login_failures_are_generic_and_rate_limited(appenv_ui):
    for i in range(5):
        r = appenv_ui.client.post("/ui/login",
                               data={"username": UI_ADMIN[0],
                                     "password": "wrong"},
                               follow_redirects=False)
        assert r.status_code == 401
        assert "failed" in r.text.lower()
    r = appenv_ui.client.post("/ui/login",
                           data={"username": UI_ADMIN[0],
                                 "password": UI_ADMIN[1]})
    assert r.status_code == 429               # even the right password now


def test_session_cookie_does_not_authenticate_the_api(appenv_ui):
    ui_login(appenv_ui.client)
    r = appenv_ui.client.get("/api/v1/status")   # no bearer, only cookie
    assert r.status_code == 401


# ---- CSRF ----

def test_state_changing_routes_require_csrf(appenv_ui):
    ui_login(appenv_ui.client)
    r = appenv_ui.client.post("/ui/wearers/create",
                           data={"id": "x1", "name": "X"},
                           follow_redirects=False)
    assert r.status_code == 403               # no token
    csrf = ui_login(appenv_ui.client)
    r = appenv_ui.client.post("/ui/wearers/create",
                           data={"id": "x1", "name": "X", "csrf": csrf},
                           follow_redirects=False)
    assert r.status_code == 303


# ---- roles ----

def _make_responder(appenv_ui, csrf):
    r = appenv_ui.client.post("/ui/operators/create",
                           data={"username": "resp", "password": "longenough1",
                                 "role": "responder", "csrf": csrf},
                           follow_redirects=False)
    assert r.status_code == 303


def test_responder_cannot_administrate(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    _make_responder(appenv_ui, csrf)
    rcsrf = ui_login(appenv_ui.client, "resp", "longenough1")
    assert appenv_ui.client.get("/ui/").status_code == 200          # can view
    assert appenv_ui.client.get("/ui/operators").status_code == 403
    r = appenv_ui.client.post("/ui/wearers/create",
                           data={"id": "x2", "name": "X", "csrf": rcsrf},
                           follow_redirects=False)
    assert r.status_code == 403
    # refusal is audited
    events = appenv_ui.store.recent_events(None, limit=10)
    assert any(e["kind"] == "admin_refused" for e in events)


def test_cannot_demote_last_admin_or_disable_self(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    r = appenv_ui.client.post(f"/ui/operators/{UI_ADMIN[0]}/role",
                           data={"role": "responder", "csrf": csrf},
                           follow_redirects=False)
    assert r.status_code == 409
    r = appenv_ui.client.post(f"/ui/operators/{UI_ADMIN[0]}/disable",
                           data={"csrf": csrf}, follow_redirects=False)
    assert r.status_code == 409


def test_disabled_operator_and_password_reset_kill_sessions(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    _make_responder(appenv_ui, csrf)
    ui_login(appenv_ui.client, "resp", "longenough1")
    csrf = ui_login(appenv_ui.client)  # back to admin (new session)
    r = appenv_ui.client.post("/ui/operators/resp/password",
                           data={"password": "newpassword9", "csrf": csrf},
                           follow_redirects=False)
    assert r.status_code == 303
    # old responder password no longer works; new one does
    r = appenv_ui.client.post("/ui/login",
                           data={"username": "resp",
                                 "password": "longenough1"},
                           follow_redirects=False)
    assert r.status_code == 401
    assert ui_login(appenv_ui.client, "resp", "newpassword9")


# ---- fleet ordering ----

def test_fleet_sorts_worst_first(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    appenv_ui.client.post("/ui/wearers/create",
                       data={"id": "calm", "name": "Calm", "csrf": csrf})
    # default wearer gets an unacked escalation via the API
    appenv_ui.client.post("/api/v1/alarm", json={"detector": "impact"},
                       headers=wearer_headers())
    page = appenv_ui.client.get("/ui/fragments/fleet")
    assert page.status_code == 200
    assert page.text.index("default") < page.text.index("calm")
    assert "Alarm active" in page.text


def test_fleet_battery_columns(appenv_ui):
    client = appenv_ui.client
    ui_login(client)
    for point, expected in [
        (None, ("—", "—")),
        ((90, 5), ("90%", "5% · time unknown")),
        ((0, None), ("0%", "—")),
        ((-1, 0), ("—", "0% · time unknown")),
        ((None, -1), ("—", "—")),
    ]:
        if point is not None:
            appenv_ui.store.add_heartbeat_point("default", *point)
        for path in ("/ui/", "/ui/fragments/fleet"):
            page = client.get(path)
            assert page.status_code == 200
            assert "<th>Phone battery</th><th>Watch battery (last known)</th>" in page.text
            cells = re.findall(r"<td>([^<>]*)</td>", page.text)
            assert tuple(cells[-3:-1]) == expected


def test_phone_repeats_old_battery_observation(appenv_ui):
    client = appenv_ui.client
    ui_login(client)
    observed = int(time.time()) - 12 * 60
    heartbeat = {"watch_battery_pct": 53, "watch_battery_observed_at": observed}
    for _ in range(2):
        assert client.post("/api/v1/heartbeat", headers=wearer_headers(), json=heartbeat).is_success
    assert [p["watch_battery_observed_at"] for p in appenv_ui.store.heartbeat_trail("default")] == [observed] * 2
    for path in ("/ui/", "/ui/fragments/fleet"):
        assert "<td>53% · 12m ago</td>" in client.get(path).text


def test_drill_and_real_alarm_have_separate_badges(appenv_ui):
    ui_login(appenv_ui.client)
    for kind in ("test", "watch_alarm"):
        appenv_ui.client.post("/api/v1/alarm", json={"detector": "impact", "kind": kind},
                              headers=wearer_headers())
    for path in ("/ui/fragments/fleet", "/ui/fragments/wearer/default"):
        page = appenv_ui.client.get(path)
        assert 'st-test">Fire drill · awaiting acknowledgement' in page.text
        assert 'st-alarm">Alarm active · awaiting acknowledgement' in page.text


def test_wearer_live_fragment_tracks_ack_without_reload(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    appenv_ui.client.post("/api/v1/alarm", json={"detector": "impact"},
                          headers=wearer_headers())
    frag = appenv_ui.client.get("/ui/fragments/wearer/default")
    assert frag.status_code == 200
    assert "Awaiting acknowledgement" in frag.text
    esc_id = frag.text.split("<b>")[1].split("</b>")[0]
    r = appenv_ui.client.post(f"/ui/escalations/{esc_id}/ack",
                              data={"csrf": csrf}, follow_redirects=False)
    assert r.status_code == 303
    frag = appenv_ui.client.get("/ui/fragments/wearer/default")
    assert "Awaiting acknowledgement" not in frag.text and "acknowledged" in frag.text
    # the fragment is behind the operator session like every other page
    from fastapi.testclient import TestClient
    anon = TestClient(appenv_ui.client.app)
    assert anon.get("/ui/fragments/wearer/default",
                    follow_redirects=False).status_code in (303, 401)


# ---- escalation actions ----

def test_operator_ack_gates_promotion_and_is_audited(appenv_ui):
    ui_login(appenv_ui.client)
    csrf = ui_login(appenv_ui.client)
    r = appenv_ui.client.post("/api/v1/alarm", json={"detector": "impact"},
                           headers=wearer_headers())
    esc_id = r.json()["escalation_id"]
    esc = appenv_ui.main.escalations[esc_id][1]
    assert not esc.any_ack

    r = appenv_ui.client.post(f"/ui/escalations/{esc_id}/ack",
                           data={"csrf": csrf}, follow_redirects=False)
    assert r.status_code == 303
    assert esc.any_ack                        # engine gating engaged
    events = appenv_ui.store.recent_events("default", limit=10)
    assert any(e["kind"] == "operator_ack" and e["operator"] == UI_ADMIN[0]
               for e in events)


def test_resolve_requires_typed_reason(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    r = appenv_ui.client.post("/api/v1/alarm", json={"detector": "sos"},
                           headers=wearer_headers())
    esc_id = r.json()["escalation_id"]

    r = appenv_ui.client.post(f"/ui/escalations/{esc_id}/resolve",
                           data={"resolution": "handled", "reason": "  ",
                                 "csrf": csrf}, follow_redirects=False)
    assert r.status_code == 422
    assert not appenv_ui.main.escalations[esc_id][1].resolved

    r = appenv_ui.client.post(f"/ui/escalations/{esc_id}/resolve",
                           data={"resolution": "false_alarm",
                                 "reason": "drill during setup",
                                 "csrf": csrf}, follow_redirects=False)
    assert r.status_code == 303
    assert appenv_ui.main.escalations[esc_id][1].resolved
    events = appenv_ui.store.recent_events("default", limit=10)
    assert any(e["kind"] == "resolved" and
               e.get("reason") == "drill during setup" for e in events)


# ---- admin actions ----

def test_enroll_code_shown_once_and_usable(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    r = appenv_ui.client.post("/ui/wearers/default/enroll-code",
                           data={"csrf": csrf})
    assert r.status_code == 200
    import re
    m = re.search(r"<b>([0-9A-Z]{4}-[0-9A-Z]{4})</b>", r.text)
    assert m, "code not displayed"
    enroll = appenv_ui.client.post("/api/v1/enroll", json={"code": m.group(1)})
    assert enroll.status_code == 200


def test_ui_fire_drill_creates_test_escalation(appenv_ui):
    csrf = ui_login(appenv_ui.client)
    r = appenv_ui.client.post("/ui/wearers/default/drill",
                           data={"csrf": csrf}, follow_redirects=False)
    assert r.status_code == 303
    kinds = [e.kind.value for _, e in appenv_ui.main.escalations.values()]
    assert "test" in kinds


# ---- admin-token retirement (design D5) ----

def test_env_admin_token_is_retired_once_accounts_exist(appenv_ui):
    # bootstrap created an admin account in this fixture, so the env
    # token must be refused with a pointed message on the API
    r = appenv_ui.client.get("/api/v1/status",
                          headers={"Authorization": "Bearer admintok"})
    assert r.status_code == 403
    assert "retired" in r.json()["detail"]


def test_operator_session_administrates_the_api(appenv_ui):
    """Design D5 parity: admin API routes accept operator sessions."""
    ui_login(appenv_ui.client)
    r = appenv_ui.client.get("/api/v1/admin/wearers")  # cookie only
    assert r.status_code == 200
    assert any(w["id"] == "default" for w in r.json()["wearers"])
