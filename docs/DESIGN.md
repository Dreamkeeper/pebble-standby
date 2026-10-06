# Standby — design pass (2026-10-06)

The brief for the UI work that follows: the Android companion rebuilt in
Jetpack Compose + Material 3 first, the web dashboard restyled with
Basecoat second. This document fixes *what* each screen is for, who looks
at it in what situation, which words and colours mean what everywhere,
and what "good" looks like, so the two implementation changes do not have
to invent any of it.

Scope: presentation, wording, navigation, onboarding. The alert ladder,
the protocols and the server API do not change.

---

## 1. The one job, and the chains that deliver it

Core Job (product-requirements spec): **"have the people I choose know
within minutes if I become unresponsive."** Success criteria, in the
wearer's priority order: (1) it actually fires when needed, (2) it does
not fire when not needed, (3) I never have to think about it, (4) my
people can act on what they get.

Criterion 3 is the design brief. The product's value is *nothing to do*.
Every screen the wearer sees costs attention the brain did not want to
spend, so each must answer one question in one glance and then get out of
the way: **"Am I covered right now, and if not, what is the one thing to
do?"**

The chains a UI has to carry end to end (a broken step anywhere means the
Core Job never lands):

| Chain | Steps | Where it breaks today |
|---|---|---|
| **Setup** (once) | install app → Pebble paired → watchapp on the watch → enrol with a code → at least one contact → permissions (battery, lock screen) → fire drill → "Covered" | Spread over three screens plus a notification; order not enforced; HyperOS permissions discovered by failure; the drill is on the Contacts screen |
| **Daily** (every day) | wear → glance → charge → take off → wear | Main screen mixes status with token fields and a "Debug & feasibility tests" button |
| **Event** (rare, the whole point) | watch "Are you OK?" → countdown → phone alarm → cancel or let it escalate → say what happened | Works; wording uses detector names; colours hard-coded; no way to see afterwards what happened |
| **Responder** | Telegram message → understand → press Acknowledge → act (call, go, call EMS) | Message text is server-side; dashboard is admin-flavoured |
| **Support** | something is off → find out → send diagnostics | Debug screen is developer-facing; S1–S7 drills exposed to every wearer |

Design principle for all three surfaces, from the attention page of the
canon: audit every screen for attention spends the wearer did not ask
for. Configuration asked four questions where one default would do;
developer instrumentation in the wearer's path; status expressed as data
instead of a verdict.

---

## 2. Who is looking, when

| Person | Situation | What they need from the screen |
|---|---|---|
| **Wearer, settled** | Glancing at phone or watch during the day | "Covered." Nothing else. Watch: green and the time. |
| **Wearer, 3 a.m.** | Woken by the watch buzzing "Are you OK?", half asleep, one hand | One obvious button, huge, readable without glasses, no choice to make |
| **Wearer, false alarm on phone** | Full-screen alarm after a desk knock; contacts are 20 s from being told | CANCEL that cannot be missed or mis-hit; what is happening; then one tap to say why |
| **Wearer, taking the watch off** | Shower, charger, airport | Confidence that monitoring paused on its own and will resume; no nag storm |
| **Wearer, first hour** | Just installed, cryonicist with a standby contract, moderately technical | A guided path to "Covered" in under ten minutes; told plainly what the system will and will not do (no automatic emergency call) |
| **Contact / responder** | Telegram at night, on a phone | Who, what, where, since when, what to do; one tap to acknowledge |
| **Operator / admin** | Desk, laptop, supporting a tester | Worst thing first across all wearers; one wearer's whole picture on one page |
| **Owner / developer** | Investigating a false nag | Counters, drills, logs, bundles. Needed, but never in the wearer's path |

---

## 3. One vocabulary, one palette

The same word and colour must mean the same thing on the watch, the
phone, in notifications, in Telegram texts and on the dashboard.

### 3.1 States of coverage

