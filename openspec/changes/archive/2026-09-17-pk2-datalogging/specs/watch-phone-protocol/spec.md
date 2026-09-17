# Delta: watch-phone-protocol — worker DataLogging over PebbleKit2

## MODIFIED Requirements

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
