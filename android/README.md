# Standby — Android companion

Kotlin foreground-service app paired with the Standby watchapp (UUID
`7f8e2c40-3a55-4d9b-9f21-6b1e0c2d4a90`). It receives the watch's alarm
ladder, raises the full-screen alarm with a cancel window, escalates to
the wearer's contacts (server, Telegram), and watches the watch: link
state, worker liveness, heap, and the phone's own survival across Doze,
reboots and app updates.

## Watch transport

**PebbleKit2** (`io.rebble.pebblekit2:client` 1.3.1) is primary:

- **AppMessage** — the Pebble app binds `PebbleKit2ListenerService`
  when the watchapp opens; `WatchLink` sends through
  `DefaultPebbleSender`. Alarm-path messages are acknowledged
  end-to-end with episode ids.
- **Data logging** — the background worker's once-a-minute heartbeat
  record arrives through `onDataLogReceived` with the watchapp closed
  (needs a Pebble app that forwards data logging:
  [coredevices/mobileapp#378](https://github.com/coredevices/mobileapp/pull/378)).
  Records are parsed, de-duplicated and handed to the service by
  [`WorkerRecords`](app/src/main/java/org/cryomonitor/companion/WorkerRecords.kt);
  a latched ALARM seen only in a record is escalated (spool recovery).

The PebbleKit Classic broadcast transport (`PebbleTransport`,
`DataLogReceiver`) remains as an automatic fallback for older phone
apps. On the stock Pebble app, which forwards no worker data yet, the
companion runs in **store-app mode**
([`PebbleAppPolicy`](app/src/main/java/org/cryomonitor/companion/PebbleAppPolicy.kt)):
liveness comes from a brief hourly watchapp sync instead of records.

The watchapp's `companionApp` declaration in `watchapp/package.json` is
what routes both PebbleKit2 channels to this app — keep its package
name in sync with `applicationId`.

## Layout

| File | Role |
|---|---|
| `MonitorService` | foreground service: watchdogs, heartbeat to the server, alarm handling, soak counters |
| `PebbleKit2ListenerService`, `WatchLink`, `PebbleTransport` | watch transports |
| `WorkerRecords`, `DataLogReceiver` | worker heartbeat records (PebbleKit2 + classic) |
| `AlarmActivity`, `Escalator`, `ServerClient` | alarm UI, contact escalation, self-hosted backend |
| `BootReceiver`, `SoakStats`, `PebbleAppPolicy` | reboot/update recovery, soak instrumentation, store-app mode |
| `DebugActivity`, `LogActivity` | soak card, drills, sensor lab, log viewer/share |
| `EnrollActivity`, `ContactsActivity`, `MainActivity` | enrollment by code, contacts, settings |

Architecture lineage: a Kotlin port of OpenSeizureDetector's
`Android_Pebble_SD` (GPL-3.0).

## Build

Requirements: JDK 17, Android SDK platform 37, **Gradle 9.6+** (AGP
9.3.1, Kotlin 2.4.10 — PebbleKit2 ≥ 1.3 requires compileSdk 37). There
is no Gradle wrapper in this directory; use a local Gradle 9.6
install. Android Studio is not required.

```bash
cd android
gradle test assembleSideloadRelease
# -> app/build/outputs/apk/sideload/release/app-sideload-release.apk
```

Release signing reads `keystore.properties` and `keystore/*.jks`, both
git-ignored; without them the release build is unsigned. Unit tests
(JVM, 27) cover the server client, reboot-drill verdicts, store-app
policy and worker-record parsing/replay protection; under AGP 9 `test`
runs the debug variants.

Flavors: `sideload` is what ships in [`../dist/`](../dist/). `play` is
the same code reserved for a Play Store listing. SMS/call escalation
permissions are currently in neither — they trip Play Protect on
sideloaded builds and will return in a dedicated gateway flavor.

The Android package id stays `org.cryomonitor.companion` (the project's
former name) so existing installs, enrollments and settings carry over.
