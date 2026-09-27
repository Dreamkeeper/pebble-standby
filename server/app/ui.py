"""Operator web dashboard (design D1): server-rendered Jinja2 + vendored
htmx, same process as the API. No new unauthenticated surface beyond the
login form and one static file; every state change verifies CSRF and
lands in the audit log with the operator's identity.

Imports of runtime state (escalations, monitors) happen lazily via
``_rt()`` because main.py includes this router at import time.
"""
from __future__ import annotations

import os
import secrets
import time
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates

from . import diagnostics as diag
from . import operators as ops
from .deadman import PhoneState
from .escalation import AlertKind
from .store import db
from .wearers import ContactIn, _validate_contact, generate_enroll_code, \
    hash_token, _normalize_code, ENROLL_TTL_S

router = APIRouter(prefix="/ui")

_HERE = Path(__file__).parent
templates = Jinja2Templates(directory=str(_HERE / "templates"))
UI_REFRESH_S = int(os.environ.get("CM_UI_REFRESH_S", "15"))


def _rt():
    from . import main
    return main


def _fmt(t: float | None) -> str:
    if not t:
        return "never"
    d = time.time() - t
    if d < 90: return f"{int(d)}s ago"
    if d < 5400: return f"{int(d // 60)}m ago"
    if d < 172800: return f"{d / 3600:.1f}h ago"
    return f"{d / 86400:.1f}d ago"


# ---- state model: worst first ----

def wearer_states(w: dict) -> tuple[int, list[dict]]:
    """(badness score, [{css,label}]) — higher score sorts first."""
    rt = _rt()
    wid = w["id"]
    states, score = [], 0
    if not w["enabled"]:
        return 0, [{"css": "disabled", "label": "disabled"}]

    unacked = acked = 0
    for eid, (ewid, esc) in rt.escalations.items():
        if ewid == wid and not esc.resolved:
            if esc.any_ack: acked += 1
            else: unacked += 1
    if unacked:
        states.append({"css": "alarm", "label": f"ESCALATING ×{unacked}"})
        score = max(score, 100)
    if acked:
        states.append({"css": "late", "label": f"escalation acked ×{acked}"})
        score = max(score, 60)

    m = rt.get_monitor(wid)
    st = m.evaluate(time.time())
    if st == PhoneState.SILENT:
        states.append({"css": "silent", "label": "phone SILENT"})
        score = max(score, 90)
    elif st == PhoneState.LATE:
        states.append({"css": "late", "label": "phone late"})
        score = max(score, 50)
    elif st == PhoneState.OFFLINE_DECLARED:
        states.append({"css": "offline", "label": "offline (declared)"})
        score = max(score, 20)

    from .wearers import wearer_degraded
    if wearer_degraded(wid):
        states.append({"css": "degraded", "label": "DEGRADED (no contacts)"})
        score = max(score, 70)

    susp = db.get_suspended_until(wid)
    if susp and susp > time.time():
        states.append({"css": "suspended",
                       "label": f"suspended {int((susp - time.time()) / 60)}m"})
        score = max(score, 30)

    if not states:
        states.append({"css": "ok", "label": "OK"})
    return score, states


def build_fleet() -> list[dict]:
    rt = _rt()
    rows = []
    for w in db.list_wearers():
        score, states = wearer_states(w)
        trail = db.heartbeat_trail(w["id"], limit=1)
        m = rt.get_monitor(w["id"])
        rows.append({
            "id": w["id"], "name": w["name"], "states": states,
            "score": score, "last_hb": _fmt(m.last_heartbeat_t),
            "battery": trail[-1]["battery"] if trail else None,
            "escalations": sum(1 for wid, e in rt.escalations.values()
                               if wid == w["id"] and not e.resolved),
        })
    rows.sort(key=lambda r: (-r["score"], r["id"]))
    return rows


# ---- auth surface ----

@router.get("/static/htmx.min.js")
def htmx():
    return FileResponse(_HERE / "static" / "htmx.min.js",
                        media_type="application/javascript")


@router.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    return templates.TemplateResponse(request, "login.html", {
        "op": None, "error": None,
        "no_admins": not ops.admin_accounts_exist()})


