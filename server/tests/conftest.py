"""Shared fixtures: a fresh app + temp SQLite per test, fake channels."""
from __future__ import annotations

import importlib
import os

import pytest
from fastapi.testclient import TestClient

WEARER_TOKEN = "testtoken"          # becomes wearer 'default' via bootstrap
ADMIN_TOKEN = "admintok"
UI_ADMIN = ("adminui", "s3cretpass!")


class FakeChannel:
    def __init__(self, name: str):
        self.name = name
        self.sent: list[tuple] = []   # (address, text, ack_url, ack_token)
        self.fail = False
        self.on_deliver = None        # optional hook(address, ...)

    def deliver(self, address, text, ack_url, ack_token) -> bool:
        if self.on_deliver:
            self.on_deliver(address, text, ack_url, ack_token)
        if self.fail:
            return False
        self.sent.append((address, text, ack_url, ack_token))
        return True


def _build_appenv(tmp_path, monkeypatch, ui_bootstrap: bool):
    monkeypatch.setenv("CM_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("CM_API_TOKEN", WEARER_TOKEN)
    monkeypatch.setenv("CM_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("CM_DISABLE_PUMP", "1")
    monkeypatch.setenv("CM_DISABLE_TG_POLL", "1")
    monkeypatch.setenv("CM_PUBLIC_URL", "https://cm.test")
    monkeypatch.setenv("CM_LOG_LEVEL", "WARNING")
    monkeypatch.setenv("CM_UI_INSECURE_COOKIES", "1")  # TestClient is http
    if ui_bootstrap:
        # bootstrapping a web admin RETIRES CM_ADMIN_TOKEN (design D5)
        monkeypatch.setenv("CM_UI_ADMIN_USER", UI_ADMIN[0])
        monkeypatch.setenv("CM_UI_ADMIN_PASSWORD", UI_ADMIN[1])
    else:
        monkeypatch.delenv("CM_UI_ADMIN_USER", raising=False)
        monkeypatch.delenv("CM_UI_ADMIN_PASSWORD", raising=False)

    from app import store as store_mod
    store = importlib.reload(store_mod)
    from app import wearers as wearers_mod
    wearers = importlib.reload(wearers_mod)
    from app import operators as operators_mod
    operators = importlib.reload(operators_mod)
    from app import diagnostics as diagnostics_mod
    importlib.reload(diagnostics_mod)
    from app import ui as ui_mod
    importlib.reload(ui_mod)
    from app import main as main_mod
    main = importlib.reload(main_mod)

    fakes = {"telegram": FakeChannel("telegram"),
             "ntfy": FakeChannel("ntfy"),
             "email": FakeChannel("email")}
    main.CHANNELS = fakes

    client = TestClient(main.app)
    client.__enter__()
    return client, type("AppEnv", (), {
        "client": client, "main": main, "store": store.db,
        "wearers": wearers, "operators": operators, "fakes": fakes,
    })()


@pytest.fixture()
def appenv(tmp_path, monkeypatch):
    """Fresh app, no web-admin bootstrap: CM_ADMIN_TOKEN stays valid."""
    client, env = _build_appenv(tmp_path, monkeypatch, ui_bootstrap=False)
    try:
        yield env
    finally:
        client.__exit__(None, None, None)


@pytest.fixture()
def appenv_ui(tmp_path, monkeypatch):
    """Fresh app WITH the bootstrapped web admin (env token retired)."""
    client, env = _build_appenv(tmp_path, monkeypatch, ui_bootstrap=True)
    try:
        yield env
    finally:
        client.__exit__(None, None, None)


def ui_login(client, username: str = UI_ADMIN[0],
             password: str = UI_ADMIN[1]) -> str:
    """Logs the TestClient in; returns the session's CSRF token."""
    import re
    r = client.post("/ui/login",
                    data={"username": username, "password": password},
                    follow_redirects=False)
    assert r.status_code == 303, r.text
    page = client.get("/ui/")
    m = re.search(r'"X-CSRF": "([^"]+)"', page.text)
    assert m, "csrf token not found in page"
    return m.group(1)


def wearer_headers(token: str = WEARER_TOKEN) -> dict:
    return {"Authorization": f"Bearer {token}"}


def admin_headers() -> dict:
    return {"Authorization": f"Bearer {ADMIN_TOKEN}"}
