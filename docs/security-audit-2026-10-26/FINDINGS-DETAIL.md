# FINDINGS-DETAIL — confirmed records of medium severity or higher

## No per-wearer bound on open escalations, contacts or per-cycle sends: one wearer token drives unbounded recurring fan-out through the operator's channels and head-of-line blocks every other wearer's delivery and dead-man evaluation

`server/app/main.py:create_escalation:no-per-wearer-open-escalation-or-fanout-bound` — overall **medium**

### Ordered trace
1. **entrypoint** `server/app/main.py:248` (alarm): POST /api/v1/alarm authenticated by a wearer bearer token (require_wearer); AlarmIn.detector (line 167) is a required, uncapped str and kind is any AlertKind value.
2. **propagation** `server/app/main.py:255` (alarm): Dedup loop returns an existing id only when an unresolved escalation of this wearer has the identical kind and identical detector string; any new detector value falls through to create_escalation (259).
3. **propagation** `server/app/main.py:136` (create_escalation): A fresh esc_id is minted, snapshot_tiers (108-131) copies every contact of the wearer that has an address, the Escalation is stored in the module dict (138) and persisted (141); no per-wearer count, rate or size check.
4. **propagation** `server/app/wearers.py:305` (upsert_contact): The same wearer token creates a new contact on every POST without an id (store.upsert_contact, store.py:249 mints uuid4 ids); _validate_contact (252-264) checks only address syntax; no per-wearer contact count.
5. **propagation** `server/app/wearers.py:340` (upsert_tier): repeat_after_s and promote_after_s are refused only below 60 s; the wearer may set a 60 s repeat on its own tier.
6. **propagation** `server/app/escalation.py:162` (Escalation.step): Every unacked contact is due as soon as t >= first_due_t (set to the creation time at 92), one Send per channel (169-172), and again whenever t - last_sent_t >= repeat_after_s (165-166) until resolve().
7. **propagation** `server/app/main.py:488` (pump_cycle): `ok = await _deliver(channel, address, text, ack_url, raw_token)` is awaited for each Send of each escalation in turn; _deliver (409-411) runs the blocking channel transport in a thread and waits for it, so the coroutine advances one send at a time.
8. **sink** `server/app/main.py:468` (pump_cycle): The delivery loop walks every unresolved escalation of every wearer in one coroutine; later escalations' sends and the next cycle's dead-man section for all wearers (442-465, _pump_loop 525-535) run only after the earlier sends complete, and every send goes out through the operator's shared CHANNELS (76) to the wearer-chosen address.

### Evidence
- `server/app/main.py:167`: `detector: str` with no length or value constraint: the dedup key space is unbounded and the text is embedded verbatim in every message (render_message, channels.py:32-33).
- `server/app/main.py:82`: `escalations` and `_ack_cache` (83) are module dicts that only grow at runtime; resolve() sets a flag and nothing deletes entries.
- `server/app/main.py:545`: Startup restores every unresolved escalation from the store regardless of the wearer's enabled flag, so a restart does not end the fan-out.
- `server/app/main.py:442`: Only the dead-man section skips disabled wearers; the delivery loop (468-470) checks esc.resolved alone, so disabling the wearer does not stop its sends.
- `server/app/main.py:525`: _pump_loop awaits pump_cycle serially and sleeps 5 s between cycles; there is no concurrency bound, per-wearer send budget or per-cycle deadline.
- `server/app/store.py:249`: `cid = contact.get('id') or uuid.uuid4().hex[:12]`: each wearer POST without an id inserts a new contact row; no count constraint anywhere in the store.
- `server/app/store.py:183`: wearer_for_token requires w.enabled=1, so after an admin disables the wearer its own resolve-open/resolve routes no longer authenticate and only an operator can stop the escalations.
- `server/app/wearers.py:340`: Only `repeat_after_s < 60 or promote_after_s < 60` is refused; no maximum number of tiers or contacts.
- `server/app/escalation.py:92`: first_due_t is the tier activation time, so every tier-0 contact is due in the first pump cycle after creation.
- `server/app/channels.py:20`: TIMEOUT_S = 15 bounds each individual transport call; nothing bounds the number of calls per cycle.
- `server/app/channels.py:61`: Telegram sends use the operator's bot token in the URL; EmailChannel (115-119) uses the operator's SMTP account with From=sender and To=the wearer-chosen address.
- `server/app/channels.py:155`: urlopen raises HTTPError (an OSError) for any non-2xx including Telegram 429, which is returned as a plain False with Retry-After ignored and no backoff; record_sent (main.py:497-498) then schedules the next attempt at the repeat cadence.
- `server/app/ui.py:278`: Operator resolution is one escalation per CSRF form POST; the API resolve route (main.py:285-301) is likewise one id per call; no bulk resolve exists.
- `server/docker-compose.yml:17`: mem_limit 512m is the only resource bound on the container; an OOM restart restores the unresolved escalations (main.py:545) rather than ending them.
- `openspec/specs/escalation-and-deadman/spec.md:207`: Requirement: one wearer's alarm, silence, suspension or configuration SHALL never affect another wearer's escalation behaviour.
- `openspec/specs/wearer-management/spec.md:6`: Wearers are first-class, isolated tenants on one instance (a family, a small organisation, a response group).

