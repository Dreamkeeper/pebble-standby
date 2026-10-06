# Delta: watch-phone-protocol

## ADDED Requirements

### Requirement: Detector settings are owned by the phone and applied live on the watch
The phone SHALL hold the wearer's detector settings (per-detector
on/off, the thresholds a wearer can reason about, and the ladder
timings) and send each as a field id with a 16-bit value. The watch
SHALL validate the value against the field's range, apply an accepted
value immediately without a worker restart, persist the whole
configuration, and acknowledge with the field, the value in force and
whether the set was accepted. Settings SHALL be sent whenever the
watchapp opens until every field has been acknowledged, and at once
while it is open; the phone SHALL show a setting as pending until the
watch confirms it.

#### Scenario: Turning a detector off reaches the watch
- **WHEN** the wearer switches off the no-movement detector while the
  watchapp is open
- **THEN** the watch acknowledges the field with value 0, and no
  no-movement episode starts afterwards until it is switched on again

#### Scenario: An out-of-range value is refused, not clamped
- **WHEN** the phone sends a time-to-answer of 5 s
- **THEN** the watch keeps its current value, acknowledges with that
  value and ok=0, and the phone shows the value the watch kept

#### Scenario: Settings survive a worker restart
- **WHEN** the watch restarts after settings were acknowledged
- **THEN** the worker runs with the acknowledged settings

#### Scenario: Changing the check-in interval reschedules from now
- **WHEN** the scheduled check-in is enabled with a 4 h interval at 09:00
- **THEN** the next check-in is due at 13:00

#### Scenario: Watchapp closed when a setting changes
- **WHEN** the wearer changes a setting while the watchapp is not open
- **THEN** the phone marks it pending, requests a watchapp launch unless
  another app is on the wearer's screen, and sends it when the watchapp
  opens
