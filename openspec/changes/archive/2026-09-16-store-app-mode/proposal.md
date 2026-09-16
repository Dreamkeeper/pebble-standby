# Store-app mode: run on the stock Pebble app without worker telemetry

## Why

The beta cannot wait for upstream. On the stock Pebble app, worker
DataLogging never reaches the companion (coredevices/mobileapp#386 is
open and unreviewed), and today the companion treats "no records" as
"worker dead":

- The provisioning watchdog faults 15 min after start, self-heal
  launches the watchapp, the open-app message clears the fault flag —
  and because the rule keyed on *records* (never seen on the stock
  app), it re-fires on the next 15 s tick: a launch loop every ~30 s.
- DataLogging alarm recovery, watch-age telemetry and the soak metrics
  silently vanish with no indication of why.
- The Debug screen still calls this "S5 NO-GO, fallback planned".

Alarms themselves still arrive through the watchapp launch path
(71 ms measured), so the safety core works on the stock app; the
companion just has to stop mistaking missing telemetry for a dead
worker, and say honestly what it can and cannot see.

## What changes

- A `Pebble app mode` setting: **auto** (default) / **patched** /
  **store**. Auto resolves to store mode until the first worker record
  ever arrives (persisted `dlEverSeen`), then to patched.
- **Worker proof of any kind** (record or open-app message) ends the
  provisioning watchdog for good — fixes the launch loop on every app,
  not just the stock one.
- In store mode the periodic sync launch (already an owner-approved
  fallback) becomes the liveness signal: hourly by default if the
  interval is off; two consecutive launches with no watch data raise
  one fault. The eviction watchdog stays disarmed (it needs records).
- The notification status line, the Debug S5 card and the soak report
  name the mode; the S5 text explains what telemetry is missing.
- Pure `PebbleAppPolicy` object with JVM unit tests.

## Out of scope

Shipping testers the patched Pebble app; any watch-side change; a
worker-side periodic self-launch (a screen takeover the sync launch
already provides, under user control).

## Impact

- Specs: `companion-resilience` (ADDED "Runs on the stock Pebble app").
- Code: `PebbleAppPolicy.kt` (new), `SettingsStore`, `MonitorService`,
  `MainActivity`, `DebugActivity`.
- Release: companion 0.6.3 (42). No protocol change.
