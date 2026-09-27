# diagnostics-sharing Specification

## Purpose
TBD - created by archiving change consented-diagnostics. Update Purpose after archive.

## Requirements

### Requirement: Diagnostics leave the phone only by the wearer's action
The companion SHALL send or share diagnostic data only as the direct
result of the wearer tapping Share or Send in the app. The server SHALL
NOT be able to retrieve logs from the phone by any means other than an
upload the wearer confirmed; a server request SHALL only produce a
notification and a pending request in the app.

#### Scenario: A server request waits for the wearer
- **WHEN** an admin requests diagnostics and the phone receives the request
- **THEN** the phone shows a notification and uploads nothing until the
  wearer taps Send

#### Scenario: Declining is reported
- **WHEN** the wearer taps Decline on a pending request
- **THEN** nothing is uploaded and the server marks the request declined

### Requirement: A bundle covers the range the wearer picks
Share and Send SHALL use the same bundle: the companion's daily log files
for the chosen number of days (1, 3, 7 or 14, today inclusive, selected
by the date in the file name), the soak report and a manifest listing
the files, range, app and device versions and the redaction rules. The
bundle SHALL NOT be truncated to a byte budget.

#### Scenario: An older day is included
- **WHEN** the wearer shares 14 days and the log directory holds daily
  files for the last 40 days
- **THEN** the bundle contains exactly the 14 most recent daily files,
  complete

#### Scenario: The confirmation matches what is sent
- **WHEN** the wearer opens Send
- **THEN** the dialog shows the range, the compressed size, the file
  count and the redaction rules of the bundle that will be uploaded

### Requirement: Secrets and precise locations are removed on the phone
Before a bundle is shared or uploaded, the companion SHALL remove stored
tokens and any bot-token or bearer-credential shaped strings, round
coordinates to two decimals, and mask Telegram chat ids to their last
three digits.

#### Scenario: A token in a log line
- **WHEN** a log line contains the stored API token or a Telegram bot token
- **THEN** the bundled line contains `[redacted]` in its place

#### Scenario: An alarm location
- **WHEN** a log line contains `loc=(55.621867, 37.740187)`
- **THEN** the bundled line contains `loc=(55.62, 37.74)`

### Requirement: The server stores uploads per wearer, briefly, for admins
The server SHALL accept a diagnostics upload only with a valid phone
token, only as a zip archive and only up to the configured size limit,
store it under the uploading wearer, delete it after the retention
period (default 30 days), and let only admins download or delete it.
Uploads, downloads, deletions, requests and declines SHALL be audited.

#### Scenario: Oversized or foreign data is refused
- **WHEN** a phone uploads a body that is not a zip archive or exceeds
  the size limit
- **THEN** the server rejects it and stores nothing

#### Scenario: Retention
- **WHEN** an upload is older than the retention period
- **THEN** the file and its record are deleted

#### Scenario: Responders cannot read logs
- **WHEN** a responder account opens a diagnostics download link
- **THEN** the server refuses it

#### Scenario: A fulfilled request
- **WHEN** the phone uploads with the id of a pending request
- **THEN** the request is marked fulfilled and linked to the upload on
  the dashboard
