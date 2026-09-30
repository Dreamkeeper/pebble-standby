# Delta: detector-ladder

## MODIFIED Requirements

### Requirement: Staged escalation with implicit-then-explicit cancellation
An alert SHALL pass through CHECKIN (default 30 s, configurable) and
COUNTDOWN (default 30 s; 20 s for impact; 5 s for manual SOS) before the
ALARM action is emitted. During CHECKIN, SUSTAINED wearer motion SHALL
auto-dismiss pulse-loss and non-motion alerts; impact alerts, scheduled
check-ins and SOS SHALL be cancelled only by an explicit button press
(or the phone's cancel). During COUNTDOWN, only an explicit button press
cancels. Sustained motion is a jerk above motion_jerk_mg in at least
three distinct seconds within a ten-second window. A single jerk is a
bump — a desk in use, a bed partner turning, a vehicle — and SHALL NOT
dismiss a check-in (field 2026-09-09: a desk bump cancelled a pulse-loss
CHECKIN one second after it started).

The watch's OWN vibration SHALL never count as motion or as a shock. The
app SHALL announce every vibration to the worker before starting the
motor, and the core SHALL discard motion and shocks from the
announcement until the motor duration plus 1 s of accelerometer delivery
latency plus 1.5 s of case ringing has passed. Samples the firmware flags
as taken during a motor run are discarded as well, and jerks within
1.5 s after such a sample are ignored (field 2026-09-09 12:33: an impact
CHECKIN's own buzzes cancelled it; field 2026-09-30 07:31: the same,
because the firmware stops setting the flag for a worker after any app
exits — the announcement does not depend on the firmware).

#### Scenario: Motion dismisses a check-in
- **WHEN** a pulse-loss or non-motion alert is in CHECKIN stage
- **AND** sustained wrist motion is detected (three motion-seconds
  within ten seconds)
- **THEN** the alert is cancelled with reason MOTION and no alarm fires

#### Scenario: An impact check-in needs a button press
- **WHEN** an impact alert is in CHECKIN stage
- **AND** sustained wrist motion is detected
- **THEN** the check-in continues to COUNTDOWN unless SELECT (or the
  phone's cancel) ends it, which cancels with reason USER

#### Scenario: A bump does not dismiss a check-in
- **WHEN** a pulse-loss alert is in CHECKIN stage
- **AND** isolated jerks arrive (one every 15 s, as from a surface
  being bumped)
- **THEN** the check-in continues to COUNTDOWN and ALARM unless a
  button press or a changed pulse value cancels it

#### Scenario: A check-in survives its own buzzes on a hard surface
- **WHEN** a CHECKIN vibrates every 5 s while the watch lies on a hard
  surface that rings after each buzz
- **THEN** the aftershocks are not motion, the check-in is not
  cancelled, and COUNTDOWN follows

#### Scenario: An announced vibration is neither motion nor a shock
- **WHEN** the app announces a vibration of D ms
- **AND** unflagged jerks and a high-G sample arrive within D + 2.5 s
- **THEN** no motion is recorded, no impact candidate starts, and a
  jerk after the window counts as motion again

#### Scenario: Motion does NOT dismiss a countdown
- **WHEN** any alert has advanced to COUNTDOWN stage
- **AND** motion is detected
- **THEN** the countdown continues; only an explicit "I'm OK" cancels

#### Scenario: Ladder exhaustion latches the alarm
- **WHEN** a COUNTDOWN expires without user cancellation
- **THEN** CM_ACT_ALARM is emitted with the originating detector
- **AND** the alarm state persists until the user presses "I'm OK"

### Requirement: Impact detection
The system SHALL detect (a) freefall (magnitude < freefall_below_mg,
default 300) followed by impact (> impact_above_mg, default 2400) within
freefall_window_ms (default 1500), and (b) single shocks > crash_above_mg
(default 3800). After a candidate impact, a settle window
(impact_settle_s, default 5 s) is ignored, then an immobility window
(impact_immobile_s, default 60 s) must pass with no motion before CHECKIN
starts. Samples flagged did_vibrate SHALL be discarded, and no
freefall, impact or shock SHALL be recognised within 1.5 s after such a
sample or within an announced-vibration window (the case ringing after
our own vibration). The wearer's alarm clock SHALL NOT register as a
shock: where the firmware exposes the next enabled alarm, the worker
SHALL apply the vibration guard from 15 s before it rings until 120 s
after (field 2026-09-30 07:30: the alarm's vibration read as a hard
shock, then a check-in after 60 s of lying still). Snoozed re-rings are
not exposed by the firmware and are not covered. On HR hardware that
has ever seen a pulse, an impact with no valid reading between the
shock and the end of the immobility window SHALL be discarded silently:
the watch is not on a readable wrist (a set-down on a desk registers as
a shock, field 2026-09-09) and the pulse ladder and not-worn nag own
what follows; a fallen wearer keeps producing readings at the idle
cadence through that window. All thresholds are user-configurable;
defaults derive from OpenSeizureDetector and are subject to field-trial
tuning.

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