| State | Meaning | Watch | Phone / web colour role | Word |
|---|---|---|---|---|
| **Covered** | Monitoring, watch linked, contacts exist, server fine | green background, time, "Monitoring" | `primary` container (green-tinted in the brand theme) | "Covered" |
| **Paused** | Suspended, carry mode, on charger | neutral background, "Suspended 30 min" / "Charging" | `tertiary` container (calm blue) | "Paused · charging" / "Paused · 23 min left" |
| **Needs attention** | Not worn, no pulse signal while moving, watch link lost, server unreachable, no contacts | neutral, hint text | custom `caution` token (amber), never red | "Needs attention" + one line + one action |
| **Check-in** | "Are you OK?" stage | amber | `caution` | "Are you OK?" |
| **Countdown / Alarm** | Countdown running or alarm latched | red | `error` | "Alarm in 20 s" / "Alarm" |
| **Off** | Monitoring disabled or not set up | grey | `surfaceVariant` | "Not set up" |

Material 3 has no warning role; the theme adds one token pair
(`caution` / `onCaution`, amber on dark, deep amber on light) and uses
`error` only for countdown and alarm, so red keeps its meaning. The
dashboard's `.st-*` classes map onto the same six states; the extra
server states (phone silent, degraded) are "Needs attention" variants
with their own line of text, not their own colours.

### 3.2 Names of things

Wearer-facing text never uses detector identifiers or engineering words.

| Internal | Wearer sees |
|---|---|
| pulse | "No pulse signal" (never "no pulse" or "cardiac") |
| nonmotion | "No movement" |
| impact | "Hard impact" |
| checkin | "Missed check-in" |
| notworn | "Watch not worn?" |
| sos | "SOS" |
| PRE_ALARM / COUNTDOWN | "Alarm in N s" |
| ALARM | "Alarm" |
| worker, DataLogging, S5, heap, flush | nothing — Diagnostics only |
| escalation | "alert" (verb: "your contacts are being alerted") |
| degraded | "Alerts reach nobody" |
| server | "your server" (it is theirs) |

Positioning guardrail (product-requirements): no text claims to detect
cardiac arrest or anything medical. "Pulse *signal*" everywhere, and the
loose-strap ambiguity appears wherever detection quality is described.

### 3.3 Type and layout

Material 3 type scale as already adopted; the Compose rebuild keeps the
4 dp grid and 16/24 dp rhythm. Two exceptions set deliberately:

- **Alarm numbers** are display-size (57 sp or larger), and the cancel
  button is at least 72 dp tall and full width. A 3 a.m. screen is read
  from arm's length without glasses.
- **Status verdict** on Home is headline-size; everything under it is
  body text. The verdict is the screen.

Dark theme follows the system; the alarm screen is always high-contrast
regardless of theme. Font scaling to 200 % must not clip the verdict or
the cancel button. Touch targets 48 dp minimum, 56 dp for anything a
wearer uses in an emergency.

### 3.4 Motion and tone

Calm by default: state changes fade, nothing bounces. The alarm screen is
the only place with continuous motion (the countdown). Copy is plain,
second person, one sentence per thing, and always says what to do next.
No exclamation marks except "Alarm". No emoji in wearer-facing text; the
dashboard drops the 🔔 / ⏱ button prefixes too.

---

## 4. Android companion — information architecture

Four destinations plus two full-screen flows. Navigation: a bottom bar is
wrong for a product you should not need to open; Home carries everything
day-to-day, and the rest is one tap away through Home's cards and a
settings icon.

```
Home ──────────── Coverage verdict card, watch card, people card, server card
 ├─ Contacts & safety net
 ├─ Settings (state on every row, see §4.5)
 │    ├─ What Standby watches for: one row per detector, switch + value → detail page
 │    ├─ When it asks: time to answer, countdowns, night hours
 │    ├─ Who is alerted: contacts, emergency number
 │    ├─ Your server (enrol, change, re-enrol)
 │    ├─ This phone: Let it run (n of 6 granted), Pebble app mode
 │    └─ More: Advanced (fallback bot, manual server), Diagnostics, Set up again
 └─ (full-screen) Alarm        ← launched by the service, over the lock screen
(first run) Onboarding → ends on Home showing "Covered"
```

### 4.1 Home

One screen, four cards, worst first:

1. **Verdict card** — the state from §3.1 as a headline, one line of
   reason, one action button when the state is not Covered
   ("Add a contact", "Open Bluetooth", "Re-enrol", "Resume monitoring").
   Covered shows nothing but the headline and "since 07:12".
