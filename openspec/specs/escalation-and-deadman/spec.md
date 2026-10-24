# Escalation & Dead-Man Specification

## Purpose

Defines how a confirmed alarm reaches humans, and how the system notices
that the phone itself has gone dark. Implemented in
`server/app/escalation.py` and `server/app/deadman.py` (pure state
machines, injected clocks); scenario coverage in `server/tests/`.
Phone-direct fallback lives in the Android companion (`Escalator.kt`).

Settled product principles: humans first (the system never auto-dials
emergency services — the wearer gets a one-tap dialer; contacts get
location and context so THEY can call EMS), and acknowledged-and-retried
delivery, never fire-and-forget.

Alert kinds: watch_alarm (full escalation), phone_silent (advisory),
fault (wearer first), test (fire drill, tagged TEST).

## Requirements

### Requirement: Tiered fan-out with ACK-gated promotion
Escalation SHALL proceed in ordered tiers (default: relatives → CSO
standby team). Each tier fans out to every contact over all of that
contact's channels. If NO contact has acknowledged, the next tier SHALL
activate after promote_after_s (default 600 s, configurable per tier).
Unacknowledged contacts SHALL be re-sent every repeat_after_s (default
1800 s).

#### Scenario: Tier 1 fans out immediately
- **WHEN** a watch_alarm escalation starts
- **THEN** every Tier-1 contact receives one send per configured channel

#### Scenario: No acknowledgement promotes the next tier
- **WHEN** promote_after_s elapses with zero acknowledgements
- **THEN** the next tier's contacts start receiving sends

#### Scenario: First acknowledgement stops promotion but not repeats
- **WHEN** one contact acknowledges
- **THEN** no further tiers are activated
- **AND** other unacknowledged contacts continue receiving repeats
- **AND** duplicate ACKs from the same contact are idempotent

#### Scenario: Resolution silences everything
- **WHEN** the escalation is resolved ("false_alarm" by wearer cancel, or
  "handled" by a responder)
- **THEN** no further sends occur for that escalation

### Requirement: Every outbound message carries a one-tap ACK
Each send SHALL include a unique acknowledgement affordance (Telegram
inline button, signed ACK URL in email/ntfy) that records which contact
acknowledged and when. Acknowledgements SHALL be receivable end to end:
Telegram button presses arrive via bot long-polling (no inbound webhook,
so the public surface does not grow) and are answered so the contact
sees confirmation; ntfy and email ACK links resolve against the public
hostname and confirm in the response. ACK tokens are single-purpose,
unguessable, durable across restarts, scoped to one wearer's one
escalation, and invalid or replayed tokens are rejected without side
effects.

#### Scenario: ACK link records the contact
- **WHEN** a contact taps their ACK link/button
- **THEN** the server marks that contact acknowledged for that
  escalation and logs the event

#### Scenario: Telegram acknowledgement round-trip
- **WHEN** a contact presses the inline acknowledge button in Telegram
- **THEN** the server records the acknowledgement within one polling
  interval (default 30 s, configurable), stops promotion per the
  ACK-gating rules, and the contact receives visible confirmation

#### Scenario: Stale token is inert
- **WHEN** an ACK token for a resolved or unknown escalation is used
- **THEN** nothing changes server-side and the response says the
  acknowledgement is no longer applicable

### Requirement: Server-primary with phone-direct fallback
The companion SHALL post alarms to the server, which owns tiered
escalation over telegram/email/ntfy. Phone-direct SMS fires redundantly
regardless (it reaches contacts without data connectivity); phone-direct
Telegram fires only when the server is unreachable. Wearer cancellation
SHALL retract everywhere: server resolve plus retraction messages on any
phone-direct channel that fired.

#### Scenario: Server down does not lose the alarm
- **WHEN** an ALARM arrives from the watch and the server is unreachable
- **THEN** the companion escalates phone-direct (SMS + Telegram) and
  marks the server unreachable for the mutual watchdog

