"""Diagnostics bundles: phone uploads only, admin-only reading, requests
the phone must answer, retention (spec: diagnostics-sharing)."""
import io
import os
import re
import time
import zipfile

from conftest import ui_login, wearer_headers


def _zip_bytes(text: str = "hello") -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("manifest.txt", text)
    return buf.getvalue()


def _upload(client, body: bytes, query: str = "?days=7", headers=None):
    h = {"Content-Type": "application/zip"}
    h.update(wearer_headers() if headers is None else headers)
    return client.post("/api/v1/diagnostics" + query, content=body, headers=h)


def test_upload_needs_a_phone_token(appenv):
    r = _upload(appenv.client, _zip_bytes(), headers={})
    assert r.status_code == 401
    assert appenv.store.list_diagnostics("default") == []


def test_upload_stores_the_zip_per_wearer_and_audits(appenv):
    body = _zip_bytes("bundle-1")
    r = _upload(appenv.client, body)
    assert r.status_code == 200, r.text
    did = r.json()["id"]
    rows = appenv.store.list_diagnostics("default")
    assert [d["id"] for d in rows] == [did]
    assert rows[0]["size"] == len(body) and rows[0]["days"] == 7
    from app import diagnostics
    with open(diagnostics.bundle_path("default", did), "rb") as fh:
        assert fh.read() == body
    kinds = [e["kind"] for e in appenv.store.recent_events("default")]
    assert "diagnostics_uploaded" in kinds


def test_non_zip_and_oversized_bodies_are_refused(appenv, monkeypatch):
    assert _upload(appenv.client, b"just some text").status_code == 415
    assert _upload(appenv.client, b"").status_code == 415
    from app import diagnostics
    monkeypatch.setattr(diagnostics, "MAX_BYTES", 64)
    assert _upload(appenv.client, _zip_bytes("x" * 500)).status_code == 413
    assert appenv.store.list_diagnostics("default") == []
    # no partial files left behind
    base = os.path.join(os.environ["CM_DATA_DIR"], "diagnostics", "default")
    assert not os.path.isdir(base) or os.listdir(base) == []


def test_bad_request_id_is_rejected(appenv):
    r = _upload(appenv.client, _zip_bytes(), query="?days=7&request_id=../../etc")
    assert r.status_code == 422


def _request_from_dashboard(env, days="3"):
    csrf = ui_login(env.client)
    r = env.client.post("/ui/wearers/default/diagnostics/request",
                        data={"csrf": csrf, "days": days})
    assert r.status_code == 200
    reqs = env.store.list_diag_requests("default")
    return csrf, reqs[0]


def test_dashboard_request_only_queues_a_question(appenv_ui):
    _csrf, req = _request_from_dashboard(appenv_ui)
    assert req["state"] == "pending" and req["days"] == 3
    # the phone learns about it through the leased command channel...
    hb = appenv_ui.client.post("/api/v1/heartbeat", json={"phone_battery_pct": 80},
                               headers=wearer_headers()).json()
    assert hb["command"] == f"diag_request:{req['id']}:3"
    # ...and nothing has been uploaded by asking
    assert appenv_ui.store.list_diagnostics("default") == []


def test_upload_with_request_id_fulfils_the_request(appenv_ui):
    _csrf, req = _request_from_dashboard(appenv_ui)
    r = _upload(appenv_ui.client, _zip_bytes(), query=f"?days=3&request_id={req['id']}")
    assert r.status_code == 200
    req2 = appenv_ui.store.get_diag_request("default", req["id"])
    assert req2["state"] == "fulfilled"
    assert req2["diagnostics_id"] == r.json()["id"]


def test_decline_is_recorded_and_idempotent(appenv_ui):
    _csrf, req = _request_from_dashboard(appenv_ui)
    url = f"/api/v1/diagnostics/requests/{req['id']}/decline"
    r = appenv_ui.client.post(url, headers=wearer_headers())
    assert r.status_code == 200 and r.json()["state"] == "declined"
    assert appenv_ui.client.post(url, headers=wearer_headers()).json()["state"] == "declined"
    assert appenv_ui.client.post("/api/v1/diagnostics/requests/nope/decline",
                                 headers=wearer_headers()).json()["state"] == "unknown"
    kinds = [e["kind"] for e in appenv_ui.store.recent_events("default")]
    assert kinds.count("diagnostics_declined") == 1


def test_a_newer_request_supersedes_a_pending_one(appenv_ui):
    csrf, first = _request_from_dashboard(appenv_ui)
    appenv_ui.client.post("/ui/wearers/default/diagnostics/request",
                          data={"csrf": csrf, "days": "14"})
    states = {r["id"]: r["state"] for r in appenv_ui.store.list_diag_requests("default")}
    assert states[first["id"]] == "superseded"
    assert sorted(states.values()) == ["pending", "superseded"]


def test_admin_downloads_and_deletes_responder_cannot(appenv_ui):
    did = _upload(appenv_ui.client, _zip_bytes("secret-ish")).json()["id"]
    csrf = ui_login(appenv_ui.client)
    page = appenv_ui.client.get("/ui/wearers/default")
    assert did in page.text and "Request diagnostics" in page.text
    dl = appenv_ui.client.get(f"/ui/wearers/default/diagnostics/{did}")
    assert dl.status_code == 200 and dl.content.startswith(b"PK")
    appenv_ui.client.post("/ui/operators/create",
                          data={"username": "resp", "password": "longenough1",
                                "role": "responder", "csrf": csrf})
    ui_login(appenv_ui.client, "resp", "longenough1")
    assert appenv_ui.client.get(
        f"/ui/wearers/default/diagnostics/{did}").status_code == 403
    assert "Request diagnostics" not in appenv_ui.client.get("/ui/wearers/default").text
    csrf = ui_login(appenv_ui.client)
    appenv_ui.client.post(f"/ui/wearers/default/diagnostics/{did}/delete",
                          data={"csrf": csrf})
    assert appenv_ui.store.list_diagnostics("default") == []
    kinds = [e["kind"] for e in appenv_ui.store.recent_events("default", limit=50)]
    assert "diagnostics_downloaded" in kinds and "diagnostics_deleted" in kinds


def test_download_is_scoped_to_the_wearer_in_the_url(appenv_ui):
    did = _upload(appenv_ui.client, _zip_bytes()).json()["id"]
    csrf = ui_login(appenv_ui.client)
    appenv_ui.client.post("/ui/wearers/create", data={"id": "bob", "name": "Bob",
                                                      "csrf": csrf})
    assert appenv_ui.client.get(
        f"/ui/wearers/bob/diagnostics/{did}").status_code == 404


def test_retention_deletes_old_bundles(appenv):
    did = _upload(appenv.client, _zip_bytes()).json()["id"]
    from app import diagnostics
    path = diagnostics.bundle_path("default", did)
    assert diagnostics.purge_expired(time.time()) == 0
    later = time.time() + (diagnostics.RETENTION_DAYS + 1) * 86400
    assert diagnostics.purge_expired(later) == 1
    assert appenv.store.list_diagnostics("default") == []
    assert not os.path.exists(path)