### Principal and affected resource
The holder of a wearer token adds many contacts with third-party addresses, lowers its tier cadence to 60 s, and posts alarms with distinct detector strings; every pump cycle then relays attacker-chosen text to those addresses from the operator's identity and other wearers' alerts and silent-phone detection wait behind the queue until an operator resolves each escalation by hand.

### Native input and bounded instructions
- `POST /api/v1/contacts  Authorization: Bearer <wearer token>  {"name":"c<i>","email":"r<i>@example.test"}  for i in 0..19`
- `POST /api/v1/tiers  Authorization: Bearer <wearer token>  {"name":"primary","repeat_after_s":60,"promote_after_s":60}`
- `POST /api/v1/alarm  Authorization: Bearer <wearer token>  {"detector":"d<i>"}  for i in 0..19 (any distinct string; e.g. attacker text)`

1. Use the `appenv` fixture from server/tests/conftest.py (CM_DISABLE_PUMP=1; appenv.fakes are FakeChannel instances; wearer 'default' authenticates with wearer_headers()). Run with `cd server && .venv/Scripts/python.exe -m pytest -q <testfile>` only on a developer machine, never against a deployment.
2. For i in range(20): POST /api/v1/contacts with wearer_headers() and {'name': f'c{i}', 'email': f'r{i}@example.test'}; assert every response is 200. POST /api/v1/tiers with {'name':'primary','repeat_after_s':60,'promote_after_s':60}; assert 200.
3. For i in range(20): POST /api/v1/alarm with wearer_headers() and {'detector': f'd{i}'}; assert 20 distinct escalation_id values and len(appenv.main.escalations) == 20 (no 4xx, no cap).
4. Create a second wearer: POST /api/v1/admin/wearers with admin_headers() and {'id':'wb','name':'B'}; then `from app.wearers import hash_token; appenv.store.add_token('wb', hash_token('wbtoken'))`; POST /api/v1/contacts with wearer_headers('wbtoken') and {'name':'R','telegram_chat_id':'999999'}; POST /api/v1/alarm with wearer_headers('wbtoken') and {'detector':'impact'} (this escalation is created last, so it sits last in dict order).
5. Install `order = []` and set `appenv.fakes[ch].on_deliver = lambda a, *_: (order.append(a), time.sleep(0.005))` for ch in ('email','telegram'). t = time.time(); t0 = time.monotonic(); asyncio.run(appenv.main.pump_cycle(t)); wall = time.monotonic() - t0.
6. Assert len(appenv.fakes['email'].sent) == 400 (20 contacts x 20 escalations), that every email text contains its escalation's detector string, that order[-1] == '999999' and order.index('999999') == 400 (wearer B's real alarm is delivered only after all 400 of the attacker's sends), and that wall >= 400 * 0.005 (cycle time grows linearly with the attacker's send count; the next cycle's dead-man evaluation for every wearer cannot start before this returns).
7. asyncio.run(appenv.main.pump_cycle(t + 61)); assert len(appenv.fakes['email'].sent) == 800: the load recurs every 60 s with no further requests until an operator resolves each of the 20 escalations individually. Optionally POST /api/v1/admin/wearers/default/disable with admin_headers() and run pump_cycle(t + 122): the count rises to 1200, showing disable does not stop it.

### Observed output and invariant
Not executed in this run (source-only policy; no sandbox on this host). The source establishes deterministically that the fixture steps above return 200 for every contact and alarm with no limit, that one pump cycle produces contacts x escalations x channels sends through the operator's channel objects in creation order with the other wearer's send last, that the sends recur every repeat_after_s (minimum 60 s) across restarts and after the wearer is disabled, and that each send is awaited serially so the cycle's wall time is the sum of the individual transport times (each up to TIMEOUT_S=15 s). The real-world delay and operator spend scale with provider latency and limits, which were not measured.

