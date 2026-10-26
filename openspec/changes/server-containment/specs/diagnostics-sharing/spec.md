# Delta: diagnostics-sharing

## ADDED Requirements

### Requirement: Diagnostics storage per wearer is bounded
A wearer SHALL hold at most 20 bundles and 200 MiB in total (both
configurable); an upload beyond either limit SHALL be refused with 429
until older bundles are deleted or expire. The limits SHALL be passed
through the compose file so an operator's `.env` takes effect.

#### Scenario: Quota reached
- **WHEN** a wearer holding the maximum number of bundles uploads another
- **THEN** the server answers 429 and stores nothing
