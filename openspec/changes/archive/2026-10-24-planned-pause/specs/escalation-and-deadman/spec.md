# Delta: escalation-and-deadman

## ADDED Requirements

### Requirement: A declared offline window can be cancelled
An offline window declared with a duration of 0 SHALL clear any active
window at once, after which the normal dead-man rules apply from the
next heartbeat. Durations SHALL be limited to 7 days. The response
SHALL carry the dead-man state in force.

#### Scenario: Wearer resumes early
- **WHEN** the phone declares a 6 h window and then a window of 0
- **THEN** the state is no longer offline_declared and the next
  silence is judged by the usual grace
