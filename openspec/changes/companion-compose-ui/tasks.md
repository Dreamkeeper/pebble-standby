# Tasks

- [ ] 1. Foundations: Compose BOM + Material 3, `CmTheme` with dynamic
       colour, brand fallback and the caution tokens; `CoverageState`
       model with precedence, reasons and actions; JVM tests.
- [ ] 2. Strings: move wearer-facing text to resources, `DetectorNames`
       mapping, Diagnostics strings in a separate file, jargon-audit
       test; notification texts reworded, channel names renamed.
- [ ] 3. Host Activity + NavHost; Home with the four cards; the old
       main-screen fields relocated to Settings → Advanced (SMS fields
       hidden on flavours without SMS permissions).
- [ ] 4. Settings: Server (enrol / change / re-enrol), Pebble app mode,
       Permissions rows with live status, Advanced (wearer name,
       emergency override, watch sync, fallback bot + test).
- [ ] 5. Contacts & safety net as a composable: tiers explained in
       place, channel icons, inline "where is my chat id", fire drill.
- [ ] 6. Alarm screen: composable content in `AlarmActivity`, display
       countdown, 72 dp cancel, emergency number default by country
       (JVM-tested) with override, cancel vibration, "what happened?"
       sheet.
- [ ] 7. Onboarding routes 1–6, first-run gate, "Set up again" in
       Settings; drill step observes the acknowledgement.
- [ ] 8. Diagnostics: debug screen moved under Settings as composables
       (soak card, Share, Send; drills + sensor lab + reboot drill
       behind the long-press unlock); parity check of routes.
- [ ] 9. Remove the view-based Activities; manifest, notification
       intents and FileProvider paths updated; companion 0.7.0; README
       and android/README; `dist/` APK.
- [ ] 10. Acceptance (DESIGN §9): glance, first-run, jargon, night,
        theme, scale and parity checks recorded in SOAK-TEST.md.
- [ ] 11. Owner verification: fresh install on the phone through
        onboarding to Covered with a real drill; a night test; a week
        of daily use with the new Home.
