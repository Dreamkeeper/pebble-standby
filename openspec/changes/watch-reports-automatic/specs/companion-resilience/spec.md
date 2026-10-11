# Delta: companion-resilience

## MODIFIED Requirements

### Requirement: Runs on the stock Pebble app (store-app mode)
The companion SHALL operate correctly whether or not the phone's Pebble
app forwards worker DataLogging (the stock Core app does since
PebbleKit2 1.3.0; older builds do not). There SHALL be no mode setting:
the companion SHALL run sync-only until the first worker record has
ever been received, then record-based, and that resolution SHALL
persist. Worker proof of ANY kind — a DataLogging record or an
open-watchapp message — SHALL satisfy the provisioning watchdog
permanently; a provisioning fault SHALL be raised at most once per
service start. While sync-only, the periodic watchapp sync launch SHALL
be the worker-liveness signal (hourly when the user interval is off,
counted from service start on a fresh install), two consecutive sync
launches yielding no watch data SHALL raise one fault, the
record-cadence (eviction) watchdog SHALL stay disarmed, and the state
SHALL be visible in the persistent notification, the Settings Pebble
page ("No reports from the watch yet, checked hourly" / "The watch
reports every minute"), the Debug screen and the soak report. Alarm
delivery through the watchapp launch path is unaffected.

#### Scenario: Fresh install on the stock Pebble app
- **WHEN** the companion starts on a phone with the stock Pebble app,
  the watch is connected and no worker record ever arrives
- **THEN** exactly one provisioning fault is raised after the grace,
  its self-heal launch produces an open-app message that ends it, and
  no further provisioning fault or launch loop follows

#### Scenario: Hourly liveness on the stock app
- **WHEN** sync-only is in effect and the sync interval is off
- **THEN** the watchapp is launched briefly about once an hour, watch
  data age stays under the interval, and two consecutive launches with
  no watch data raise one fault notification

#### Scenario: Auto mode learns the patched app
- **WHEN** the first worker record arrives
- **THEN** the companion switches to record-based liveness for good,
  record-based watchdogs arm, and the notification no longer shows
  "sync only"
