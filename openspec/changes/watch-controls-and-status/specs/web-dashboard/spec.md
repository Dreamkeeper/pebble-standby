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

#### Scenario: Repeated phone report with a cached battery
- **WHEN** the companion forwards a cached watch percentage
- **THEN** it SHALL send the original observation time, and the fleet SHALL show its age beside the percentage on initial load and refresh. A valid percentage without a timestamp SHALL show “time unknown”.

#### Scenario: Repeated values and delayed records
- **WHEN** a new watch message reports the same percentage
- **THEN** the companion SHALL advance the observation time. For a buffered worker record it SHALL use the record epoch and retain a newer known observation if an older record arrives later.
