# Own-vibration and alarm-clock guards; impact check-in needs a button

## Why

On 2026-09-30 07:30 the wearer's alarm clock vibration registered as a
hard shock; after 60 s of stillness the "Are you OK?" screen opened and
cancelled itself 2 s later — its own buzz counted as sustained motion.
The week's bundle holds seven more such self-cancelled impact check-ins
(09-23..09-26, all asleep, all reason MOTION). The 0.5.6 guard relies
on the firmware's per-sample `did_vibrate` flag, and that flag is dead
for a background worker: PebbleOS stops vibe-history collection on
every foreground-app exit and only restarts it when the accelerometer
subscriber list goes from empty to non-empty, which never happens while
the worker (and the HRM) stay subscribed. Upstream main still has this
(`prv_app_cleanup` → `sys_vibe_history_stop_collecting`). A check-in
that cancels itself from its own buzz would do so after a real fall
too, so the impact ladder cannot currently reach an alarm.

## What changes

1. **Self-vibration guard.** The app announces every vibration to the
   worker (`WMSG_VIBE`, duration) before starting the motor. The core
   discards motion and shocks from the announcement until
   duration + accel batch latency (1 s) + ringing (1.5 s). The flagged-
   sample rule stays as defence in depth.
2. **Alarm-clock guard.** Every 10 s the worker peeks the next enabled
   alarm (`alarm_service_peek_next`, SDK 4.33 / firmware Aug 2026+) and,
   from 15 s before it rings until 120 s after, applies the same guard.
   Snoozes are not visible to the API and are not covered; on Pebble 2
   HR the API is a stub and the guard is inactive.
3. **Impact check-in requires a button press.** Sustained motion no
   longer dismisses an impact CHECKIN — only SELECT (or the phone's
   cancel). Pulse-loss and non-motion check-ins keep motion dismissal.
   Owner decision 2026-09-30.
4. Watchapp 0.5.8, built with SDK 4.33.1.

Separately (not in this change): a one-line PebbleOS fix removing the
redundant `sys_vibe_history_stop_collecting()` from app cleanup is
prepared on a local branch for an upstream PR.

## Impact

- `watchapp/src/core/detectors.{c,h}`: `cm_vibe_guard()`, impact
  exception in the motion-dismiss rule; host tests (+3, one rewritten).
- `watchapp/src/core/protocol.h`: `WMSG_VIBE`.
- `watchapp/src/c/main.c`: every `vibes_*` call goes through a helper
  that announces first.
- `watchapp/worker_src/c/worker.c`: handles `WMSG_VIBE`; alarm peek.
- Spec `detector-ladder`: two requirements modified.
