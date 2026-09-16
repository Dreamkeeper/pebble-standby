# Pebble Cryonics Monitor

A 24/7 personal alarm system for cryonicists, built on Pebble smartwatches
(Pebble Time 2, Pebble 2 HR, Pebble 2 Duo, Pebble Round 2), an Android
companion app, and an optional self-hosted server.

It detects **unresponsiveness** — loss of pulse signal combined with
stillness, hard impacts (falls, vehicle crashes) followed by immobility,
prolonged absence of micro-movement, or a missed check-in — and escalates
through a configurable chain: the wearer first (multi-stage confirmations to
kill false positives), then relatives, then the cryonics organization's
standby team. Humans decide whether to call emergency services; the system
never auto-dials them.

> **This is not a medical device.** It does not diagnose cardiac arrest or
> any medical condition. Optical wrist sensors lose signal both when
> perfusion stops *and* when the strap is loose — the multi-stage
> confirmation ladder exists precisely because the two are indistinguishable
> at the sensor. Treat it as a personal alarm, not a monitor of record.

## Components

| Directory | What it is |
|---|---|
| [`watchapp/`](watchapp/) | Pebble app (C, SDK 4.x): background-worker monitoring (71 ms alarm-path launch, measured), on-watch alert ladder with acknowledged delivery + episode identity, monotonic detector clock (wall-jump immune), suspension menu, carry mode, charging hold, HR-quality gate, sensor-fault nag. Targets `emery`, `diorite`, `flint`, `gabbro`. |
| [`android/`](android/) | Android companion (Kotlin): foreground service with Doze-proof exact-alarm heartbeat, watch watchdog + self-heal, full-screen alarm with cancel window, DataLogging spool recovery (alarms escalate even if the watch died out of range), boot/update recovery, soak-test instrumentation with built-in drills, Telegram/server escalation, enrollment-by-code onboarding. SMS/call fallback is parked until a dedicated gateway flavor (Play Protect flags those permissions). |
| [`server/`](server/) | Self-hosted backend (Python/FastAPI, Docker): phone dead-man monitoring with auto all-clear on recovery, tiered escalation with delivery ACK + retry via Telegram/ntfy/email, idempotent alarm intake, leased command queue, web dashboard. Deployed on a Synology NAS behind HTTPS. |
| [`docs/`](docs/) | Product plan, soak-test protocol, deployment guides, review/optimization records, upstream-PR docs. |
| [`openspec/`](openspec/) | Living specs ([OpenSpec](https://github.com/Fission-AI/openspec)): detector ladder, watch↔phone protocol, escalation/dead-man, suspension. Changes flow through `/opsx:propose` → `/opsx:apply` → `/opsx:archive`. |
| [`tools/`](tools/) | False-alarm log analysis, battery test harnesses. |
| [`dist/`](dist/) | Current sideload artifacts: watchapp `.pbw`, companion `.apk`, and the dual-slot test firmware `.pbz` for Pebble Time 2. |

## Design lineage

- Alarm state machine, watchdog and escalation patterns ported from
  [OpenSeizureDetector](https://github.com/OpenSeizureDetector) (GPL-3.0).
- Alert-ladder UX modeled on Google Pixel Watch Loss of Pulse Detection
  (FDA De Novo) and Apple Watch Fall/Crash Detection.
- Detection thresholds informed by [cryonicsmonitoring.org](https://www.cryonicsmonitoring.org)
  and the Cryonics Institute Check-In escalation ladder.

## Status

**Field-testing (soak) phase** — the full chain (watch → phone → server →
Telegram/ntfy) runs 24/7 on real hardware: Pebble Time 2, Android 16 phone,
self-hosted server. Current builds: watchapp **0.5.6**, companion **0.6.3**,
all in [`dist/`](dist/).

Done since the pre-alpha milestone:

- **Alarm delivery hardening** — every escalation episode carries a minted
  ID end-to-end; the watch retries PRE_ALARM/ALARM/CANCEL with backoff until
  the phone ACKs; the DataLogging spool doubles as an authoritative alarm
  recovery path with episode dedup; detectors run on a monotonic clock so
  time-zone flips and NTP jumps cannot fire or kill a countdown.
- **False-alarm classes closed** — sensor-fault vs not-worn discrimination,
  per-sample HR-quality gating (validated against a 450+-sample sensor lab),
  carry mode, wall-clock-safe suspensions, stale-action expiry.
- **Survivability** — phone reboot/app-update recovery, Doze-proof
  heartbeats, provisioning watchdog with self-heal, worker heap telemetry
  (the 10.5 KB worker RAM budget is actively managed; verbose logging
  compiles out), soak counters + reboot/outage drills built into the app.
- **Server hardening** — dead-man race fixes, idempotent alarm intake,
  leased commands (survive lost responses), auto-resolve + all-clear when a
  silent phone recovers, per-wearer event isolation.
- **Upstream PRs** — [coredevices/PebbleOS#1960](https://github.com/coredevices/PebbleOS/pull/1960)
  (off-wrist HR invalidation) and
  [coredevices/mobileapp#386](https://github.com/coredevices/mobileapp/pull/386)
  (third-party DataLogging forwarding). Until merged, [`dist/`](dist/)
  carries a patched dual-slot PebbleOS build exposing the raw HR-quality
  metric, and the companion is used with a patched Core app build.

See [docs/SOAK-TEST.md](docs/SOAK-TEST.md) for the ongoing soak protocol and
[docs/PLAN.md](docs/PLAN.md) for the roadmap (onboarding, distribution, and
the SMS/call gateway flavor are next after the soak).

## Building

**Watchapp** (pebble-tool 5.x + SDK 4.9.169+, Linux/WSL):

```bash
cd watchapp && pebble build     # -> build/watchapp.pbw (emery/diorite/flint/gabbro)
```

Install: sideload `build/watchapp.pbw` via the Pebble/Core mobile app or
Rebble Sideload Helper.

**Android companion** (JDK 17, Android SDK platform 36, Gradle 8.11+, AGP 8.9+):

```bash
cd android && gradle assembleSideloadRelease
# -> app/build/outputs/apk/sideload/release/app-sideload-release.apk
```

**Server** (Docker; see [docs/DEPLOY-SYNOLOGY.md](docs/DEPLOY-SYNOLOGY.md)
for Synology Container Manager and
[docs/SERVER-DEPLOYMENT.md](docs/SERVER-DEPLOYMENT.md) for the update
procedure):

```bash
cd server && cp .env.example .env   # set CM_API_TOKEN etc.
docker compose up -d --build        # API on :8080, ntfy on :8090
```

Tests: `watchapp/tests` (host C, gcc/MSVC — 216 checks), `server/tests`
(pytest — 73 checks), and `android` JVM unit tests (`gradle test`).

## License

GPL-3.0 — see [LICENSE](LICENSE).
