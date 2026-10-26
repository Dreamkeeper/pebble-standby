# Security audit report — Standby server (`server/`), run-1

## 1. Run facts

- **Profile:** `quick` (one hunter wave, one final coverage critic, one fresh verifier per candidate acting as both candidate validator and final record reviewer). **This is a partial pass by design**: a quick, scoped run never claims complete coverage, and the README of the skill itself reports that a single run finds roughly half of what repeated runs find.
- **Scope:** server/. Scoped run: the self-hosted FastAPI server (app, templates, static, Dockerfile, compose files, Caddyfile, requirements, .env.example, tests as fixtures). Android companion, watchapp, CI workflows and docs are out of scope.
- **Source ref:** `d6156197b9fd` on branch main; worktree had untracked files outside the scope only.
- **Budget:** 26 agent invocations (raised from 24 after the hunter wave produced 13 distinct candidates); spent 25: 4 reconnaissance, 7 hunters, 1 critic, 13 verifiers.
- **Execution policy:** sandboxed source-and-local-only. **No OS-enforced sandbox exists on the audit host** (Windows, no Docker, no network namespace, no read-only mounts, no resource limits), so **no target code was executed anywhere in this run**. Every check is a source check. Records marked `confirmed` are confirmed from source control flow alone, with the repository's own tests cited where they already assert the behaviour; every record whose outcome turns on a runtime, framework-default or deployment fact is `needs_validation` with an exact local pytest plan or an owner-observed check. The project owner can execute the local plans in the repository's existing in-process test fixture (`server/tests/conftest.py`) after the run; that is outside this audit's evidence.
- **Prior runs:** none. The 2026-10-13 configuration and supply-chain audit (`docs/SECURITY-AUDIT-2026-10-13.md`) is not a hunt and was not carried as coverage. No same-source confirmations carried; no changed-source revalidations.
- **Coverage ledger:** 23 units: 8 covered, 9 candidate, 0 blocked, 6 deferred (critic proposals that the quick profile does not hunt), 0 out of scope. Excluded companion domains: supply chain and release (CI out of scope; pins audited 2026-10-13), client-side (server-rendered Jinja with autoescape and a strict CSP; folded into Injection), memory safety, AI/LLM, desktop/mobile (no such surfaces in `server/`).
- **Final critic:** accepted 6 missing unit(s) and 0 reassignment(s), all recorded as deferred for a later run; critic `stop` = False.

## 2. Security posture summary

The server's identity and tenant boundaries held under review: wearer tokens bind every phone route to one wearer id, the store filters by wearer everywhere, operator sessions are hashed, rotated on password change and role-checked live, every `/ui/*` mutation carries CSRF, SQL is parameterised, templates autoescape under a strict CSP, the diagnostics path construction is regex-locked, and no secrets live in the repository. What the hunt found instead sits in **containment and lifecycle**: a wearer token (a semi-trusted tenant, exactly the principal a beta adds) has no quota on contacts, open escalations, sends or diagnostics bytes; the single sequential pump couples every wearer's delivery to every other wearer's inputs; an administrative disable does not stop queued alerting; the login throttle can be turned against the operator; one admin API path takes a session cookie without CSRF; and the default compose exposes an unauthenticated ntfy and a plaintext listener on every interface while leaving the proxy untrusted, which collapses all clients into one rate-limit bucket. None of these is a cross-tenant read or write. Several of them are a cross-tenant denial of the one thing this product exists to do, deliver an alert, and the beta multiplies the number of principals who could trigger them.

## 3. Confirmed findings

| Severity | Title | Affected boundary | Observed result (source-established) |
|---|---|---|---|
| medium | No per-wearer bound on open escalations, contacts or per-cycle sends: one wearer token drives unbounded recurring fan-out through the operator's channels and head-of-line blocks every other wearer's delivery and dead-man evaluation | server/app/main.py:468 (pump_cycle) | Not executed in this run (source-only policy; no sandbox on this host). The source establishes deterministically that the fixture steps above return 200 for every contact and alarm with no limit, that one pump cycle prod |
| low | Disabling a wearer does not stop its open escalations, while removing the wearer's own ability to cancel them | server/app/main.py:469 (pump_cycle) | Source-established, not executed in this run (execution policy forbids running target code on this host): after the disable the second pump_cycle must deliver a second send to each contact because no code between set_wea |
| low | Login throttle is a hard lockout keyed on the bare username: five anonymous wrong passwords keep the named operator out of the dashboard | server/app/operators.py:89 (check_rate_limit) | Source-established and not executed in this run (policy forbids running target code): operators.py:85-89 refuses the login with 429 once five u:<username> rows exist in the window, independent of the a:<client> count, an |
| low | Enrol-code exchange checks single use with a SELECT outside the write transaction and burns the code with an unconditional UPDATE, so two overlapping requests mint two wearer tokens from one code | server/app/store.py:215 (exchange_enroll_code) | Not executed in this run (source-only policy, no sandbox). The source establishes: the SELECT at store.py:205 runs outside any transaction, the UPDATE at 213 cannot fail for a second writer and its rowcount is ignored, a |
| low | An operator whose username contains '/' can log in as admin but cannot be disabled, demoted or password-reset by any route | server/app/ui.py:531 (op_disable) | Not executed in this run (source-only policy, no sandbox). The source establishes: create returns 303 and inserts the row; every management POST for the name, whether the template's /ui/operators/ops/x/... form target or |

### MEDIUM — No per-wearer bound on open escalations, contacts or per-cycle sends: one wearer token drives unbounded recurring fan-out through the operator's channels and head-of-line blocks every other wearer's delivery and dead-man evaluation

**Fingerprint:** `server/app/main.py:create_escalation:no-per-wearer-open-escalation-or-fanout-bound`  
**Source:** `server/app/main.py:248` (entrypoint, alarm); `server/app/main.py:255` (propagation, alarm); `server/app/main.py:136` (propagation, create_escalation); `server/app/wearers.py:305` (propagation, upsert_contact); `server/app/wearers.py:340` (propagation, upsert_tier); `server/app/escalation.py:162` (propagation, Escalation.step); `server/app/main.py:488` (propagation, pump_cycle); `server/app/main.py:468` (sink, pump_cycle)

**Lower-trust principal:** The holder of a wearer token adds many contacts with third-party addresses, lowers its tier cadence to 60 s, and posts alarms with distinct detector strings; every pump cycle then relays attacker-chosen text to those addresses from the operator's identity and other wearers' alerts and silent-phone detection wait behind the queue until an operator resolves each escalation by hand.

**Root cause:** The server has no containment limit on any wearer-controlled multiplier (contacts per wearer, open escalations per wearer, alarm rate, detector length, minimum cadence beyond 60 s) and delivers all wearers' due sends serially in the one coroutine that also runs every wearer's dead-man check, through shared operator-owned channel credentials.

**Intended behaviour:** A wearer configures a small set of its own emergency contacts and a phone retry maps onto the existing escalation; alert volume is bounded by the tier design, one wearer's load cannot delay another wearer's alert delivery or silent-phone detection, and the operator's channel credentials are not usable as an unbounded relay by any single wearer token.

**Bounded reproduction (target-native, from the existing pytest fixture):**
1. Use the `appenv` fixture from server/tests/conftest.py (CM_DISABLE_PUMP=1; appenv.fakes are FakeChannel instances; wearer 'default' authenticates with wearer_headers()). Run with `cd server && .venv/Scripts/python.exe -m pytest -q <testfile>` only on a developer machine, never against a deployment.
2. For i in range(20): POST /api/v1/contacts with wearer_headers() and {'name': f'c{i}', 'email': f'r{i}@example.test'}; assert every response is 200. POST /api/v1/tiers with {'name':'primary','repeat_after_s':60,'promote_after_s':60}; assert 200.
3. For i in range(20): POST /api/v1/alarm with wearer_headers() and {'detector': f'd{i}'}; assert 20 distinct escalation_id values and len(appenv.main.escalations) == 20 (no 4xx, no cap).
4. Create a second wearer: POST /api/v1/admin/wearers with admin_headers() and {'id':'wb','name':'B'}; then `from app.wearers import hash_token; appenv.store.add_token('wb', hash_token('wbtoken'))`; POST /api/v1/contacts with wearer_headers('wbtoken') and {'name':'R','telegram_chat_id':'999999'}; POST /api/v1/alarm with wearer_headers('wbtoken') and {'detector':'impact'} (this escalation is created last, so it sits last in dict order).
5. Install `order = []` and set `appenv.fakes[ch].on_deliver = lambda a, *_: (order.append(a), time.sleep(0.005))` for ch in ('email','telegram'). t = time.time(); t0 = time.monotonic(); asyncio.run(appenv.main.pump_cycle(t)); wall = time.monotonic() - t0.
6. Assert len(appenv.fakes['email'].sent) == 400 (20 contacts x 20 escalations), that every email text contains its escalation's detector string, that order[-1] == '999999' and order.index('999999') == 400 (wearer B's real alarm is delivered only after all 400 of the attacker's sends), and that wall >= 400 * 0.005 (cycle time grows linearly with the attacker's send count; the next cycle's dead-man evaluation for every wearer cannot start before this returns).
7. asyncio.run(appenv.main.pump_cycle(t + 61)); assert len(appenv.fakes['email'].sent) == 800: the load recurs every 60 s with no further requests until an operator resolves each of the 20 escalations individually. Optionally POST /api/v1/admin/wearers/default/disable with admin_headers() and run pump_cycle(t + 122): the count rises to 1200, showing disable does not stop it.

Payloads:
- `POST /api/v1/contacts  Authorization: Bearer <wearer token>  {"name":"c<i>","email":"r<i>@example.test"}  for i in 0..19`
- `POST /api/v1/tiers  Authorization: Bearer <wearer token>  {"name":"primary","repeat_after_s":60,"promote_after_s":60}`
- `POST /api/v1/alarm  Authorization: Bearer <wearer token>  {"detector":"d<i>"}  for i in 0..19 (any distinct string; e.g. attacker text)`

