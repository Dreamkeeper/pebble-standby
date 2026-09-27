# 7-day soak-test protocol

Goal: evidence that the stack behaves unattended — self-recovers from
the failures that will actually happen (reboots, BLE outages, server
blips) and, above all, **does not cry wolf**. False alarms are the
killer metric: responders who get woken for nothing stop responding.

Companion ≥ v0.5.0 collects the evidence automatically (Debug → Soak &
recovery). The wearer's cost is ~2 minutes a day.

## One-time setup (day 0)

1. Install companion v0.5.0+, watchapp v0.4.6+.
2. HyperOS/Xiaomi: Security app → Autostart → enable for Cryonics
   Monitor (Debug has a shortcut button). Also Settings → Battery →
   No restrictions for the app. Without Autostart, boot recovery is
   blocked by the OS.
3. Run the two recovery drills once:
   - **Reboot drill:** arm, reboot the phone, unlock it, wait 2 min
     without opening the app, then open Debug → expect PASS with the
     boot→service delay. (Verified 2026-08-28: PASS, 96 s.)
   - **Watch-outage drill:** start, power the watch off, wait 5 min,
     power on, confirm → expect sane detect/reconnect times.
     (Verified 2026-08-28: detect 9 s, link-up 5 s.)
4. Debug → **Reset soak counters** — AFTER the drills, since the
   drills themselves rack up disconnects/downtime/self-heals. The
   drill verdicts survive the reset window note in the report.
5. Fire one **test alarm** (Contacts screen) and confirm every
   responder actually received it. Schedule one of these near your
   normal bedtime once during the week — the "does it wake a human
   through DND" question is part of this protocol.

## Daily (≈2 min)

- Wear the watch normally; charge in your normal routine (charging =
  suspension is by design).
- Once a day open Debug and glance at the Soak card:
  - **alarms: pre / full** — for every increment, write one line in
    the log below: real event or false alarm, and what you were doing.
  - **disconnects / downtime** — sanity-check against your day (out
    of range? watch died?).
- Run the latency drill every other day (it doubles as the S7
  regression).

## Log (fill in)

| Day | Pre-alarms | Alarms | False? Context | Notes |
|-----|-----------|--------|----------------|-------|
| 1 | | | | |
| 2 | | | | |
| 3 | | | | |
| 4 | | | | |
| 5 | | | | |
| 6 | | | | |
| 7 | | | | |

## PASS gates (end of week, from the shared Soak report)

| Metric | Gate |
|--------|------|
| False full alarms (contacts alerted for nothing) | **0** |
| False pre-alarms (watch/phone nag, self-cancelled) | ≤ 2/week, each explained |
| Missed real nags (took watch off >5 min unsuspended, no nag) | 0 observed |
| Service starts: `other` unexplained (not launcher/enroll opens) | crash-loop check: no unexplained growth |
| Reboot drill | PASS, service < 120 s after boot |
| Watch-outage drill | detect ≤ 90 s, reconnect ≤ 120 s from power-on |
| Server-fails | transient only (no all-day gaps); dashboard trail has no unexplained holes |
| Worker faults | 0 (or each one self-healed within 10 min) |
| Not-worn / sensor nags (`notworn-nags`, `sensor-faults`) | each one explained (watch really off, or sensor really dead); a nag on a worn wrist = false positive, file the log window (`tools/worker_log_timeline.py`) |
| Watch battery (S6 card) | ≥ 7 projected days → GO |

Failing a gate = file the context (Soak report + log ring share),
fix, reset counters, restart the week. A clean week closes the soak
and is the release evidence for "runs unattended".

## Known issues under observation

- **Post-mortem without ADB (2026-09-09, bundle 2026-09-27).** Debug ->
  View logs -> Share (or Send to my server) exports the companion's
  daily files for a chosen range as one zip — the old 2 MB text tail
  lost everything older than about four days; `python tools/worker_log_timeline.py
  cm-YYYYMMDD.log` rebuilds the watch's true per-minute timeline (record
  time = log time - flush latency), lists nags/hunts/gated readings and
  prints a window around each nag. This is how the 02:05 sleeping
  "Not worn?" was traced to three identical 60 s samples.

- **Xiaomi Second Space = monitoring blind window (owner, 2026-08-29).**
  Switching to Second Space freezes main-space apps: the companion
  service pauses, watch alarms cannot reach the phone, and server
  heartbeats stop — long visits will trigger the server's
  phone_silent ADVISORY to responders. During the soak: note Second
  Space visits in the log; expected footprint is server-fails ticks
  and heartbeat gaps on the dashboard trail. If visits are frequent,
  the fix is wiring the server's offline-windows API to a one-tap
  "quiet window" (backlog).

- **HRM fails to start on some boots (firmware).** 2026-08-28/29: the
  sensor produced no readings after 2 of 3 consecutive power-ons (no
  LED, bpm 0, change age growing from boot); the third boot recovered
  it in ~30 s. Watch-side symptom on a failed boot: a legitimate
  "Not worn?" nag ~3 min after boot if the arm is still. If this
  recurs during the soak, note boot time + whether HR ever started —
  that's a PebbleOS bug report (flaky gh3x2x init), possibly worth an
  upstream issue alongside PR #1960.
- Fixed in watchapp 0.4.7: a parked nag/alarm could survive a reboot
  and replay onto the fresh boot (stale pending-action replay).
