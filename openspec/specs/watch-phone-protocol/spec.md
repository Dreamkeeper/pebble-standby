# Watch ↔ Phone Protocol Specification

## Purpose

Defines every message between the watchapp and the Android companion, and
the transports that carry them. The wire constants are mirrored in code at
`watchapp/src/core/protocol.h` (C) and `android/.../Protocol.kt` +
`PebbleTransport.kt` (Kotlin). Watchapp UUID:
`7f8e2c40-3a55-4d9b-9f21-6b1e0c2d4a90`.

AppMessage keys (`messageKeys` in package.json — fixed numbers, never
renumber): MSG_TYPE=1 (uint8), DETECTOR=2 (uint8), STAGE=3 (uint8),
SECONDS=4 (uint16), CANCEL_REASON=5 (uint8), HEARTBEAT_SEQ=6 (uint16),
WATCH_BATTERY=7 (uint8 %), HR_BPM=8 (uint8, 0=no signal),
SUSPEND_REMAINING_S=9 (uint16), CFG_BLOB=10 (bytes, packed cm_config).

Message types (MSG_TYPE): HEARTBEAT=1 (watch→phone, 1/min while app
open), PRE_ALARM=2 (watch→phone, countdown started), ALARM=3
(watch→phone, ladder exhausted), CANCEL=4 (watch→phone, with
CANCEL_REASON), SUSPENDED=5 (watch→phone), CONFIG=6 (phone→watch,
CFG_BLOB), CONFIG_ACK=7 (watch→phone), USER_OK_REMOTE=8 (phone→watch),
SET_DEBUG=9 (phone→watch, SECONDS carries 0/1).

## Requirements

### Requirement: Protocol mirrors change together
Any change to message types, key numbers, payload shapes, or persist/WMSG
constants SHALL update `protocol.h`, `Protocol.kt`/`PebbleTransport.kt`,
and this spec in the same change. (Both field bugs shipped so far —
the companionApp schema mismatch and a missing Kotlin constant — were
violations of this rule.)

#### Scenario: A new message type lands consistently
- **WHEN** a change adds or modifies a PMSG/WMSG/persist constant
- **THEN** the C header, the Kotlin mirror, and this spec all change in
  the same proposal, and the tasks include rebuilding both apps

### Requirement: PebbleKit2 is the primary transport
The companion SHALL implement the PebbleKit2 contract: a
`BasePebbleListenerService` exported with the
`io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH` intent filter (inbound
data, app opened/closed callbacks) and `DefaultPebbleSender` outbound.
The watchapp SHALL declare `companionApp.android.apps[].package =
"org.cryomonitor.companion"` in package.json using the object schema — a
bare string breaks the Core app's .pbw parser and a declared-but-
unimplemented companion makes the Core app NACK all messages.

#### Scenario: Core app binds the companion when the watchapp opens
- **WHEN** the wearer opens the watchapp on the watch
- **THEN** the Core mobile app binds the companion's listener service and
  delivers onAppOpened with the watch identifier

### Requirement: PebbleKit Classic is the automatic fallback
The companion SHALL keep the Classic broadcast-intent transport
registered at runtime (`com.getpebble.action.app.SEND` / `.RECEIVE` /
`.RECEIVE_ACK`; extras `uuid`, `transaction_id`, `msg_data` in the
classic PebbleDictionary JSON encoding). Outbound sends SHALL fall back
to Classic only on transport-level PebbleKit2 unavailability (old phone
app), NOT when the watch is disconnected or NACKs — watch-state results
are authoritative.

#### Scenario: Old phone app still works
- **WHEN** the installed Pebble phone app lacks PebbleKit2 support
- **THEN** watch messages arrive via Classic broadcasts and outbound
  sends succeed via the Classic SEND intent

