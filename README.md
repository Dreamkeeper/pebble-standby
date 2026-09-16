# Standby

**An unresponsiveness alarm for Pebble.** If your pulse signal
disappears while you are still, if the watch registers a hard impact
followed by immobility, if you stop moving for too long, or if you miss
a check-in, the watch asks "Are you OK?", counts down, and only then
the phone alerts the people you chose — relatives first, then, if you
want, an organisation on standby. Humans decide what happens next; the
system never auto-dials emergency services.

Built first for **cryonicists**, whose standby team must be reached
within minutes of unresponsiveness. Useful to anyone whose plan is
"call my people, not an ambulance". Formerly *Pebble Cryonics
Monitor*; the old GitHub URL redirects.

Runs on Pebble Time 2, Pebble 2 HR, Pebble 2 Duo and Pebble Round 2,
an Android companion app, and an optional self-hosted server.

> **This is not a medical device.** It does not diagnose cardiac arrest or
> any medical condition. Optical wrist sensors lose signal both when
> perfusion stops *and* when the strap is loose — the multi-stage
> confirmation ladder exists precisely because the two are indistinguishable
> at the sensor. Treat it as a personal alarm, not a monitor of record.

## Components

| Directory | What it is |
|---|---|
| [`watchapp/`](watchapp/) | Pebble app (C, SDK 4.x): background-worker monitoring (71 ms alarm-path launch, measured), on-watch alert ladder with acknowledged delivery + episode identity, monotonic detector clock (wall-jump immune), sustained-motion and own-vibration guards, suspension menu, carry mode, charging hold, HR-quality gate, not-worn and sensor-fault nags. Targets `emery`, `diorite`, `flint`, `gabbro`. |
| [`android/`](android/) | Android companion (Kotlin): foreground service with Doze-proof exact-alarm heartbeat, watch watchdog + self-heal, full-screen alarm with cancel window, DataLogging spool recovery (alarms escalate even if the watch died out of range), store-app mode for the stock Pebble app, boot/update recovery, soak-test instrumentation with built-in drills, Telegram/server escalation, enrollment-by-code onboarding. SMS/call fallback is parked until a dedicated gateway flavor (Play Protect flags those permissions). |
| [`server/`](server/) | Self-hosted backend (Python/FastAPI, Docker): phone dead-man monitoring with auto all-clear on recovery, tiered escalation with delivery ACK + retry via Telegram/ntfy/email, idempotent alarm intake, leased command queue, web dashboard. Deployed on a Synology NAS behind HTTPS. |
| [`docs/`](docs/) | Product plan, soak-test protocol, deployment guides, review/optimization records, upstream-PR docs, outreach drafts. |
| [`openspec/`](openspec/) | Living specs ([OpenSpec](https://github.com/Fission-AI/openspec)): product requirements, detector ladder, watch↔phone protocol, escalation/dead-man, suspension, companion resilience. Changes flow through `/opsx:propose` → `/opsx:apply` → `/opsx:archive`. |
| [`tools/`](tools/) | Worker-log timeline for post-mortems, false-alarm log analysis, battery test harnesses. |
| [`dist/`](dist/) | Current sideload artifacts: watchapp `.pbw`, companion `.apk`, and the dual-slot test firmware `.pbz` for Pebble Time 2. |

## Design lineage

- Alarm state machine, watchdog and escalation patterns ported from
  [OpenSeizureDetector](https://github.com/OpenSeizureDetector) (GPL-3.0).
- Alert-ladder UX modeled on Google Pixel Watch Loss of Pulse Detection
  (FDA De Novo) and Apple Watch Fall/Crash Detection.
- Detection thresholds informed by [cryonicsmonitoring.org](https://www.cryonicsmonitoring.org)
  and the Cryonics Institute Check-In escalation ladder.

## Status

**Beta preparation** — the full chain (watch → phone → server →
Telegram/ntfy) has run 24/7 on real hardware since August 2026: Pebble
Time 2, Android 16 phone, self-hosted server. A seven-day soak passed
every stability gate (10,032 worker records, zero false alarms, zero
heartbeat gaps). Current builds: watchapp **0.5.7**, companion
**0.6.4**, all in [`dist/`](dist/).

What the field testing taught, each with a fix and a test:

- **Alarm delivery hardening** — every escalation episode carries a
  minted ID end-to-end; the watch retries PRE_ALARM/ALARM/CANCEL with
  backoff until the phone ACKs; the DataLogging spool doubles as an
  authoritative alarm recovery path; detectors run on a monotonic clock.
- **False-alarm classes closed** — a sleeping wearer's steady pulse is
  not "not worn" (the 1 Hz hunt arbitrates first); a desk bump or a bed
  partner turning is not "I'm fine" (only sustained motion dismisses);
  the alert's own vibration is never motion; a set-down shock is not a
  fall; sensor-fault vs not-worn discrimination; per-sample HR-quality
  gating validated against a 450-sample sensor lab; carry mode.
- **Survivability** — phone reboot/app-update recovery, Doze-proof
  heartbeats, provisioning watchdog with self-heal, worker heap
  telemetry within the 10.5 KB worker budget, soak counters and drills
  in the app, store-app mode when the stock Pebble app forwards no
  worker telemetry.
- **Server hardening** — dead-man race fixes, idempotent alarm intake,
  leased commands, auto-resolve + all-clear when a silent phone
  recovers, per-wearer event isolation.
- **Upstream PRs** — [coredevices/PebbleOS#1960](https://github.com/coredevices/PebbleOS/pull/1960)
  (off-wrist HR invalidation) and
  [coredevices/mobileapp#386](https://github.com/coredevices/mobileapp/pull/386)
  (third-party DataLogging forwarding). Until merged, [`dist/`](dist/)
  carries a patched dual-slot PebbleOS build exposing the raw HR-quality
  metric; on the stock Pebble app the companion runs in store-app mode.

See [docs/SOAK-TEST.md](docs/SOAK-TEST.md) for the soak protocol,
[docs/PLAN.md](docs/PLAN.md) for the roadmap, and
[docs/OUTREACH-DRAFTS.md](docs/OUTREACH-DRAFTS.md) for the beta plan.

## Building

**Watchapp** (pebble-tool 5.x + SDK 4.9.169+, Linux/WSL):

```bash
cd watchapp && pebble build     # -> build/watchapp.pbw (emery/diorite/flint/gabbro)
```

Install: sideload the `.pbw` via the Pebble/Core mobile app or
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
(pytest — 73 checks), and `android` JVM unit tests (`gradle test`, 22).

Internal identifiers (Android package `org.cryomonitor.companion`,
server module and container names, the watchapp UUID) deliberately keep
their original names so existing installs and enrollments carry over.

## License

GPL-3.0 — see [LICENSE](LICENSE).
