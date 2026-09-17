"""Client identity behind a reverse proxy.

Login and enrollment throttles key on the caller's address. Behind any
reverse proxy (Caddy on a VPS, DSM on a NAS) that address is the proxy's
for every caller unless X-Forwarded-For from the proxy is trusted —
uvicorn does this when FORWARDED_ALLOW_IPS names the proxy. These tests
run the same middleware uvicorn installs.
"""
from fastapi.testclient import TestClient
from uvicorn.middleware.proxy_headers import ProxyHeadersMiddleware

BAD_CODE = {"code": "ZZZZ-ZZZZ"}   # well-formed, never issued -> 410


def _attempt(client, ip):
    return client.post("/api/v1/enroll", json=BAD_CODE,
                       headers={"X-Forwarded-For": ip})


def test_each_client_behind_a_trusted_proxy_has_its_own_rate_limit(appenv):
    proxied = TestClient(ProxyHeadersMiddleware(appenv.main.app, trusted_hosts="*"))
    limit = appenv.wearers.ENROLL_RATE
    for _ in range(limit):
        assert _attempt(proxied, "203.0.113.7").status_code == 410
    assert _attempt(proxied, "203.0.113.7").status_code == 429   # that client is throttled
    assert _attempt(proxied, "198.51.100.9").status_code == 410  # another client is not


def test_forwarded_headers_from_an_untrusted_peer_are_ignored(appenv):
    # The TestClient peer ("testclient") is not in the trusted list, so a
    # spoofed X-Forwarded-For must not buy a fresh rate-limit bucket.
    direct = TestClient(ProxyHeadersMiddleware(appenv.main.app, trusted_hosts="10.0.0.1"))
    limit = appenv.wearers.ENROLL_RATE
    for i in range(limit):
        assert _attempt(direct, f"203.0.113.{i}").status_code == 410
    assert _attempt(direct, "198.51.100.9").status_code == 429


def test_failed_login_audit_records_the_real_client(appenv_ui):
    proxied = TestClient(ProxyHeadersMiddleware(appenv_ui.main.app, trusted_hosts="*"))
    proxied.post("/ui/login", data={"username": "nobody", "password": "x"},
                 headers={"X-Forwarded-For": "203.0.113.44"})
    events = appenv_ui.store.recent_events(None)
    failed = [e for e in events if e.get("kind") == "login_failed" or e.get("type") == "login_failed"]
    assert failed, events
    assert "203.0.113.44" in str(failed[0])
