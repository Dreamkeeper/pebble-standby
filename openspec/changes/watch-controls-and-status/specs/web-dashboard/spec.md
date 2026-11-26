## ADDED Requirements

### Requirement: Dashboard colours reflect available evidence
The dashboard SHALL distinguish confirmed watch alarms, fire drills, attention conditions and pauses by text and semantic colour. A healthy phone report SHALL be labelled as phone reporting and a missing first report SHALL be identified separately.

#### Scenario: Drill next to a real alarm
- **WHEN** a wearer has a real watch alarm and a fire drill
- **THEN** the fleet distinguishes their kind and acknowledgement state

### Requirement: Separate phone and watch battery readings
The fleet SHALL show “Phone battery” and “Watch battery (last known)” from each wearer's latest phone report. Each valid level SHALL be shown as a percentage, including 0%. A missing or unknown level SHALL be shown as an em dash.

#### Scenario: The phone has no watch reading
- **WHEN** the phone reports its own battery at 61% and supplies no watch level
- **THEN** both the initial fleet page and its refreshed row show 61% for the phone and — for the watch
