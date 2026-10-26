"""Operator accounts, sessions, roles, and CSRF for the web dashboard
(design D2/D3/D6).

Passwords: stdlib hashlib.scrypt (n=16384, r=8, p=1), per-password salt,
stored as JSON. Sessions: 256-bit random ids stored hashed, HttpOnly
cookies, server-side revocation. Roles: 'admin' and 'responder' — one
check, no permission framework. CSRF: per-session token verified on
every state-changing UI route via header (htmx) or form field.
"""
from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import secrets
import time
from dataclasses import dataclass

from fastapi import HTTPException, Request

from .store import db

log = logging.getLogger("cryomonitor.operators")

SESSION_COOKIE = "cm_session"
SESSION_TTL_S = int(os.environ.get("CM_SESSION_TTL_S", str(7 * 86400)))
LOGIN_WINDOW_S = 900
LOGIN_MAX_ATTEMPTS = 5          # per address, and per account from one address
LOGIN_USER_ALERT_ATTEMPTS = 20  # per account from anywhere: audited, never a lockout
_SCRYPT = {"n": 16384, "r": 8, "p": 1}


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    h = hashlib.scrypt(password.encode(), salt=salt, **_SCRYPT)
    return json.dumps({"salt": base64.b64encode(salt).decode(),
                       "hash": base64.b64encode(h).decode(), **_SCRYPT})


def verify_password(password: str, pw_json: str) -> bool:
    try:
        d = json.loads(pw_json)
        h = hashlib.scrypt(password.encode(),
                           salt=base64.b64decode(d["salt"]),
                           n=d["n"], r=d["r"], p=d["p"])
        return secrets.compare_digest(h, base64.b64decode(d["hash"]))
    except (KeyError, ValueError):
        return False


def _hash_session(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


@dataclass(frozen=True)
class Operator:
    username: str
    role: str            # "admin" | "responder"
    csrf: str


def bootstrap_admin() -> bool:
    """First-boot admin from env, consumed exactly once (design D5)."""
    if db.count_admins() > 0:
        return False
    user = os.environ.get("CM_UI_ADMIN_USER", "")
    password = os.environ.get("CM_UI_ADMIN_PASSWORD", "")
    if not user or not password:
        return False
    db.create_operator(user, hash_password(password), "admin")
    db.add_event(None, "operator_bootstrap",
                 {"operator": user,
                  "note": "initial admin from env; remove the CM_UI_ADMIN_* "
                          "variables from .env now"})
    return True


def admin_accounts_exist() -> bool:
    return db.count_admins() > 0


# ---- login / logout ----

def check_rate_limit(username: str, client: str) -> None:
    # The account bucket is bound to the calling address: a stranger's
    # failures for this username never lock the operator out from another
    # address (security hunt 2026-10-26, check_rate_limit:username-lockout).
    since = time.time() - LOGIN_WINDOW_S
    if (db.login_attempts_since(f"u:{username}@{client}", since) >= LOGIN_MAX_ATTEMPTS or
            db.login_attempts_since(f"a:{client}", since) >= LOGIN_MAX_ATTEMPTS):
        raise HTTPException(429, "too many login attempts; wait 15 minutes")


def login(username: str, password: str, client: str) -> tuple[str, Operator] | None:
    """Returns (raw session id, operator) or None. Generic failures only."""
    check_rate_limit(username, client)
    op = db.get_operator(username)
    ok = op is not None and bool(op["enabled"]) and \
        verify_password(password, op["pw"])
    if not ok:
        db.record_login_attempt(f"u:{username}@{client}")
        db.record_login_attempt(f"a:{client}")
        db.record_login_attempt(f"u:{username}")
        db.add_event(None, "login_failed", {"operator": username[:64],
                                            "client": client})
        if db.login_attempts_since(f"u:{username}", time.time() - LOGIN_WINDOW_S) \
                == LOGIN_USER_ALERT_ATTEMPTS:
            db.add_event(None, "login_guessing_suspected",
                         {"operator": username[:64], "client": client})
        return None
    raw = secrets.token_urlsafe(32)
    csrf = secrets.token_urlsafe(24)
    db.create_session(_hash_session(raw), username, csrf,
                      time.time() + SESSION_TTL_S)
    db.add_event(None, "login", {"operator": username, "client": client})
    return raw, Operator(username, op["role"], csrf)


def logout(raw_session: str) -> None:
    db.revoke_session(_hash_session(raw_session))


# ---- request dependencies ----

def current_operator(request: Request) -> Operator | None:
    raw = request.cookies.get(SESSION_COOKIE)
    if not raw:
        return None
    row = db.lookup_session(_hash_session(raw), time.time())
    if not row:
        return None
    return Operator(row["username"], row["role"], row["csrf"])


def require_operator(request: Request) -> Operator:
    op = current_operator(request)
    if op is None:
        raise HTTPException(status_code=303, detail="login required",
                            headers={"Location": "/ui/login"})
    return op


def require_ui_admin(request: Request) -> Operator:
    op = require_operator(request)
    if op.role != "admin":
        db.add_event(None, "admin_refused", {"operator": op.username,
                                             "surface": "ui"})
        raise HTTPException(403, "admin role required")
    return op


async def verify_csrf(request: Request, op: Operator) -> None:
    """State-changing UI routes only. Header (htmx) or form field."""
    supplied = request.headers.get("X-CSRF", "")
    if not supplied:
        form = await request.form()
        supplied = str(form.get("csrf", ""))
    if not secrets.compare_digest(supplied, op.csrf):
        raise HTTPException(403, "CSRF token missing or wrong")


def secure_cookies() -> bool:
    # Tests and plain-HTTP tailnet deployments set CM_UI_INSECURE_COOKIES=1;
    # the public deployment terminates TLS at DSM and keeps Secure on.
    return os.environ.get("CM_UI_INSECURE_COOKIES") != "1"
