# A knock waits longer than a fall

## Why

Every false hard-impact alert in the field came from the bare-shock
branch of the detector (a single sample over 3.8 g with no freefall
before it): a set-down on a desk (2026-09-09), the alarm clock's
vibration (2026-09-30), and a knock followed by a quiet minute
(2026-10-09 19:17, worn, pulse steady, dismissed 4 s after the
check-in). The freefall-then-impact signature has never fired falsely.
A knock is followed by a minute of stillness far more often than a
fall is: sitting down and reading the phone does it.

## What changes

- **Two stillness windows.** After a freefall-then-impact the immobility
  window stays `impact_immobile_s` (default 60 s). After a bare shock it
  is `shock_immobile_s` (default 120 s, range 30-600). The check-in and
  countdown add about 50 s either way, so a real collapse is still
  noticed within three minutes.
- **The knob reaches the phone.** Settings field 21 (`SHOCK_IMMOBILE_S`)
  on the Hard impact page as "Stillness after a knock"; the row reads
  "asks after 60 s of stillness, 120 s after a knock".
- **Settings survive a watchapp upgrade.** The persisted configuration
  is discarded when its size changes (it just did), so the phone now
  re-sends every field the wearer changed from its default at each
  watchapp open, not only the pending ones.

## Out of scope

Raising `crash_above_mg`: the accelerometer range on the Time 2 is not
confirmed, and a higher threshold could sit above saturation.
