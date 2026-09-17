# VPS deployment kit; client identity behind a reverse proxy

## Why

Beta testers who want a server will rent a VPS, not buy a Synology.
The application is host-agnostic, but everything a public deployment
needs was either provided by Synology DSM (TLS termination, backups) or
written down only as advice (`docs/SERVER-DEPLOYMENT.md` Part 2).
Reviewing it for the kit also confirmed a real defect that affects
*every* deployment behind a reverse proxy, the current NAS included:

- Dashboard login and enrollment throttle on `request.client.host`.
  Behind a proxy that is the proxy's address for every caller (the NAS
  logs show `172.23.0.1` on every request), so all clients share one
  rate-limit bucket — ten bad enrollment attempts or five bad logins
  from anyone lock everyone out — and the audit trail records the proxy
  instead of the client.

## What changes

- `server/docker-compose.vps.yml` overlay + `server/Caddyfile`: Caddy
  with automatic Let's Encrypt in front of the app. The overlay pins the
  app and ntfy ports to loopback **itself**, because Docker-published
  ports bypass `ufw`.
- `FORWARDED_ALLOW_IPS` is wired through compose
  (`CM_FORWARDED_ALLOW_IPS`, default loopback; the VPS overlay trusts
  its own proxy). uvicorn's proxy-header handling then supplies the real
  client address to the existing throttles and audit events. Tests run
  the same middleware.
- Container healthcheck on `/api/v1/health`.
- `server/scripts/backup.sh`: consistent online SQLite backup via the
  backup API, 14 rotations.
- `docs/DEPLOY-VPS.md`: fresh Debian box → HTTPS dashboard → first
  enrolled phone, hardening, backups, updates, external uptime check.
- Server 0.3.2.

Separately fixed while answering "where do Telegram messages come
from": the companion sent phone-direct Telegram on *every* alarm,
contradicting the escalation spec ("only when the server is
unreachable"); now it follows the spec and retracts only where it
fired (companion 0.6.6). No spec change — the code is brought to the
spec.

## Impact

- Specs: new capability `server-deployment`.
- No API or schema change; existing deployments keep working (default
  proxy trust stays loopback).
