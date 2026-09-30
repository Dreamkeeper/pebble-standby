# Detector Ladder Specification

## Purpose

Defines the detectors that decide when the wearer may be unresponsive and
the multi-stage alert ladder that turns a detection into an alarm while
absorbing false positives. Implemented in `watchapp/src/core/detectors.{h,c}`
(platform-independent, integer-only, runs in the 10.5 kB background
worker); scenario coverage lives in `watchapp/tests/test_detectors.c`
(80 checks). Ladder stages: SILENT GATES → CHECKIN ("Are you OK?") →
COUNTDOWN → ALARM (latched).

## Requirements

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

### Requirement: One alert at a time
While an alert is active (any stage), other detectors SHALL NOT start a
second ladder.

#### Scenario: Impact during a pulse-loss check-in is not double-alerted
- **WHEN** a pulse-loss alert is in CHECKIN stage
- **AND** an impact candidate occurs
- **THEN** no second ladder starts while the first alert is active

### Requirement: Pulse-loss detection on HR hardware
On watches with a heart-rate sensor (emery, diorite), pulse LIVENESS
SHALL mean a CHANGING raw value: the S4 sensor lab (2026-08-27, Time 2)
proved that off-body the firmware keeps serving the last computed bpm
with fresh events — bit-identical for many minutes — while a living
wearer's raw bpm always jitters. A frozen reading is only evidence the
watch was recently worn (grace), never evidence of life.

The system SHALL start a silent "pulse hunt" (HR burst sampling at 1 s
for pulse_hunt_s, default 45 s — burst spin-up was measured at ~23 s)
when EITHER readings have been absent for pulse_lost_after_s (default
150 s) OR the value has not changed for pulse_flat_after_s (default
300 s) while the wearer is still (no SUSTAINED motion for
pulse_still_s, default 20 s — single bumps do not count) and the
watch was recently worn (a reading within pulse_worn_grace_min, default
10 min). Only a CHANGED value ends a hunt or dismisses a pulse-loss
CHECKIN; a frozen feed lets the ladder proceed — the 1 Hz hunt is the
arbiter between alive-at-rest (jitters within seconds) and frozen.
Readings below pulse_min_bpm (default 25) count as no signal. Sustained
motion during a hunt stands it down silently; single bumps do not.

Before starting a hunt, the episode SHALL be classified: SUSTAINED
motion within removal_window_s (default 45 s) of the last VALUE CHANGE
is PROBABLE REMOVAL — a dead wearer does not move as the pulse stops,
while removing a watch is seconds of handling — and the pulse ladder
SHALL NOT run; the not-worn nag owns the episode. A freeze with no
sustained motion near the change moment (still wearer) keeps the full
ladder; a single bump at that moment no longer diverts a collapse to
the nag. Residual risks are documented and accepted: a removal too
gentle to register sustained motion still runs the ladder (the phone
cancel absorbs it).

#### Scenario: Pulse loss escalates through the full ladder
- **WHEN** the pulse stops or freezes while the wearer is still
- **AND** no sustained motion occurred near the last value change
- **AND** the silent hunt sees no CHANGING value
- **THEN** CHECKIN starts, then COUNTDOWN, then ALARM, and HR burst
  sampling is released when the ladder ends

#### Scenario: Bumped surface does not block the ladder
- **WHEN** the pulse is absent and the surface receives an isolated
  jerk every 15 s
- **THEN** the hunt still starts and concludes, and CHECKIN follows

#### Scenario: Taking the watch off routes to the nag, not the ladder
- **WHEN** the pulse value freezes (readings may continue frozen)
- **AND** sustained motion was observed within removal_window_s of the
  last value change (handling: unbuckling, setting the watch down)
- **THEN** no hunt and no CHECKIN start for this episode
- **AND** the not-worn nag fires at its own threshold despite the
  continuing frozen readings

#### Scenario: Pulse returns during the hunt
- **WHEN** a pulse hunt is running
- **AND** a reading with a CHANGED value arrives
- **THEN** the hunt ends silently with no user-visible alert

#### Scenario: Returning pulse dismisses the check-in
- **WHEN** a pulse-loss alert is in CHECKIN stage
- **AND** a reading with a CHANGED value arrives
- **THEN** the alert cancels with reason PULSE

#### Scenario: User cancellation snoozes re-triggering
- **WHEN** the user cancels a pulse-loss alert
- **THEN** pulse-loss SHALL NOT re-trigger for pulse_snooze_min
  (default 10 min) unless a valid pulse is seen first

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

### Requirement: Non-motion detection
The system SHALL start CHECKIN when no micro-movement has been detected
for nonmotion_day_min (default 40) during day or nonmotion_night_min
(default 90) during night (night window default 23:00–07:00,
configurable), while the watch is worn. Motion is a magnitude jerk ≥
motion_jerk_mg (default 60) between consecutive samples.

On HR hardware, a valid pulse is proof of life: while a pulse was read
within pulse_proof_min (default 5 min), non-motion SHALL NOT start a
ladder — stillness alone (sleep, meditation, television) must never
ping a wearer whose pulse is visible. Non-motion on HR hardware remains
only as a backstop for a silently failing sensor: it may fire in the
band where the pulse is staler than pulse_proof_min but the worn grace
(10 min) has not lapsed. On non-HR hardware (flint, gabbro) the watch
is assumed worn and non-motion is the primary detector — a documented
limitation.

#### Scenario: Still with a live pulse stays silent
- **WHEN** a wearer on HR hardware is perfectly still past the day
  threshold
- **AND** valid pulse readings continue to arrive
- **THEN** no CHECKIN, hunt, nag, or alarm is emitted

#### Scenario: Sensor goes stale during long stillness
- **WHEN** stillness has exceeded the day threshold
- **AND** the last valid pulse is older than pulse_proof_min but
  within the worn grace
