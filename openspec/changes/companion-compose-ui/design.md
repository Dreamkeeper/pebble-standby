# Design

## D1. One state model, three consumers

`CoverageState` is a pure Kotlin value computed from `MonitorService`
facts (watch linked, worker proof age, suspension/charging, server
reachability, contacts count, enrolment, active alert stage, not-worn
and sensor faults). It yields `state` (Covered, Paused, NeedsAttention,
CheckIn, Alarm, NotSetUp), `reason` (one sentence) and `action`
(label + navigation target, or none). Precedence: Alarm > CheckIn >
NotSetUp > NeedsAttention > Paused > Covered. Home's verdict card, the
ongoing notification's one line and the Diagnostics soak card all render
the same object, so the three can never disagree. It is JVM-tested
without Android.

## D2. Compose incrementally, one Activity

`MainActivity` becomes a `ComponentActivity` hosting a `NavHost` with
routes `home`, `contacts`, `settings`, `settings/server`,
`settings/pebble`, `settings/permissions`, `settings/advanced`,
`diagnostics`, `onboarding/{step}`. Old Activities are converted one at
a time into composables and removed when their route exists; Intents
from notifications target the host Activity with a route extra.
`AlarmActivity` stays an Activity because `setShowWhenLocked` /
`setTurnScreenOn` and the full-screen intent need one; its content is a
composable sharing the theme.

## D3. Theme

`CmTheme` wraps `MaterialTheme` with dynamic colour on Android 12+ and
a brand fallback (deep teal primary as the icon). It adds
`caution`/`onCaution` and `cautionContainer`/`onCautionContainer` via a
`CompositionLocal`, amber on both light and dark. The alarm composable
does not read the theme's colour scheme for its background: red
(`error` from the fallback palette) and white text regardless of
dynamic colour or dark mode, so the night test is theme-independent.

## D4. Wearer words live in resources, and a test reads them

All wearer-facing text moves to `strings.xml` (already required for
Compose previews and future localisation). `DetectorNames` maps the
protocol ids to §3.2 words in one place. A JVM test scans
`strings.xml` and the notification texts for the jargon list in
DESIGN §9 (worker, DataLogging, S1–S7, heap, flush, escalation,
degraded, PRE_ALARM, raw detector ids); Diagnostics strings are in a
separate resource file excluded from the scan.

## D5. Onboarding as a route, not a wizard library

Six composable steps under `onboarding/{step}` with a shared progress
header. Step 2 (the watch) reads `PebbleAppPolicy` and the last worker
proof; step 3 reuses the existing enrol logic; step 5 (permissions) uses
one `PermissionRow` composable per permission that opens the exact
system page via the existing helpers and re-checks on resume; step 6
fires the drill through `MonitorService` and observes the server's
acknowledgement via the status endpoint, timing out to "sent — the
acknowledgement will show on Home". `onboardingDone` in `SettingsStore`
gates the first-run redirect; "Set up again" re-enters at step 2 and
never clears enrolment unless the server step is used.

## D6. Emergency number default

`TelephonyManager.networkCountryIso`, falling back to the SIM country,
then the locale, mapped through a small table (112 default for the EU
and most of the world; 911 US/CA; 999 UK/IE; 000 AU; 110/119 JP
ambulance; 111 NZ). The override in Advanced wins when set. The
mapping and precedence are JVM-tested.

## D7. Diagnostics gating

`Diagnostics` shows the soak card, Share and Send to all. A long-press
on the version line toggles `diagnosticsUnlocked` (persisted), which
reveals drills, the sensor lab and the reboot drill. Nothing is
removed; the parity check in DESIGN §9 is a test of route coverage.

## D8. Minimum SDK and footprint

minSdk stays 26. Compose BOM 2026.x with Material 3 1.4 (stable); no
Expressive APIs. Expect the APK to grow by about 2 MB; acceptable for a
sideloaded app, and R8 stays on for release.

## D9. What stays as is

`MonitorService`, `Escalator`, `ServerClient`, `WorkerRecords`,
`PebbleAppPolicy`, `DiagnosticsBundle`, `SoakStats` logic are
untouched except for the texts they emit. The S4 sensor-lab state
machine moves into Diagnostics as a composable with the same
instructions.