2. **Watch card** — linked / not; battery with age ("82 %, 4 min ago");
   last worker data; paused state. Tapping opens Settings → Pebble app.
3. **People card** — "3 contacts in 2 tiers · last drill 12 days ago",
   with "Fire drill" as the secondary action. Tapping opens Contacts.
4. **Server card** — "your server: cm.example.org · reachable" or the
   plain-language fault. Tapping opens Settings → Server.

Nothing on Home is a text field. The current manual-configuration block
(server URL, API token, Telegram token, chat ids, SMS numbers) moves to
Settings → Advanced, where it is labelled as the fallback it is.

### 4.2 Alarm (pre-alarm and alarm)

Full screen over the lock screen, screen on, as today. Layout top to
bottom: state word ("Alarm in 18 s" / "Alarm"), cause in wearer words
("Hard impact, then no movement"), the countdown as the dominant number,
**I'M OK — CANCEL** (72 dp, full width, `error` container), then a
smaller "Call 112" (the configured number). Below the fold: "Your
contacts are being alerted. Cancelling tells them it was a false alarm."

After cancel, the same "what happened?" sheet as today (loose strap,
slept on my arm, took the watch off, real but fine, skip), then one line
of confirmation and the screen closes itself. The verdict card on Home
shows "Last alert cancelled 07:31 — loose strap" for a day.

### 4.3 Onboarding (first run, and re-runnable from Settings)

Six steps, each one screen, progress shown, each skippable only where
skipping does not break the chain:

1. **What this is.** The one-liner and the two guardrails: no automatic
   emergency call; loss of pulse *signal* also happens with a loose
   strap, which is why the watch asks first.
2. **The watch.** Detects the Pebble app and whether the watchapp
   reports; offers the sideload if not. Explains store-app mode in one
   sentence when it applies.
3. **Your server.** Enrol with a code (default) or "I don't have a
   server" → explains what is lost (acknowledgements, tiers, dead-man)
   and offers the fallback bot setup.
4. **Your people.** Add the first contact; tiers explained with one
   diagram line ("Tier 1 is told first; Tier 2 if nobody acknowledges
   in 10 min").
5. **Let it run.** Permissions with live status: battery exemption, show
   over lock screen, pop-ups in background (HyperOS), start after
   reboot. Each row opens the exact system page and comes back green.
   Rows Android cannot answer are never "check manually": a Test button
   performs the real action (the service launches a neutral test screen
   from the background, or over the lock screen, the way an alarm would)
   and the row then states what happened and when. "Start after reboot"
   reads the evidence of the last restart; Check arms the next one.
6. **Prove it.** Fire drill; the screen waits for the contact's
   acknowledgement and shows it arriving. End state: Home, "Covered".

Target: a technical cryonicist reaches "Covered" in under ten minutes
without reading the README.

### 4.4 Contacts & safety net

Keep the current model (tiers, channels, copies to yourself, fire drill).
Changes: tier explained in place; channel icons instead of
"telegram/ntfy/email" words; "Where do I get a chat id?" inline help
with the bot's reply quoted; delete asks once, as now.

### 4.5 Settings: state on the row, features first

Reference pattern (Pixel Watch app → Safety & emergency; Apple Watch →
Emergency SOS / Fall Detection; Garmin Connect → Safety & Tracking): the
top level lists what the product *watches for*, every row carries its
current value as the second line, and binary things are switches on the
row itself. A row never describes itself; it states its state.

```
What Standby watches for
  Pulse signal loss       On · asks after 2½ min without signal      [switch]
  Hard impact             On · asks after 60 s of stillness          [switch]
  No movement             On · 40 min by day, 90 min at night        [switch]
  Scheduled check-in      Off                                        [switch]
  SOS on the watch        Always on
When it asks
  Time to answer          30 s          Countdown before the alarm   30 s (impact 20 s)
  Night hours             23:00 – 07:00
Who is alerted
  Contacts & safety net   Anna, Boris · 2 tiers · drill 12 days ago
  Emergency number        112 · default for Russia
Your server               cm.example.org · reachable
This phone
  Let it run              4 of 6 granted · 1 to grant · 1 not tested
  Pebble app              Automatic · patched app, reports every minute
More
  Advanced · Diagnostics · Set up again · Standby 0.7.0
```

Each detector row opens a page with the big switch at the top, a plain
explanation that includes what it cannot detect (the loose-strap
ambiguity for pulse signal), then its thresholds as pickers with the
watch's defaults marked. A changed value shows "pending" until the watch
confirms it (change watch-settings-sync). Diagnostics is the current
debug screen moved under More, with the soak card first, drills under
"Tests" and logs/bundles under "Share with support"; drills and the lab
unlock with a long-press on the version line. Its labels may keep
engineering words; it is the one place for them.

### 4.6 Notifications

The service's ongoing notification is the Home verdict in one line
("Covered · watch 82 % · your server reachable"). Faults use the same
plain words as the verdict card and always end with the action. Channel
names seen in Android settings: "Status", "Needs attention", "Alarms",
"Requests from your server". Drill and soak chatter stays out of
notifications and in Diagnostics.

---

## 5. Watch

No layout change in this pass; two alignments. Words follow §3.2 exactly
("Alarm in 18 s" during the countdown instead of a bare number where
space allows; "Watch not worn?"). Colours follow §3.1 (already green /
amber / red; "Paused" neutral). The hint line gains the suspension
remaining time where it does not already show it.

---

## 6. Web dashboard (for the Basecoat change)

Same vocabulary, same six states, same worst-first fleet. Three
structural changes queued for that change: a responder view that is the
Telegram message's big brother (who / what / where / since when /
acknowledge) rather than an admin page; the wearer page split into
"Now" (live status, active alerts, contacts) and "Manage" (enrolment,
tokens, drills, diagnostics, disable); request/ack/resolve buttons as
proper primary/secondary/destructive variants.