### Requirement: Graduated dead-man response to phone silence
With phone heartbeats expected every heartbeat_interval_s (default 300),
the server SHALL mark LATE after late_after_missed intervals (default 2;
quiet re-ping, no human contact) and SILENT after silent_after_s (default
1800), raising a phone_silent ADVISORY escalation — explicitly
distinguished in wording from a confirmed watch alarm.

#### Scenario: Missed heartbeats escalate gradually
- **WHEN** the phone misses two heartbeat intervals
- **THEN** the state is LATE and no contact is notified
- **AND** when silent_after_s elapses the state is SILENT and Tier 1
  receives an advisory

#### Scenario: A never-registered phone is not an emergency
- **WHEN** the server has never received a heartbeat (fresh install)
- **THEN** the dead-man state is OK, not SILENT

### Requirement: Legitimate offline situations soften the dead-man
A low-battery warning from the phone SHALL extend the SILENT threshold by
low_battery_extra_s (default 3600), cleared when a heartbeat shows
battery above 20%. A wearer-declared offline window (airplane, subway)
SHALL suppress dead-man escalation until the window ends, after which the
full silence grace restarts from the window end.

#### Scenario: Dying battery buys grace
- **WHEN** the phone sends low_battery_warning and then goes dark
- **THEN** SILENT is deferred by low_battery_extra_s beyond the normal
  threshold

#### Scenario: Declared offline window suppresses escalation
- **WHEN** an offline window is declared for N seconds
- **THEN** no dead-man escalation occurs during the window
- **AND** after it ends the phone has the full normal grace before
  SILENT

### Requirement: Mutual watchdog
The phone SHALL warn the wearer when the server becomes unreachable (and
switch to phone-direct escalation); the server SHALL escalate when the
phone goes silent. When configured (planned, M4), secondary liveness
signals (OwnTracks/Dawarich last location update) SHALL be consulted
before raising phone_silent.

#### Scenario: Server outage is surfaced to the wearer
- **WHEN** server heartbeats start failing
- **THEN** the wearer receives a FAULT notification exactly once per
  outage, and recovery is logged

### Requirement: Endpoints are exposed according to what they reveal
Any endpoint returning wearer data (locations, contact identities, event
log) SHALL require the API token. A liveness endpoint carrying no wearer
data MAY be unauthenticated so uptime monitors and health checks work on
a publicly reachable deployment. Contact acknowledgement links SHALL
authenticate by unguessable per-send token rather than the API token,
because contacts have no credentials.

#### Scenario: Public health probe leaks nothing
- **WHEN** an unauthenticated client requests the health endpoint
- **THEN** it receives only service liveness fields
- **AND** the same client is refused (401) on the status endpoint

### Requirement: Fire drills exercise the real chain
A TEST alarm SHALL traverse the identical code path as watch_alarm —
tiers, channels, ACK tracking — with every message visibly tagged TEST.

#### Scenario: Fire drill reaches contacts with TEST tagging
- **WHEN** the wearer triggers the fire-drill action
- **THEN** contacts receive normally-routed messages prefixed TEST and
  ACKs are tracked as usual

### Requirement: Due sends are delivered through configured channels
When the escalation engine declares a `Send` due, the server SHALL
deliver it through the contact's configured channels: Telegram (bot
message with an inline acknowledge button), ntfy (push notification with
an acknowledge action), and email (message with an acknowledge link). A
send counts as delivered only when the transport accepts it; transport
failures SHALL NOT be silently dropped — the send is retried on the
tier's existing repeat cadence (default 1800 s, configurable per tier)
and the failure is visible in the status payload and the log.

#### Scenario: An alarm reaches a real contact
- **WHEN** an escalation makes a send due for a contact configured with
  Telegram
- **THEN** that contact's Telegram receives a message containing the
  wearer's name, alert kind, detector, location link when available,
  and an inline acknowledge button — TEST-tagged when the alert kind is
  test