**Conditions:** authentication_level: A valid wearer bearer token (an enrolled phone, a stolen phone or token, or a hostile tenant on a shared instance); no operator credential is needed.; system_configuration: At least one outbound channel configured (CM_TELEGRAM_BOT_TOKEN, CM_SMTP_*, CM_NTFY_URL); the per-escalation multiplier is the number of configured channels each contact carries (up to three).; data_state: The cross-wearer delay effect requires at least one other enabled wearer on the same instance; the operator-channel abuse does not.; third_party_dependency: The size of the delay to other wearers (sends x real transport latency, each at most 15 s) and of the operator's spend, reputation damage or bot/SMTP throttling depend on the channel providers' latency and account limits, which are outside the repository; the count and serial ordering of sends do not.

**Actual result:** Not executed in this run (source-only policy; no sandbox on this host). The source establishes deterministically that the fixture steps above return 200 for every contact and alarm with no limit, that one pump cycle produces contacts x escalations x channels sends through the operator's channel objects in creation order with the other wearer's send last, that the sends recur every repeat_after_s (minimum 60 s) across restarts and after the wearer is disabled, and that each send is awaited serially so the cycle's wall time is the sum of the individual transport times (each up to TIMEOUT_S=15 s). The real-world delay and operator spend scale with provider latency and limits, which were not measured.

**Impact:** Unbounded, recurring messages carrying attacker-chosen text to arbitrary third-party addresses from the operator's SMTP identity and Telegram bot, persisting across restarts and after the wearer is disabled, stoppable only by an operator resolving each escalation one at a time; while the queue drains, every other wearer's alert delivery and silent-phone detection on the same instance are delayed by the serialized transport time, a safety-function impact whose magnitude depends on channel latency and provider limits.  
**Likelihood:** Requires a valid wearer bearer token; wearers are a small, semi-trusted population of enrolled phones. A lost or stolen phone, an exfiltrated token or a hostile beta tester on a shared instance suffices, and the requests are trivial JSON POSTs with no further control.  
**Priority rationale:** overall `medium` (impact `medium`, likelihood `low`); confidence `high`: Every multiplier (uncapped contacts, uncapped open escalations keyed on a free detector string, immediate due, 60 s minimum repeat, no pruning, restart restore, disable not honoured by delivery) and the strictly serial single-coroutine delivery are explicit in source with no limiting control in the app, the store, the compose files or the Caddyfile; only the real-world magnitude of the delay and spend is runtime-dependent and was not measured.

**Smallest source fix:** Enforce containment on the server regardless of what a wearer token asks for: cap contacts per wearer, cap open escalations per wearer and collapse further alarms into the newest open one, bound detector length and restrict kind to the phone-origin values, and decouple delivery so one wearer's queue cannot head-of-line block other wearers' sends or the dead-man check (per-wearer delivery tasks or a bounded concurrency budget per cycle, with the dead-man evaluation in its own task). These limits are enforced in server code on the wearer's own data, so they hold the spec's isolation invariant without trusting the phone.

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

### LOW — Disabling a wearer does not stop its open escalations, while removing the wearer's own ability to cancel them

**Fingerprint:** `server/app/main.py:pump_cycle:disabled-wearer-escalations-keep-sending`  
**Source:** `server/app/wearers.py:206` (entrypoint, disable_wearer); `server/app/store.py:160` (propagation, Store.set_wearer_enabled); `server/app/store.py:189` (propagation, Store.wearer_for_token); `server/app/main.py:469` (sink, pump_cycle)

**Lower-trust principal:** Not an attacker action: the failing principals are the admin whose kill switch does not stop outbound paging and the disabled wearer who can no longer cancel a false alarm; the affected parties are the wearer's contacts (kept paged) and the operator's channel spend. The reproduction uses only the in-repo pytest fixture (server/tests/conftest.py appenv: tmp SQLite, CM_DISABLE_PUMP=1, FakeChannel replacing every channel) with no network and no live target.

**Root cause:** pump_cycle's delivery loop (server/app/main.py:468-470) filters only on esc.resolved; Store.set_wearer_enabled (server/app/store.py:158-161) and both disable routes (server/app/wearers.py:201-208, server/app/ui.py:452-457) neither resolve nor suspend the wearer's open escalations, and startup restore (main.py:545-547 via store.py:290-296) ignores wearers.enabled.

**Intended behaviour:** openspec/specs/wearer-management/spec.md lines 10-12: a disabled wearer's tokens stop authenticating and their monitoring stops 'without alarming'. The dead-man section of pump_cycle already honours enabled (main.py:442-444). Disabling should likewise stop that wearer's open escalations (resolve them with an audited reason, or at least suspend delivery) so no further sends or tier promotions occur, immediately and across restarts.

**Bounded reproduction (target-native, from the existing pytest fixture):**
1. Add a test to server/tests/test_delivery.py using the appenv fixture: call _setup_contacts(appenv); esc_id = _alarm(appenv); t0 = time.time(); _cycle(appenv, t0). Assert len(appenv.fakes['telegram'].sent) == 1 (first page to contact R1).
2. r = appenv.client.post('/api/v1/admin/wearers/default/disable', headers=admin_headers()); assert r.status_code == 200 and appenv.store.get_wearer('default')['enabled'] == 0.
3. r = appenv.client.post('/api/v1/alarm/resolve-open', json={}, headers=wearer_headers()); assert r.status_code == 401 (the disabled wearer can no longer cancel); assert not appenv.main.escalations[esc_id][1].resolved.
4. _cycle(appenv, t0 + 1801); assert len(appenv.fakes['telegram'].sent) == 2 and not appenv.main.escalations[esc_id][1].resolved: the disabled wearer's contact was paged again on the repeat cadence.
5. Optionally: rows = appenv.store.load_unresolved_escalations(); assert [e for _, e, _ in rows] == [esc_id] to show the escalation would be restored unchanged by a restart.
6. Run with: cd server && .venv/Scripts/python.exe -m pytest -q tests/test_delivery.py -k disabled (JDK/pebble not needed).

Payloads:
- `POST /api/v1/contacts {"name": "R1", "telegram_chat_id": "111111", "ntfy_topic": "cm-r1"} with Authorization: Bearer testtoken (via _setup_contacts in server/tests/test_delivery.py)`
- `POST /api/v1/alarm {"detector": "impact", "kind": "watch_alarm", "lat": 52.5, "lon": 13.4} with Authorization: Bearer testtoken (via _alarm)`
- `POST /api/v1/admin/wearers/default/disable with Authorization: Bearer admintok`
- `POST /api/v1/alarm/resolve-open {} with Authorization: Bearer testtoken`

**Conditions:** authorization_role: An admin (env CM_ADMIN_TOKEN while no admin operator exists, or an admin operator session) disables a wearer.; data_state: The wearer has at least one unresolved escalation whose tier snapshot contains deliverable contacts at the moment of disabling (contacts are frozen into the escalation at creation, main.py:108-131, so later contact edits do not stop it either).

**Actual result:** Source-established, not executed in this run (execution policy forbids running target code on this host): after the disable the second pump_cycle must deliver a second send to each contact because no code between set_wearer_enabled and the delivery loop reads wearers.enabled and the escalation remains unresolved with last_sent_t = t0 and repeat_after_s = 1800; resolve-open must return 401 because wearer_for_token requires w.enabled=1. The escalation also remains in load_unresolved_escalations output.

**Impact:** The admin's only lifecycle kill switch does not stop outbound alerting: contacts keep being paged on every repeat interval and tiers keep promoting until an operator or the admin token resolves each escalation by hand, the stop does not survive restarts, and the wearer is locked out of cancelling. Blast radius is the wearer's contacts and the operator's Telegram/ntfy/SMTP spend; it does not cross into other wearers' data.  
**Likelihood:** Requires an admin to disable a wearer while that wearer has an open escalation with deliverable contacts; no attacker action is involved, but removing a departed, misconfigured or abusive wearer during a false alarm is exactly the moment the switch is reached for, and the fleet view then hides the ongoing escalation.  
**Priority rationale:** overall `low` (impact `medium`, likelihood `low`); confidence `high`: Pure control flow verified line by line at d615619: the delivery loop's only filter is esc.resolved, both disable routes perform a single-column UPDATE, startup restore filters on resolved=0 only, and the token lookup's enabled=1 predicate is explicit. The reproduction uses only existing fixtures and helpers; no runtime or deployment fact is involved. Not executed in this run.

**Smallest source fix:** Make disable propagate to queued work. In pump_cycle's delivery loop, skip escalations whose wearer is not enabled and resolve them with an audited 'wearer_disabled' resolution (persisted, with a 'resolved' event, by='system'), so the stop also survives restarts. Do the same synchronously inside both disable routes (wearers.py disable_wearer and ui.py ui_disable_wearer) so the stop is immediate and the dashboard shows it. Consider sending the contacts already paged (esc.alerted_contacts()) an all-clear like _notify_phone_recovered does, since 'without alarming' should not leave responders with an open page. Add a regression test that disables a wearer mid-escalation and asserts no further sends, a resolved escalation and the audit event; re-enabling must not resurrect a resolved escalation.

`server/app/main.py`:
```python
    # escalation delivery
    enabled = {w["id"] for w in db.list_wearers() if w["enabled"]}
    for esc_id, (wid, esc) in list(escalations.items()):
        if esc.resolved:
            continue
        if wid not in enabled:
            # Disable is the admin's stop switch: nothing more leaves for
            # this wearer, and the stop is visible in the audit stream.
            esc.resolve("wearer_disabled")
            _persist_escalation(esc_id)
            db.add_event(wid, "resolved", {"id": esc_id,
                                           "resolution": "wearer_disabled",
                                           "by": "system"})
            continue
        sends = esc.step(t)

```

`server/app/wearers.py`:
```python
@router.post("/admin/wearers/{wearer_id}/disable")
def disable_wearer(wearer_id: str, request: Request,
                   authorization: str | None = Header(default=None)):
    require_admin(authorization, request)
    if not db.get_wearer(wearer_id):
        raise HTTPException(404, "unknown wearer")
    db.set_wearer_enabled(wearer_id, False)
    db.add_event(wearer_id, "wearer_disabled", {})
    # Stop queued alerting now, not on the next pump cycle (the same helper
    # should be called from ui.ui_disable_wearer).
    from . import main as rt
    rt.resolve_open_escalations(wearer_id, "wearer_disabled", by="admin")
    return {"ok": True}

```

