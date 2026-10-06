# Watch settings owned by the phone, applied live on the watch

## Why

The product spec says every detector is optional and configurable, and
the design pass puts "what Standby watches for" at the top of Settings
with switches and thresholds. Today nothing on the phone can change a
detector: the watch accepts a whole configuration blob that only takes
effect when the worker restarts, and the companion never sends it. The
Settings redesign (companion-compose-ui) cannot show switches that lie,
so the sync comes first.

## What changes

- **One message per setting.** `PMSG_CONFIG_SET` (phone → watch) carries
  a field id and a 16-bit value; the app forwards it to the worker, the
  core validates and applies it immediately, the worker persists the
  whole configuration, and `PMSG_CONFIG_ACK` returns the field, the
  value actually in force and an ok flag. The old blob push stays for
  compatibility but is no longer used.
- **The phone is the source of truth.** `WatchConfig` on the phone holds
  the wearer's detector settings with the watch's defaults, tracks which
  fields the watch has acknowledged, and sends the pending ones whenever
  the watchapp opens (the hourly sync, a self-heal launch, the wearer
  opening it) or right away when it is already open. Changing a setting
  launches the watchapp if nothing else is on the wearer's screen.
- **Fields exposed:** per-detector on/off (pulse signal, impact, no
  movement, scheduled check-in, not-worn nag, sensor-fault nag), the
  thresholds a wearer can reason about (pulse signal lost / frozen after,
  impact stillness, no-movement day and night minutes, night hours,
  check-in interval and grace, not-worn and sensor-fault minutes), and
  the ladder timings (time to answer, countdown, impact countdown). Raw
  accelerometer thresholds stay compile-time defaults.
- Watchapp 0.5.9; companion side ships inside 0.7.0.

## Impact

- `watchapp/src/core/detectors.{c,h}`: `cm_apply_config()` with field
  ids and ranges; check-in rescheduling when its interval or switch
  changes; host tests.
- `watchapp/src/core/protocol.h`, `src/c/main.c`, `worker_src/c/worker.c`:
  the new messages and the persist.
- `android`: `WatchConfig`, `SettingsStore` fields, `MonitorService`
  send-on-open and ack handling, `Protocol.kt`; JVM tests for the field
  table and pending/ack bookkeeping.
- Spec `watch-phone-protocol`: one requirement added.
