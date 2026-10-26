# Delta: escalation-and-deadman

## ADDED Requirements

### Requirement: One wearer's alarms are contained
The server SHALL bound what a single wearer token can make the pump do:
at most 3 open escalations per wearer (a further alarm from that wearer
SHALL fold into the newest open escalation and be recorded as
`alarm_collapsed`), detector text of 1 to 64 printable characters, only
the phone-origin kinds `watch_alarm`, `fault` and `test`, and at most 20
sends per wearer per pump cycle (the remainder SHALL be delivered on the
following cycles and recorded as `send_suppressed`). A transport that
raises SHALL count as a failed send, and one escalation's failure SHALL
never prevent delivery of the escalations after it in the same cycle.

#### Scenario: A fourth distinct alarm folds into the newest
- **WHEN** a wearer with three open escalations posts an alarm with a new
  detector string
- **THEN** no new escalation starts, the response carries the newest open
  escalation's id, and an `alarm_collapsed` event is recorded

#### Scenario: A raising transport does not stall the pump
- **WHEN** a channel raises while delivering one wearer's send
- **THEN** that send is recorded as failed and retried on the repeat
  cadence, and every other wearer's due sends in the cycle are delivered

### Requirement: Disabling a wearer stops its paging
Disabling a wearer SHALL resolve that wearer's open escalations with
resolution `wearer_disabled`, at the moment of disabling and again in the
pump for any escalation found open for a disabled wearer, so that no
further sends or tier promotions occur and a restart cannot resurrect
them.

#### Scenario: Contacts are not paged after a disable
- **WHEN** an admin disables a wearer with an open escalation after its
  first delivery
- **THEN** the escalation is resolved as `wearer_disabled`, no second
  delivery happens on the repeat cadence, and the store holds no
  unresolved escalation for that wearer
