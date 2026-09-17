# Worker DataLogging over PebbleKit2

## Why

Upstream closed our classic-broadcast forwarding PR (mobileapp#386,
2026-09-16: "This is going through pebblekit 2.0") — as they had closed
the near-identical #292 in July. The accepted path already exists:

- **Library:** PebbleKit Android 2 (`io.rebble.pebblekit2`) 1.3.0 added
  data-log delivery over the bound listener service:
  `onDataLogReceived` / `onDataLogSessionFinished`, with Ack/Nack.
- **App:** mobileapp#378 (open; the maintainer is committing to it)
  forwards third-party sessions through that API, routed to the
  package the watchapp declares in `companionApp.android.apps[]` —
  which our `package.json` already names.

It is also a better channel than the one we proposed: the Pebble app
keeps a batch until the companion acknowledges it and retries a bounded
number of times; delivery is targeted at our package rather than
broadcast to every app; the bind wakes the companion with the watchapp
closed. For the alarm-recovery role of the spool, "retried until
acknowledged" replaces "best effort".

## What changes

- Companion depends on `io.rebble.pebblekit2:client:1.3.1` and
  overrides the two data-log callbacks in the existing listener service.
- One shared, pure `WorkerRecords` model: record parsing (8/14/16 B),
  batch splitting, replay protection, log line, hand-off to
  MonitorService. The classic broadcast receiver now delegates to it
  and stays as a fallback for an older patched Pebble app.
- **Replay protection:** PebbleKit2 may redeliver a batch; records
  carry a strictly increasing epoch, so anything not newer than the
  last processed record is dropped (a >1 h backward step is treated as
  a watch clock correction, not a replay). The persisted episode ring
  already protects the alarm path; this protects the counters, the
  watchdogs and the log.
- **Ack policy:** Ack = processed. Data we can never use (foreign
  watchapp, unknown tag, unusable item size) is Acked so the Pebble app
  discards it; only a valid new record that could not be handed to
  MonitorService is Nacked.
- Debug S5 card and the record log line name the transport.
- The protocol spec's heartbeat requirement is corrected (it still
  described tag 0xC201 and the 8-byte record).

## Impact

- Specs: `watch-phone-protocol` (MODIFIED "Watch heartbeats provide
  phone-side liveness").
- Code: `WorkerRecords.kt` (new), `PebbleKit2ListenerService`,
  `DataLogReceiver`, `Protocol`, `SettingsStore`, `MonitorService`,
  `DebugActivity`, `build.gradle.kts`; tests `WorkerRecordsTest`.
- Release: companion 0.6.5 (44). Watchapp unchanged.
- Verification needs a Pebble app build containing mobileapp#378
  (test APK built from upstream master + that commit).