@router.post("/login")
async def login_post(request: Request):
    form = await request.form()
    client = request.client.host if request.client else "unknown"
    result = ops.login(str(form.get("username", "")).strip(),
                       str(form.get("password", "")), client)
    if result is None:
        return templates.TemplateResponse(request, "login.html", {
            "op": None, "error": "Login failed.",
            "no_admins": not ops.admin_accounts_exist()},
            status_code=401)
    raw, _op = result
    resp = RedirectResponse("/ui/", status_code=303)
    resp.set_cookie(ops.SESSION_COOKIE, raw, max_age=ops.SESSION_TTL_S,
                    httponly=True, samesite="lax", secure=ops.secure_cookies(),
                    path="/")
    return resp


@router.post("/logout")
async def logout_post(request: Request):
    op = ops.current_operator(request)
    if op is not None:
        await ops.verify_csrf(request, op)
        raw = request.cookies.get(ops.SESSION_COOKIE, "")
        ops.logout(raw)
        db.add_event(None, "logout", {"operator": op.username})
    resp = RedirectResponse("/ui/login", status_code=303)
    resp.delete_cookie(ops.SESSION_COOKIE, path="/")
    return resp


# ---- pages ----

@router.get("/", response_class=HTMLResponse)
def fleet_page(request: Request):
    op = ops.require_operator(request)
    return templates.TemplateResponse(request, "fleet.html", {
        "op": op, "fleet": build_fleet(), "refresh_s": UI_REFRESH_S,
        "now": time.strftime("%H:%M:%S")})


@router.get("/fragments/fleet", response_class=HTMLResponse)
def fleet_fragment(request: Request):
    op = ops.require_operator(request)
    return templates.TemplateResponse(request, "_fleet_rows.html", {
        "op": op, "fleet": build_fleet(), "now": time.strftime("%H:%M:%S")})


def _render_wearer(request: Request, op: ops.Operator, wid: str,
                   enroll_code: str | None = None, notice: str | None = None,
                   contact_error: str | None = None,
                   status_code: int = 200) -> HTMLResponse:
    rt = _rt()
    w = db.get_wearer(wid)
    if not w:
        raise HTTPException(404, "unknown wearer")
    _score, states = wearer_states(w)
    m = rt.get_monitor(wid)
    trail = db.heartbeat_trail(wid)
    active = [{"id": eid, "kind": e.kind.value, "detector": e.detector,
               "any_ack": e.any_ack, "started": _fmt(e.started_t)}
              for eid, (ewid, e) in rt.escalations.items()
              if ewid == wid and not e.resolved]
    events = [{"when": _fmt(e["t"]), "kind": e["kind"],
               "detail": ", ".join(f"{k}={v}" for k, v in e.items()
                                   if k not in ("t", "kind", "wearer_id"))}
              for e in reversed(db.recent_events(wid, limit=30))]
    from .wearers import wearer_degraded
    diagnostics = [{"id": d["id"], "when": _fmt(d["created_t"]),
                    "kb": (d["size"] + 1023) // 1024, "days": d["days"],
                    "request_id": d["request_id"]}
                   for d in db.list_diagnostics(wid)]
    diag_requests = [{"id": r["id"], "when": _fmt(r["created_t"]),
                      "days": r["days"], "by": r["requested_by"],
                      "state": r["state"], "diagnostics_id": r["diagnostics_id"]}
                     for r in db.list_diag_requests(wid)]
    return templates.TemplateResponse(request, "wearer.html", {
        "op": op, "wearer": w, "states": states,
        "last_hb": _fmt(m.last_heartbeat_t),
        "battery": trail[-1]["battery"] if trail else None,
        "trail": trail, "active": active,
        "contacts": db.list_contacts(wid), "tiers": db.list_tiers(wid),
        "degraded": wearer_degraded(wid), "events": events,
        "enroll_code": enroll_code, "notice": notice,
        "refresh_s": UI_REFRESH_S,
        "diagnostics": diagnostics, "diag_requests": diag_requests,
        "diag_ranges": diag.REQUEST_RANGES,
        "diag_retention_days": diag.RETENTION_DAYS,
        "contact_error": contact_error}, status_code=status_code)