Invariant violated: A wearer configures a small set of its own emergency contacts and a phone retry maps onto the existing escalation; alert volume is bounded by the tier design, one wearer's load cannot delay another wearer's alert delivery or silent-phone detection, and the operator's channel credentials are not usable as an unbounded relay by any single wearer token.

### Conditions and containment
- authentication_level: A valid wearer bearer token (an enrolled phone, a stolen phone or token, or a hostile tenant on a shared instance); no operator credential is needed.
- system_configuration: At least one outbound channel configured (CM_TELEGRAM_BOT_TOKEN, CM_SMTP_*, CM_NTFY_URL); the per-escalation multiplier is the number of configured channels each contact carries (up to three).
- data_state: The cross-wearer delay effect requires at least one other enabled wearer on the same instance; the operator-channel abuse does not.
- third_party_dependency: The size of the delay to other wearers (sends x real transport latency, each at most 15 s) and of the operator's spend, reputation damage or bot/SMTP throttling depend on the channel providers' latency and account limits, which are outside the repository; the count and serial ordering of sends do not.

### Remediation and regression
Enforce containment on the server regardless of what a wearer token asks for: cap contacts per wearer, cap open escalations per wearer and collapse further alarms into the newest open one, bound detector length and restrict kind to the phone-origin values, and decouple delivery so one wearer's queue cannot head-of-line block other wearers' sends or the dead-man check (per-wearer delivery tasks or a bounded concurrency budget per cycle, with the dead-man evaluation in its own task). These limits are enforced in server code on the wearer's own data, so they hold the spec's isolation invariant without trusting the phone.

`server/app/wearers.py`:
```python
MAX_CONTACTS_PER_WEARER = 10

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
        raise HTTPException(422, {"fields": {"contacts": f"at most {MAX_CONTACTS_PER_WEARER} contacts per wearer"}})
    cid = db.upsert_contact(wid, body.model_dump())
    ...
```

`server/app/main.py`:
```python
from typing import Literal
from pydantic import BaseModel, Field

MAX_OPEN_ESCALATIONS_PER_WEARER = 3

class AlarmIn(BaseModel):
    token: str = ""
    detector: str = Field(min_length=1, max_length=64)
    kind: Literal["watch_alarm", "fault", "test"] = "watch_alarm"
    lat: float | None = None
    lon: float | None = None

@app.post("/api/v1/alarm")
def alarm(a: AlarmIn, authorization: str | None = Header(default=None)):
    auth = require_wearer(authorization, a.token)
    loc = f"{a.lat},{a.lon}" if a.lat is not None else ""
    mine = [(eid, e) for eid, (wid, e) in escalations.items()
            if wid == auth.wearer_id and not e.resolved]
    for eid, e in mine:
        if e.kind.value == a.kind and e.detector == a.detector:
            return {"escalation_id": eid}
    if len(mine) >= MAX_OPEN_ESCALATIONS_PER_WEARER:
        db.add_event(auth.wearer_id, "alarm_collapsed", {"detector": a.detector, "into": mine[-1][0]})
        return {"escalation_id": mine[-1][0]}   # fold into the newest open escalation
    esc_id = create_escalation(auth.wearer_id, AlertKind(a.kind), a.detector, loc, time.time())
    return {"escalation_id": esc_id}

# pump: deliver per wearer under a bounded budget so one wearer cannot block the rest
_SEND_CONCURRENCY = asyncio.Semaphore(4)
MAX_SENDS_PER_WEARER_PER_CYCLE = 30

async def _deliver_escalation(esc_id, wid, esc, sends, budget):
    ...  # existing per-send body wrapped in `async with _SEND_CONCURRENCY`, try/except per send,
    ...  # stop after `budget` sends and record a 'send_suppressed' event for the rest

async def pump_cycle(now=None):
    ...dead-man section unchanged...
    per_wearer: dict[str, list] = {}
    for esc_id, (wid, esc) in list(escalations.items()):
        if not esc.resolved and (sends := esc.step(t)):
            per_wearer.setdefault(wid, []).append((esc_id, esc, sends))
    await asyncio.gather(*(_deliver_wearer(wid, items, t, MAX_SENDS_PER_WEARER_PER_CYCLE)
                           for wid, items in per_wearer.items()), return_exceptions=True)
```