### Requirement: Alarm-path messages are ordered and complete
The watch SHALL send PRE_ALARM when a COUNTDOWN starts and ALARM when it
expires; the phone SHALL treat ALARM as authoritative even if PRE_ALARM
was missed. CANCEL retracts both at any point and carries the reason.

#### Scenario: Remote cancellation round-trip
- **WHEN** the wearer cancels on the phone (USER_OK_REMOTE sent to watch)
- **THEN** the watch clears its ladder and replies with CANCEL
- **AND** the phone retracts escalation exactly once

### Requirement: Only attention-demanding events may take the screen
Launching the foreground app hides whatever watchface the wearer chose,
so the worker SHALL launch it only for events the wearer must see or act
on immediately: CHECKIN_START, COUNTDOWN_START, ALARM, and
CHECKIN_REMINDER (which occurs only when the wearer opted into scheduled
check-ins). Informational events — ALERT_CANCELLED, NOTWORN_NAG,
SUSPEND_EXPIRED, AUTO_RESUMED — SHALL be delivered by AppWorkerMessage
only, reaching the app when it already happens to be open. An app
launched by the worker SHALL return to the watchface once its alert
ends. Consequence to accept: informational events are silent on the
watch while the app is closed until the DataLogging→companion path
lands (M0 spike S5), after which the phone delivers them.

#### Scenario: A cancelled check-in does not hijack the watchface
- **WHEN** motion dismisses a pulse-loss alert
- **THEN** the watchface remains (or is restored) rather than the app
  being launched to announce the cancellation

### Requirement: Worker alarms survive the launch gap
Because the background worker cannot vibrate, draw UI, or send
AppMessage, any action that does launch the app SHALL be parked in
persist storage (PK_PENDING_ACTION=2) before `worker_launch_app()`; the
foreground app consumes it on launch, or live via WMSG_ACTION if already
open. Worker↔app
messages use AppWorkerMessage types: ACTION=1, USER_OK=2, SUSPEND=3,
RESUME=4, SOS=5, STATUS_REQ=6, STATUS=7, SET_DEBUG=8. Persist keys:
CONFIG=1, PENDING_ACTION=2, MODE=3, SUSPEND_UNTIL=4,
SUSPEND_AUTORESUME=5, DEBUG=6.

#### Scenario: Alarm reaches the phone from a closed watchapp
- **WHEN** the worker's ladder needs the CHECKIN UI while the foreground
  app is closed
- **THEN** the action is persisted, the app is launched by the worker,
  picks the action up, vibrates, and sends the corresponding PMSG

### Requirement: Watch heartbeats provide phone-side liveness
While the foreground watchapp is open it SHALL send HEARTBEAT once per
minute (sequence, battery). The worker SHALL log a DataLogging record
every 60 s (session tag 0xC202, 16-byte v3 item: `epoch_s u32 LE`,
stage in the low nibble and stage detector in the high nibble of one
byte, battery, bpm, suspended, `change_age_s u16`, `motion_age_s u16`,
diagnostic flags, free heap / 64, `episode u16`; the companion SHALL
also parse the 14- and 8-byte predecessors) as the audit trail, the
backup liveness channel and the authoritative ALARM recovery path.

The companion SHALL receive these records through PebbleKit2 data-log
delivery (`io.rebble.pebblekit2` ≥ 1.3.0: `onDataLogReceived` /
`onDataLogSessionFinished` on the bound listener service), routed by
the `companionApp.android.apps[].package` the watchapp declares, and
SHALL keep accepting the classic `com.getpebble.action.dl` broadcasts
as a fallback. Both transports SHALL feed one record pipeline. Because
PebbleKit2 may deliver a batch more than once, a record whose epoch is
not newer than the last processed record SHALL be dropped, except that
a backward step of more than one hour is accepted as a watch clock
correction. The companion SHALL acknowledge a batch only after every
new record in it was handed to the monitor service; it SHALL
acknowledge (discard) data it can never use — a foreign watchapp, an
unknown tag, an unusable item size — and SHALL negatively acknowledge
only a valid new record it could not hand over. The companion's
watch-watchdog raises a FAULT after WATCH_SILENT_AFTER_S (default
300 s) without watch data.

