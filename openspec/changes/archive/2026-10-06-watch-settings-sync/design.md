# Design

## D1. Field ids, not struct layout

The blob push depended on the phone reproducing the C struct's byte
layout, which is why it was never used. Each setting gets a small
integer id (`CM_CFG_*`, mirrored in `WatchConfig.Field`) and travels as
`KEY_DETECTOR = id`, `KEY_SECONDS = value` in the existing dictionary
keys; the worker message `WMSG_CFG_SET` carries the same pair in
`data0/data1`. Values are unsigned 16-bit; booleans are 0/1; hours are
0–23. Adding a field is one line on each side and never breaks older
watches, which ack unknown ids with ok=0.

## D2. Validation on the watch, echo of the value in force

`cm_apply_config(c, field, value)` clamps nothing: a value outside the
field's range is refused (returns 0) and the ack carries the value still
in force. The phone shows what the watch confirmed, so the two can never
disagree silently. Ranges are deliberately generous but keep the ladder
sane (time to answer 10–120 s, countdowns 10–120 s / 10–60 s for
impact, pulse signal lost 60–600 s, stillness after impact 30–300 s,
no-movement 10–240 min by day and 10–480 by night, check-in interval
30–1440 min).

## D3. Side effects inside the core

Enabling the scheduled check-in or changing its interval reschedules the
next check-in from now, so a wearer who turns it on at 09:00 with a 4 h
interval is asked at 13:00, not at an epoch computed when the worker
started. Disabling a detector mid-episode leaves the current ladder
alone; the next tick simply stops starting new episodes.

## D4. Persist the whole configuration

The worker writes the complete `cm_config` to `PK_CONFIG` after every
accepted set, so a restart loads exactly what the wearer last confirmed.
`load_config()` keeps overriding `hr_available` from hardware.

## D5. Delivery when the watchapp is open

AppMessage reaches the watch only while the watchapp is in the
foreground. `WatchConfig` keeps `pending` (changed since last ack) and
`everSynced`; `MonitorService.onWatchappOpened()` sends all pending
fields, or every field when nothing was ever acked, 150 ms apart, after
the 1.2 s the inbox needs to register. A change made while the watchapp
is open is sent at once. A change made while it is closed asks for a
launch through the existing self-heal path, which already refuses to
interrupt another app on the wearer's screen and lands on the next sync
otherwise. The Settings screen shows "Will reach the watch at the next
sync" until the ack arrives.

## D6. Not covered

Suspension, carry mode and the worker's debug and quality-metric flags
keep their existing messages. Accelerometer thresholds (jerk, freefall,
impact g) stay compile-time: they were tuned against the sensor lab and
a wearer has no way to reason about them.
