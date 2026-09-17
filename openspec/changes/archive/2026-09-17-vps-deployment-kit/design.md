# Design

## D1. Overlay, not a second stack

`docker-compose.vps.yml` is applied on top of the base file so the
environment list lives in one place. It replaces (`!override`, Compose
≥ 2.24) the published ports with loopback bindings and adds Caddy.
Pinning in the overlay rather than via an env var or a firewall rule is
deliberate: Docker inserts its own iptables rules ahead of `ufw`, so a
"deny 8080" does nothing for a published port, and an env var can be
forgotten. `COMPOSE_FILE` in `.env` lets every later command — and the
backup script — be a plain `docker compose …`.

## D2. Trusting the proxy

uvicorn enables proxy headers by default but only honours them from
`FORWARDED_ALLOW_IPS` (default `127.0.0.1`), which is why nothing
worked behind DSM or would behind Caddy: the proxy connects from a
docker bridge address. The VPS overlay sets `*`, which is safe exactly
because D1 makes Caddy and loopback the only ways to reach port 8080.
The base file passes through `CM_FORWARDED_ALLOW_IPS` for other
setups; for Synology the value is the bridge gateway, with the caveat
(documented in `.env.example`) that a LAN-published 8080 lets LAN
clients spoof the header — bind it to the NAS if that matters. No
application code changes: the throttles and audit events already use
`request.client.host`, which uvicorn now fills correctly.

## D3. Tests exercise the real middleware

`ProxyHeadersMiddleware` is what uvicorn installs; wrapping the app in
it under `TestClient` tests the behaviour we ship: per-client buckets
behind a trusted proxy, spoofed headers ignored from an untrusted peer,
and the failed-login audit event recording the forwarded address.

## D4. Backups use the SQLite backup API

Copying a live SQLite file can capture a torn write. The script runs
`sqlite3.Connection.backup()` inside the container (Python is already
there; no extra packages on the host), writes into the bind-mounted
data directory, rotates 14 copies, and the guide insists on an off-box
copy.

## D5. What the kit does not solve

Nothing inside the server can report that the server is down. The
survivability plan's external dead-man ping is still open; the guide
tells operators to point an external uptime monitor at the health
endpoint meanwhile.
