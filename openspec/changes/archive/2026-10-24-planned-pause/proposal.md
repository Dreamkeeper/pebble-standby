# Planned pause: a day without the watch

## Why

The owner has full-day activities where the watch cannot be worn (an
aqua park). Today the watch pauses only itself (30, 60 or 120 min, UP
button) and auto-resumes on wear; the phone does not know the pause is
planned and raises "needs attention" after 25 min of silence; the
server already has a declared offline window but nothing calls it. A
longer timer on the watch would silence the watch and leave the phone
nagging and the server guessing.

## What changes

- **Pause from the phone.** Home offers "Pause monitoring…" with
  presets a wearer thinks in (2 h, 4 h, rest of today, until tomorrow
  morning) and a clock. One tap: the watch suspends for the same period
  when it can be reached (new `PMSG_SUSPEND_SET`), the server gets the
  offline window and shows "paused by wearer", Home reads "Paused until
  08:00", and the watch-silence faults stay quiet until the horizon.
- **Early return.** When the watch auto-resumes (worn again) it already
  tells the phone; the phone then cancels the server window (duration
  0) and Home returns to Covered. Resume on the phone does the same and
  resumes the watch.
- **"Until worn again" on the watch.** A fourth UP preset: an
  auto-resuming suspension capped at 14 h (`CM_UNTIL_WORN_MIN`), for the
  locker decision without the phone in hand.
- **The heartbeat now carries the pause** (`suspended_until`), so the
  dashboard's existing "suspended" badge works for both kinds.

## Out of scope

Recurring schedules (the suspension spec's learning layer) and a real
MenuLayer on the watch.
