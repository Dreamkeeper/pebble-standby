# Tasks

- [x] 1. `PebbleAppPolicy` pure object: mode parsing, store-mode
       resolution, effective sync cadence, sync-due clock, provisioning
       rule on proof-of-any-kind, sync-miss fault; JVM unit tests.
- [x] 2. Settings: `pebbleAppMode` (auto/patched/store), `dlEverSeen`;
       advanced-settings field in MainActivity.
- [x] 3. MonitorService: proof stamp from records and open-app messages;
       provisioning watchdog via the policy; store-mode sync liveness
       with two-miss fault; status line shows "store app".
- [x] 4. Debug S5 card describes the mode; soak report tags store mode.
- [x] 5. Companion 0.6.3 (42) built, tests green, dist updated.
- [ ] 6. Owner verification: set mode to `store` on the current phone
       (patched app) and watch a full hour — no provisioning fault, one
       sync launch, "store app" in the notification; then back to `auto`
       — mode reads PATCHED (records seen). First beta tester on the
       stock app: no fault loop, hourly sync, alarm drill via the
       launch path.
