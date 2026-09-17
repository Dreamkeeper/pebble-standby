# Standby — watchapp

The Pebble side (C, SDK 4.x): a **background worker** that monitors
24/7 and a small foreground app that shows alerts and takes button
input. Targets `emery` (Pebble Time 2), `diorite` (Pebble 2 HR), `flint`
(Pebble 2 Duo) and `gabbro` (Pebble Round 2). Watches without a
heart-rate sensor run the motion, impact and check-in detectors only.

## What it watches for

| Detector | Trigger | Then |
|---|---|---|
| Pulse loss | readings stop, or the value freezes, while you are still | silent 45 s hunt at 1 Hz → "Are you OK?" → countdown → alarm |
| Impact | freefall-then-impact or a hard shock, then 60 s of immobility (while worn) | "Are you OK?" → fast countdown → alarm |
| Non-motion | no micro-movement for 40 min by day / longer at night | same ladder |
| Check-in | scheduled check-in missed | same ladder; only a button press answers it |
| Not worn / sensor fault | no pulse and no motion / no pulse while moving | a nag to the wearer only — never contacts |

A live wrist is recognised by a **changing** pulse value; only
**sustained** motion (three seconds within ten) dismisses a check-in,
so a bumped desk or a bed partner does not, and the watch's own
vibration never counts as motion.

## Buttons (main screen)

| Button | Action |
|---|---|
| SELECT | "I'm OK" — check in, cancel an alert, or end a suspension |
| UP | suspend monitoring: 30 → 60 → 120 min (auto-resumes when worn again) |
| UP (hold) | carry mode: 120 min, timer only |
| DOWN (hold) | manual SOS |

On the charger monitoring holds automatically.

## Layout

| Path | Role |
|---|---|
| `src/core/detectors.{c,h}` | the detector core: platform-independent, integer-only, no allocation; shared by worker and tests |
| `src/core/protocol.h` | message ids, persist keys, the 16-byte heartbeat record |
| `worker_src/c/worker.c` | background worker: sensors → core → actions; DataLogging heartbeat every minute; launches the app for alerts |
| `src/c/main.c` | foreground app: alert ladder UI, acknowledged delivery to the phone, suspension menu |
| `tests/test_detectors.c` | host tests for the core (216 checks) |

The worker's code, data, stack and heap share about 10.5 KB, so its
binary size is a budget: verbose logging compiles out by default
(`-DCM_WORKER_VERBOSE=1` for a debug build), and free heap is reported
in every heartbeat record.

## Build and test

```bash
pebble build                       # pebble-tool 5.x, SDK 4.9.169+ (Linux/WSL)
# -> build/watchapp.pbw            # sideload via the Pebble app

cd tests
gcc -Wall -Wextra -std=c11 -I../src/core -o test_runner \
    test_detectors.c ../src/core/detectors.c && ./test_runner
```

`package.json` declares the Android companion package under
`companionApp`; that declaration is what routes PebbleKit2 messages and
worker data logging to the Standby app. The app UUID is fixed — changing
it would orphan every install.
