"""Tenancy: auth resolution, enrollment, wearer administration, and
contact/tier CRUD (design D8/D9/D10).

Auth resolves in exactly one place: ``resolve_auth`` maps the request
credential to a role plus wearer id. Everything downstream is keyed by
that wearer id; no module-level "current wearer" exists anywhere.
"""
from __future__ import annotations

import hashlib
import os
import re
import secrets
import time
from dataclasses import dataclass

from fastapi import APIRouter, Header, HTTPException, Query, Request
from pydantic import BaseModel

from .store import db

router = APIRouter(prefix="/api/v1")

ADMIN_TOKEN = os.environ.get("CM_ADMIN_TOKEN", "")
ENROLL_TTL_S = int(os.environ.get("CM_ENROLL_TTL_S", str(24 * 3600)))
# One wearer's safety net is a handful of people; an uncapped list is a
# relay for whoever holds the token (security hunt 2026-10-26).
MAX_CONTACTS_PER_WEARER = 10

_CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def hash_token(value: str) -> str:
    # High-entropy random tokens: plain SHA-256 is appropriate (design D8).
    return hashlib.sha256(value.encode()).hexdigest()


@dataclass(frozen=True)
class Auth:
    role: str            # "wearer" | "admin"
    wearer_id: str | None


def _bearer(authorization: str | None, token: str = "") -> str:
    supplied = token or ""
    if authorization:
        scheme, _, value = authorization.partition(" ")
        if scheme.lower() == "bearer":
            supplied = value.strip()
    return supplied


def resolve_auth(authorization: str | None, token: str = "") -> Auth | None:
    supplied = _bearer(authorization, token)
    if not supplied:
        return None
    if ADMIN_TOKEN and secrets.compare_digest(supplied, ADMIN_TOKEN):
        # Design D5: once the first web admin account exists, the env
        # credential is retired — one admin auth system, not two.
        from .operators import admin_accounts_exist
        if admin_accounts_exist():
            raise HTTPException(
                403,
                "CM_ADMIN_TOKEN is retired: operator accounts exist. Use the "
                "web dashboard (or an operator session) for administration, "
                "and remove CM_ADMIN_TOKEN from .env.")
        return Auth(role="admin", wearer_id=None)
    wearer_id = db.wearer_for_token(hash_token(supplied))
    if wearer_id:
        return Auth(role="wearer", wearer_id=wearer_id)
    return None


def require_wearer(authorization: str | None = Header(default=None),
                   token: str = "") -> Auth:
    auth = resolve_auth(authorization, token)
    if auth is None:
        raise HTTPException(401, "bad token")
    if auth.role != "wearer":
        raise HTTPException(403, "wearer credential required")
    return auth


def require_admin(authorization: str | None = Header(default=None),
                  request: Request | None = None) -> Auth:
    # Operator sessions (web dashboard) administrate the API too — one
    # admin auth system after CM_ADMIN_TOKEN retires (design D5).
    if request is not None:
        from .operators import current_operator
        op = current_operator(request)
        if op is not None:
            if op.role == "admin":
                # A cookie is ambient: a mutation needs the session's CSRF
                # token in the X-CSRF header, as every /ui route requires
                # (security hunt 2026-10-26, require_admin:cookie-path-no-csrf).
                if request.method not in ("GET", "HEAD", "OPTIONS"):
                    supplied = request.headers.get("X-CSRF", "")
                    if not secrets.compare_digest(supplied, op.csrf):
                        raise HTTPException(403, "CSRF token missing or wrong")
                return Auth(role="admin", wearer_id=None)
            db.add_event(None, "admin_refused",
                         {"operator": op.username, "surface": "api"})
            raise HTTPException(403, "admin role required")
    auth = resolve_auth(authorization)
    if auth is None:
        raise HTTPException(401, "bad token")
    if auth.role != "admin":
        db.add_event(auth.wearer_id, "admin_refused", {"role": auth.role})
        raise HTTPException(403, "admin credential required")
    return auth


