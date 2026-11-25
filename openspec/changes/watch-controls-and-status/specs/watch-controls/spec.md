## ADDED Requirements

### Requirement: Stable controls on the main watch screen
One short UP SHALL immediately request a 30-minute pause with auto-resume, regardless of any previous pause choice. A short DOWN SHALL open a duration menu, with BACK abandoning the menu and SELECT applying the highlighted duration. Long UP and long DOWN on the main screen SHALL retain carry mode and manual SOS.

#### Scenario: Shower after a different pause
- **WHEN** the wearer has resumed after a two-hour pause and taps UP once on Home
- **THEN** a 30-minute pause starts directly

#### Scenario: Browse without pausing
- **WHEN** the wearer opens the menu and presses BACK
- **THEN** the watch returns to its existing state

### Requirement: Action and feedback on alert screens
The foreground SHALL show “Are you OK?” during check-in, a labelled countdown before alarm, and “Alarm active” at alarm. The middle button SHALL answer in every phase. Timer redraws SHALL preserve pending and failed cancellation messages. Local monitoring, pauses and attention SHALL be distinguishable by words on every supported display.

#### Scenario: Acknowledgement is delayed
- **WHEN** SELECT requests cancellation and the display timer ticks
- **THEN** the request remains visible until the worker confirms or the retry prompt appears
