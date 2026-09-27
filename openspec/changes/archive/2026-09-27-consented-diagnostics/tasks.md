# Tasks

- [x] 1. Companion: `DiagnosticsBundle` (range selection by file date,
       redaction, zip with manifest + soak report) and JVM tests.
- [x] 2. Companion: soak report moved to `SoakStats.render()` so the
       bundle and the debug screen share it.
- [x] 3. Companion: log screen — range choice for Share (zip via the
       FileProvider), "Send to server" with the confirmation dialog,
       pending-request dialog with Send / Decline.
- [x] 4. Companion: `ServerClient.uploadDiagnostics` /
       `declineDiagnostics` (+ MockWebServer tests);
       `MonitorService` handles `diag_request:<id>:<days>` by storing
       the request and notifying. Companion 0.6.8.
- [x] 5. Server: schema v4, storage, `POST /api/v1/diagnostics`,
       decline endpoint, retention purge (startup + hourly).
- [x] 6. Server: dashboard section (uploads, requests, request button,
       admin download/delete) + audit events. Server 0.3.3.
- [x] 7. Server tests: auth, zip check, size limit, request →
       fulfilled/declined, retention, responder refused, admin download.
- [x] 8. Docs: server/android READMEs, SOAK-TEST post-mortem section,
       `.env.example`; build the APK into `dist/`; the timeline tool
       reads a bundle `.zip` directly.
- [ ] 9. Owner verification: deploy server 0.3.3 to the NAS, install
       companion 0.6.8, request diagnostics from the dashboard, Send
       from the phone, download from the dashboard.