@router.get("/wearers/{wid}", response_class=HTMLResponse)
def wearer_page(request: Request, wid: str):
    return _render_wearer(request, ops.require_operator(request), wid)


@router.get("/fragments/wearer/{wid}", response_class=HTMLResponse)
def wearer_fragment(request: Request, wid: str):
    """Live status + active escalations, polled by the wearer page so an
    incoming ACK/resolve appears without a manual reload (E2E T5)."""
    op = ops.require_operator(request)
    rt = _rt()
    w = db.get_wearer(wid)
    if not w:
        raise HTTPException(404, "unknown wearer")
    _score, states = wearer_states(w)
    m = rt.get_monitor(wid)
    trail = db.heartbeat_trail(wid)
    active = [{"id": eid, "kind": e.kind.value, "detector": e.detector,
               "any_ack": e.any_ack, "started": _fmt(e.started_t)}
              for eid, (ewid, e) in rt.escalations.items()
              if ewid == wid and not e.resolved]
    return templates.TemplateResponse(request, "_wearer_live.html", {
        "op": op, "states": states, "last_hb": _fmt(m.last_heartbeat_t),
        "battery": trail[-1]["battery"] if trail else None,
        "trail": trail, "active": active})


# ---- escalation actions (responder + admin) ----

@router.post("/escalations/{esc_id}/ack")
async def ui_ack(request: Request, esc_id: str):
    op = ops.require_operator(request)
    await ops.verify_csrf(request, op)
    rt = _rt()
    entry = rt.escalations.get(esc_id)
    if not entry or entry[1].resolved:
        raise HTTPException(404, "escalation not active")
    wid, esc = entry
    if esc.record_operator_ack(f"operator:{op.username}"):
        rt._persist_escalation(esc_id)
        db.add_event(wid, "operator_ack", {"id": esc_id,
                                           "operator": op.username})
    return RedirectResponse(f"/ui/wearers/{wid}", status_code=303)


@router.post("/escalations/{esc_id}/resolve")
async def ui_resolve(request: Request, esc_id: str):
    op = ops.require_operator(request)
    await ops.verify_csrf(request, op)
    form = await request.form()
    resolution = str(form.get("resolution", "handled"))
    reason = str(form.get("reason", "")).strip()
    if not reason:
        raise HTTPException(422, "a reason is required to resolve")
    rt = _rt()
    entry = rt.escalations.get(esc_id)
    if not entry:
        raise HTTPException(404, "unknown escalation")
    wid, esc = entry
    esc.resolve(resolution)
    rt._persist_escalation(esc_id)
    db.add_event(wid, "resolved", {"id": esc_id, "resolution": resolution,
                                   "reason": reason,
                                   "operator": op.username})
    return RedirectResponse(f"/ui/wearers/{wid}", status_code=303)


# ---- wearer administration (admin only) ----

@router.post("/wearers/create")
async def ui_create_wearer(request: Request):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    form = await request.form()
    wid = str(form.get("id", "")).strip()
    name = str(form.get("name", "")).strip()
    import re
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,31}", wid) or db.get_wearer(wid):
        raise HTTPException(422, "bad or duplicate wearer id")
    db.create_wearer(wid, name or wid)
    db.add_event(wid, "wearer_created", {"name": name, "operator": op.username})
    return RedirectResponse(f"/ui/wearers/{wid}", status_code=303)