### LOW — Login throttle is a hard lockout keyed on the bare username: five anonymous wrong passwords keep the named operator out of the dashboard

**Fingerprint:** `server/app/operators.py:check_rate_limit:username-lockout`  
**Source:** `server/app/ui.py:142` (entrypoint, login_post); `server/app/ui.py:145` (propagation, login_post); `server/app/operators.py:94` (propagation, login); `server/app/operators.py:99` (propagation, login); `server/app/store.py:437` (propagation, login_attempts_since); `server/app/operators.py:89` (sink, check_rate_limit)

**Lower-trust principal:** An unauthenticated client that can reach /ui/login and knows or guesses an operator username wants to keep that operator out of the dashboard, for example during an escalation.

**Root cause:** check_rate_limit (server/app/operators.py:87-89) treats the per-username counter as a hard block, and that counter is fed by failures recorded for any caller (operators.py:99) with no binding to the calling address, so an unauthenticated third party controls whether a named operator may authenticate.

**Intended behaviour:** Password guessing against an account is slowed, but a legitimate operator presenting the correct password from their own address can always sign in; failures caused by a stranger must not deny another principal access.

**Bounded reproduction (target-native, from the existing pytest fixture):**
1. Existing assertion (same address for attacker and operator): cd server && .venv/Scripts/python.exe -m pytest -q tests/test_dashboard.py::test_login_failures_are_generic_and_rate_limited — five wrong passwords for UI_ADMIN[0] return 401, then the correct password returns 429.
2. Isolated check that the u:<username> key alone locks the operator out from a different address (add temporarily to server/tests/test_dashboard.py and run with the same pytest command): from fastapi.testclient import TestClient; def test_stranger_locks_out_operator(appenv_ui): stranger = TestClient(appenv_ui.main.app, client=("203.0.113.9", 50000)); for _ in range(5): assert stranger.post("/ui/login", data={"username": UI_ADMIN[0], "password": "wrong"}, follow_redirects=False).status_code == 401; r = appenv_ui.client.post("/ui/login", data={"username": UI_ADMIN[0], "password": UI_ADMIN[1]}, follow_redirects=False); assert r.status_code == 429 and "cm_session" not in r.cookies
3. The fixture's own client address ('testclient') has no a: rows, so the 429 observed in step 2 can only come from the u:adminui count (operators.py:87). Note that X-Forwarded-For is not honoured by TestClient (uvicorn's ProxyHeadersMiddleware is not in the test path), so the second address must be set through the TestClient client= argument, not a header.

Payloads:
- `POST /ui/login  Content-Type: application/x-www-form-urlencoded  username=<operator>&password=wrong   (five times within 900 s from the attacker's address, then one more each time a row ages out)`
- `POST /ui/login  Content-Type: application/x-www-form-urlencoded  username=<operator>&password=<correct>   (from the operator's own address: 429, no Set-Cookie cm_session)`

**Conditions:** authentication_level: Anonymous; only network reachability of POST /ui/login is needed (published on all interfaces by the base compose, behind TLS on the VPS and NAS deployments).; data_state: The attacker must supply the operator's exact username (after whitespace strip); the bootstrap admin name is owner-chosen and not enumerable from the login page.; timing_dependency: The lockout lasts only while the attacker keeps at least five failures inside the rolling 900 s window; at most five of the attacker's own requests per address are recorded per window because the 429 is raised before recording.; system_configuration: Not demonstrated, deployment-dependent aggravation: if the server sits behind a reverse proxy whose address is not in FORWARDED_ALLOW_IPS (base compose default 127.0.0.1), request.client.host is the proxy for every caller and the a:<client> bucket locks all operators out after five anonymous failures with no username needed.

**Actual result:** Source-established and not executed in this run (policy forbids running target code): operators.py:85-89 refuses the login with 429 once five u:<username> rows exist in the window, independent of the a:<client> count, and tests/test_dashboard.py:38-49 asserts the 429 on the correct password after five wrong attempts. Expected result of the isolated check: five 401s for the stranger, then 429 and no cm_session cookie for the operator's correct password from a different address.

**Impact:** Denies new dashboard logins for the targeted operator while the attack continues; existing 7-day sessions, alert delivery, contact ack links and Telegram/ntfy acknowledgements are unaffected, and no data or privilege is gained.  
**Likelihood:** Unauthenticated and trivial (five form posts per 15 minutes), but the attacker must know or guess the exact operator username, the login page reveals nothing about accounts, and the deployments are small personal instances; the no-username variant needs a proxy left out of FORWARDED_ALLOW_IPS, which is deployment-dependent.  
**Priority rationale:** overall `low` (impact `low`, likelihood `low`); confidence `high`: The complete path from the anonymous form POST to the 429 is in repository source with deterministic logic (operators.py:85-100, store.py:428-438), and the repository's own test asserts the 429 on the correct password after five failures; the only unverified element is the deployment-dependent shared-bucket aggravation, which is recorded as a condition and not relied on.

**Smallest source fix:** Keep the per-address counter as the hard block, but stop a stranger's failures from locking the account: key the account bucket on (username, client address) so that only failures from the operator's own address can block that address, and keep a plain per-username counter only to raise an audit event when an account is under sustained guessing. Add a regression test in which five failures from a second TestClient address do not prevent ui_login from the fixture's address. Separately, every deployment behind a proxy must set CM_FORWARDED_ALLOW_IPS to the proxy's address, otherwise the a:<client> bucket is still one shared lockout for all callers (docker-compose.yml:50-56). The existing test test_login_failures_are_generic_and_rate_limited keeps passing because the same address hits the a: and u:@address buckets.

`server/app/operators.py`:
```python
LOGIN_WINDOW_S = 900
LOGIN_MAX_ATTEMPTS = 5            # per address, and per account from one address, per window
LOGIN_USER_ALERT_ATTEMPTS = 20    # per account from any address: audit only, never a lockout


def check_rate_limit(username: str, client: str) -> None:
    since = time.time() - LOGIN_WINDOW_S
    # The account bucket is bound to the calling address: a stranger's
    # failures for this username never lock the operator out from
    # another address. The address bucket stays a hard block.
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
        db.add_event(None, "login_failed", {"operator": username,
                                            "client": client})
        if db.login_attempts_since(f"u:{username}",
                                   time.time() - LOGIN_WINDOW_S) == LOGIN_USER_ALERT_ATTEMPTS:
            db.add_event(None, "login_guessing_suspected",
                         {"operator": username, "client": client})
        return None
    raw = secrets.token_urlsafe(32)
    csrf = secrets.token_urlsafe(24)
    db.create_session(_hash_session(raw), username, csrf,
                      time.time() + SESSION_TTL_S)
    db.add_event(None, "login", {"operator": username, "client": client})
    return raw, Operator(username, op["role"], csrf)

```

`server/tests/test_dashboard.py`:
```python
from fastapi.testclient import TestClient


def test_a_strangers_failures_do_not_lock_the_operator_out(appenv_ui):
    stranger = TestClient(appenv_ui.main.app, client=("203.0.113.9", 50000))
    for _ in range(5):
        r = stranger.post("/ui/login",
                          data={"username": UI_ADMIN[0], "password": "wrong"},
                          follow_redirects=False)
        assert r.status_code == 401
    # the stranger's own address is now blocked ...
    r = stranger.post("/ui/login",
                      data={"username": UI_ADMIN[0], "password": "wrong"},
                      follow_redirects=False)
    assert r.status_code == 429
    # ... but the operator, from another address, still gets in
    assert ui_login(appenv_ui.client)

```

### LOW — Enrol-code exchange checks single use with a SELECT outside the write transaction and burns the code with an unconditional UPDATE, so two overlapping requests mint two wearer tokens from one code

**Fingerprint:** `server/app/store.py:exchange_enroll_code:select-then-update-non-atomic`  
**Source:** `server/app/wearers.py:157` (entrypoint, enroll); `server/app/store.py:127` (propagation, Store._conn); `server/app/store.py:205` (propagation, exchange_enroll_code); `server/app/store.py:213` (propagation, exchange_enroll_code); `server/app/store.py:215` (sink, exchange_enroll_code)

**Lower-trust principal:** A party who has obtained a wearer's live enrol code (for example by seeing it in transit or on screen) sends POST /api/v1/enroll with that code at the same instant the legitimate phone does. Both receive 200 and a distinct, non-expiring wearer bearer token; the phone's enrolment does not fail, so no one is prompted to regenerate the code or revoke tokens. The attacker's token grants the wearer's entire phone API: heartbeats that satisfy the dead-man check, alarms that page the contacts, and read/write of the wearer's contacts and tiers.