#### Scenario: A down transport does not lose the alert
- **WHEN** a channel transport rejects or times out
- **THEN** the failure is recorded and visible in status, other channels
  for the same contact still fire, and the failed channel is retried on
  the repeat cadence

### Requirement: Alarm and dead-man state survive restarts
Escalation state (per-contact sends, acknowledgements, resolution), ACK
tokens, the event log, and every wearer's dead-man baseline SHALL be
persisted to the server's data volume. On startup the server SHALL
restore unresolved escalations and continue their repeat/promotion
timers with elapsed downtime counted (a restart must never reset an
escalation's clocks or resurrect a resolved one). Delivery is
at-least-once across a crash: in the worst case a send accepted just
before a crash may be repeated, and any duplicate SHALL carry the same
escalation identifier.

#### Scenario: Restart during an active escalation
- **WHEN** the server restarts while an unacknowledged escalation is
  active
- **THEN** after startup the escalation is still active, prior
  acknowledgements are still honored, previously issued ACK links still
  work, and the next repeat/promotion happens no later than it would
  have without the restart

#### Scenario: Dead-man survives the restart honestly
- **WHEN** the server restarts after some downtime
- **THEN** each wearer's last-heartbeat baseline is restored from disk
  and the downtime counts toward that wearer's phone silence rather
  than resetting it

### Requirement: Escalation and dead-man state are per-wearer and isolated
Every escalation, dead-man baseline, contact list, tier definition, and
event-log entry SHALL belong to exactly one wearer. One wearer's alarm,
silence, suspension, or configuration SHALL never affect another
wearer's escalation behavior, and no API response authenticated with one
wearer's token may contain another wearer's data. Contacts and tiers
come from the store (managed via the wearer-management API); a wearer
whose configuration has no deliverable contact SHALL be individually
DEGRADED — visible in authenticated status with the wearer named, and on
the public health endpoint only as a count, never a name.

#### Scenario: Two wearers, one alarm
- **WHEN** wearer A's ladder exhausts while wearer B is quiet
- **THEN** only A's tiers escalate, B's contacts receive nothing, and
  B's status is unchanged

#### Scenario: Unconfigured wearer is loud about it, privately
- **WHEN** a wearer has no contact with at least one working channel
  address
- **THEN** authenticated status marks that wearer DEGRADED with a
  reason, public health exposes at most a degraded count, and no
  escalation for that wearer pretends to fan out to nobody

### Requirement: Wearers may receive copies of their own escalations
A wearer with self-notification addresses configured (Telegram chat id,
ntfy topic, and/or email on the wearer record) SHALL receive one labeled
copy per delivery cycle of their own escalations, summarizing which
contacts were notified on which channels (a literal copy of every
individual send would multiply into alert fatigue — N contacts × M
channels per cycle). Configuring an address is the opt-in; removing it
is the opt-out. Wearer copies SHALL carry no acknowledge affordance and
SHALL never satisfy ACK gating — awareness is not response. Copies are
sent for all alert kinds including phone-silent advisories (their main
purpose: a wearer reading on a second device learns their contacts were
just alerted).

#### Scenario: Wearer learns their contacts were paged
- **WHEN** an escalation send fans out for a wearer who configured a
  self-notification address
- **THEN** the wearer's address receives a labeled copy without an ACK
  button, and tier promotion timing is unaffected by its delivery

#### Scenario: No address, no copies
- **WHEN** a wearer has no self-notification address configured
- **THEN** no wearer copies are attempted and nothing is logged as a
  failed send

### Requirement: A declared offline window can be cancelled
An offline window declared with a duration of 0 SHALL clear any active
window at once, after which the normal dead-man rules apply from the
next heartbeat. Durations SHALL be limited to 7 days. The response
SHALL carry the dead-man state in force.

#### Scenario: Wearer resumes early
- **WHEN** the phone declares a 6 h window and then a window of 0
- **THEN** the state is no longer offline_declared and the next
  silence is judged by the usual grace
