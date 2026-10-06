# Tasks

- [x] 1. Core: `CM_CFG_*` field ids, `cm_apply_config()` with ranges and
       the check-in reschedule side effect; host tests (accept, refuse,
       reschedule, persistence round-trip of the struct).
- [x] 2. Protocol + app + worker: `PMSG_CONFIG_SET`/`PMSG_CONFIG_ACK`
       with field/value/ok, `WMSG_CFG_SET`/`WMSG_CFG_ACK`, persist on
       accept. Watchapp 0.5.9, `dist/` pbw.
- [x] 3. Companion: `WatchConfig` (field table, defaults, pending/acked
       bookkeeping, JVM tests), `Protocol.kt` ids, `MonitorService`
       send-on-open / send-now / ack handling, launch request on change.
- [ ] 4. Owner verification: flip a detector off and on from the phone
       with the watchapp open (ack within seconds), change it with the
       watchapp closed (arrives at the next sync), restart the watch and
       confirm the setting held.
