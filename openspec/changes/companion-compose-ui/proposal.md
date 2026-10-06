# Companion rebuilt in Jetpack Compose against the design pass

## Why

The companion's six screens are hand-written view code that grew with
the field work: the main screen mixes the coverage status with token
fields and a "Debug & feasibility tests" button, setup is spread over
three screens and a notification, developer drills sit in the wearer's
path, and the alarm screen hard-codes its colours. Beta testers will
judge the product by these screens. The design pass
([docs/DESIGN.md](../../../docs/DESIGN.md), owner decisions accepted
2026-10-06) fixed the vocabulary, palette, information architecture,
onboarding and acceptance checks; this change implements them in
Jetpack Compose + Material 3, the current Android toolkit and the one
the Pebble app itself uses.

## What changes

- **Compose + Material 3** replaces the programmatic views, screen by
  screen, one Activity hosting a NavHost. Dynamic colour stays; the
  theme adds the `caution` token pair for "Needs attention".
- **Home** shows the coverage verdict (Covered / Paused / Needs attention
  / Check-in / Alarm / Not set up) with one reason and one action, then
  watch, people and server cards. No text fields on Home.
- **Onboarding** (first run, re-runnable from Settings as "Set up
  again"): what this is → the watch → your server → your people →
  let it run (permissions with live status) → prove it (fire drill that
  waits for the acknowledgement). Ends on Home showing "Covered".
- **Alarm screen** redesigned for 3 a.m.: display-size countdown,
  72 dp full-width I'M OK — CANCEL, emergency number below, same
  "what happened?" sheet; theme-independent high contrast.
- **Settings** gathers server, Pebble app mode, permissions, Advanced
  (wearer name, emergency number override, watch sync, fallback bot)
  and **Diagnostics** (the whole debug screen, moved). Beta testers see
  the soak card, Share and Send; drills and the sensor lab unlock with
  a long-press on the version line.
- **Vocabulary and palette** from DESIGN §3 applied to every
  wearer-facing string and notification; detector ids never shown.
- **Decisions implemented:** emergency number defaults from the SIM
  country with an override; SMS fallback fields hidden until a gateway
  flavour exists; "Paused" for wearers, "Suspended" in specs and logs.
- Companion 0.7.0.

Not in this change: the watch, the server, the dashboard (Basecoat
change follows), any detector or protocol behaviour.

## Impact

- `android/`: Compose BOM and Material 3 dependencies, `CmTheme`,
  `MainActivity` becomes the NavHost host; `EnrollActivity`,
  `ContactsActivity`, `LogActivity`, `DebugActivity` become Compose
  screens; `AlarmActivity` stays a separate Activity (lock-screen flags)
  with Compose content. `MonitorService` notification texts reworded;
  a `CoverageState` model computed in one place and consumed by Home,
  the ongoing notification and Diagnostics. `SettingsStore` gains the
  onboarding-done flag and the emergency-number override.
- Tests: JVM tests for `CoverageState` (state precedence, reasons,
  actions), string audit test (no jargon in wearer-facing resources),
  emergency-number default by country.
- New capability spec `companion-ui`.
