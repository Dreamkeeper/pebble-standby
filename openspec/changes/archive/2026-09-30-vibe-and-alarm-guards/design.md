# Design

## D1. Announce-before-buzz, one guard mechanism

The core already has `vibe_guard_until_ms`; while it is in the future
every sample is neither motion nor a shock ("ringing"). `cm_vibe_guard(c,
duration_ms, now_ms)` extends it to `now + duration + 1000 + 1500` and
never shortens it. The 1000 ms covers accelerometer delivery: the worker
receives 25 samples once a second, stamped with the delivery time, so the
buzz's samples arrive up to a second after the announcement. The 1500 ms
is the existing case-ringing allowance.

The app sends `WMSG_VIBE` (data0 = motor duration in ms) *before* each
`vibes_*` call. App→worker messages are delivered on the worker task in
order with the accel callbacks, so the guard is set before the batch
holding the buzz arrives. Duration estimates: short pulse 300 ms, double
pulse 700 ms, custom patterns = sum of segments.

The worker never announces its own actions: the worker does not vibrate
(only the app does), and the app announces even auto-launch buzzes.

## D2. Alarm-clock window

`alarm_service_peek_next()` returns the next enabled alarm's fire time
(snoozes use a separate firmware timer and are not reported; smart
alarms report the start of their window). The worker checks every 10 s;
inside `[T − 15 s, T + 120 s]` it calls `cm_vibe_guard` with the
remaining window. The window suppresses motion as well as shocks (the
same rule as D1): the alarm's buzz would otherwise count as sustained
motion and dismiss a pulse-loss check-in that happens to be pending.
Two minutes of motion blindness costs nothing the detectors care about
(non-motion threshold is 40 min; a pulse hunt during the window ends on
the first changed reading).

On diorite (Pebble 2 HR) the SDK defines the call as a constant 0 and
the code compiles to nothing.

## D3. Impact check-in: button only

`note_motion()` excludes `CM_DET_IMPACT` from motion dismissal alongside
scheduled check-ins and SOS. The pre-UI immobility gate is unchanged:
deliberate motion during the 60 s window still discards the candidate
silently, so getting up after a stumble never shows a screen. Once the
screen is up, a fall victim's involuntary movement no longer silences
it; the cost is one SELECT press after a false shock.

## D4. Not changed

`did_vibrate` handling stays (works on firmware with the upstream fix).
The companion and server are untouched: the cancel reason and messages
are the same.
