# Delta: suspension

## MODIFIED Requirements

### Requirement: Expiry re-arms cleanly
At expiry or resume, all detector baselines SHALL reset to "now" so the
stillness and pulse-absence accumulated during suspension cannot
instantly trigger an alert. The pulse-loss ladder SHALL stay disarmed
until the first valid reading after the hold ended (resume, expiry or
unplug): silence without a single reading is not a lost pulse but a
watch that is not yet read (wet or loose wrist, or not on a wrist), and
the not-worn and sensor nags own it (field 2026-10-12 07:30). The
wearer is notified (vibration + phone message); if wear signals remain
absent after expiry, the not-worn nag ladder applies — never a contact
alarm.

#### Scenario: Expiry does not instantly alarm
- **WHEN** a suspension expires while the watch is still off-wrist
- **THEN** SUSPEND_EXPIRED is emitted with no immediate pulse-loss or
  non-motion trigger, and the not-worn nag follows if wear signals stay
  absent

#### Scenario: No reading since the resume is not a lost pulse
- **WHEN** the wearer resumes with SELECT, moves for six minutes with no
  valid reading, then stands still for four minutes
- **THEN** no pulse hunt and no check-in start; after the first valid
  reading the ladder is armed again and a later loss is treated as one
