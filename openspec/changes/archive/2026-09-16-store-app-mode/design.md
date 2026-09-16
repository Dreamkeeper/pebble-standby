# Design

## D1. Detection is "have we ever seen a record", not fingerprinting

The companion cannot ask the phone's Pebble app whether it forwards
DataLogging. But a record is proof positive, and its absence over
time on a connected watch is the only negative evidence there is. So
auto mode is store mode until the first record ever arrives, and
patched from then on (persisted). The cost of the pessimistic default
on a patched install is one hourly sync launch before the first batch
lands — usually never, since records arrive within ~10 min. An
explicit setting exists for testers who know what they run.

## D2. One notion of "worker proof"

`workerLastProofT` is stamped by both a record and an open-app
message. The provisioning watchdog is now a pure function of that
stamp; the eviction watchdog keeps its record-only arming because it
measures the *cadence* of records, which the stock app never has.
This also fixes a latent bug independent of the beta: on any phone,
after a self-heal launch the open-app message cleared the fault flag
but not the record-based condition, so the fault re-fired.

## D3. Liveness in store mode = the sync launch

The periodic sync launch already exists (owner decision 2026-08-27):
the watchapp opens for a few seconds, reports status and battery, the
auto-launch guard returns the watchface. In store mode it is promoted
from convenience to the liveness signal: hourly if the user left the
interval at 0, otherwise the user's interval, counted from service
start on a fresh install. A launch that yields no watch data is a
miss; two in a row raise one FAULT notification (soak `worker-faults`),
cleared by the next successful launch. The self-heal launch itself
re-arms a dead worker (the watchapp's ensure-worker-running path), so
the fault and the remedy are the same act.

## D4. What store mode gives up, stated in the UI

No DataLogging alarm recovery (the live launch path is the only alarm
channel), no per-minute worker records (no heap/quality/hunt
telemetry, no `worker_log_timeline` post-mortems), watch age granular
to the sync interval. The notification shows "store app", the Debug
S5 card describes the mode and cadence, the soak report tags
`dl-records` with "(store-app mode)". Nothing pretends.
