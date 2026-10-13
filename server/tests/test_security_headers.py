"""Response hardening for the dashboard and the API (hardening 2026-10-13).

Every response carries the security headers; the dashboard pages are not
cached; the API description is not served unless CM_API_DOCS=1; the
dashboard's scripts need no eval, so the CSP allows none.
"""
import pathlib

from conftest import ui_login, wearer_headers

REQUIRED = {
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "referrer-policy": "same-origin",
    "cross-origin-opener-policy": "same-origin",
}


def _assert_hardened(r):
    for k, v in REQUIRED.items():
        assert r.headers.get(k) == v, (k, r.headers.get(k))
    csp = r.headers["content-security-policy"]
    assert "default-src 'self'" in csp
    assert "script-src 'self'" in csp and "unsafe-eval" not in csp
    assert "frame-ancestors 'none'" in csp
    assert "form-action 'self'" in csp
    assert "object-src 'none'" in csp
    assert "permissions-policy" in r.headers


def test_login_page_and_api_carry_the_headers(appenv_ui):
    c = appenv_ui.client
    r = c.get("/ui/login")
    assert r.status_code == 200
    _assert_hardened(r)
    assert r.headers["cache-control"] == "no-store"
    # an API error response too: headers come from one middleware
    r = c.get("/api/v1/status")
    assert r.status_code in (401, 403)
    _assert_hardened(r)


def test_logged_in_pages_are_not_cached(appenv_ui):
    c = appenv_ui.client
    ui_login(c)
    r = c.get("/ui/")
    assert r.status_code == 200
    assert r.headers["cache-control"] == "no-store"
    _assert_hardened(r)


def test_api_description_is_off_by_default(appenv):
    c = appenv.client
    for path in ("/openapi.json", "/docs", "/redoc"):
        assert c.get(path).status_code == 404, path
    # the health endpoint still works for deploy checks
    assert c.get("/api/v1/health").status_code == 200


def test_dashboard_scripts_need_no_eval():
    base = pathlib.Path(__file__).resolve().parents[1] / "app"
    html = (base / "templates" / "base.html").read_text(encoding="utf-8")
    assert '"allowEval": false' in html
    wearer = (base / "templates" / "wearer.html").read_text(encoding="utf-8")
    assert "[" not in wearer.split("hx-trigger=")[1].split("\n")[0], \
        "an hx-trigger event filter needs eval; the rule lives in dashboard.js"
    assert "htmx:beforeSwap" in (base / "static" / "dashboard.js").read_text(encoding="utf-8")


def test_static_script_is_served(appenv_ui):
    r = appenv_ui.client.get("/ui/static/dashboard.js")
    assert r.status_code == 200
    assert "javascript" in r.headers["content-type"]
