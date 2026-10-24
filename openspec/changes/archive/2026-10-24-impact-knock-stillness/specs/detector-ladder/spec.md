# Delta: detector-ladder

## MODIFIED Requirements

### Requirement: Impact detection
The system SHALL detect (a) freefall (magnitude < freefall_below_mg,
default 300) followed by impact (> impact_above_mg, default 2400) within
freefall_window_ms (default 1500), and (b) single shocks > crash_above_mg
(default 3800). After a candidate impact, a settle window
(impact_settle_s, default 5 s) is ignored, then an immobility window
must pass with no motion before CHECKIN starts: impact_immobile_s
(default 60 s) after a freefall-then-impact, shock_immobile_s (default
120 s) after a bare shock with no freefall before it (field 2026-10-09
19:17: a knock, then a quiet minute while worn with a steady pulse, asked
"Are you OK?"; every false impact so far came from a bare shock, none
from the freefall signature). Samples flagged did_vibrate SHALL be
discarded, and no freefall, impact or shock SHALL be recognised within
1.5 s after such a sample or within an announced-vibration window (the
case ringing after our own vibration). The wearer's alarm clock SHALL
NOT register as a shock: where the firmware exposes the next enabled
alarm, the worker SHALL apply the vibration guard from 15 s before it
rings until 120 s after (field 2026-09-30 07:30: the alarm's vibration
read as a hard shock, then a check-in after 60 s of lying still). Snoozed
re-rings are not exposed by the firmware and are not covered. On HR
hardware that has ever seen a pulse, an impact with no valid reading
between the shock and the end of the immobility window SHALL be
discarded silently: the watch is not on a readable wrist (a set-down on
a desk registers as a shock, field 2026-09-09) and the pulse ladder and
not-worn nag own what follows.

#### Scenario: Fall followed by immobility alarms with the fast fuse
- **WHEN** freefall→impact is detected and no motion occurs through the
  settle + immobility window
- **AND** the wearer's readings continue through that window
- **THEN** CHECKIN starts with detector IMPACT
- **AND** the COUNTDOWN uses the impact fuse (20 s)

#### Scenario: Getting up after a fall stays silent
- **WHEN** freefall→impact is detected
- **AND** motion occurs after the settle window
- **THEN** the candidate is discarded with no user-visible alert

#### Scenario: Setting the watch down is not a fall
- **WHEN** a shock is detected and no valid reading arrives through the
  settle + immobility window
- **THEN** no CHECKIN starts and no alarm fires

#### Scenario: The alarm clock is not a fall
- **WHEN** the next enabled alarm is due within 15 s
- **AND** high-G samples arrive while it rings and the wearer then lies
  still for the immobility window
- **THEN** no CHECKIN starts

#### Scenario: A knock followed by a quiet minute stays silent
- **WHEN** a bare shock is followed by 66 s of stillness while worn
- **THEN** no check-in starts; it starts only once shock_immobile_s has
  passed with no movement

#### Scenario: A fall keeps the shorter window
- **WHEN** freefall-then-impact is followed by 66 s of stillness while worn
- **THEN** the impact check-in starts