#### Scenario: Worker eviction is surfaced, not silent
- **WHEN** no watch data has arrived for WATCH_SILENT_AFTER_S while
  Bluetooth remains connected
- **THEN** the companion notifies the wearer of a probable worker
  eviction and attempts a watchapp relaunch

#### Scenario: Records arrive with the watchapp closed
- **WHEN** the Pebble app forwards data logging over PebbleKit2 and the
  watchapp is closed
- **THEN** the companion is woken by the bind, processes each record in
  the batch in logging order, acknowledges the batch, and the Debug
  screen shows the record count with transport "PebbleKit2"

#### Scenario: A redelivered batch changes nothing
- **WHEN** the Pebble app sends the same batch a second time
- **THEN** every record in it is dropped as a replay, no counter,
  watchdog or alarm path runs twice, and the batch is acknowledged

#### Scenario: Unusable data is discarded, not retried
- **WHEN** a data-log batch arrives for another watchapp, with an
  unknown tag, or with an item size the companion cannot parse
- **THEN** the companion acknowledges it so the Pebble app discards it

### Requirement: Latency drill measures the real alarm path on demand
The system SHALL provide an operator-triggered latency drill that
exercises the production alarm path end to end: worker fire → persist
handoff → `worker_launch_app()` → foreground vibration → AppMessage to
the phone. The drill SHALL be triggerable from the web dashboard
(admin; queued via the heartbeat command channel, delivered exactly
once) and from the companion app (immediate). The worker SHALL wait a
fixed arming delay (default 10 s) after the watchapp exits so the
measured launch is a genuine cold start. Results SHALL be recorded as
a server event carrying the watch-measured launch milliseconds, the
phone-side round-trip estimate, and the phone model.

#### Scenario: Dashboard-triggered drill produces a measurement
- **WHEN** an admin queues a latency drill for a wearer
- **THEN** the phone receives the command on its next heartbeat, at
  most once
- **AND** within the arming delay plus a few seconds the watch
  vibrates, shows the launch latency, and a `latency_drill` event with
  launch_ms and phone_model appears in the wearer's event feed

#### Scenario: Drill leaves no residue
- **WHEN** a latency drill completes
- **THEN** no ladder stage, escalation, or persisted pending action
  remains, and the watchapp returns to the watchface shortly after
  showing the result

### Requirement: Guided sensor lab streams raw HR telemetry on demand
The companion SHALL be able to start and stop a sensor-lab mode on the
watch (PMSG_HR_LAB). While active: the worker SHALL sample the raw HR
metric in burst mode and relay, every 2 s, the raw peek value, the
seconds since the last HealthService HR event, and its free heap to the
companion via the open watchapp; the detector core SHALL be held
silently (pre-alarm stages cancel, a latched ALARM survives, baselines
reset on release) so deliberate off-wrist test stages cannot start
ladders or nags; and the watchapp SHALL stay open (exempt from the
auto-launch guard) to relay samples, returning to the watchface when
the lab ends. The companion SHALL guide the wearer through the staged
wear conditions, record all samples, and produce a shareable per-stage
summary with the full sample log.

#### Scenario: Off-wrist lab stages raise no alarms
- **WHEN** the sensor lab is active and the watch lies on the table for
  minutes without pulse or motion
- **THEN** no check-in, hunt, nag, or alarm is emitted, and monitoring
  resumes with fresh baselines when the lab ends

#### Scenario: The lab yields a per-stage sensor characterization
- **WHEN** the wearer completes the guided stages
- **THEN** the companion stores and can share a summary showing, per
  stage, the sample count, nonzero-reading share, bpm range, and median
  event age — the data that decides the phantom-pulse question