def scoped_wearer_id(auth: Auth, wearer_id: str | None) -> str:
    """Admin must name a wearer; a wearer is always themselves."""
    if auth.role == "wearer":
        if wearer_id and wearer_id != auth.wearer_id:
            raise HTTPException(403, "cannot act on another wearer")
        return auth.wearer_id
    if not wearer_id:
        raise HTTPException(422, "wearer_id required for admin operations")
    if not db.get_wearer(wearer_id):
        raise HTTPException(404, "unknown wearer")
    return wearer_id


def legacy_bootstrap() -> bool:
    """First boot with an empty wearer table + legacy CM_API_TOKEN (D10)."""
    legacy = os.environ.get("CM_API_TOKEN", "")
    if not legacy or db.list_wearers():
        return False
    db.create_wearer("default", "Default wearer")
    db.add_token("default", hash_token(legacy))
    db.add_event("default", "legacy_migrated",
                 {"note": "wearer created from CM_API_TOKEN bootstrap"})
    return True


# ---- enrollment (public endpoint, rate limited) ----

_enroll_attempts: dict[str, list[float]] = {}
ENROLL_RATE = 10          # attempts
ENROLL_WINDOW_S = 3600    # per hour per client address


def _rate_limited(client: str) -> bool:
    now = time.time()
    lst = [t for t in _enroll_attempts.get(client, []) if now - t < ENROLL_WINDOW_S]
    lst.append(now)
    _enroll_attempts[client] = lst
    return len(lst) > ENROLL_RATE


def generate_enroll_code() -> str:
    raw = "".join(secrets.choice(_CROCKFORD) for _ in range(8))
    return f"{raw[:4]}-{raw[4:]}"


def _normalize_code(code: str) -> str:
    return code.strip().upper().replace("-", "").replace(" ", "")


class EnrollIn(BaseModel):
    code: str


@router.post("/enroll")
def enroll(body: EnrollIn, request: Request):
    client = request.client.host if request.client else "unknown"
    if _rate_limited(client):
        raise HTTPException(429, "too many attempts; try later")
    normalized = _normalize_code(body.code)
    if not re.fullmatch(f"[{_CROCKFORD}]{{8}}", normalized):
        raise HTTPException(422, "malformed code")
    new_token = secrets.token_hex(32)
    wearer_id = db.exchange_enroll_code(
        hash_token(normalized), time.time(), hash_token(new_token))
    if wearer_id is None:
        raise HTTPException(410, "code invalid, expired, or already used")
    db.add_event(wearer_id, "enrolled", {"client": client})
    return {"token": new_token, "wearer_id": wearer_id}


# ---- admin: wearer lifecycle ----

class WearerIn(BaseModel):
    id: str
    name: str


@router.post("/admin/wearers")
def create_wearer(body: WearerIn, request: Request,
                  authorization: str | None = Header(default=None)):
    require_admin(authorization, request)
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,31}", body.id):
        raise HTTPException(422, "id must be lowercase kebab, 2-32 chars")
    if db.get_wearer(body.id):
        raise HTTPException(409, "wearer id exists")
    db.create_wearer(body.id, body.name)
    db.add_event(body.id, "wearer_created", {"name": body.name})
    return {"ok": True, "wearer_id": body.id}


@router.get("/admin/wearers")
def list_wearers(request: Request,
                 authorization: str | None = Header(default=None)):
    require_admin(authorization, request)
    return {"wearers": db.list_wearers()}


@router.post("/admin/wearers/{wearer_id}/disable")
def disable_wearer(wearer_id: str, request: Request,
                   authorization: str | None = Header(default=None)):
    require_admin(authorization, request)
    if not db.get_wearer(wearer_id):
        raise HTTPException(404, "unknown wearer")
    db.set_wearer_enabled(wearer_id, False)
    db.add_event(wearer_id, "wearer_disabled", {})
    from . import main as rt   # stop queued paging now, not next cycle
    rt._resolve_open_for(wearer_id, "wearer_disabled", by="admin")
    return {"ok": True}


