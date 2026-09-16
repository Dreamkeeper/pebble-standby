# Standby (formerly Pebble Cryonics Monitor) — Product & Architecture Plan

## Context

A cryonicist's worst-case scenario is deanimation (cardiac arrest) or incapacitation (fall, vehicle crash) with nobody noticing for hours. The goal is a Pebble watchapp + Android companion app that detects these events via heart rate sensor + accelerometer and alerts an escalation chain: relatives, the cryonics organization (CSO standby team), and emergency services.

Target hardware: Pebble Time 2 (primary), Pebble 2 Duo, Pebble Round 2. Uses the Pebble background worker so monitoring continues regardless of the foreground watchface/app.

Design inputs:
- User's comparison spreadsheet of 50+ personal alarm wearables with quality metrics (false positive/negative handling, non-motion alarming, HR alarming, man-down, check-ins, escalation, wearability).
- Apple Watch fall/crash detection and heart-rate alert UX as references.
- cryonicsmonitoring.org product insights.

## Key quality metrics from the spreadsheet (to design against)

- Handling of false positives/negatives: consumer-accessible sensitivity settings, observed FP rate target ≤1/day (Blackline benchmark), pre-alarm countdown with cancel.
- Detection: Man Down (fall), Non-Motion Alarming (absence of micro-movements), Heart Rate Alarming (too slow / absent), optional check-in every X minutes (Blackline: 1–240 min).
- SOS button to manually trigger an alarm.
- The device actively encourages wearing (alerts if not worn / no motion).
- Escalation: alerts to relatives + CSO + emergency services; voice-2-voice via phone.
- 24/7 wearability: sleep-safe (non-motion detection must not wake the wearer — Blackline flaw), shower handling (→ suspension feature).
- Geolocation attached to alarms (via phone GPS).
- Apple Watch criticism from the table: "Alarm signal transmission is too unreliable — got an MMS only several hours later" → alarm delivery needs delivery confirmation + retries + server-side watchdog.
- Battery life is a top wearability metric (Pebble's multi-day battery is the differentiator vs Apple Watch's 0.75 days).

## Research findings (condensed)

