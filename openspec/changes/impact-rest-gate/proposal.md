# An impact out of rest is not a fall

## Why

The wearer is woken at night by "Are you OK?". The companion logs
(pulled 2026-10-24) hold nine night-time or early-morning impact
check-ins this month (Oct 5, 11 twice, 14 twice, 17, 18, 20, 24), all
of the same shape: 6 to 34 minutes with no motion at all and a steady
pulse of 55-75 bpm, one jerk (the watch arm hitting the headboard or
dropping onto the mattress), then sleep stillness through whichever
immobility window applied, so the question vibrated. Both the freefall
signature (60 s window) and the bare shock (120 s since 0.5.10) occurred.
The 120 s knock rule did not help: a sleeper is still for far longer.

## What changes

- **A rest gate before the impact, not more stillness after it.** A fall
  is preceded by standing or walking, that is sustained motion seconds
  before it. An impact candidate (either signature) that arrives after
  `impact_rest_min` (default 5, range 1-60) with no sustained motion is
  dropped silently. A wearer who stops living while resting is the pulse
  and no-movement detectors' case, which is what they exist for.
- **The knob reaches the phone.** Settings field 22 (`IMPACT_REST_MIN`)
  on the Hard impact page as "Ignored after resting for", 5 min.
- **Accepted miss.** A wearer who stood or sat perfectly still for over
  five minutes and then collapsed with an impact gets no impact
  question, only the slower pulse or no-movement ladder.

## Out of scope

Gating by the configured night window: two of the nine events (07:04
and 08:29) fall outside it, and the rule is causal without the clock.
