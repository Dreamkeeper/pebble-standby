# Design

## D1. One record pipeline, two transports

`WorkerRecords.process(context, bytes, transport)` is the single entry
point: parse → replay check → log → `ACTION_WORKER_HEARTBEAT` intent →
persist the epoch. The PebbleKit2 callback splits a batch into items
and calls it per item; the classic receiver calls it per broadcast.
Everything downstream (watchdogs, soak counters, alarm recovery,
store-app auto-detection via `dlEverSeen`) is untouched and transport
agnostic. The model is pure Kotlin and unit-tested; only `process`
touches Android.

## D2. Replay protection by epoch

PebbleKit2's contract: "the companion must tolerate a batch that it
gets more than one time". Worker records are written once a minute
with the wall-clock epoch, strictly increasing under normal operation,
so `epoch > lastProcessed` is a sufficient and O(1) test, persisted in
SharedPreferences. A watch clock set *back* would otherwise silence the
channel until the clock caught up; a backward step of more than one
hour is therefore accepted as a new timeline. Residual: a backward
correction of under an hour drops records until the epoch passes the
old maximum — at most an hour of telemetry, never an alarm, since the
live AppMessage path and the episode ring carry alarms.

## D3. Ack means processed; never Nack what can never succeed

Nack asks the Pebble app to keep and resend. That is right for "valid
record, MonitorService could not be started" and wrong for data we can
never consume — a foreign UUID, an unknown tag, an item size the parser
does not know: those are Acked (discarded) with a warning, otherwise
the Pebble app would burn its retries and hold garbage. A mixed batch
is Nacked if any new record failed to deliver; the redelivery is safe
because the records that did get through are now replays.

## D4. Classic receiver kept

The patched Pebble app some installs run today forwards by classic
broadcast. Removing the receiver would cut their telemetry on upgrade.
It costs a manifest entry and ten lines now that parsing is shared;
remove it once #378 has been in a store release for a while.

## D5. Routing needs nothing new on the watch

#378 resolves the companion from the PBW's
`companionApp.android.apps[].package`; `watchapp/package.json` has
declared `org.cryomonitor.companion` there since the PebbleKit2
AppMessage work. The session tag (0xC202) and the 16-byte item size
are unchanged.
