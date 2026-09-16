# Delta: companion-resilience — store-app mode

## ADDED Requirements

### Requirement: Runs on the stock Pebble app (store-app mode)
The companion SHALL operate correctly when the phone's Pebble app does
not forward worker DataLogging (the stock Core app; upstream
mobileapp#386 pending). A `Pebble app mode` setting SHALL offer auto
(default), patched and store; auto SHALL resolve to store mode until
the first worker record has ever been received, then to patched, and
the resolution SHALL persist. Worker proof of ANY kind — a DataLogging
record or an open-watchapp message — SHALL satisfy the provisioning
watchdog permanently; a provisioning fault SHALL be raised at most
once per service start. In store mode the periodic watchapp sync
launch SHALL be the worker-liveness signal (hourly when the user
interval is off, counted from service start on a fresh install), two
consecutive sync launches yielding no watch data SHALL raise one
fault, the record-cadence (eviction) watchdog SHALL stay disarmed, and
the mode SHALL be visible in the persistent notification, the Debug
screen and the soak report together with what telemetry is
unavailable. Alarm delivery through the watchapp launch path is
unaffected.

#### Scenario: Fresh install on the stock Pebble app
- **WHEN** the companion starts on a phone with the stock Pebble app,
  the watch is connected and no worker record ever arrives
- **THEN** exactly one provisioning fault is raised after the grace,
  its self-heal launch produces an open-app message that ends it, and
  no further provisioning fault or launch loop follows

#### Scenario: Hourly liveness on the stock app
- **WHEN** store mode is in effect and the sync interval is off
- **THEN** the watchapp is launched briefly about once an hour, watch
  data age stays under the interval, and two consecutive launches with
  no watch data raise one fault notification

#### Scenario: Auto mode learns the patched app
- **WHEN** a worker record arrives while the mode is auto
- **THEN** the mode resolves to patched, record-based watchdogs arm,
  and the notification no longer shows "store app"
