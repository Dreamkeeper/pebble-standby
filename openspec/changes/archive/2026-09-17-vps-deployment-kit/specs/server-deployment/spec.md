# Delta: server-deployment (new capability)

## ADDED Requirements

### Requirement: Client identity survives a reverse proxy
When the server runs behind a reverse proxy, per-client protections
(dashboard login throttle, enrollment throttle) and audit events SHALL
use the real client address taken from the proxy's forwarding headers,
and SHALL do so only for proxies named in the deployment's trusted list
(`FORWARDED_ALLOW_IPS`, default loopback). Forwarding headers from any
other peer SHALL be ignored, so a caller cannot buy a fresh rate-limit
bucket by spoofing them. A deployment SHALL trust a proxy only when that
proxy is the sole network path to the application port.

#### Scenario: One abusive client does not lock out the others
- **WHEN** a client behind the trusted proxy exhausts the enrollment or
  login attempt limit
- **THEN** that client is throttled and a different client behind the
  same proxy is not

#### Scenario: Spoofed forwarding headers do not help
- **WHEN** a peer that is not a trusted proxy sends requests with
  varying X-Forwarded-For values
- **THEN** they count against that peer's single bucket

#### Scenario: The audit trail names the client
- **WHEN** a login fails behind the trusted proxy
- **THEN** the recorded event carries the forwarded client address, not
  the proxy's

### Requirement: Reference public deployment
The repository SHALL ship a runnable public-VPS deployment: a compose
overlay that terminates HTTPS with automatically issued certificates,
exposes only ports 80 and 443, and binds the application and the bundled
push server to loopback within the overlay itself (container-published
ports bypass host firewalls such as ufw); a container healthcheck on the
health endpoint; and a step-by-step guide from a fresh OS to an enrolled
phone, including hardening, updates and an external uptime check. The
reference deployment SHALL require no application code or schema
difference from any other deployment.

#### Scenario: Fresh machine to enrolled phone
- **WHEN** an operator follows the guide on a fresh Debian or Ubuntu VPS
  with a DNS name
- **THEN** the dashboard is reachable over HTTPS with a valid
  certificate, the application port is not reachable from the internet,
  and a phone can enroll with a code

### Requirement: Backups are consistent and rotated
The deployment SHALL provide a backup procedure that produces a
consistent copy of the database while the server is running (SQLite
online backup, not a file copy), retains a bounded number of recent
copies, and documents both off-machine copying and restore.

#### Scenario: Backup during operation
- **WHEN** the backup runs while heartbeats are being written
- **THEN** the resulting file opens as a valid database and the server
  is not interrupted
