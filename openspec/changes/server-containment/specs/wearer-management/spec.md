# Delta: wearer-management

## ADDED Requirements

### Requirement: A wearer's contact list is bounded
A wearer SHALL have at most 10 contacts; creating an eleventh SHALL be
refused with a field error naming the limit. Editing an existing contact
is unaffected.

#### Scenario: The eleventh contact is refused
- **WHEN** a wearer with ten contacts posts a new contact
- **THEN** the server answers 422 with a `contacts` field error and the
  list keeps ten entries

### Requirement: An enrol code is exchanged at most once, even concurrently
The code burn SHALL be a single conditional update whose changed-row
count is the uniqueness check; a concurrent exchange of the same code
SHALL mint no token and SHALL be answered as an already-used code.

#### Scenario: Two overlapping exchanges of one code
- **WHEN** two requests carrying the same valid code both pass the lookup
  before either burns the code
- **THEN** exactly one receives a token and the store holds one token row
  for that wearer

### Requirement: Heartbeat values are finite and bounded
`suspended_until` SHALL be a finite epoch time in range; battery and age
fields SHALL be bounded; a request failing validation SHALL be answered
422 without echoing the offending value; a non-finite value that reaches
the store SHALL render as not suspended rather than fail a page.

#### Scenario: Infinity in a heartbeat
- **WHEN** a heartbeat carries `suspended_until: Infinity`
- **THEN** the server answers 422 and the fleet page still renders
