# Delta: companion-ui (new capability)

## ADDED Requirements

### Requirement: The home screen is a coverage verdict
The companion's home screen SHALL state the wearer's coverage as one of
six states — Covered, Paused, Needs attention, Check-in, Alarm, Not set
up — with one sentence of reason and at most one action, computed by a
single state model that the ongoing notification and the diagnostics
screen also render. Precedence SHALL be Alarm, Check-in, Not set up,
Needs attention, Paused, Covered. The home screen SHALL contain no text
input.

#### Scenario: Everything is fine
- **WHEN** the watch is linked, worker data is recent, no alert is
  active, contacts exist and the server is reachable
- **THEN** home shows "Covered" with the time since, and no action

#### Scenario: Alerts would reach nobody
- **WHEN** no contact is configured
- **THEN** home shows "Needs attention", the reason "Alerts reach
  nobody", and the action "Add a contact", and the ongoing notification
  carries the same words

#### Scenario: Monitoring is paused
- **WHEN** the watch is suspended, in carry mode or on the charger
- **THEN** home shows "Paused" with the cause and remaining time, and
  the word "Suspended" appears nowhere on the screen

### Requirement: One vocabulary and palette on the phone
Wearer-facing text SHALL use the design pass vocabulary: detector ids
are rendered as "No pulse signal", "No movement", "Hard impact",
"Missed check-in", "Watch not worn?", "SOS"; the countdown stage as
"Alarm in N s"; and the words worker, DataLogging, S1–S7, heap, flush,
escalation, degraded, PRE_ALARM and raw detector ids SHALL NOT appear
outside Diagnostics. Red (`error`) SHALL be used only for Countdown and
Alarm; "Needs attention" and Check-in SHALL use the theme's caution
token; Paused the tertiary role; Covered the primary role. No text
SHALL claim detection of cardiac arrest or any medical condition.

#### Scenario: A string audit passes
- **WHEN** the wearer-facing string resources and notification texts
  are scanned for the jargon list
- **THEN** no match is found

#### Scenario: A pulse-signal alarm is shown
- **WHEN** the watch escalates the pulse detector
- **THEN** the phone shows "No pulse signal", never "pulse" alone or
  any medical claim

### Requirement: Guided first run ends in Covered
On first launch the companion SHALL guide the wearer through: what this
is (one-liner and the two guardrails: no automatic emergency call;
loss of pulse signal also happens with a loose strap), the watch, the
server (enrol by code, or no server with the fallback bot), the first
contact, permissions with live status that open the exact system page,
and a fire drill that waits for an acknowledgement. The flow SHALL be
re-runnable from Settings without clearing enrolment unless the server
step is used.

#### Scenario: A new wearer reaches Covered
- **WHEN** a wearer completes all six steps
- **THEN** home shows "Covered" and a drill acknowledgement is recorded

#### Scenario: Permissions are checked where they are granted
- **WHEN** a permission row is tapped
- **THEN** the exact system settings page opens, and on return the row
  reflects the new status without a restart

### Requirement: The alarm screen is readable at arm's length at night
The alarm screen SHALL show the state word, the cause in wearer words,
a display-size countdown, a full-width cancel control at least 72 dp
tall, and the emergency number as a secondary action, with high
contrast independent of theme and dynamic colour. Cancelling SHALL
confirm by vibration and open the "what happened?" sheet. The
emergency number SHALL default from the phone's network or SIM country,
with an override in Advanced settings.

#### Scenario: Night test
- **WHEN** an alarm fires on a phone lying face down in a dark room
- **THEN** the screen turns on, the cancel control occupies the bottom
  third at full width, and a cancel is confirmed by a distinct vibration

#### Scenario: Default emergency number
- **WHEN** no override is set and the network country is one with a
  known number
- **THEN** the alarm screen offers that number ("Call 112" in most of
  Europe, "Call 911" in the US)

### Requirement: Developer instrumentation is out of the wearer's path
Soak counters, drills, the sensor lab, logs and diagnostics bundles
SHALL live under Settings → Diagnostics. The soak card, Share and Send
SHALL be visible to every wearer; drills and the sensor lab SHALL be
hidden until unlocked by a long-press on the version line. Every
setting and drill reachable before this change SHALL remain reachable.

#### Scenario: A beta tester opens Diagnostics
- **WHEN** Diagnostics has not been unlocked
- **THEN** it shows the soak card, Share and Send to my server, and no
  drill or lab controls

#### Scenario: Parity
- **WHEN** the list of settings and drills of companion 0.6.8 is checked
  against the new navigation
- **THEN** each has a route

### Requirement: Fallback fields follow their availability
The SMS fallback fields SHALL be hidden while no build flavour grants
the SMS permissions; the phone-direct Telegram fallback and manual
token entry SHALL be present only under Advanced settings and labelled
as fallbacks.

#### Scenario: Sideload build
- **WHEN** the sideload flavour is installed
- **THEN** no SMS contact field is shown anywhere
