# Delta: watch-phone-protocol

## ADDED Requirements

### Requirement: The phone can suspend and resume the watch
`PMSG_SUSPEND_SET` (phone → watch) SHALL carry the suspension length in
minutes in SECONDS (0 = resume now) and the auto-resume flag in
DETECTOR. The watch SHALL confirm either way through the existing
`PMSG_SUSPENDED` (duration, or 0 when ended).

#### Scenario: Phone pause reaches the watch
- **WHEN** the phone sends `PMSG_SUSPEND_SET` with 840 minutes and
  auto-resume
- **THEN** the watch suspends, shows "Until worn again", and replies
  `PMSG_SUSPENDED` with the duration
