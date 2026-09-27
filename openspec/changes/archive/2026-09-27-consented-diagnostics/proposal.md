# Diagnostics the phone chooses to share

## Why

Every post-mortem so far has needed ADB or a hand-shared log, and both
fell short on 2026-09-27: the in-app Share button keeps only the last
2 MB (about four days), so the day that held all 46 "not worn" nags
(September 19) was missing, and recovering it took a Wi-Fi-only
wireless-debugging session. Beta testers will never have ADB.

The owner's constraint: **data leaves the phone only when the wearer
decides.** The server may ask; it may never pull.

## What changes

1. **Full-range bundle.** Share builds one compressed bundle for a range
   the wearer picks (1, 3, 7 or 14 days; default 7) instead of a 2 MB
   text tail: the daily log files, a manifest, and the soak report.
2. **"Send to my server".** The same bundle can be uploaded to the
   wearer's own Standby server over the existing authenticated phone
   connection — only after a confirmation that shows the range, size,
   file list and what was redacted. The server keeps it per wearer for
   30 days (configurable) and lists it on the wearer's dashboard page;
   admins can download or delete it.
3. **Request, not pull.** An admin can click "Request diagnostics" on
   the dashboard. The phone receives it through the existing leased
   command channel and shows a notification; the upload happens only if
   the wearer taps Send in the app. Declining is reported back and shown
   on the dashboard.
4. **Redaction on the phone.** Before anything leaves the phone, stored
   secrets (server token, fallback bot token) and anything shaped like a
   bot token or bearer credential are removed, coordinates are rounded
   to two decimals (about 1 km), and Telegram chat ids are masked to
   their last three digits. The confirmation lists these rules.

Not included: automatic upload after a fault (item 5 of the discussion;
left out on purpose).

## Impact

- Companion: new `DiagnosticsBundle` (builder + redaction, JVM-tested),
  log screen gains range choice and "Send to server", request handling
  in `MonitorService`, `ServerClient.uploadDiagnostics/declineDiagnostics`.
  The soak report moves out of `DebugActivity` so the bundle can include
  it. Companion 0.6.8.
- Server: `POST /api/v1/diagnostics` (phone token, zip body, size cap),
  `POST /api/v1/diagnostics/requests/{id}/decline`, storage under
  `CM_DATA_DIR/diagnostics/`, retention purge, dashboard section with
  request/download/delete (admin), audit events. Schema v4. Server 0.3.3.
- New capability spec `diagnostics-sharing`.