@router.post("/admin/wearers/{wearer_id}/enable")
def enable_wearer(wearer_id: str, request: Request,
                  authorization: str | None = Header(default=None)):
    require_admin(authorization, request)
    if not db.get_wearer(wearer_id):
        raise HTTPException(404, "unknown wearer")
    db.set_wearer_enabled(wearer_id, True)
    db.add_event(wearer_id, "wearer_enabled", {})
    return {"ok": True}


@router.post("/admin/wearers/{wearer_id}/enroll-code")
def issue_enroll_code(wearer_id: str, request: Request,
                      authorization: str | None = Header(default=None)):
    require_admin(authorization, request)
    w = db.get_wearer(wearer_id)
    if not w or not w["enabled"]:
        raise HTTPException(404, "unknown or disabled wearer")
    code = generate_enroll_code()
    db.create_enroll_code(wearer_id, hash_token(_normalize_code(code)),
                          time.time() + ENROLL_TTL_S)
    db.add_event(wearer_id, "enroll_code_issued", {"ttl_s": ENROLL_TTL_S})
    return {"code": code, "expires_in_s": ENROLL_TTL_S}


@router.post("/admin/wearers/{wearer_id}/revoke-tokens")
def revoke_tokens(wearer_id: str, request: Request,
                  authorization: str | None = Header(default=None)):
    require_admin(authorization, request)
    n = db.revoke_tokens(wearer_id)
    db.add_event(wearer_id, "tokens_revoked", {"count": n})
    return {"revoked": n}


# ---- contacts and tiers (wearer = own; admin = any via ?wearer_id=) ----

_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
_CHAT_ID_RE = re.compile(r"^-?\d{5,20}$")
_NTFY_RE = re.compile(r"^[A-Za-z0-9_-]{4,64}$")


def _validate_contact(c: "ContactIn") -> dict[str, str]:
    errors: dict[str, str] = {}
    if not c.name.strip():
        errors["name"] = "required"
    if c.telegram_chat_id and not _CHAT_ID_RE.fullmatch(c.telegram_chat_id):
        errors["telegram_chat_id"] = "must be a numeric Telegram chat id"
    if c.ntfy_topic and not _NTFY_RE.fullmatch(c.ntfy_topic):
        errors["ntfy_topic"] = "4-64 chars: letters, digits, - and _"
    if c.email and not _EMAIL_RE.fullmatch(c.email):
        errors["email"] = "not a valid email address"
    if not (c.telegram_chat_id or c.ntfy_topic or c.email):
        errors["channels"] = "at least one channel address is required"
    return errors


class ContactIn(BaseModel):
    id: str | None = None
    name: str
    tier_name: str = "primary"
    telegram_chat_id: str | None = None
    ntfy_topic: str | None = None
    email: str | None = None
    position: int = 0


class TierIn(BaseModel):
    name: str
    position: int = 0
    repeat_after_s: int = 1800
    promote_after_s: int = 600


class SelfNotifyIn(BaseModel):
    telegram_chat_id: str | None = None
    ntfy_topic: str | None = None
    email: str | None = None


def _auth_any(authorization: str | None) -> Auth:
    auth = resolve_auth(authorization)
    if auth is None:
        raise HTTPException(401, "bad token")
    return auth


@router.get("/contacts")
def list_contacts(wearer_id: str | None = Query(default=None),
                  authorization: str | None = Header(default=None)):
    auth = _auth_any(authorization)
    wid = scoped_wearer_id(auth, wearer_id)
    return {"contacts": db.list_contacts(wid), "tiers": db.list_tiers(wid)}