- **THEN** CHECKIN starts with detector NONMOTION

#### Scenario: Daytime stillness triggers, motion dismisses
- **WHEN** a wearer on motion-only hardware (flint/gabbro) is perfectly
  still for nonmotion_day_min
- **THEN** CHECKIN starts with detector NONMOTION
- **AND** subsequent motion cancels it with reason MOTION

#### Scenario: Night threshold is longer
- **WHEN** the local time is inside the night window
- **THEN** stillness shorter than nonmotion_night_min does not trigger

### Requirement: Scheduled check-in requires a deliberate button press
Scheduled check-in SHALL default to disabled: it is the only detector
that demands the wearer's attention while nothing is wrong, so it is
opt-in. When enabled, the wearer SHALL receive a reminder (checkin_remind_min,
default 5 min before due) and must press the check-in button within
checkin_interval_min + checkin_grace_min (defaults 240 + 15 min). A
missed deadline starts the ladder. Motion SHALL NOT satisfy a scheduled
check-in. Any check-in button press reschedules the next round.

#### Scenario: Missed check-in escalates despite motion
- **WHEN** the check-in deadline + grace passes without a button press
- **THEN** CHECKIN starts with detector CHECKIN
- **AND** wearer motion does not dismiss it — only the button does

### Requirement: Not-worn nag never escalates to contacts
When the watch appears off-wrist (HR hardware: no pulse AND no motion
for notworn_after_min, default 3), the system SHALL first run the
silent 1 Hz pulse hunt (pulse_hunt_s) rather than nag: at the 60 s
idle cadence a sleeping wearer's steady bpm repeats exactly like a
frozen off-body reading (field 2026-09-09). A bpm value change during
that hunt SHALL end it silently, re-arm the episode, and suppress
further not-worn hunts for a cooldown (10 min). Only if the 1 Hz
stream stays flat or absent for the whole hunt SHALL the nag be
emitted to the wearer (watch + phone), exactly once per episode, and
it SHALL NOT notify contacts. Removal-classified pulse losses route
here. A not-worn hunt SHALL never escalate into the alert ladder. The
watch-side nag screen SHALL release the display after 3 min if
unanswered; the phone notification persists.

#### Scenario: Watch left on the nightstand nags once
- **WHEN** the watch sees neither pulse nor motion for notworn_after_min
  without a suspension
- **AND** the arbiter hunt sees no changing value for pulse_hunt_s
- **THEN** one CM_ACT_NOTWORN_NAG is emitted
- **AND** no repeat nag occurs until pulse or motion returns

#### Scenario: Sleeping wearer with a steady pulse is not nagged
- **WHEN** the wearer is motionless and the idle-cadence bpm repeats
  the same value for notworn_after_min
- **THEN** a silent hunt starts, the 1 Hz stream shows a changing
  value within seconds, the hunt ends with no user-visible alert
- **AND** no further not-worn hunt starts within the cooldown

#### Scenario: Unanswered nag hands the screen back
- **WHEN** a not-worn or sensor-fault nag has been on the watch screen
  for 3 min without a button press
- **THEN** the watch returns to the watchface while the phone
  notification remains

### Requirement: Manual SOS
A deliberate long-press SHALL start a COUNTDOWN (countdown_sos_s, default
5 s, mis-press protection) that escalates to ALARM; it skips silent gates
and CHECKIN, and motion does not cancel it.

#### Scenario: SOS fires after the short fuse
- **WHEN** the wearer triggers manual SOS
- **THEN** a 5 s COUNTDOWN starts and, uncancelled, emits ALARM(SOS)

### Requirement: Detector core stays worker-safe
The detector core SHALL use no dynamic allocation, no floating point, and
no Pebble APIs, and SHALL remain host-compilable for the test suite.
Timestamps are uint32 milliseconds with wrap-safe comparisons valid for
spans < 24 days.

#### Scenario: Core compiles and passes on a host toolchain
- **WHEN** watchapp/tests/test_detectors.c is compiled with MSVC or gcc
- **THEN** the suite builds without Pebble headers and all checks pass

### Requirement: Sensor-fault nag
On HR hardware, when the bpm value has not changed for
sensor_fault_after_min (default 10) while motion remains recent
(within the not-worn threshold), the watch SHALL nag the wearer once
per episode with a sensor-fault message ("no pulse signal — sensor
dead, or watch carried off-wrist; reboot or suspend") and notify the
phone (`PMSG_SENSOR_FAULT`), which SHALL raise a FAULT notification
and count the event in the soak counters. Contacts are never alerted.
The episode re-arms on a bpm change or a baseline reset
(charging/lab-hold release, suspension end). Motion alone MUST NOT
re-arm it — motion does not disprove a dead sensor. The not-worn nag
keeps requiring BOTH pulse and motion stale; the two nags are
mutually exclusive by construction (motion recency splits them).

#### Scenario: Sensor dies while worn
- **WHEN** the wearer keeps moving normally but the bpm value stays
  frozen (or absent) beyond sensor_fault_after_min
- **THEN** the watch shows the sensor-fault nag (not "Not worn?"),
  the phone shows a FAULT with reboot/suspend guidance, and no
  pre-alarm or contact escalation occurs

#### Scenario: One nag per episode
- **WHEN** the condition persists after the nag
- **THEN** no further sensor-fault nags fire until a bpm change (or
  baseline reset) re-arms the detector

#### Scenario: Still wearer stays with the pulse ladder
- **WHEN** the pulse signal is absent and the wearer is also still
  beyond the stillness threshold
- **THEN** the pulse-loss hunt/ladder (or the not-worn nag, per the
  removal signature) owns the episode — the sensor-fault nag stays
  silent during hunts and active stages