---

## 7. Accessibility and the night test

- **Night test:** phone face down on a nightstand; the alarm turns the
  screen on at full brightness, the cancel button is findable by
  position alone (bottom third, full width), and the result is
  confirmed by a distinct vibration, not only by colour.
- Colour never carries meaning alone: every state has its word.
- TalkBack: every control labelled; the verdict announced on Home open.
- Font scaling 200 %: Home verdict, alarm number and cancel label stay
  whole; secondary text may truncate.
- One-handed: primary actions in the lower half on phones; no
  destructive action at a screen edge.
- Reduced motion honoured; the countdown still updates once a second.

---

## 8. Not changing

The ladder (check-in → countdown → alarm), timings, detector logic, the
watch↔phone protocol, server API, the dashboard's authentication, and
the diagnostics data. Package name and identifiers stay.

---

## 9. Acceptance checks for the Compose change

- **Glance test:** a stranger told "this app watches over someone" says
  whether that person is covered within five seconds of seeing Home.
- **First-run test:** fresh install to "Covered" in under ten minutes,
  including a fire drill acknowledged by a contact, without the README.
- **Jargon test:** no wearer-facing string contains worker, DataLogging,
  S1–S7, heap, flush, escalation, degraded, PRE_ALARM or a detector id.
- **Night test** as in §7, on the owner's phone, lights off.
- **Theme test:** light, dark, dynamic colour on; the six states remain
  distinguishable and the alarm screen is unchanged by theme.
- **Scale test:** 200 % font, 320 dp-wide screen: nothing clipped on
  Home or Alarm.
- **Parity test:** every setting and drill reachable today is reachable
  after, in Settings or Diagnostics.

---

## 10. Decisions for the owner (all five accepted 2026-10-06)

1. **Emergency number default.** Today it is a manual field. Proposal:
   default from the SIM country (112 / 911 / 999 / 000) with an override
   in Advanced.
2. **SMS fallback fields.** Parked in the code (Play Protect); proposal:
   hide them until the gateway flavour exists rather than show fields
   that do nothing.
3. **Diagnostics visibility.** Proposal: always present under Settings,
   but beta testers see only the soak card, Share and Send; drills and
   the sensor lab appear after a long-press on the version line.
4. **Onboarding re-entry.** Proposal: "Set up again" in Settings re-runs
   steps 2–6 without touching enrolment unless asked.
5. **Naming of Paused.** "Paused" versus "Suspended" (the code's word).
   This document uses Paused for wearers and keeps Suspended in specs
   and logs.
