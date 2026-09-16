"""FastAPI wiring: heartbeats in, delivered escalations out.

Multi-wearer: every phone endpoint resolves to a wearer via its bearer
token (wearers.resolve_auth); runtime state is per wearer and persisted
through store.db. The pump delivers due sends through real channels and
survives restarts (design D1-D11).
"""
from __future__ import annotations

import asyncio
import logging
import os
import secrets
import threading
import time
import uuid

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

from . import operators, telegram_poll, ui
from .channels import build_channels, render_message
from .deadman import DeadmanConfig, DeadmanMonitor, PhoneState
from .escalation import AlertKind, Contact, Escalation, Tier
from .store import db
from .wearers import (hash_token, legacy_bootstrap, require_wearer,
                      resolve_auth, router as wearers_router, wearer_degraded)

logging.basicConfig(
    level=os.environ.get("CM_LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s")
log = logging.getLogger("cryomonitor")

app = FastAPI(title="Standby Server", version="0.3.1")
app.include_router(wearers_router)
app.include_router(ui.router)

PUBLIC_URL = os.environ.get("CM_PUBLIC_URL", "").rstrip("/")
CHANNELS = build_channels(dict(os.environ))

# ---- runtime state (rebuilt from the store at startup) ----

monitors: dict[str, DeadmanMonitor] = {}
_prev_states: dict[str, PhoneState] = {}
escalations: dict[str, tuple[str, Escalation]] = {}  # esc_id -> (wearer, esc)
_ack_cache: dict[tuple[str, str], str] = {}          # (esc_id, contact) -> token


def get_monitor(wearer_id: str) -> DeadmanMonitor:
    if wearer_id not in monitors:
        monitors[wearer_id] = DeadmanMonitor(DeadmanConfig())
        row = db.load_deadman_all().get(wearer_id)
        if row:
            monitors[wearer_id].last_heartbeat_t = row["last_heartbeat_t"]
            monitors[wearer_id].low_battery_notice_t = row["low_battery_notice_t"]
            monitors[wearer_id].offline_until_t = row["offline_until_t"]
    return monitors[wearer_id]


def _persist_deadman(wearer_id: str) -> None:
    m = monitors[wearer_id]
    db.save_deadman(wearer_id, m.last_heartbeat_t, m.low_battery_notice_t,
                    m.offline_until_t)


def _persist_escalation(esc_id: str) -> None:
    wearer_id, esc = escalations[esc_id]
    db.save_escalation(wearer_id, esc_id, esc.to_state())


def snapshot_tiers(wearer_id: str) -> list[Tier]:
    """Store rows -> engine Tiers with addresses frozen in (design D2)."""
    tiers = []
    contacts = db.list_contacts(wearer_id)
    for tr in db.list_tiers(wearer_id):
        members = []
        for c in contacts:
            if c["tier_name"] != tr["name"]:
                continue
            addresses = [(name, c[col]) for name, col in
                         (("telegram", "telegram_chat_id"),
                          ("ntfy", "ntfy_topic"), ("email", "email"))
                         if c[col]]
            if not addresses:
                continue
            members.append(Contact(
                id=c["id"], name=c["name"],
                channels=tuple(n for n, _ in addresses),
                addresses=tuple(addresses)))
        if members:
            tiers.append(Tier(name=tr["name"], contacts=members,
                              repeat_after_s=tr["repeat_after_s"],
                              promote_after_s=tr["promote_after_s"]))
    return tiers


def create_escalation(wearer_id: str, kind: AlertKind, detector: str,
                      location: str, t: float) -> str:
    esc_id = f"esc-{int(t)}-{wearer_id}-{uuid.uuid4().hex[:6]}"
    tiers = snapshot_tiers(wearer_id)
    escalations[esc_id] = (wearer_id, Escalation(
        kind=kind, tiers=tiers, started_t=t,
        detector=detector, location=location))
    _persist_escalation(esc_id)
    db.add_event(wearer_id, "alarm", {"id": esc_id, "detector": detector,
                                      "alert_kind": kind.value,
                                      "location": location})
    if not tiers:
        db.add_event(wearer_id, "escalation_degraded",
                     {"id": esc_id, "note": "no deliverable contacts"})
        log.warning("escalation %s for %s has no deliverable contacts",
                    esc_id, wearer_id)
    return esc_id


# ---- request models ----

class HeartbeatIn(BaseModel):
    token: str = ""
    phone_battery_pct: int | None = None
    watch_battery_pct: int | None = None
    watch_data_age_s: int | None = None
    suspended_until: float | None = None
    low_battery_warning: bool = False
    command_ack: str | None = None   # echo of the last executed command


class AlarmIn(BaseModel):
    token: str = ""
    detector: str
    kind: str = "watch_alarm"   # watch_alarm | fault | test
    lat: float | None = None
    lon: float | None = None


class OfflineWindowIn(BaseModel):
    token: str = ""
    duration_s: int


class DrillResultIn(BaseModel):
    token: str = ""
    launch_ms: int
    rtt_ms: int | None = None        # phone round trip, drill send -> result
    watch_ms: int | None = None      # watch-side total, arm -> result handoff
    transport_ms: int | None = None  # rtt - watch = pure BT both ways
    phone_model: str = ""


# ---- phone endpoints (wearer-scoped) ----

@app.post("/api/v1/heartbeat")
def heartbeat(hb: HeartbeatIn, authorization: str | None = Header(default=None)):
    auth = require_wearer(authorization, hb.token)
    t = time.time()
    # The pump evaluates monitors from the event loop while heartbeats
    # arrive on worker threads; the lock keeps a heartbeat from racing a
    # SILENT verdict into a false advisory (review 2026-08-29 finding 10).
    with _deadman_lock:
        m = get_monitor(auth.wearer_id)
        m.heartbeat(t, hb.phone_battery_pct)
        if hb.low_battery_warning:
            m.low_battery_notice(t)
        _persist_deadman(auth.wearer_id)
    db.add_heartbeat_point(auth.wearer_id, hb.phone_battery_pct,
                           hb.watch_battery_pct)
    db.set_suspended_until(auth.wearer_id, hb.suspended_until)
    log.debug("heartbeat %s battery=%s watch_age=%s", auth.wearer_id,
              hb.phone_battery_pct, hb.watch_data_age_s)
    resp = {"state": m.state.value, "server_time": t,
            "degraded": wearer_degraded(auth.wearer_id)}
    if hb.command_ack:
        db.ack_command(auth.wearer_id, hb.command_ack)
    cmd = db.peek_command(auth.wearer_id)
    if cmd:
        resp["command"] = cmd
        db.add_event(auth.wearer_id, "command_delivered", {"command": cmd})
    return resp


@app.post("/api/v1/drill-result")
def drill_result(r: DrillResultIn,
                 authorization: str | None = Header(default=None)):
    """M0 spike S1: latency drill measurements, collected per phone model."""
    auth = require_wearer(authorization, r.token)
    db.add_event(auth.wearer_id, "latency_drill",
                 {"launch_ms": r.launch_ms, "rtt_ms": r.rtt_ms,
                  "watch_ms": r.watch_ms, "transport_ms": r.transport_ms,
                  "phone_model": r.phone_model[:64]})
    log.info("latency drill %s: launch=%sms rtt=%sms watch=%sms "
             "transport=%sms model=%s", auth.wearer_id, r.launch_ms,
             r.rtt_ms, r.watch_ms, r.transport_ms, r.phone_model)
    return {"ok": True}


@app.post("/api/v1/offline-window")
def offline_window(w: OfflineWindowIn,
                   authorization: str | None = Header(default=None)):
    auth = require_wearer(authorization, w.token)
    get_monitor(auth.wearer_id).declare_offline(time.time(), w.duration_s)
    _persist_deadman(auth.wearer_id)
    db.add_event(auth.wearer_id, "offline_window", {"duration_s": w.duration_s})
    return {"state": PhoneState.OFFLINE_DECLARED.value}


@app.post("/api/v1/alarm")
def alarm(a: AlarmIn, authorization: str | None = Header(default=None)):
    auth = require_wearer(authorization, a.token)
    loc = f"{a.lat},{a.lon}" if a.lat is not None else ""
    # Idempotent-ish: a phone retry (lost response, service restart) must
    # not spawn a second escalation that the wearer's cancel then misses
    # (review 2026-08-29 finding 8).
    for eid, (wid, e) in escalations.items():
        if (wid == auth.wearer_id and not e.resolved
                and e.kind.value == a.kind and e.detector == a.detector):
            return {"escalation_id": eid}
    esc_id = create_escalation(auth.wearer_id, AlertKind(a.kind),
                               a.detector, loc, time.time())
    return {"escalation_id": esc_id}


@app.post("/api/v1/alarm/resolve-open")
def resolve_open(hb: HeartbeatIn,
                 authorization: str | None = Header(default=None)):
    """Wearer cancel without a remembered escalation id: 'I am OK' resolves
    every open watch-origin escalation for this wearer (never phone_silent
    — that clears itself on the next heartbeat). Covers the lost-response
    and restarted-service cases (review 2026-08-29 finding 8)."""
    auth = require_wearer(authorization, hb.token)
    resolved = []
    for eid, (wid, e) in escalations.items():
        if (wid == auth.wearer_id and not e.resolved
                and e.kind in (AlertKind.WATCH_ALARM, AlertKind.TEST)):
            e.resolve("false_alarm")
            _persist_escalation(eid)
            db.add_event(wid, "resolved",
                         {"id": eid, "resolution": "false_alarm",
                          "by": "wearer_resolve_open"})
            resolved.append(eid)
    return {"resolved": resolved}


@app.post("/api/v1/alarm/{esc_id}/resolve")
def resolve(esc_id: str, resolution: str = "false_alarm", token: str = "",
            authorization: str | None = Header(default=None)):
    auth = resolve_auth(authorization, token)
    if auth is None:
        raise HTTPException(401, "bad token")
    entry = escalations.get(esc_id)
    if not entry:
        raise HTTPException(404, "unknown escalation")
    wearer_id, esc = entry
    if auth.role == "wearer" and auth.wearer_id != wearer_id:
        raise HTTPException(404, "unknown escalation")  # no cross-wearer probing
    esc.resolve(resolution)
    _persist_escalation(esc_id)
    db.add_event(wearer_id, "resolved",
                 {"id": esc_id, "resolution": resolution, "by": auth.role})
    return {"ok": True}


# ---- acknowledgements ----

async def do_ack(raw_token: str) -> str:
    row = db.lookup_ack_token(hash_token(raw_token))
    if not row:
        return "Unknown or expired acknowledgement link."
    entry = escalations.get(row["escalation_id"])
    if not entry or entry[1].resolved:
        return "Acknowledgement no longer applicable — already resolved."
    wearer_id, esc = entry
    if esc.record_ack(row["contact_id"], time.time()):
        _persist_escalation(row["escalation_id"])
        db.add_event(wearer_id, "ack", {"id": row["escalation_id"],
                                        "contact": row["contact_id"]})
        return "Acknowledged. Thank you — updates will follow."
    return "Already acknowledged. Thank you."


@app.get("/api/v1/ack/{ack_token}")
async def ack_page(ack_token: str):
    """No side effects on GET: mail scanners and link previewers prefetch
    these URLs, and a robot must never acknowledge an emergency (review
    2026-08-29 finding 2). The human confirms with one tap (POST)."""
    known = db.lookup_ack_token(hash_token(ack_token)) is not None
    body = ("<h2>Acknowledge this alert?</h2>"
            "<p>Tap to confirm you are responding.</p>"
            f"<form method='post' action='/api/v1/ack/{ack_token}'>"
            "<button style='font-size:1.4em;padding:0.6em 1.2em'>"
            "I acknowledge — I'm on it</button></form>"
            if known else
            "<p>Unknown or expired acknowledgement link.</p>")
    return HTMLResponse(f"<html><body style='font-family:sans-serif;"
                        f"max-width:30em;margin:3em auto'>{body}</body></html>")


@app.post("/api/v1/ack/{ack_token}")
async def ack(ack_token: str):
    msg = await do_ack(ack_token)
    return HTMLResponse("<html><body style='font-family:sans-serif;"
                        f"max-width:30em;margin:3em auto'><p>{msg}</p>"
                        "</body></html>")


# ---- observability ----

@app.get("/api/v1/health")
def health():
    degraded = sum(1 for w in db.list_wearers()
                   if w["enabled"] and wearer_degraded(w["id"]))
    return {"status": "ok", "service": "cryomonitor", "version": app.version,
            "wearers_degraded": degraded}


def _wearer_status(wearer_id: str) -> dict:
    m = get_monitor(wearer_id)
    m.evaluate(time.time())
    return {
        "phone": m.state.value,
        "last_heartbeat_t": m.last_heartbeat_t,
        "degraded": wearer_degraded(wearer_id),
        "active_escalations": {
            eid: {"kind": e.kind.value, "detector": e.detector,
                  "resolved": e.resolved, "any_ack": e.any_ack}
            for eid, (wid, e) in escalations.items()
            if wid == wearer_id and not e.resolved},
    }


@app.get("/api/v1/status")
def status(token: str = "", authorization: str | None = Header(default=None)):
    auth = resolve_auth(authorization, token)
    if auth is None:
        raise HTTPException(401, "bad token")
    if auth.role == "wearer":
        return {**_wearer_status(auth.wearer_id),
                "recent_events": db.recent_events(auth.wearer_id)}
    return {"wearers": [{"id": w["id"], "name": w["name"],
                         "enabled": bool(w["enabled"]),
                         **_wearer_status(w["id"])}
                        for w in db.list_wearers()],
            "recent_events": db.recent_events(None)}


# ---- the pump ----

def _ack_token_for(esc_id: str, wearer_id: str, contact_id: str) -> str:
    key = (esc_id, contact_id)
    if key not in _ack_cache:
        raw = secrets.token_urlsafe(24)
        # persisted BEFORE any send (design D3)
        db.add_ack_token(wearer_id, hash_token(raw), esc_id, contact_id)
        _ack_cache[key] = raw
    return _ack_cache[key]


# Serializes DeadmanMonitor mutation (heartbeat threads) against the pump's
# evaluate/decide sequence (review 2026-08-29 finding 10).
_deadman_lock = threading.Lock()


def _has_open(wearer_id: str, kind: AlertKind) -> bool:
    return any(wid == wearer_id and e.kind == kind and not e.resolved
               for wid, e in escalations.values())


async def _deliver(channel, address: str, text: str, ack_url, ack_token) -> bool:
    return await asyncio.to_thread(channel.deliver, address, text,
                                   ack_url, ack_token)


async def _notify_phone_recovered(wid: str, t: float) -> None:
    for esc_id, (ewid, esc) in list(escalations.items()):
        if ewid != wid or esc.kind != AlertKind.PHONE_SILENT or esc.resolved:
            continue
        esc.resolve("recovered")
        _persist_escalation(esc_id)
        db.add_event(wid, "escalation_resolved",
                     {"id": esc_id, "resolution": "recovered"})
        wearer = db.get_wearer(wid) or {"name": wid}
        mins = max(1, int((t - esc.started_t) / 60))
        text = (f"RECOVERED: {wearer['name']}'s phone is back online "
                f"(silent advisory cleared after ~{mins} min). "
                f"No action needed.\nRef: {esc_id}")
        for contact in esc.alerted_contacts():
            for ch_name, address in contact.addresses:
                channel = CHANNELS.get(ch_name)
                if channel is None or not address:
                    continue
                ok = await _deliver(channel, address, text, None, None)
                db.add_event(wid, "recovery_notify",
                             {"id": esc_id, "contact": contact.id,
                              "channel": ch_name, "delivered": ok})


async def pump_cycle(now: float | None = None) -> None:
    t = now if now is not None else time.time()

    # dead-man per wearer
    for w in db.list_wearers():
        if not w["enabled"]:
            continue
        wid = w["id"]
        with _deadman_lock:
            m = get_monitor(wid)
            prev = _prev_states.get(wid, m.state)
            state = m.evaluate(t)
            if state != prev:
                log.info("deadman %s: %s -> %s", wid, prev.value, state.value)
                db.add_event(wid, "deadman_transition",
                             {"from": prev.value, "to": state.value})
                _persist_deadman(wid)
            if (state == PhoneState.SILENT and prev != PhoneState.SILENT
                    and not _has_open(wid, AlertKind.PHONE_SILENT)):
                create_escalation(wid, AlertKind.PHONE_SILENT,
                                  "phone_silent", "", t)
            _prev_states[wid] = state
        if state != PhoneState.SILENT and prev == PhoneState.SILENT:
            # Phone recovered: an advisory without an all-clear leaves the
            # responder wondering (field 2026-08-29). Auto-resolve the open
            # PHONE_SILENT escalation and follow up to everyone who was
            # actually messaged.
            await _notify_phone_recovered(wid, t)

    # escalation delivery
    for esc_id, (wid, esc) in list(escalations.items()):
        if esc.resolved:
            continue
        sends = esc.step(t)
        if not sends:
            continue
        wearer = db.get_wearer(wid) or {"name": wid}
        delivered: dict[str, list[str]] = {}
        for send in sends:
            address = dict(send.contact.addresses).get(send.channel)
            channel = CHANNELS.get(send.channel)
            if not address or channel is None:
                db.add_event(wid, "send_unroutable",
                             {"id": esc_id, "contact": send.contact.id,
                              "channel": send.channel})
                continue
            raw_token = _ack_token_for(esc_id, wid, send.contact.id)
            ack_url = f"{PUBLIC_URL}/api/v1/ack/{raw_token}" if PUBLIC_URL else None
            text = render_message(wearer["name"], esc.kind.value, esc.detector,
                                  esc.location, esc_id)
            ok = await _deliver(channel, address, text, ack_url, raw_token)
            db.add_event(wid, "send",
                         {"id": esc_id, "contact": send.contact.id,
                          "channel": send.channel, "attempt": send.attempt,
                          "delivered": ok})
            if ok:
                delivered.setdefault(send.contact.name, []).append(send.channel)
        # advance every due contact's clock: failures retry on the repeat
        # cadence, not every pump cycle
        for cid in {s.contact.id for s in sends}:
            esc.record_sent(cid, t)
        _persist_escalation(esc_id)

        # wearer self-notification: one labeled summary per delivery cycle
        if delivered:
            await _self_notify(wid, wearer, esc, esc_id, delivered)


async def _self_notify(wid: str, wearer: dict, esc, esc_id: str,
                       delivered: dict[str, list[str]]) -> None:
    targets = [("telegram", wearer.get("self_telegram")),
               ("ntfy", wearer.get("self_ntfy")),
               ("email", wearer.get("self_email"))]
    summary = "; ".join(f"{name} ({', '.join(chs)})"
                        for name, chs in delivered.items())
    text = render_message(wearer["name"], esc.kind.value, esc.detector,
                          esc.location, esc_id, to_wearer=True)
    text += f"\nNotified: {summary}"
    for ch_name, address in targets:
        channel = CHANNELS.get(ch_name)
        if address and channel is not None:
            # no ACK affordance on wearer copies — awareness is not response
            ok = await _deliver(channel, address, text, None, None)
            db.add_event(wid, "self_notify",
                         {"id": esc_id, "channel": ch_name, "delivered": ok})


async def _pump_loop():
    while True:
        try:
            await pump_cycle()
        except Exception:
            log.exception("pump cycle failed")
        await asyncio.sleep(5)


@app.on_event("startup")
async def _startup():
    if legacy_bootstrap():
        log.info("legacy CM_API_TOKEN migrated into wearer 'default'")
    if operators.bootstrap_admin():
        log.info("initial web admin bootstrapped from CM_UI_ADMIN_* env; "
                 "remove those variables from .env now")
    for wid, esc_id, state in db.load_unresolved_escalations():
        escalations[esc_id] = (wid, Escalation.from_state(state))
        log.info("restored active escalation %s for %s", esc_id, wid)
    for w in db.list_wearers():
        get_monitor(w["id"])
    if os.environ.get("CM_DISABLE_PUMP") != "1":
        asyncio.create_task(_pump_loop())
        bot = os.environ.get("CM_TELEGRAM_BOT_TOKEN", "")
        if bot and os.environ.get("CM_DISABLE_TG_POLL") != "1":
            asyncio.create_task(telegram_poll.poll_loop(bot, db, do_ack))
