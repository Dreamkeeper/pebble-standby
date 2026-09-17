# Tasks

- [x] 1. `WorkerRecords`: pure parse/split/replay/describe + `process`
       hand-off; `WorkerRecordsTest`.
- [x] 2. PebbleKit2 listener: `onDataLogReceived` /
       `onDataLogSessionFinished` with the Ack policy of design D3.
- [x] 3. Classic `DataLogReceiver` delegates to `WorkerRecords`.
- [x] 4. `io.rebble.pebblekit2:client` 1.3.1; `Protocol.DL_TAG`;
       `SettingsStore.dlLastEpoch`; transport shown in the record log
       line and the Debug S5 card; companion 0.6.5 (44).
- [x] 5. Test Pebble app APK: upstream master + mobileapp#378.
- [ ] 6. Owner verification: install the test Pebble app and companion
       0.6.5; with the watchapp closed, the Debug S5 card shows
       "records=N via PebbleKit2" within ~10 min and the mode reads
       PATCHED; reboot the phone mid-spool and confirm no duplicate
       counting; report results on mobileapp#378.