### Pebble platform (developer.repebble.com, Core Devices era)
- **Background worker**: 10.5 kB heap; has Accelerometer, HealthService (incl. HR), DataLogging, ConnectionService, Battery, TickTimer, Storage. **No AppMessage, no UI, no vibration** — worker must call `worker_launch_app()` to alert/communicate in real time. Only **one worker system-wide** (other apps can evict ours — must detect). Reboot survival undocumented — needs testing + companion watchdog.
- **HR API**: `HealthMetricHeartRateRawBPM` (unfiltered, best for absence-of-pulse) + filtered BPM (can be 15 min stale). Sample period request 1–600 s (advisory, not guaranteed). HR events subscription costs ~2 kB heap. Must reset period to 0 on exit.
- **Accelerometer**: 10/25/50/100 Hz, batches up to 25 samples, `did_vibrate` flag, ±4 g range (classic; Time 2 IMU may differ). Works in worker.
- **Hardware**: Time 2 (`emery`, color touch, **HR yes**, 256 kB app RAM); Pebble 2 Duo (`flint`, B/W, **no HR**); Round 2 (`gabbro`, round color, **no HR**); Pebble 2 HR (`diorite`, HR yes). → Cardiac detection only on Time 2 / Pebble 2 HR; Duo & Round 2 get motion-only feature set.
- Write watchapp in **C** (Alloy JS doesn't cover flint, worker support undocumented). SDK 4.x lineage, pebble-tool (needs Python ≤3.13), CloudPebble revived, Rebble appstore + sideloading live.
- DataLogging buffers ~640 kB on watch while disconnected (offline audit trail), but batched — alarms go via foreground app AppMessage.
- Companion: **PebbleKitAndroid2** (Kotlin, Apache-2.0, active 2026) with `companionApp` pairing in package.json.

### Apple Watch / Pixel Watch UX references
- **Fall detection**: impact → alert with "I'm OK"/"I fell"/Emergency SOS; motion = implicit cancel; only ~60 s immobility starts the 30 s countdown; escalating volume; auto-call reads lat/long; then texts contacts. 55+ defaults to always-on; "workout only" mode exists (context toggle = the false-positive fix).
- **Crash detection**: shorter fuse (~20–40 s total) because incapacitation is likelier. Roller-coaster/ski false positives = cautionary tale; needs context modes.
- **Pixel Watch Loss of Pulse (FDA-cleared)**: green PPG AC-drop + stillness → silent gates (worn check, motion check, multi-wavelength pulse hunt, ≤20 s) → 15 s check-in (motion/pulse auto-dismisses) → 20 s countdown (**only explicit tap cancels — motion doesn't at final stage**) → auto-call with inaudible TTS. Sensitivity ~67%, specificity 99.965%/day, <0.05 false calls/person-year. Post-event "Share what happened" cause picker.
- Key principles: escalation speed scales with hypothesis severity; passive signals cancel early stages, explicit tap required at final stage; invisible confirmation gates first; escalate haptic→sound→louder (design for bystander); Apple's 4-state outcome model (Confirmed/Dismissed/Rejected/Unresponsive) for labeling.
- Apple heart-rate alerts (low HR 40/45/50 + 10 min inactive) are screening, not arrest detection — no escalation path. **PPG loses signal in low-perfusion, so "no HR reading" ≈ "loose strap"; must fuse with stillness.**

### Cryonics ecosystem
- **cryonicsmonitoring.org**: volunteer group; shipped the **Cryonics Institute Check-In** app; building custom ring (motion primary, 45/90 min day/night thresholds, LoRa base station) and wrist device (raw-PPG absence-of-vitals ML ~90% within 1 min, accel fallback, IR wear-detect gating). Philosophy: detection speed is the product; motion = no-false-negative floor; never depend on vendor cloud; >3 day battery; measure error rates.
- **CI Check-In escalation ladder** (copyable): missed check-in → SMS to user → call to user → alert ≤3 opted-in contacts w/ GPS → repeat after 30 min. **Alcor**: daily EMT call, ~12 h avg detection (too slow), false alarms cause disengagement.
- Ben Best's alarms survey: operator/human confirmation is the false-positive firewall; motion-only fails on moving vehicles ("cardiac arrest on a bumpy train").

### Open-source bases
- **OpenSeizureDetector** (GPL-3.0): Pebble watchapp + Android companion, decade-hardened. Reusable: alarm state machine (OK/WARNING/ALARM/FAULT/MUTE), multi-algorithm voting, watch heartbeat-every-20s pattern, SMS with cancellation window, fault detection (watch/phone low battery, data-rate anomalies), LAN web dashboard, bundled .pbw in APK. Its fall detector ships **disabled by default** ("too many false alarms") — freefall <200 milli-g → impact >800 milli-g within 1500 ms window.
- Permissive alternatives: `faelys/pebble-health-export` (ISC, HR upload), `npenkov/pebslee-pebble` (MIT, battery-optimized accel), PebbleKitAndroid2 (Apache), `pebble-dev/clay` (MIT config UI), `matejdro/microPebble` (GPL, modern Kotlin BLE reference).
- Morpheuz sleep monitor handles the worker-slot-eviction case — reference for that failure mode.

## User decisions (round 1)

1. **Alert channel**: self-hosted backend (VPS/NAS/home server) + phone-direct SMS/Telegram/calls fallback when server unreachable; mutual phone↔server watchdog; graceful handling of legitimate offline (subway/airplane/low battery pre-notification, SMS-to-server on 2G); optional OwnTracks/Dawarich as secondary activity signals.
2. **EMS**: humans first — relatives + CSO get alerts with location; one-tap 911 for the user on the alarm screen; no auto-dial EMS.
3. **Audience**: cryonics community release (Rebble appstore + Play Store/APK, polished onboarding).
4. **Detectors**: all four (HR+impact, non-motion, check-in, not-worn), each optional/user-configurable; learning mode (1–2 weeks) that proposes exclusion rules from observed patterns; explore small LLM (phone or server) for pattern interpretation (e.g., "10 min late from pool ≠ alarm").

## User decisions (round 2)

1. **License/base**: GPL-3.0 open source; port/borrow OpenSeizureDetector's hardened components.
2. **Watch architecture**: user-selectable — background worker mode (default) AND persistent foreground mode; foreground mode merged with YaForecasWatch2 watchface (GPL-3.0, C, rectangular Pebbles — license compatible; its PebbleKit JS weather fetch must move to the Android companion because PebbleKit JS and PebbleKit Android cannot run simultaneously).
3. **Server**: Python FastAPI + Docker; server channels Telegram + email + ntfy; optional Twilio-class plugin; later versions: SIP-style open telephony integration, and a "gateway phone" role (old Android phone sends SMS/calls at server's command) — leaning single app with role modes.
4. **Learning**: deterministic rules engine; learning layer only suggests rules the user approves; LLM (pluggable: Ollama local / Claude API) as advisor only — never in the alarm-triggering path.

---

# FINAL PLAN

## Product design

### Positioning (regulatory guardrail)
Market as a **personal alarm / unresponsiveness detector for cryonicists**, never as "cardiac arrest detection" (a medical-device claim — Pixel needed FDA De Novo). Docs state plainly: PPG loses signal during low perfusion, so the cardiac detector actually detects *absence of pulse signal + stillness*, which is also triggered by a loose strap; the multi-stage confirmation ladder exists for exactly that reason.

### Detectors (all optional, individually configurable)

| Detector | Hardware | Signal | Default gates before any alert |
|---|---|---|---|
| **Pulse-loss** | Time 2, Pebble 2 HR only | Raw HR (`HealthMetricHeartRateRawBPM`) absent/below floor + accel stillness | Worn-check (recent HR/motion history) → boost HR sampling to 1 s, "pulse hunt" ~30 s silently → still nothing → check-in stage |
| **Impact (fall/crash)** | all watches | Freefall <0.2 g → impact >0.8 g within 1.5 s (OSD constants as v1 defaults), or single high-G shock | ~60 s immobility window — any deliberate motion cancels silently (Apple pattern) |
| **Non-motion** | all watches | No micro-movement for N min while worn | N defaults: 30–45 min day / 90 min night (cryonicsmonitoring.org thresholds), user-tunable per schedule |
| **Missed check-in** | all watches | Wearer must press button every X h (Blackline-style interval, or fixed times) | Reminder vibration at T-5 min; grace period 15 min |
| **Not-worn** | all watches | No HR + no motion + not suspended | Nag on watch + phone only ("resume wearing / suspend?") — never escalates to contacts |

### Alert ladder (false-positive firewall — modeled on Pixel Loss-of-Pulse + Apple falls)

1. **Silent gates** (watch, invisible): worn-check, stillness confirmation, pulse hunt. Most false readings die here.
2. **Watch check-in** (15–30 s): vibration + "Are you OK?" screen. Motion or returning pulse auto-dismisses (except impact final stage). Button = "I'm OK".
3. **Watch countdown** (30 s, configurable): escalating vibration; phone simultaneously gets a pre-alarm and starts its own full-screen alarm + loud siren (bystander-audible). **Only explicit button/tap cancels at this stage.**
4. **Phone countdown** (60 s, configurable): full-screen alarm with big CANCEL + one-tap "Call 911" for the wearer. Cancel here retracts everything and sends "false alarm" to server.
5. **Escalation** (server primary, phone fallback): contacts notified in tiers with GPS location + live status link. Unacknowledged → next tier → repeat after 30 min (CI Check-In pattern). Delivery is acknowledged-and-retried, never fire-and-forget (the Apple Watch "MMS hours later" lesson).
6. **Post-event feedback**: after any cancelled alarm, one-tap cause picker on phone ("loose strap / slept on arm / took watch off / real event, resolved / other") — feeds the learning layer.

Escalation speed scales with hypothesis severity: impact ladder runs faster (crash victims are incapacitated instantly), non-motion slower, check-in slowest.

### Escalation chain (humans first)
- Tier 1: relatives (Telegram w/ inline ACK buttons, ntfy push, email; phone-direct SMS + auto-call as fallback layer).
- Tier 2: CSO / standby team contact(s), same channels.
- No auto-dial of EMS. Wearer gets one-tap 911 on the alarm screen; contacts are told location + situation so *they* can call EMS.
- Contacts must opt in and confirm reachability during setup (CI Check-In pattern; kills the stale-number failure mode).

### Suspension (watch-off periods)
- Presets 30 min / 1 h / 2 h + custom, from watch menu or phone app.
- Optional auto-resume: if steps and/or pulse are detected before expiry (watch back on wrist), monitoring resumes and the suspension is cancelled.
- At expiry: watch vibrates + phone notifies; if still no wear signal → not-worn nag ladder (phone nag → optional Telegram nag to wearer only), never a contact alarm.
- Suspensions are reported to the server (dashboard shows "suspended until 14:30"); server still expects phone heartbeats during suspension.
- Recurring suspension schedules supported (pool every Tue/Thu 18:00–19:30) — the thing the learning mode auto-proposes.

### Learning mode (deterministic + LLM advisor)
- Weeks 1–2 after install run in **shadow mode**: detectors log would-have-fired events but alert only the wearer (no contact escalation) — builds baselines and trust.
- Server-side pattern miner (deterministic, auditable): weekly schedules of watch-off windows, activity baselines by hour, geofence correlation (via OwnTracks/Dawarich if connected). Produces *suggestions*: "add auto-suspension Tue/Thu 18:00–19:30 (+15 min tolerance)?" — user approves each in dashboard/app.
- Optional LLM plugin (Ollama local or Claude API): phrases suggestions, explains anomalies, drafts weekly digest. Never blocks or fires alarms.

## Architecture

```
┌─ Pebble watch ──────────────┐   ┌─ Android companion (Kotlin) ─────┐   ┌─ Self-hosted server (Python) ──┐
│ Worker mode: bg worker      │   │ Foreground service + wake lock   │   │ FastAPI + SQLite (Docker)      │
│  accel+HR state machines    │BT │ PebbleKitAndroid2 transport      │   │ dead-man logic (phone HB)      │
│  worker_launch_app on event │──▶│ alarm UI, siren, cancel, 911 btn │──▶│ escalation engine + ACK track  │
│ Foreground mode: watchface- │   │ SMS + auto-call fallback layer   │HTTPS│ Telegram bot / SMTP / ntfy   │
│  style status screen (YaFW2)│   │ watch watchdog (heartbeat gap,   │   │ web dashboard + config          │
│  full API, back-btn guard   │   │  worker eviction, batteries)     │   │ pattern miner + LLM plugin     │
│ alarm UI + suspension menu  │   │ config UI (no Clay/JS)           │   │ OwnTracks/Dawarich liveness    │
└─────────────────────────────┘   └──────────────────────────────────┘   └────────────────────────────────┘
```

### Watch side (C, SDK 4.x; platforms emery/diorite/flint/gabbro)
- One app binary with **worker + foreground app**; user picks mode in settings:
  - **Worker mode (default)**: detectors run in the 10.5 kB worker using streaming features (running variance/stillness score, no raw buffering; impact detection via threshold state machine — all fit, unlike seizure FFT). On candidate event → `worker_launch_app()` → foreground app runs the check-in/countdown UI, vibration, and AppMessage to phone. Heartbeat status via DataLogging + periodic status exchange.
  - **Foreground mode ("high assurance")**: app owns the screen 24/7, doubles as a watchface — port YaForecasWatch2 rendering (time, calendar, battery, weather) + monitoring status line; weather data pushed from companion (its PebbleKit JS fetch is replaced). BACK button intercepted to prevent accidental exit (long-press still force-quits — documented).
- Watchapp relaunches its worker on every open; ConnectionService monitors phone link; on BT loss the watch escalates locally louder/longer before giving up.
- Per-detector state machines modeled on OSD's (OK → WARNING → CHECK-IN → COUNTDOWN → ALARM → latched until reset; plus FAULT and MUTE/SUSPENDED states).
- Config lives on the phone; pushed via AppMessage dictionary (OSD's `KEY_SETTINGS` sync pattern).

### Android companion (Kotlin, GPL-3.0)
- Based on OSD Android_Pebble_SD architecture (ported to Kotlin + PebbleKitAndroid2): foreground service with self-reposting notification, pluggable data sources, alarm state machine, voting, fault detection.
- Watch watchdog: expects heartbeat every N s; FAULT alarm (to wearer, then optionally Tier 1) on: no data, worker evicted (Morpheuz-style detection), watch battery low, BT gone, phone battery low.
- Alarm handling: full-screen intent activity, siren via ToneGenerator, cancel window, one-tap 911 dialer, then hands escalation to server; if server unreachable → phone-direct SMS (rate-limited, with maps link once GPS converges) + sequential auto-calls to contacts + Telegram via bot API directly.
- Server link: HTTPS heartbeats every ~5 min (configurable) carrying phone battery, connectivity class, last-watch-data age, suspension state; receives config/ack push (WebSocket or polling).
- Legitimate-offline handling: "offline window" declaration (airplane/subway — like suspension but for connectivity); low-battery pre-notification to server ("expect silence"); 2G-only → SMS heartbeat to gateway/Twilio number (deferred to later version).
- **Gateway role (later version, same APK)**: a role toggle turns the app on a spare Android phone into the server's SMS/call gateway. One app, two roles — shares the SMS/call code the wearer app already needs. Note: SEND_SMS/CALL_PHONE permissions conflict with Play Store policy → distribute that build variant via APK/F-Droid/Obtainium; Play Store build may ship without gateway role.
- Android survival: exemption-from-Doze onboarding flow (battery optimization whitelist, OEM killer guidance à la dontkillmyapp).

### Server (Python 3.12, FastAPI, SQLite→Postgres optional, Docker Compose)
- Single-wearer-first multi-user-capable data model (a family or CSO instance can host several wearers).
- Dead-man logic: missed phone heartbeats → grace ladder (re-ping phone via ntfy/FCM + Telegram to wearer) → check OwnTracks/Dawarich last-update as secondary liveness → escalate "phone went silent" as an *advisory* (distinct severity from confirmed watch alarm) to Tier 1.
- Escalation engine: per-tier channel fan-out, delivery receipts, ACK tracking (Telegram inline buttons / signed ACK links in email/ntfy), automatic retry & tier promotion timers, full audit log.
- Channels: Telegram bot, SMTP, ntfy (self-hostable push). Plugin interface for telephony: Twilio-class providers, SIP (e.g. via a pluggable SIP client library / Asterisk ARI integration — explore, later version), gateway-phone driver (later version).
- Web dashboard: live status (watch/phone/battery/last-seen), event & alarm log, contact management with opt-in confirmation flow, suspension schedules, learning suggestions inbox, test-alarm button ("fire drill" mode that tags messages as TEST).
- Mutual watchdog: phone alerts the wearer if server stops answering; server alerts contacts if phone goes silent.

### Device support matrix
| | Time 2 (emery) | Pebble 2 HR (diorite) | 2 Duo (flint) | Round 2 (gabbro) |
|---|---|---|---|---|
| Pulse-loss | ✅ | ✅ | — | — |
| Impact / non-motion / check-in / not-worn | ✅ | ✅ | ✅ | ✅ |
| Merged watchface (YaFW2, rectangular) | ✅ | ✅ (B/W) | ✅ (B/W) | needs round layout — status-only screen in v1 |

## Repository layout

```
pebble-cryonics-monitor/          (GPL-3.0, monorepo)
├── watchapp/        # C: app + worker, per-platform builds (pebble-tool, Python 3.13)
├── android/         # Kotlin companion (PebbleKitAndroid2), bundles .pbw for sideload-install
├── server/          # FastAPI + Docker Compose; escalation, dashboard, pattern miner, plugins/
├── docs/            # user guide, threat-model/failure-mode analysis, thresholds rationale
└── tools/           # false-alarm log analyzer (port of OSD logAnalyser), battery test scripts
```

## Implementation milestones

- **M0 — Feasibility spikes (do first; each is a go/no-go)**: worker→`worker_launch_app`→vibrate+AppMessage round-trip latency; worker survival across reboot/battery-death; raw HR at 1 s period behavior on real Time 2 (and what "no reading" looks like off-wrist vs still); DataLogging delivery latency as heartbeat channel; battery drain at candidate sampling profiles (accel 25 Hz batched + HR 60 s baseline / 1 s burst); PebbleKitAndroid2 + Core app end-to-end on emulator + real hardware.
- **M1 — Watch + phone core**: worker detectors (impact, non-motion, not-worn; pulse-loss on HR hardware), alarm ladder UI on watch + phone, suspension with presets + auto-resume, phone-direct SMS/call/Telegram escalation, watch↔phone watchdogs. *Usable product with no server.*
- **M2 — Server**: heartbeats, dead-man advisory, escalation engine w/ ACK+retry, Telegram/email/ntfy, dashboard, offline windows, fire-drill mode, contact opt-in flow.
- **M3 — Community release**: onboarding (Doze exemptions, contact confirmation, 2-week shadow/learning mode), pattern miner + suggestions, post-event cause picker, foreground/watchface merged mode, Rebble appstore + APK; docs.
- **M4 — Later versions**: LLM advisor plugin, Twilio/SIP telephony plugins, gateway-phone role, OwnTracks/Dawarich integration, SMS-heartbeat for 2G, Round 2 watchface layout.

## Key risks / open items to verify empirically (from research)
1. Worker cannot vibrate/UI — inferred from docs omission; verify in M0 (changes alarm path if wrong).
2. Worker reboot survival undocumented; single-worker eviction by other apps — companion watchdog is mandatory regardless.
3. HR sample-period request is advisory; PPG "no reading" is ambiguous (arrest vs loose strap) — ladder design assumes this.
4. Battery: continuous sensing vs 30-day claims — measure; use adaptive duty cycling (cheap always-on stillness detector; HR burst only on candidate events).
5. PebbleKitAndroid2 is young (DataLogging support unconfirmed) — fallback: legacy PebbleKit intents or app-level heartbeat via AppMessage from foreground syncs.
6. Play Store policy vs SEND_SMS/CALL_PHONE — plan APK/F-Droid variant.
7. Do not market as medical device / arrest detection.

## Verification
- **Bench**: emulator builds for all 4 platforms; unit tests for detector state machines (C, host-compiled with injected sensor traces); server pytest suite incl. escalation-timer and ACK/retry logic; end-to-end alarm-latency measurement (watch event → contact Telegram message) with target < 2 min.
- **Field**: 2-week on-wrist shadow-mode trial logging all would-be alarms (target: <1 uncancelled false alarm/day pre-tuning, then drive down); simulated events — watch off wrist + still (pulse-loss), controlled fall onto mattress (impact), phone in faraday bag / battery pull (dead-man); battery-life measurement per mode.
- **Fire-drill mode**: monthly TEST alarm through the full chain incl. contact ACKs — verifies the whole system, catches config rot.