@router.post("/wearers/{wid}/contacts")
async def ui_save_contact(request: Request, wid: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    form = await request.form()
    body = ContactIn(
        name=str(form.get("name", "")).strip(),
        tier_name=str(form.get("tier_name", "primary")),
        telegram_chat_id=str(form.get("telegram_chat_id", "")).strip() or None,
        ntfy_topic=str(form.get("ntfy_topic", "")).strip() or None,
        email=str(form.get("email", "")).strip() or None)
    errors = _validate_contact(body)
    if errors:
        return _render_wearer(request, op, wid, contact_error="; ".join(
            f"{k}: {v}" for k, v in errors.items()), status_code=422)
    cid = db.upsert_contact(wid, body.model_dump())
    db.add_event(wid, "contact_upserted", {"contact_id": cid,
                                           "operator": op.username})
    return RedirectResponse(f"/ui/wearers/{wid}", status_code=303)


@router.post("/wearers/{wid}/contacts/{cid}/delete")
async def ui_delete_contact(request: Request, wid: str, cid: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    if db.delete_contact(wid, cid):
        db.add_event(wid, "contact_deleted", {"contact_id": cid,
                                              "operator": op.username})
    return RedirectResponse(f"/ui/wearers/{wid}", status_code=303)


@router.post("/wearers/{wid}/enroll-code")
async def ui_enroll_code(request: Request, wid: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    w = db.get_wearer(wid)
    if not w or not w["enabled"]:
        raise HTTPException(404, "unknown or disabled wearer")
    code = generate_enroll_code()
    db.create_enroll_code(wid, hash_token(_normalize_code(code)),
                          time.time() + ENROLL_TTL_S)
    db.add_event(wid, "enroll_code_issued", {"operator": op.username,
                                             "ttl_s": ENROLL_TTL_S})
    # rendered directly (display-once); never via redirect query string
    return _render_wearer(request, op, wid, enroll_code=code)


@router.post("/wearers/{wid}/revoke-tokens")
async def ui_revoke_tokens(request: Request, wid: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    n = db.revoke_tokens(wid)
    db.add_event(wid, "tokens_revoked", {"count": n, "operator": op.username})
    return _render_wearer(request, op, wid,
                          notice=f"{n} token(s) revoked — the phone must "
                                 "re-enroll.")


@router.post("/wearers/{wid}/drill")
async def ui_drill(request: Request, wid: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    rt = _rt()
    esc_id = rt.create_escalation(wid, AlertKind.TEST, "fire-drill-ui", "",
                                  time.time())
    db.add_event(wid, "fire_drill", {"id": esc_id, "operator": op.username})
    return RedirectResponse(f"/ui/wearers/{wid}", status_code=303)


@router.post("/wearers/{wid}/latency-drill")
async def ui_latency_drill(request: Request, wid: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    db.queue_command(wid, "latency_drill")
    db.add_event(wid, "latency_drill_queued", {"operator": op.username})
    return _render_wearer(request, op, wid,
                          notice="Latency drill queued — the phone picks it "
                                 "up on its next heartbeat (≤5 min); the "
                                 "result appears in Recent events.")


# ---- diagnostics (admin only; spec: diagnostics-sharing) ----

@router.post("/wearers/{wid}/diagnostics/request")
async def ui_request_diagnostics(request: Request, wid: str):
    """Ask, never pull: the phone shows a notification and uploads only
    if the wearer taps Send."""
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    if not db.get_wearer(wid):
        raise HTTPException(404, "unknown wearer")
    form = await request.form()
    try:
        days = diag.normalize_days(int(str(form.get("days", "7"))))
    except ValueError:
        raise HTTPException(422, "bad day count")
    req_id = secrets.token_hex(6)
    db.create_diag_request(wid, req_id, days, op.username)
    db.queue_command(wid, f"diag_request:{req_id}:{days}")
    db.add_event(wid, "diagnostics_requested", {"request_id": req_id,
                                                "days": days,
                                                "operator": op.username})
    return _render_wearer(request, op, wid,
                          notice=f"Asked the phone for {days} day(s) of logs. "
                                 "It shows a notification on its next heartbeat "
                                 "(within 5 min); nothing is sent until the "
                                 "wearer taps Send. A queued latency drill the "
                                 "phone has not picked up yet is replaced.")


@router.get("/wearers/{wid}/diagnostics/{did}")
def ui_download_diagnostics(request: Request, wid: str, did: str):
    op = ops.require_ui_admin(request)
    if not db.get_diagnostics(wid, did):
        raise HTTPException(404, "unknown diagnostics bundle")
    path = diag.bundle_path(wid, did)
    if not os.path.exists(path):
        raise HTTPException(404, "bundle file missing")
    db.add_event(wid, "diagnostics_downloaded", {"id": did,
                                                 "operator": op.username})
    return FileResponse(path, media_type="application/zip",
                        filename=f"standby-diagnostics-{wid}-{did}.zip")


@router.post("/wearers/{wid}/diagnostics/{did}/delete")
async def ui_delete_diagnostics(request: Request, wid: str, did: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    if diag.delete_bundle(wid, did):
        db.add_event(wid, "diagnostics_deleted", {"id": did,
                                                  "operator": op.username})
    return RedirectResponse(f"/ui/wearers/{wid}", status_code=303)


@router.post("/wearers/{wid}/disable")
async def ui_disable_wearer(request: Request, wid: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    db.set_wearer_enabled(wid, False)
    db.add_event(wid, "wearer_disabled", {"operator": op.username})
    return RedirectResponse(f"/ui/wearers/{wid}", status_code=303)


@router.post("/wearers/{wid}/enable")
async def ui_enable_wearer(request: Request, wid: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    db.set_wearer_enabled(wid, True)
    db.add_event(wid, "wearer_enabled", {"operator": op.username})
    return RedirectResponse(f"/ui/wearers/{wid}", status_code=303)


# ---- operator management (admin only) ----

@router.get("/operators", response_class=HTMLResponse)
def operators_page(request: Request, notice: str | None = None,
                   error: str | None = None):
    op = ops.require_ui_admin(request)
    return templates.TemplateResponse(request, "operators.html", {
        "op": op, "operators": db.list_operators(),
        "notice": notice, "error": error})


@router.post("/operators/create")
async def op_create(request: Request):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    form = await request.form()
    username = str(form.get("username", "")).strip()
    password = str(form.get("password", ""))
    role = str(form.get("role", "responder"))
    if not username or len(password) < 8 or role not in ("admin", "responder"):
        return operators_page(request,
                              error="username, role, and a password of at "
                                    "least 8 characters are required")
    if db.get_operator(username):
        return operators_page(request, error="username exists")
    db.create_operator(username, ops.hash_password(password), role)
    db.add_event(None, "operator_created", {"operator": username, "role": role,
                                            "by": op.username})
    return RedirectResponse("/ui/operators", status_code=303)


@router.post("/operators/{username}/role")
async def op_role(request: Request, username: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    form = await request.form()
    role = str(form.get("role", ""))
    if role not in ("admin", "responder"):
        raise HTTPException(422, "bad role")
    if role != "admin" and username == op.username and db.count_admins() <= 1:
        raise HTTPException(409, "cannot demote the last admin")
    db.update_operator(username, role=role)
    db.add_event(None, "operator_role", {"operator": username, "role": role,
                                         "by": op.username})
    return RedirectResponse("/ui/operators", status_code=303)


@router.post("/operators/{username}/password")
async def op_password(request: Request, username: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    form = await request.form()
    password = str(form.get("password", ""))
    if len(password) < 8:
        return operators_page(request, error="password too short (min 8)")
    db.update_operator(username, pw_json=ops.hash_password(password))
    db.revoke_operator_sessions(username)
    db.add_event(None, "operator_password_reset", {"operator": username,
                                                   "by": op.username})
    return RedirectResponse("/ui/operators", status_code=303)


@router.post("/operators/{username}/disable")
async def op_disable(request: Request, username: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    if username == op.username:
        raise HTTPException(409, "cannot disable yourself")
    db.update_operator(username, enabled=False)
    db.revoke_operator_sessions(username)
    db.add_event(None, "operator_disabled", {"operator": username,
                                             "by": op.username})
    return RedirectResponse("/ui/operators", status_code=303)


@router.post("/operators/{username}/enable")
async def op_enable(request: Request, username: str):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    db.update_operator(username, enabled=True)
    db.add_event(None, "operator_enabled", {"operator": username,
                                            "by": op.username})
    return RedirectResponse("/ui/operators", status_code=303)


# ---- audit (admin only, read-only) ----

@router.get("/audit", response_class=HTMLResponse)
def audit_page(request: Request):
    op = ops.require_ui_admin(request)
    events = [{"when": _fmt(e["t"]), "wearer": e.get("wearer_id"),
               "kind": e["kind"],
               "detail": ", ".join(f"{k}={v}" for k, v in e.items()
                                   if k not in ("t", "kind", "wearer_id"))}
              for e in reversed(db.recent_events(None, limit=200))]
    return templates.TemplateResponse(request, "audit.html", {
        "op": op, "events": events})