@router.post("/contacts")
def upsert_contact(body: ContactIn, wearer_id: str | None = Query(default=None),
                   authorization: str | None = Header(default=None)):
    auth = _auth_any(authorization)
    wid = scoped_wearer_id(auth, wearer_id)
    errors = _validate_contact(body)
    if errors:
        raise HTTPException(422, {"fields": errors})
    if body.tier_name not in {t["name"] for t in db.list_tiers(wid)}:
        raise HTTPException(422, {"fields": {"tier_name": "unknown tier"}})
    existing = {c["id"] for c in db.list_contacts(wid)}
    if body.id not in existing and len(existing) >= MAX_CONTACTS_PER_WEARER:
        raise HTTPException(422, {"fields": {
            "contacts": f"at most {MAX_CONTACTS_PER_WEARER} contacts per wearer"}})
    cid = db.upsert_contact(wid, body.model_dump())
    db.add_event(wid, "contact_upserted",
                 {"contact_id": cid, "by": auth.role})
    return {"ok": True, "contact_id": cid}


@router.delete("/contacts/{contact_id}")
def delete_contact(contact_id: str, wearer_id: str | None = Query(default=None),
                   authorization: str | None = Header(default=None)):
    auth = _auth_any(authorization)
    wid = scoped_wearer_id(auth, wearer_id)
    if not db.delete_contact(wid, contact_id):
        raise HTTPException(404, "unknown contact")
    db.add_event(wid, "contact_deleted",
                 {"contact_id": contact_id, "by": auth.role})
    return {"ok": True}


@router.post("/tiers")
def upsert_tier(body: TierIn, wearer_id: str | None = Query(default=None),
                authorization: str | None = Header(default=None)):
    auth = _auth_any(authorization)
    wid = scoped_wearer_id(auth, wearer_id)
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", body.name):
        raise HTTPException(422, {"fields": {"name": "lowercase kebab, <=32"}})
    if body.repeat_after_s < 60 or body.promote_after_s < 60:
        raise HTTPException(422, {"fields": {"timing": "minimum 60 seconds"}})
    db.upsert_tier(wid, body.name, body.position,
                   body.repeat_after_s, body.promote_after_s)
    db.add_event(wid, "tier_upserted", {"tier": body.name, "by": auth.role})
    return {"ok": True}


@router.delete("/tiers/{name}")
def delete_tier(name: str, wearer_id: str | None = Query(default=None),
                authorization: str | None = Header(default=None)):
    auth = _auth_any(authorization)
    wid = scoped_wearer_id(auth, wearer_id)
    if not db.delete_tier(wid, name):
        raise HTTPException(409, "tier not empty or unknown")
    db.add_event(wid, "tier_deleted", {"tier": name, "by": auth.role})
    return {"ok": True}


@router.put("/self-notify")
def set_self_notify(body: SelfNotifyIn,
                    wearer_id: str | None = Query(default=None),
                    authorization: str | None = Header(default=None)):
    auth = _auth_any(authorization)
    wid = scoped_wearer_id(auth, wearer_id)
    fake = ContactIn(name="self", telegram_chat_id=body.telegram_chat_id,
                     ntfy_topic=body.ntfy_topic, email=body.email)
    errors = {k: v for k, v in _validate_contact(fake).items()
              if k != "channels"}  # clearing all addresses is a valid opt-out
    if errors:
        raise HTTPException(422, {"fields": errors})
    db.set_self_notify(wid, body.telegram_chat_id, body.ntfy_topic, body.email)
    db.add_event(wid, "self_notify_updated", {"by": auth.role})
    return {"ok": True}


# ---- shared helpers for main.py ----

def deliverable_contacts(wearer_id: str) -> list[dict]:
    return [c for c in db.list_contacts(wearer_id)
            if c["telegram_chat_id"] or c["ntfy_topic"] or c["email"]]


def wearer_degraded(wearer_id: str) -> bool:
    return not deliverable_contacts(wearer_id)
