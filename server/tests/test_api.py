"""Phone-facing API surface: auth, health/status exposure, alarm lifecycle."""
from conftest import admin_headers, wearer_headers


def test_health_is_public_and_leaks_nothing(appenv):
    r = appenv.client.get("/api/v1/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    # count-only degradation signal; never names or ids
    assert set(body) == {"status", "service", "version", "wearers_degraded"}


def test_status_requires_token(appenv):
    assert appenv.client.get("/api/v1/status").status_code == 401
    r = appenv.client.get("/api/v1/status", headers=wearer_headers())
    assert r.status_code == 200
    assert r.json()["phone"] == "ok"


def test_bearer_and_legacy_query_token_both_work(appenv):
    ok = appenv.client.post("/api/v1/heartbeat",
                            json={"phone_battery_pct": 70},
                            headers=wearer_headers())
    assert ok.status_code == 200
    legacy = appenv.client.post("/api/v1/heartbeat",
                                json={"token": "testtoken",
                                      "phone_battery_pct": 70})
    assert legacy.status_code == 200


def test_heartbeat_trail_stores_both_batteries(appenv):
    """M0 spike S6: watch battery drain is measured from the trail."""
    r = appenv.client.post("/api/v1/heartbeat",
                           json={"phone_battery_pct": 70,
                                 "watch_battery_pct": 80},
                           headers=wearer_headers())
    assert r.status_code == 200
    trail = appenv.store.heartbeat_trail("default")
    assert trail and trail[0]["battery"] == 70
    assert trail[0]["watch_battery"] == 80


def test_latency_drill_command_leased_until_acked(appenv):
    """M0 spike S1 + review finding 14: the drill is redelivered on every
    heartbeat until the phone acks it (a deleted-on-read command dies with
    a lost HTTP response); the ack consumes it."""
    appenv.store.queue_command("default", "latency_drill")
    first = appenv.client.post("/api/v1/heartbeat", json={},
                               headers=wearer_headers()).json()
    assert first.get("command") == "latency_drill"
    second = appenv.client.post("/api/v1/heartbeat", json={},
                                headers=wearer_headers()).json()
    assert second.get("command") == "latency_drill"  # unacked: redelivered
    third = appenv.client.post("/api/v1/heartbeat",
                               json={"command_ack": "latency_drill"},
                               headers=wearer_headers()).json()
    assert "command" not in third                    # ack consumed it

    r = appenv.client.post("/api/v1/drill-result",
                           json={"launch_ms": 71, "rtt_ms": 9459,
                                 "watch_ms": 9100, "transport_ms": 359,
                                 "phone_model": "TestPhone 9"},
                           headers=wearer_headers())
    assert r.status_code == 200
    kinds = [e["kind"] for e in appenv.store.recent_events("default", limit=10)]
    assert "latency_drill" in kinds


def test_bad_credentials_rejected(appenv):
    for hdr in ["Bearer wrong", "Basic testtoken", "testtoken", ""]:
        r = appenv.client.get("/api/v1/status",
                              headers={"Authorization": hdr})
        assert r.status_code == 401, hdr


def test_alarm_lifecycle(appenv):
    r = appenv.client.post("/api/v1/alarm",
                           json={"detector": "impact", "lat": 52.5, "lon": 13.4},
                           headers=wearer_headers())
    assert r.status_code == 200
    esc_id = r.json()["escalation_id"]

    s = appenv.client.get("/api/v1/status", headers=wearer_headers()).json()
    assert esc_id in s["active_escalations"]
    assert s["active_escalations"][esc_id]["detector"] == "impact"

    r = appenv.client.post(f"/api/v1/alarm/{esc_id}/resolve",
                           params={"resolution": "false_alarm"},
                           headers=wearer_headers())
    assert r.status_code == 200
    s = appenv.client.get("/api/v1/status", headers=wearer_headers()).json()
    assert esc_id not in s["active_escalations"]


def test_offline_window(appenv):
    r = appenv.client.post("/api/v1/offline-window",
                           json={"duration_s": 3600},
                           headers=wearer_headers())
    assert r.status_code == 200
    assert r.json()["state"] == "offline_declared"


def test_offline_window_cancel_and_limits(appenv):
    h = wearer_headers()
    appenv.client.post("/api/v1/offline-window", json={"duration_s": 3600}, headers=h)
    r = appenv.client.post("/api/v1/offline-window", json={"duration_s": 0}, headers=h)
    assert r.status_code == 200
    assert r.json()["state"] != "offline_declared"
    assert appenv.client.post("/api/v1/offline-window",
                              json={"duration_s": 8 * 86400}, headers=h).status_code == 400
    assert appenv.client.post("/api/v1/offline-window",
                              json={"duration_s": -5}, headers=h).status_code == 400


def test_admin_status_lists_all_wearers(appenv):
    r = appenv.client.get("/api/v1/status", headers=admin_headers())
    assert r.status_code == 200
    ids = [w["id"] for w in r.json()["wearers"]]
    assert "default" in ids  # legacy bootstrap wearer


def test_admin_cannot_use_phone_endpoints(appenv):
    r = appenv.client.post("/api/v1/heartbeat", json={},
                           headers=admin_headers())
    assert r.status_code == 403
