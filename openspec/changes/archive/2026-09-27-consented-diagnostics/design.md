# Design

## D1. One bundle, two exits

`DiagnosticsBundle.build(logDir, days, now, secrets, header)` writes a
zip: `manifest.txt` (app/device/range/redaction summary/file list),
`soak.txt`, and the selected `cm-YYYYMMDD.log` files, each redacted
line by line. Share hands the zip to the Android chooser; Send uploads
the same bytes. One builder means what the wearer previews is exactly
what either exit sends. Plain-text logs compress about tenfold, so a
week (~3.5 MB) becomes a few hundred KB.

The range is inclusive of today: `days = 7` selects the files dated
today and the six days before. Files are chosen by the date in their
name, not mtime, so a copied or touched file does not shift the window.

## D2. Redaction is conservative and on the phone

The server is the wearer's own, but bundles can also go to a chat via
Share, so the phone redacts for the worst audience:

- Exact values of the stored API token and fallback bot token, when
  non-empty, become `[redacted]`.
- Regexes: Telegram bot token shape `\d{6,12}:[A-Za-z0-9_-]{30,}`;
  `Bearer <token>`; `token=<value>` query parameters.
- Coordinate pairs (`(55.6218, 37.7401)`, `q=55.6218,37.7401`,
  `lat=`/`lon=`) are rounded to two decimals.
- Telegram chat ids in `Telegram to <id>` / `fallback test to <id>` keep
  only their last three digits.

The server URL stays: it is needed to read the HTTP lines and it is the
destination of the upload anyway. The manifest lists every rule, and the
confirmation dialog repeats them in plain words.

## D3. Upload transport

`POST /api/v1/diagnostics?days=N[&request_id=R]`, body
`application/zip`, bearer phone token (never in the URL). The server
checks the zip signature, enforces `CM_DIAG_MAX_BYTES` (default 20 MB,
413 above), writes `CM_DATA_DIR/diagnostics/<wearer>/<id>.zip`
atomically (temp file + rename), records a row and an audit event.
The companion uses a 120 s call timeout for this call only.

## D4. Requests ride the leased command channel

The dashboard creates a request row (`pending`) and queues the command
`diag_request:<id>:<days>` on the existing one-slot command queue.
The phone acks it like any command, stores it as the pending request and
posts a notification ("Your Standby server asks for the last N days of
logs"). Tapping opens the log screen, which shows the request dialog:
**Send** builds and uploads with `request_id` (the server marks the
request fulfilled), **Decline** calls the decline endpoint. Dismissing
the notification changes nothing; the request stays visible on the log
screen until answered or superseded by a newer one.

The one-slot queue means a request can replace an unacknowledged
latency drill (and vice versa); both are rare admin actions and the
dashboard notice says so.

## D5. Retention and access

Uploads older than `CM_DIAG_RETENTION_DAYS` (default 30) are deleted —
file and row — at startup and hourly from the pump loop. Download and
delete are **admin-only** (responders handle alarms; they do not need
the wearer's logs); both are audited. Deleting a wearer's upload is
immediate and irreversible, which is the point.

## D6. Schema v4

```sql
CREATE TABLE diagnostics (id TEXT PRIMARY KEY, wearer_id TEXT NOT NULL,
  created_t REAL NOT NULL, size INTEGER NOT NULL, days INTEGER NOT NULL,
  request_id TEXT);
CREATE TABLE diag_requests (id TEXT PRIMARY KEY, wearer_id TEXT NOT NULL,
  days INTEGER NOT NULL, requested_by TEXT NOT NULL, created_t REAL NOT NULL,
  state TEXT NOT NULL DEFAULT 'pending', resolved_t REAL);
```