**Root cause:** server/app/store.py:201-217 implements a check-then-act across two statements that are not covered by one write transaction: the uniqueness check is a SELECT executed before any lock is taken (the connection at store.py:127 keeps sqlite3's default legacy transaction control, which begins the implicit transaction only at the first DML statement), and the burn at store.py:213 is `UPDATE enroll_codes SET used_t=? WHERE code_hash=?` with no `used_t IS NULL` predicate and no rowcount check, so a second writer that already passed the SELECT re-burns the code and still reaches the token INSERT at store.py:215.

**Intended behaviour:** The method's docstring (store.py:203) and the architecture's trust-boundary table state that the code is burned and the token minted atomically: exactly one token may ever be issued for one enrol code, and any second exchange of the same code, concurrent or sequential, must return None so the endpoint answers 410 (wearers.py:167-168) and the duplicate is visible to the wearer and operator.

**Bounded reproduction (target-native, from the existing pytest fixture):**
1. Save the second payload as server/tests/test_enroll_race.py (it uses only the existing `appenv` fixture from server/tests/conftest.py and the stdlib).
2. From server/ run `.venv/Scripts/python.exe -m pytest -q tests/test_enroll_race.py` (JDK/Docker not needed; the local venv is CPython 3.14.3, which has the same default transaction control as the deployed 3.12.14; to match production exactly, run the same test inside the pinned image).
3. Observe that the test passes as written: both exchange_enroll_code calls return 'alice' and `SELECT COUNT(*) FROM tokens WHERE wearer_id='alice'` is 2 for one enrol code. Optionally also run the end-to-end variant: issue one code via POST /api/v1/admin/wearers/alice/enroll-code and fire two POST /api/v1/enroll requests with it from two threads through appenv.client; without the barrier this is timing-dependent and may need repetition.
4. Apply the remediation below and rerun: exactly one call returns 'alice', the other returns None (410 at the endpoint), and the token count is 1; the existing test_enrollment_flow_and_single_use still passes.

Payloads:
- `POST /api/v1/enroll  Content-Type: application/json  {"code": "<same valid 8-symbol code>"}  (sent twice, concurrently, from two clients)`
- `import sqlite3, threading, time   def test_enroll_code_exchanged_twice_when_requests_overlap(appenv, monkeypatch):     store = appenv.store     store.create_wearer('alice', 'Alice')     code_hash = appenv.wearers.hash_token('ABCDEFGH')     store.create_enroll_code('alice', code_hash, time.time() + 3600)     barrier = threading.Barrier(2, timeout=15)      class Proxy:  # wraps sqlite3.Connection; parks both callers after their SELECT         def __init__(self, conn):             object.__setattr__(self, '_c', conn)         def __getattr__(self, name):             return getattr(self._c, name)         def __setattr__(self, name, value):             setattr(self._c, name, value)         def __enter__(self):             self._c.__enter__()             return self         def __exit__(self, *exc):             return self._c.__exit__(*exc)         def execute(self, sql, *args):             if sql.lstrip().startswith('UPDATE enroll_codes'):                 barrier.wait()  # both SELECTs have run before either UPDATE             return self._c.execute(sql, *args)      real_connect = sqlite3.connect     monkeypatch.setattr(sqlite3, 'connect', lambda *a, **k: Proxy(real_connect(*a, **k)))     results = []      def worker(i):         results.append(store.exchange_enroll_code(code_hash, time.time(), 'tok%d' % i))      threads = [threading.Thread(target=worker, args=(i,)) for i in range(2)]     for t in threads:         t.start()     for t in threads:         t.join(60)     n = real_connect(store.path).execute(         'SELECT COUNT(*) FROM tokens WHERE wearer_id=?', ('alice',)).fetchone()[0]     # current source: both calls succeed and two live tokens exist for one code     assert results == ['alice', 'alice'] and n == 2     # after the fix the expectation becomes: results.count('alice') == 1 and n == 1 `

**Conditions:** authentication_level: No credentials: /api/v1/enroll is anonymous. Each party needs one request, well under the 10/h per-client-address limit (wearers.py:131-140), even when both share one bucket.; data_state: A valid, unexpired (24 h default TTL, wearers.py:25), not-yet-used enrol code for an enabled wearer must be known to both callers; the code is 2^40-entropy and handed to the wearer out of band, so this is possession of the code, not guessing.; timing_dependency: Both requests' SELECTs must complete before either UPDATE takes the write lock. In production that window is the sub-millisecond gap inside one method call on two threadpool threads; the local reproduction makes it deterministic with a barrier placed before the UPDATE.; system_configuration: Store leaves sqlite3's default transaction mode (store.py:127) and the deployed interpreter is CPython 3.12.14 (Dockerfile:4), for which the documented default (LEGACY_TRANSACTION_CONTROL, isolation_level '') begins implicit transactions only before INSERT/UPDATE/DELETE/REPLACE. Setting isolation_level=None would not help; only a conditional UPDATE or BEGIN IMMEDIATE does.

**Actual result:** Not executed in this run (source-only policy, no sandbox). The source establishes: the SELECT at store.py:205 runs outside any transaction, the UPDATE at 213 cannot fail for a second writer and its rowcount is ignored, and the INSERT at 215 executes unconditionally; with CPython's documented default transaction control and SQLite's busy-handler retry for a deferred transaction whose first statement is a write, the barrier test above yields results == ['alice', 'alice'] and two token rows, i.e. two live wearer tokens issued for one single-use code, each returned with HTTP 200 by wearers.py:170.

**Impact:** A second never-expiring wearer bearer token is issued at the anonymous boundary to a party other than the phone, without the 410 that would alert the wearer/operator; the token drives a safety function (heartbeats that silence the dead-man check, alarms that page contacts, contact and tier edits for that wearer). The delta over plain code misuse is concealment and persistence: the only remaining signal is a second `enrolled` event with another client address on the wearer page, and recovery is the admin's revoke-all-tokens action. No cross-wearer or operator impact.  
**Likelihood:** Requires prior possession of a live enrol code (2^40 space, 24 h TTL, delivered out of band) and landing a request inside the sub-millisecond gap between the legitimate phone's SELECT and UPDATE without being able to observe when the phone will enrol; a party holding the code can already obtain a token by using it first. The non-adversarial variant (a phone client retrying the same code within milliseconds) is more plausible than an attack.  
**Priority rationale:** overall `low` (impact `medium`, likelihood `low`); confidence `medium`: Every statement on the path was re-read at the cited lines and the non-atomic check-then-act is unambiguous in source; the two-token outcome additionally relies on two documented library defaults not executed in this run (CPython 3.12/3.14 legacy transaction control issuing BEGIN only before DML, and SQLite's busy-handler retry for a deferred transaction whose first statement is a write). The deterministic barrier test above resolves both in one run from the existing fixtures.

**Smallest source fix:** Make the burn itself the uniqueness check: update the row only where `used_t IS NULL AND expires_t > ?` and insert the token only when that UPDATE reports exactly one changed row; otherwise return None. A single UPDATE statement is atomic in SQLite, so whichever writer runs first wins and the other sees rowcount 0 regardless of Python's transaction mode. Keep the SELECT for the enabled-wearer join (or fold it into the UPDATE with a subquery). Optionally also start the method with `c.execute('BEGIN IMMEDIATE')` so the read and write happen under one write lock. Add the barrier test above as a regression test expecting one token.

`server/app/store.py`:
```python
    def exchange_enroll_code(self, code_hash: str, now: float,
                             new_token_hash: str) -> str | None:
        """Atomically burn the code and mint the token. None if invalid."""
        with self._conn() as c:
            r = c.execute(
                """SELECT e.wearer_id FROM enroll_codes e JOIN wearers w
                   ON w.id = e.wearer_id
                   WHERE e.code_hash=? AND e.used_t IS NULL
                     AND e.expires_t > ? AND w.enabled=1""",
                (code_hash, now)).fetchone()
            if not r:
                return None
            # The burn is the uniqueness check: a single conditional UPDATE is
            # atomic, so a concurrent exchange of the same code changes 0 rows.
            burned = c.execute(
                "UPDATE enroll_codes SET used_t=? "
                "WHERE code_hash=? AND used_t IS NULL AND expires_t > ?",
                (now, code_hash, now)).rowcount
            if burned != 1:
                return None
            c.execute("INSERT INTO tokens (token_hash, wearer_id, created_t) "
                      "VALUES (?,?,?)", (new_token_hash, r["wearer_id"], now))
            return r["wearer_id"]

```

### LOW — An operator whose username contains '/' can log in as admin but cannot be disabled, demoted or password-reset by any route

**Fingerprint:** `server/app/ui.py:op_create:username-unroutable`  
**Source:** `server/app/ui.py:485` (entrypoint, op_create); `server/app/ui.py:494` (propagation, op_create); `server/app/operators.py:95` (propagation, login); `server/app/templates/operators.html:29` (propagation, operators table); `server/app/ui.py:531` (sink, op_disable)

**Lower-trust principal:** A holder of an admin session (stolen cm_session cookie or an unattended admin browser) creates a second admin named 'ops/x'; after the owner regains control, no dashboard action can disable, demote or reset that account.

**Root cause:** server/app/ui.py:488 validates the username only for non-emptiness (and operators.py:71 not at all), while every management route and the operators template address operators as a single, un-encoded URL path segment matched by Starlette's [^/]+ convertor; a '/' in the name therefore escapes the admin's revocation routes with no alternative management surface.

**Intended behaviour:** Every operator account an admin (or the env bootstrap) creates can later be disabled, demoted or password-reset by an admin through the dashboard (coverage invariant); a username that cannot be addressed by the management routes must be refused at creation.

**Bounded reproduction (target-native, from the existing pytest fixture):**
1. Add to server/tests/test_dashboard.py (uses the existing appenv_ui fixture and ui_login helper from server/tests/conftest.py): def test_slash_username_is_unmanageable(appenv_ui): c = appenv_ui.client; csrf = ui_login(c)
2. r = c.post('/ui/operators/create', data={'username': 'ops/x', 'password': 'longenough1', 'role': 'admin', 'csrf': csrf}, follow_redirects=False); assert r.status_code == 303 (account created)
3. for path in ('/ui/operators/ops/x/disable', '/ui/operators/ops%2Fx/disable', '/ui/operators/ops/x/password', '/ui/operators/ops/x/role'): r = c.post(path, data={'csrf': csrf, 'password': 'newpassword9', 'role': 'responder'}, follow_redirects=False); assert r.status_code == 404 (no route matches; compare /ui/operators/resp/disable which returns 303 in test_disabled_operator_and_password_reset_kill_sessions)
4. csrf2 = ui_login(c, 'ops/x', 'longenough1'); assert c.get('/ui/operators').status_code == 200 (the account logs in and holds the admin role)
5. Run: cd server && .venv/Scripts/python.exe -m pytest -q tests/test_dashboard.py -k slash_username

Payloads:
- `username=ops/x&password=longenough1&role=admin&csrf=<csrf>`
- `POST /ui/operators/ops/x/disable`
- `POST /ui/operators/ops%2Fx/disable`

**Conditions:** authorization_role: Creating the account needs an admin dashboard session (an attacker who holds one transiently, or a legitimate admin making a typo); the finding is about revocation failing afterwards, not initial access.; data_state: An operators row whose username contains '/' (via op_create or CM_UI_ADMIN_USER at bootstrap); the generated forms for '?', '#' or '%2F' names also break but those rows remain reachable with a hand-encoded URL.

**Actual result:** Not executed in this run (source-only policy, no sandbox). The source establishes: create returns 303 and inserts the row; every management POST for the name, whether the template's /ui/operators/ops/x/... form target or the hand-encoded ops%2Fx, is decoded to a path with an extra segment that matches no compiled route and returns FastAPI's default 404; login as 'ops/x' returns 303 with a session whose lookup_session role is 'admin'. If the fixed build is in place, the create instead returns 200 with the 'username, role, and a password...' error page and the row is absent.

**Impact:** An admin account the application cannot disable, demote or password-reset keeps full dashboard authority (wearer contacts and addresses, diagnostics bundles, escalation acks, enrol codes, further operators) until the owner edits the SQLite file by hand on the production data volume; recovery exists but is outside the product.  
**Likelihood:** Requires an admin session already in the attacker's hands (or an admin's own typo); the vulnerability only extends an existing compromise into persistence and leaves an operator_created audit event naming the account.  
**Priority rationale:** overall `low` (impact `medium`, likelihood `low`); confidence `medium`: The complete path is read from the repository and the installed framework sources (route compilation, str convertor, pre-routing percent-decoding in both uvicorn parsers and the TestClient) and no alternative management surface exists; not executed in this run, and the pinned Starlette 1.6.0 was not inspected locally, though the str convertor's no-separator contract is stable documented behaviour.

**Smallest source fix:** Enforce the invariant at creation: accept only a routable username alphabet in op_create and bootstrap_admin (mirror the wearer-id rule at wearers.py:183), so no row can exist that the management routes cannot address. Defensively also URL-encode the name in operators.html (`{{ o.username | urlencode }}`) and, at startup, log or refuse any existing operators row whose name fails the rule; the deployment check below finds existing rows.

`server/app/ui.py`:
```python
import re

_USERNAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,31}")

@router.post("/operators/create")
async def op_create(request: Request):
    op = ops.require_ui_admin(request)
    await ops.verify_csrf(request, op)
    form = await request.form()
    username = str(form.get("username", "")).strip()
    password = str(form.get("password", ""))
    role = str(form.get("role", "responder"))
    if (not _USERNAME_RE.fullmatch(username) or len(password) < 8
            or role not in ("admin", "responder")):
        return operators_page(request,
                              error="username (letters, digits, . _ -, max 32), "
                                    "role, and a password of at least 8 "
                                    "characters are required")
    ...
```

`server/app/templates/operators.html`:
```python
<form class="inline" method="post" action="/ui/operators/{{ o.username | urlencode }}/role">
...
<form class="inline" method="post" action="/ui/operators/{{ o.username | urlencode }}/password">
...
<form class="inline" method="post" action="/ui/operators/{{ o.username | urlencode }}/{{ 'disable' if o.enabled else 'enable' }}">
```

## 4. NEEDS VALIDATION

These are source-grounded leads whose decisive fact lies outside the source or needs execution this run could not perform. They carry **no severity** and are not confirmed vulnerabilities.

| Title | Repository trace | Exact blocker | Bounded local next step | Safe owner-observed check |
|---|---|---|---|---|
| VPS Caddyfile always serves the unauthenticated bundled ntfy on public 443 under the placeholder name ntfy.localhost, contradicting its own 'never reachable' promise | docker-compose.vps.yml:40 → docker-compose.vps.yml:44 → Caddyfile:16 → Caddyfile:17 → docker-compose.yml:75 | Caddy 2.11.7 behaviour is a provider fact outside the repository and could not be rendered in this run (no execution permitted): that both site blocks land on one :443 server routed by Host, that a *.localhost address is served with an internal-issuer certific | On any machine with the caddy 2.11.7 binary, no network needed and nothing deployed: `CM_DOMAIN=standby.example.org CM_NTFY_DOMAIN=ntfy.localhost caddy adapt --config server/Caddyfile --adapter caddyfile --pretty`. Expected if the candidate holds: one server u | Owner only, on the VPS host over loopback, nothing sent from elsewhere and nothing changed: (1) `docker compose ps` to record whether the caddy service from docker-compose.vps.yml is running at all; (2) `grep -E '^(CM_NTFY_DOMAIN\|CM_NTFY_URL)=' .env` to recor |
| A single wearer token can fill the shared ./data volume with diagnostics bundles; a full volume would block SQLite writes that every escalation send depends on | diagnostics.py:74 → diagnostics.py:82 → diagnostics.py:104 → store.py:498 → docker-compose.yml:68 → main.py:394 | Whether the host filesystem under ./data enforces a quota for UID 10001 or the shared folder (Synology shared-folder quota; VPS partition layout and free space) is a deployment fact not visible in source. Without a quota the shared-impact chain holds; with one | Step 1 (missing bound, existing fixtures, no filesystem tricks): in server/tests, with the appenv fixture from conftest.py, loop 50 times posting content=b'PK\x03\x04' to '/api/v1/diagnostics?days=1' with headers {'Content-Type': 'application/zip', **wearer_he | On the NAS or VPS host, without generating any requests against the service: run df -h on the filesystem holding ./data and du -sh ./data/diagnostics; on DSM check Control Panel > Shared Folder > Edit > Quota (and user quota for UID 10001) for the folder holdi |
| A wearer heartbeat with suspended_until = +Infinity makes every operator fleet and wearer page fail with HTTP 500 until a finite or null value is stored | main.py:190 → routing.py:446 → main.py:204 → store.py:482 → ui.py:92 → ui.py:94 | No target code may run in this audit: the sqlite3 round-trip of float('inf') through Store.set_suspended_until/get_suspended_until (Python sqlite3 binding plus SQLite REAL storage) and the resulting OverflowError / HTTP 500 were not observed locally. | In server/tests with the existing fixtures (conftest.py: appenv_ui, ui_login, wearer_headers, WEARER_TOKEN enrols wearer 'default'): (1) csrf = ui_login(appenv_ui.client) FIRST, because ui_login itself GETs /ui/ and would trip the bug afterwards; (2) r = appen | The owner, on the NAS, greps the monitor container log for 'OverflowError' with 'wearer_states' in the traceback to confirm the failure has never fired in production, and confirms that the only heartbeat sender is the companion (ServerClient.kt:173 sends a fin |
| A non-OSError raised by one channel send stalls delivery of every later escalation on the instance; a wearer-supplied alarm detector string containing a lone UTF-16 surrogate is the source-visible trigger | main.py:248 → main.py:134 → main.py:486 → channels.py:87 → main.py:498 → main.py:530 | Whether pydantic-core 2.46.4 (compiled extension; Rust source not present in server/.venv) accepts a Python str containing a lone UTF-16 surrogate during validate_python of an unconstrained `str` field. The JSON-decoder half of the hunter's blocker is settled  | Run from server/ with the existing pytest fixtures (conftest.py appenv; FakeChannel; CM_DISABLE_PUMP=1), noting that server/.venv is Python 3.14.3 with starlette 1.3.1 while requirements.txt pins Python 3.12 / starlette 1.6.0 (Request.json is stdlib json.loads | Not applicable: the local fixture settles the claim; do not send a surrogate-bearing alarm to a live instance. The owner may only confirm from the deployed .env whether CM_NTFY_URL or CM_SMTP_HOST is set (either makes a wearer able to self-assign an affected c |
| A single persistently failing Telegram update (e.g. from any user who messages the bot and then blocks it) wedges the acknowledgement poller and silently stops all later Telegram acknowledgements | telegram_poll.py:77 → telegram_poll.py:90 → telegram_poll.py:67 → telegram_poll.py:121 → telegram_poll.py:99 | No target code may execute in this run (no OS-enforced sandbox on this host), so the re-fetch-and-fail-again behaviour was established from source only and not observed locally. | Add to server/tests/test_telegram_poll.py a copy of test_poll_records_ack_and_advances_offset in which fake_api (a) returns on the first and second getUpdates call {'result': [{'update_id': 41, 'message': {'text': 'hi', 'chat': {'id': 7}, 'from': {'first_name' | Owner-observed only, on the owner's own instance and own bot: from a throwaway Telegram account send the bot any message and immediately block the bot. Watch the server log (`docker compose logs -f monitor` in the compose directory) for a 'telegram poll cycle  |
| Cookie-authenticated /api/v1/admin/* mutations have no CSRF check; protection rests on SameSite=Lax alone | wearers.py:200 → wearers.py:203 → wearers.py:87 → operators.py:119 → wearers.py:206 | Whether an origin same-site with the URL operators actually use for the dashboard exists and serves HTML that a non-operator can author (plain-http dashboard and ntfy on the same LAN host name, CM_NTFY_DOMAIN relative to CM_DOMAIN on the VPS, DDNS suffix on th | Add to server/tests/test_dashboard.py and run with `cd server && .venv/Scripts/python.exe -m pytest -q -k cookie_only`: def test_admin_api_accepts_cookie_only_post(appenv_ui): ui_login(appenv_ui.client); r = appenv_ui.client.post('/api/v1/admin/wearers/default | The owner (no audit traffic is sent) lists, for the deployment they operate, the exact URL operators log in at (scheme, host, port) and every other origin sharing its scheme and registrable domain: the ntfy published on port 8090 of the NAS or at CM_NTFY_DOMAI |
| Base compose trusts only 127.0.0.1 for X-Forwarded-For, which a bridge-networked container never sees from DSM's reverse proxy; unless the NAS .env overrides CM_FORWARDED_ALLOW_IPS, all internet callers share one login and one enrol rate-limit bucket and the audit records the proxy | ui.py:144 → Dockerfile:34 → docker-compose.yml:56 → operators.py:88 → operators.py:89 | The production NAS .env value of CM_FORWARDED_ALLOW_IPS is not readable in this audit (.env is excluded). If it names the current bridge gateway, per-client buckets are restored and the candidate is refuted for that host; the repository's last record (archived | Add to server/tests/test_proxy_headers.py, using the existing appenv_ui fixture and UI_ADMIN from tests/conftest.py: client = TestClient(ProxyHeadersMiddleware(appenv_ui.main.app, trusted_hosts="10.0.0.1")) (a list the TestClient peer 'testclient' never matche | Owner, on the NAS over SSH, read-only: `docker compose exec monitor printenv FORWARDED_ALLOW_IPS` (127.0.0.1 means the default is live) and `docker network inspect <project>_default --format '{{(index .IPAM.Config 0).Gateway}}'` to compare with the configured  |
| Base compose (the NAS production file) publishes the unauthenticated, plaintext ntfy server and the plaintext app port on every host interface | docker-compose.yml:78 → docker-compose.yml:75 → .env.example:29 → main.py:485 → channels.py:83 → main.py:306 | The live NAS compose project cannot be observed here: whether the ntfy service is still present, what the published ports actually bind to (0.0.0.0 vs 127.0.0.1 vs a LAN address) and whether the router or a DSM reverse-proxy rule exposes 8090 beyond the LAN ar | From a checkout, render the manifest without starting anything: `cd server && docker compose -f docker-compose.yml config` — confirm that `services.monitor.ports` and `services.ntfy.ports` render with `published: "8080"`/`"8090"` and no `host_ip` key (meaning  | On the NAS over SSH (observation only, no traffic): (1) `docker ps --format '{{.Names}} {{.Ports}}'` — a line such as `cryomonitor-ntfy-1 0.0.0.0:8090->80/tcp` (or `sudo ss -ltnp \| grep -E ':(8081\|8080\|8090)\b'` showing `0.0.0.0:`/`*:`) confirms all-interfa |

Full traces and plans: `NEEDS-VALIDATION.md`.

## 6. Hardening notes and positive patterns

**Positive source patterns:** token-bound wearer scoping (`wearers.py` resolve_auth / scoped_wearer_id) with `WHERE wearer_id=?` in every store query; conditional upserts that cannot re-parent another tenant's rows; atomic-looking single-use enrolment codes (see the race lead); hashed sessions with live role lookup and revocation on password change and disable; per-session CSRF on every UI mutation; parameterised SQL throughout; autoescaped templates, htmx `allowEval:false`, strict CSP, `frame-ancestors 'none'`, `Referrer-Policy: same-origin`; regex-locked diagnostics paths with streamed size caps and `.part` cleanup; ack tokens hashed and persisted before send with GET side-effect free; hash-pinned dependencies and a digest-pinned, non-root, read-only container.

**Hardening notes from the hunters (not findings; no boundary violation demonstrated):**

- *hunt-deployment:* Obvious-things sweep results (no finding): no hardcoded secrets, no security TODO/FIXME/HACK, no eval/exec/subprocess in first-party code; .env and tokens.txt untracked, ignored and never in git history; CORS absent (default deny); cookie HttpOnly+SameSite=Lax+Secure; redirects fixed/path-derived; FastAPI debug off; API docs off unless CM_API_DOCS=1; dependencies hash-pinned and base image digest-pinned.
- *hunt-deployment:* .gitignore covers only server/data/*.db. In a deployment checkout (the VPS guide clones the repo to /home/standby/pebble-standby) server/data/backup/*.db (scripts/backup.sh:27), server/data/diagnostics/**/*.zip (phone log bundles), server/data/caddy/data (ACME account key and site private keys, docker-compose.vps.yml:47), server/data/ntfy and *.db-wal/*.db-shm are untracked but not ignored; a `git add -A` would stage them. Ignore `server/data/` as a whole.
- *hunt-deployment:* Wearer and admin bearer tokens are accepted as `?token=` query parameters on GET /api/v1/status and POST /api/v1/alarm/{esc_id}/resolve (main.py:286,373) and as a JSON body field; uvicorn's access log (on by default; Dockerfile:33 passes no --no-access-log) and any proxy log would record query-string tokens. The companion sends Authorization headers only, so the path is dormant: drop query/body token acceptance or pass --no-access-log.
- *hunt-deployment:* GET/POST /api/v1/ack/{raw_token} puts the ack capability in the URL path, so it lands in uvicorn/DSM/Caddy access logs if enabled. The reader is the operator (same trust) and Referrer-Policy: same-origin (main.py:56) stops referer leakage, so this is not a boundary failure; consider logging at WARNING or redacting the ack path in access logs.
- *hunt-deployment:* EmailChannel calls starttls() only when CM_SMTP_USER is set (channels.py:116-118); an unauthenticated relay configuration sends alert text, the location link and the raw ack URL in cleartext SMTP. Call starttls() whenever the server advertises STARTTLS, independent of authentication.
- *hunt-deployment:* Precedence drift: compose defaults CM_NTFY_URL to https://ntfy.sh (docker-compose.yml:34) while .env.example sets http://ntfy (:29). With the compose default, alerts including location and the raw ack URL go to a public third-party service on a contact-chosen topic (4-64 chars, wearers.py:249). Pick one default and state it in both files; consider generating topics server-side.
- *hunt-deployment:* GET /ui/login reveals to anonymous clients whether any admin account exists (ui.py:138, login.html:6-10) and GET /api/v1/health reveals the version string and a degraded-wearer count (main.py:349-354). Low value to an attacker; acceptable, but the no_admins hint could be gated to loopback.
- *hunt-deployment:* secrets.compare_digest raises TypeError on non-ASCII strings: an unauthenticated `Authorization: Bearer <non-ASCII>` when CM_ADMIN_TOKEN is set (wearers.py:54) or a non-ASCII X-CSRF header (operators.py:151) produces a 500 instead of 401/403. No disclosure (debug off), but normalise/encode before comparing.
- *hunt-deployment:* Secrets are injected as container environment (docker-compose.yml:20-56) and are visible via `docker inspect` to any docker-group user or DSM admin; CM_ADMIN_TOKEN and CM_UI_ADMIN_* are meant to be removed after bootstrap (main.py:542-544). Consider compose `secrets:` or an env_file with 600 mode (README already says chmod 600 .env).
- *hunt-deployment:* The base compose comment (docker-compose.yml:1) advertises the file for 'VPS, NAS or home server' although only the VPS overlay pins ports to loopback; a VPS operator who follows README 'docker compose up -d --build' alone exposes plaintext 8080 and unauthenticated ntfy 8090 to the internet. Either default the base file to 127.0.0.1 bindings (with DSM reading them via the host) or make the README route VPS users to the overlay unconditionally.
- *hunt-deployment:* Outside this unit's ownership, flagged for the pump retry-storm / channels units: NtfyChannel.deliver calls text.encode() (channels.py:87) on wearer-supplied detector text; if pydantic lets a lone UTF-16 surrogate (JSON "\ud800") through, the UnicodeEncodeError escapes the OSError handler, aborts pump_cycle before record_sent (main.py:488-498), and would repeat every 5 s for all wearers. Not verified here.
- *hunt-wildcard:* server/app/main.py:168,259 — AlarmIn.kind is a free string: unknown values raise ValueError (500) and the phone may send kind=phone_silent, which suppresses the pump's own PHONE_SILENT escalation via _has_open and is not cleared by resolve-open; use Literal['watch_alarm','fault','test'].
- *hunt-wildcard:* server/app/ui.py:317-335 — ui_save_contact omits the tier-existence check the API applies (wearers.py:313); a contact in a nonexistent tier counts as coverage (wearer_degraded false) but snapshot_tiers never alerts it. Mirror the check and validate wid exists.
- *hunt-wildcard:* server/app/ui.py:485 and templates/operators.html:14,23,29 — operator usernames are unvalidated yet embedded in action URL paths; names containing '/', '?', '#' or spaces become unmanageable from the UI. Constrain usernames to a regex at creation.
- *hunt-wildcard:* server/app/ui.py:471-477 — operators_page reflects notice/error query parameters into the admin page (autoescaped, text only): content-spoofing vector for phishing an admin. Pass notices internally only.
- *hunt-wildcard:* server/app/main.py:227-229 and server/app/telegram_poll.py:88-89 — INFO logs interpolate untrusted text (phone_model, Telegram sender name) without newline stripping; forged log lines are possible (CWE-117).
- *hunt-wildcard:* server/app/wearers.py:130-140 — _enroll_attempts is keyed by client address and never pruned across keys; each distinct (or, where XFF is trusted, spoofed) address adds a permanent entry until restart. Prune stale keys on each call or use a bounded LRU.
- *hunt-wildcard:* server/app/main.py:155-162 — HeartbeatIn integer fields are unbounded; values >= 2^63 make add_heartbeat_point raise sqlite3 OverflowError after the dead-man state was already updated (500 for the wearer's own heartbeat). Use conint ranges (0..100 for batteries).
- *hunt-wildcard:* server/app/main.py:442-444 vs 468-470 — disabling a wearer stops the dead-man check but not delivery of that wearer's open escalations; resolve or pause them on disable so 'disable' actually silences alerts.
- *hunt-wildcard:* server/app/operators.py:95-97 — scrypt runs only for existing enabled users, so response time reveals valid usernames; verify against a dummy hash on miss.
- *hunt-wildcard:* server/app/main.py:255,273,367 and server/app/ui.py:61 — escalations dict is iterated directly from worker threads while the pump may insert (create_escalation), risking sporadic 'dictionary changed size during iteration' 500s; iterate over list(escalations.items()) as pump_cycle does.
- *hunt-ack-channels:* server/app/telegram_poll.py:87-118 — independent of the candidate's validation, wrap each per-update Bot API call (onboarding sendMessage, answerCallbackQuery) in its own try/except that logs and continues, and persist the offset after each processed update (or at minimum in a finally for the batch) so one failing update can never block later acknowledgements; add a regression test with a raising fake_api.
- *hunt-ack-channels:* server/app/channels.py:23-41 render_message — detector is wearer-controlled, uncapped free text that lands in bodies sent from the operator's bot/sender to the wearer's contacts; a newline lets a (compromised) wearer token spoof extra 'Location:'/'Acknowledge:' lines in an otherwise trusted alert. Cap it (e.g. 64 chars, as drill-result already does for phone_model) and replace control characters with spaces at the API boundary or inside render_message.
- *hunt-ack-channels:* server/app/wearers.py:247 _EMAIL_RE allows ',', ';', '<', '>' and '"' inside the local part and domain (e.g. 'a@b.com,root'); smtplib.send_message derives RCPT TO from the To header via getaddresses, so such a value can add an unqualified local recipient on the relay. No privilege gain (the same principal may set any address), but parse the address with email.utils.parseaddr and require the parsed address to equal the input, or exclude those characters.
- *hunt-ack-channels:* server/app/wearers.py:249 _NTFY_RE accepts 4-character topics while docker-compose.yml defaults CM_NTFY_URL to the public https://ntfy.sh, where the topic name is the only secret protecting the alert text (wearer location) and the embedded raw ack URL/token in the Actions header; consider generating topics server-side (>=16 random chars) or enforcing a minimum length when the base URL is a public server. The deployment side (ntfy exposure) is the CLOUD peer's unit; the validation threshold is this unit's.
- *hunt-ack-channels:* server/app/wearers.py:248 allows negative Telegram chat ids (groups). In a group every member can read the bot message's callback_data through the Telegram API and press the button, so the 'contact' is effectively the whole group. Document this in the contact UI or restrict Telegram contacts to private chats (positive ids) unless explicitly intended.
- *hunt-ack-channels:* server/app/store.py:306-317 ack_tokens are never expired, revoked or pruned; each process restart mints an additional valid token per (escalation, contact). Add a resolved/revoked marker or delete rows when the escalation resolves, and prune rows older than the longest escalation lifetime.
- *hunt-ack-channels:* server/app/main.py:322-336 relies on GET-vs-POST to keep link scanners from acknowledging; email security sandboxes that submit forms would still acknowledge. If this matters to the owner, require a nonce-free but human-only step (e.g. a second confirmation POST with a form field the GET page sets via a button click) and record the acking client in the ack event for audit.
- *hunt-ack-channels:* server/app/telegram_poll.py:88-89 logs Telegram first_name/last_name unsanitised at INFO; Telegram normalises names but the server should still strip control characters before logging.
- *hunt-files-availability:* server/app/diagnostics.py:92 opens the .part file with open(tmp, 'wb'); use os.open with O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW so a pre-existing path or symlink in the data directory (host-side tampering) cannot redirect the write. No application-level attacker path exists today because the name carries 32 random bits and only the service writes the volume.
- *hunt-files-availability:* server/app/main.py:550 calls diagnostics.purge_expired() at startup without a guard and purge_expired (diagnostics.py:59-62) has no per-row try/except: a single PermissionError or IsADirectoryError on one bundle file (host-side permissions, as already seen in the field 2026-10-14) aborts the hourly purge every hour and makes the whole service fail to start. Wrap each row's delete_bundle in try/except and log; keep the startup call non-fatal.
- *hunt-files-availability:* server/.env.example:59-60 documents CM_DIAG_MAX_BYTES and CM_DIAG_RETENTION_DAYS but server/docker-compose.yml passes neither (no env_file, not in environment:), so operators who tighten them in .env get no effect. Pass them through compose with the current defaults.
- *hunt-files-availability:* server/app/diagnostics.py:46-52 delete_bundle calls os.remove even when the DB row did not exist; harmless given the path construction, but returning early when removed is False (or deleting the file only when the row existed) keeps file and row lifecycles identical and avoids removing an orphan a later investigation might want.
- *hunt-files-availability:* server/app/ui.py:210-213 renders every diagnostics row of a wearer (store.list_diagnostics has no LIMIT); a wearer with thousands of bundles slows the admin page. Add LIMIT/pagination alongside the per-wearer quota.
- *hunt-files-availability:* server/app/main.py:82-83 escalations and _ack_cache keep resolved escalations and their raw ack tokens in process memory until restart; prune resolved entries after a grace period (they remain in SQLite) so memory is bounded by open escalations, and so raw ack tokens for resolved escalations do not linger in RAM.
- *hunt-files-availability:* server/app/channels.py:151-156 treats Telegram HTTP 429 like any failure and ignores retry_after; honour it (per-channel backoff) so one wearer's burst does not make other wearers' sends fail in the same window.
- *hunt-files-availability:* server/app/channels.py: make deliver() implementations catch Exception (log and return False) rather than OSError only, and cap AlarmIn.detector (e.g. max_length=64, reject strings that fail .encode('utf-8')) so the message text can never raise inside the pump regardless of how the body was decoded.
- *hunt-files-availability:* server/app/main.py:468-499: consider delivering per escalation with asyncio.gather and a per-cycle deadline, or round-robin by wearer, so one wearer's sends cannot delay another wearer's; independent of the quota fix.
- *hunt-tenant-data:* server/app/store.py:248-263 upsert_contact: contacts.id is a global PRIMARY KEY shared by all wearers; when a client-chosen id collides with another wearer's row the DO UPDATE ... WHERE clause makes the statement a silent no-op while wearers.py:315-318 still returns {ok, contact_id} and logs contact_upserted. Make the key (wearer_id, id), or check cursor.rowcount and return 409, and restrict ContactIn.id to a safe pattern (e.g. ^[A-Za-z0-9_-]{1,64}$) so ids cannot contain '/', '?' or '#' that make the row unaddressable by DELETE /api/v1/contacts/{id} and by the wearer.html delete form action.
- *hunt-tenant-data:* server/app/ui.py:317-335 ui_save_contact does not check that tier_name is one of the wearer's tiers (the API does at wearers.py:313) nor that the wearer exists; a contact in a non-existent tier is never snapshotted by snapshot_tiers (main.py:112-115) yet wearer_degraded (wearers.py:378-384) still reports the wearer as covered, so alarms for that wearer reach nobody while the dashboard shows no DEGRADED badge. Apply the same tier check and a get_wearer 404 in the UI route, and make wearer_degraded count only contacts whose tier exists.
- *hunt-tenant-data:* server/app/main.py:372-379 GET /api/v1/status (wearer branch) returns the wearer's event rows including operator usernames and the free-text resolve reason typed by operators (ui.py:294-296, 273-274, 416-418). The companion does not read recent_events today; if operator notes are meant for operators only, strip 'operator' and 'reason' from the wearer-facing projection or keep operator commentary in NULL-wearer audit rows.
- *hunt-tenant-data:* server/app/store.py:359-360 recent_events splats the stored data dict after the row keys, so a future add_event call that passes a request-derived dict could overwrite t/kind/wearer_id in the returned record. Put the fixed keys last ({**json.loads(r['data']), 't': ..., 'kind': ..., 'wearer_id': ...}) or nest data under its own key.
- *hunt-tenant-data:* server/app/wearers.py:236-242 and ui.py:364-372 revoke-tokens leaves previously issued, unused enrol codes exchangeable until their TTL (store.py:201-217 checks only used_t, expires_t and w.enabled). Burn the wearer's outstanding codes in the same transaction when tokens are revoked.
- *hunt-tenant-data:* server/app/main.py:349-354 GET /api/v1/health exposes to anonymous clients an aggregate count of wearers with no deliverable contact; consider returning it only to authenticated callers or dropping it from the public health body.
- *hunt-tenant-data:* server/app/ui.py:451-466 and 364-383 (disable/enable, revoke, drill) operate on an unchecked wid and write events under it, so audit rows can be attached to non-existent wearer ids and later surface in a wearer created with that id; add the get_wearer 404 check used by ui_request_diagnostics.
- *hunt-phone-api:* server/app/main.py:259 — `AlertKind(a.kind)` raises ValueError (HTTP 500) for an unknown kind; validate `kind` with a pydantic Literal and restrict the phone to watch_alarm|fault|test (the comment at line 168), since `phone_silent` is currently accepted and lets a phone pre-empt the dead-man's own PHONE_SILENT escalation via _has_open (main.py:455-458).
- *hunt-phone-api:* server/app/main.py:167,286 — `detector` and `resolution` are unbounded strings persisted into state_json/events and rendered in messages and the dashboard; cap them (e.g. 64-128 chars) at the model; `phone_model` is capped only in the event, not in the INFO log at line 229.
- *hunt-phone-api:* server/app/wearers.py:135-140 — _rate_limited appends a timestamp even after the limit is exceeded and never removes idle keys, so each rejected request re-filters an ever-growing list (O(n) per request). Return early when `len(lst) >= ENROLL_RATE` before appending, and drop keys whose lists become empty.
- *hunt-phone-api:* server/app/main.py:155-156,286,373 — the wearer token is still accepted in the JSON body and as a `?token=` query parameter (legacy); the companion sends only the Authorization header (ServerClient.kt:17). Retire the query/body forms so a token can never land in uvicorn/proxy access logs.
- *hunt-phone-api:* server/app/main.py:157-159,198-203 — battery and age ints are unbounded; a value above 2^63 raises sqlite3 OverflowError after the monitor was already updated (partial state change, 500 to the wearer). Bound with Field(ge=-1, le=100) / sane ranges.
- *hunt-phone-api:* server/app/wearers.py:229-233, store.py:195-199 — issuing a new enrol code does not invalidate outstanding ones, and revoke-tokens/disable do not burn pending codes (a disabled-then-re-enabled wearer's old codes remain valid for the TTL); burn pending codes on revoke and on issue.
- *hunt-phone-api:* server/app/main.py:82 — resolved escalations stay in the process-wide dict until restart, and alarm/status/_has_open/ui.wearer_states iterate the whole dict per request; delete resolved entries after persisting (they remain in the DB).
- *hunt-dashboard-auth:* server/app/wearers.py:85-90 (fix for server/app/wearers.py:require_admin:cookie-path-no-csrf): when require_admin authenticates from the cm_session cookie, call operators.verify_csrf for non-GET requests or refuse cookie authentication for mutating admin API routes; also consider `__Host-cm_session` so a same-site sibling origin cannot plant a session cookie (fixation to an attacker-held operator account).
- *hunt-dashboard-auth:* server/app/ui.py:141-170: /ui/login has no pre-session anti-CSRF token or Origin check and /ui/logout without a session still clears the cookie, so a cross-site top-level form POST can force a logout or log the victim into an account whose password the attacker knows (SameSite is irrelevant because neither request needs the ambient cookie). Needs attacker operator credentials; add a double-submit cookie or Origin==Host check to login and make logout a no-op without a session.
- *hunt-dashboard-auth:* server/app/wearers.py:54 and server/app/operators.py:151: secrets.compare_digest on str raises TypeError for non-ASCII input; an Authorization header or ?token= value with bytes >= 0x80 (headers are latin-1 decoded) produces an unhandled 500 pre-authentication on every token-bearing route whenever CM_ADMIN_TOKEN is set, and an X-CSRF header with such bytes does the same for an operator. Compare bytes (supplied.encode() vs secret.encode()) instead.
- *hunt-dashboard-auth:* server/app/operators.py:63-76 and server/app/store.py:394-397: admin_accounts_exist counts only enabled admins, so the CM_ADMIN_TOKEN retirement and the once-only bootstrap both reopen if every admin is disabled or demoted (reachable by two admins acting concurrently: the last-admin guards at ui.py:508 and 535 are non-atomic check-then-act and only protect the actor). In that state a still-set CM_UI_ADMIN_USER that already exists makes create_operator raise IntegrityError inside the startup hook and the service fails to start. Use INSERT OR IGNORE / get_operator before insert, run the demote/disable guard inside one transaction and apply it to the target as well, and enforce the 8-character minimum on the env password.
- *hunt-dashboard-auth:* server/app/operators.py:85-89 and server/docker-compose.yml FORWARDED_ALLOW_IPS: behind the DSM proxy with the default 127.0.0.1 trust, request.client.host is the proxy for every caller and five failed logins by anyone lock all operators out for 15 minutes (and all enrolments share one 10/h bucket, peer-owned). Owner check: the client field of login_failed events on /ui/audit should show distinct public addresses, not one private address.
- *hunt-dashboard-auth:* server/app/ui.py:364-396 and 451-466: UI revoke-tokens, drill, latency-drill, disable and enable do not check that the wearer exists (the API siblings at wearers.py:204/215/226 do), so an admin can create events, a queued command or a TEST escalation under a nonexistent wearer id; add the get_wearer check for parity.
- *hunt-dashboard-auth:* server/app/store.py:399-425: sessions rows (expired or revoked) are never pruned; add the same housekeeping record_login_attempt does for login_attempts.
- *hunt-dashboard-auth:* server/app/wearers.py:116-125: legacy_bootstrap recreates wearer 'default' with CM_API_TOKEN on any empty wearer table; once migrated, remove CM_API_TOKEN from .env and the compose passthrough so a lost or re-mounted data volume does not resurrect the retired credential.

## 7. Coverage summary

Ledger: 23 units — covered 8, candidate 9, blocked 0, deferred 6, out of scope 0. Every covered or candidate unit carries its owner's reviewed paths and source checks; no local checks exist in this run.

Deferred and out-of-scope units (for a later `standard` run):

- `deferred` — server/app/diagnostics.py#POST /api/v1/diagnostics/requests/{req_id}/decline and upload request_id fulfilment × ATTACK-CLASSES.md#Access control: quick_profile_final_critic: Unmapped entry point. The decline route (diagnostics.py:119-130) appears in no unit's surface. The phone-API Access control unit lists only main.py routes. The diagnostics units list upload/download/delete/purge and checked request_id/req_id only as regex-safe DB values (check 1, lines 79-80 and 124-125), not their ownership or state rules. Facts to settle: the decline response says whether another id is pending, fulfilled or unknown (diagnostics.py:129-130). An upload may answer a superseded request (store.py:549-557). The admin request queues 'diag_request:<id>:<days>' in the one-slot kv command queue (ui.py:400-415; store.py:453-456), and it is redelivered on every heartbeat (main.py:211-214). Check whether diag_requests ids are global keys and whether every write is scoped by wearer_id.
- `deferred` — server/app/operators.py#hash_password,verify_password,_hash_session and server/app/wearers.py#hash_token,resolve_auth × ATTACK-CLASSES.md#Cryptography and secrets: quick_profile_final_critic: This ordinary attack class has no unit and no exclusion in the ledger or in architecture.md section 8. Crypto facts were only checked in passing inside other classes (ack entropy, session minting, enrol code space). Unowned source points: verify_password takes the scrypt n/r/p and salt from the stored record (operators.py:41-49), and only KeyError/ValueError are caught, so other decode failures propagate. The KDF uses n=16384,r=8,p=1 (operators.py:31), and env bootstrap passwords skip the 8-character minimum (operators.py:67-71). Bearer, session and ack tokens use unsalted sha256 (wearers.py:30-32, operators.py:52-53, main.py:394), which needs a judgement against their entropy. secrets.compare_digest on str raises TypeError for non-ASCII input before authentication (wearers.py:54, operators.py:151). Nobody checked whether CM_ADMIN_TOKEN or CM_API_TOKEN strength is enforced (wearers.py:24-25,118).
- `deferred` — server/app/ui.py#POST /ui/login (anonymous) × RESOURCE-EXHAUSTION-AND-AVAILABILITY.md#Pre-authentication work imbalance: quick_profile_final_critic: Accepts hunter proposals 5 (hunt-phone-api) and 7 (hunt-wildcard) for the login path. Proposal 4 (enrol _enroll_attempts key cardinality) is rejected as a duplicate: the enrol unit selected Pre-authentication work imbalance and Quota-accounting, and its check 'Rate-limit bookkeeping itself is bounded' examined wearers.py:135-140. No unit selected these blocks for /ui/login. The CSRF unit checked only username lockout. The deployment unit checked identity collapse with CM_FORWARDED_ALLOW_IPS unset. Nobody checked the documented NAS setting where it equals the bridge gateway (.env.example:71-74). In that setting a LAN client hitting the published 8080/8081 port directly can spoof X-Forwarded-For and get fresh a:<client> buckets. Unchecked source facts: (a) login_post is async def and calls ops.login synchronously (ui.py:141-146), so scrypt n=16384,r=8 (operators.py:31,44-46) runs on the event loop that also runs _pump_loop and telegram poll_loop (main.py:554-557). Every login for an existing enabled username therefore stalls alert delivery for all wearers. (b) Every failure writes a durable login_failed event holding an attacker-chosen, length-uncapped username (operators.py:99-102; ui.py:145 only strips). Those rows are never pruned, while login_attempts is pruned (store.py:432-433).
- `deferred` — server/app/ui.py#all HTML pages and fragments and server/app/templates/*.html × ATTACK-CLASSES.md#Injection: quick_profile_final_critic: Unjustified exclusion. architecture.md:65 excludes CLIENT-SIDE as 'folded into Injection on templates'. The Obvious-things unit excludes CLIENT-SIDE.md#Core discipline because 'template escaping checked under Injection units'. But the only Injection units are channels.py and the hand-built ack HTML (main.py:322-336), and neither lists ui.py or templates as starting paths. The only template check is one broad wildcard sweep. Contexts no unit owns: operator usernames HTML-escaped but not URL-encoded inside form action paths (operators.html:14,23,29; usernames unvalidated at ui.py:480-497). A name with '/' or '../' is normalised by the browser into a POST, carrying the csrf field, to a different /ui/* route. Others: phone-controlled detector, resolution and phone_model and operator reason rendered on wearer and fleet pages (ui.py:205-208, 559-562); notice/error query text reflected on the operators page (ui.py:471-477); CSRF token in hx-headers (base.html:41) with htmx allowEval false (base.html:7); FileResponse filename (ui.py:438).
- `deferred` — server/app/wearers.py#_NTFY_RE contact and self-notify ntfy topics and server/app/channels.py#NtfyChannel.deliver × PROTOCOLS-RPC-AND-MESSAGING.md#Topic, routing-key, and subscription scope gaps: quick_profile_final_critic: Accepts hunter proposal 2 (hunt-deployment). The proposed class 'WEB-PROTOCOL-AND-AUTH.md#Credential and capability strength (guessable bearer secret)' is not a heading in that file; the matching block is the PROTOCOLS topic/subscription class. No unit has a local check on this. The ack unit concluded the ack token is an unguessable capability (main.py:392), but did not check how private the channel that carries it is. The Injection unit only looked at header separators (channels.py:83-86). This concern exists only as parent_hardening text. Source: contact- or wearer-chosen topics may be 4 characters long (wearers.py:249,258). Each alert is published to that topic with the wearer name, location and ack_url = PUBLIC_URL + /api/v1/ack/<raw token> (main.py:485-488; channels.py:83-87). The compose default is the public https://ntfy.sh (docker-compose.yml:34), and the bundled ntfy is published unauthenticated on 0.0.0.0:8090 (docker-compose.yml:74-78). One ack from any reader sets any_ack and stops tier promotion for the whole escalation (escalation.py:137-155). So anyone who subscribes to the topic can silence an emergency escalation. This needs its own owner.
- `deferred` — wearer-token routes that write durable rows without retention: POST /api/v1/heartbeat (command_delivered per heartbeat), /api/v1/drill-result, /api/v1/offline-window, /api/v1/alarm (detector into state_json), /api/v1/alarm/{esc_id}/resolve (resolution) × RESOURCE-EXHAUSTION-AND-AVAILABILITY.md#Unbounded buffering and cardinality: quick_profile_final_critic: Accepts hunter proposal 3 (hunt-files-availability). The phone-API Business logic unit selected this block but checked only open-escalation count. Its last check defers size to 'the cardinality candidate', which is about escalations, not row or byte growth. Source: heartbeat writes a command_delivered event on every heartbeat while a command is pending (main.py:209-214). drill-result writes an event per call (main.py:223-226), and offline-window does too (main.py:243-244). detector and resolution are uncapped str fields (main.py:167; resolve at main.py:285-301) persisted in state_json and events. add_event inserts with no pruning (store.py:341-345). Only heartbeat_trail (store.py:448-449) and login_attempts (store.py:432-433) are pruned. The SQLite file shares ./data with diagnostics (docker-compose.yml:68). The diagnostics quota unit already notes that a write failure on that volume blocks delivery for every wearer (main.py:389-396). One unit should judge per-row cost, the missing bound and cross-wearer impact together with diagnostics.py:upload:no-per-wearer-quota.

The final critic's result is recorded verbatim in `critic-result.json`. A quick profile is a pre-declared early stop, not evidence of complete coverage; the Android companion, the watchapp and the CI workflows were out of scope entirely.
