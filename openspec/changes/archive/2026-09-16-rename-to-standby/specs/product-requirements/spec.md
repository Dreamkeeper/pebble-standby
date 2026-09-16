# Delta: product-requirements — product identity and positioning

## ADDED Requirements

### Requirement: Product identity and positioning
The product SHALL be named **Standby** and communicated through the
Core Job it performs — "have the people I choose know within minutes
if I become unresponsive" — with the one-liner "an unresponsiveness
alarm for Pebble: if your pulse signal and movement stop, the people
you choose are alerted within minutes; no automatic emergency call".
The target segment is unchanged: people whose emergency plan is a
specific person or organisation rather than an ambulance, with
cryonicists (standby-organisation escalation) as the first and
flagship sub-segment; cryonics SHALL be presented as the flagship story,
not as one entry in a list of applications. The name SHALL NOT imply
vital-sign or medical monitoring. Machine identifiers (Android
application id and package, watchapp UUID, server module and container
names) SHALL keep their historical values so installs and enrollments
carry over across the rename.

#### Scenario: A Pebble owner reads the listing
- **WHEN** someone outside the cryonics community reads the name and
  one-liner
- **THEN** they can tell what the product does and whether it is for
  them without knowing what cryonics is

#### Scenario: Existing installs survive the rename
- **WHEN** a user updates the companion and watchapp across the rename
- **THEN** enrollment, contacts, settings and soak history are intact
  and the apps show the new name
