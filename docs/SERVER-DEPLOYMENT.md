# Server survivability plan & VPS deployment guide

Status 2026-08-28: plan (not yet implemented). Current reality: FastAPI
+ SQLite in Docker on the Synology NAS, `restart: unless-stopped`
already set, DB bind-mounted at `./data`, token auth (wearer +
admin) and session-cookie dashboard auth in place.

## Part 1 — Survivability implementation plan

The server is itself a single point of failure that watches everyone
else. The design rule: **every layer's death must be noticed by a
layer that cannot die with it.**

### Phase 1 — watch the watcher (highest value, ~1 day)

1. **External dead-man ping.** The server pings a dead-man URL
   (healthchecks.io free tier, or any uptime service) every 5 min
   from its existing pump loop; env `CM_HEALTHPING_URL`. If the
   server, Docker, the NAS, or the whole house loses power, the
   *external* service alerts the responders by email/push after the
   grace period. ~20 lines; no new dependencies (urllib, same pattern
   as ntfy).
2. **`/healthz` endpoint + compose healthcheck.** Liveness that
   checks the DB is writable and the pump thread is alive; wired
   into a compose `healthcheck:` so `docker ps` shows truth. A hung
   (not crashed) process is the case `restart: unless-stopped`
   misses — pair with a host cron
   (`docker events`/autoheal or a one-line restart-on-unhealthy
   cron) on the NAS.
3. **SQLite durability.** `PRAGMA journal_mode=WAL` +
   `synchronous=NORMAL` at connect (verify store.py; add if absent) —
   survives power loss without corruption.
4. **Daily DB backup.** In-process daily `sqlite3 .backup` into
   `data/backup/` (14 rotations) + copy off-box (NAS ↔ VPS or
   rclone to any object storage). The DB holds enrollments,
   contacts, and the audit trail — losing it silently disarms
   escalation.

### Phase 2 — restart correctness (~1 day)

5. **Escalation resume test.** Kill the container mid-escalation
   (after fire, before ack) and verify the pump resumes the active
   escalation from the DB on boot, not just new ones. If it doesn't,
   persist pump state. This is a drill in the soak protocol once it
   passes.
6. **Clock sanity.** On boot, log a warning if the DB's last
   heartbeat is in the future (RTC/clock skew) — bad clocks silently
   break every age-based gate.

### Phase 3 — reduce correlated failure

7. **Channel independence.** Self-hosted ntfy on the same box dies
   with the box. Default the compose to ntfy.sh (already the
   default `CM_NTFY_URL`) or document running ntfy on a *different*
   host; Telegram is already external.
8. **NAS UPS** (hardware, owner's call) — or run the server on a VPS
   where power/network are the provider's problem (Part 2).
9. **Two-site option (later):** a warm standby (VPS primary + NAS
   secondary in observe-mode) is real engineering — only worth it
   after a clean soak month on one site.

## Part 2 — VPS deployment

### Do we need code changes?

> **Implemented 2026-09-17:** the ready-to-run kit is
> `server/docker-compose.vps.yml` + `server/Caddyfile` +
> `server/scripts/backup.sh`; the step-by-step is
> [DEPLOY-VPS.md](DEPLOY-VPS.md). The notes below are the rationale.

Almost none — the compose file is host-agnostic. Needed for a public
VPS (small, listed as tasks):

- **TLS termination:** add a Caddy (or Traefik) service to the
  compose with automatic Let's Encrypt for your domain; proxy to
  `monitor:8080`.
- **Don't expose 8080 raw:** change the port mapping to
  `127.0.0.1:8080:8080` so only the reverse proxy reaches it.
- **Env:** set `CM_PUBLIC_URL=https://your.domain` (ack links) and
  remove `CM_UI_INSECURE_COOKIES` (secure cookies on over HTTPS).
- Phone: point the companion's server URL at the domain — no VPN
  dependency (the current NAS+VPN path has the phone's VPN as a
  hidden single point of failure; a public HTTPS VPS removes it).

### VPS requirements

The stack is tiny (FastAPI + SQLite + optional ntfy + Caddy):

| Resource | Minimum | Comfortable |
|----------|---------|-------------|
| vCPU | 1 | 2 |
| RAM | 1 GB | 2 GB |
| Disk | 10 GB SSD | 20 GB SSD |
| Traffic | negligible (heartbeats are ~1 KB/5 min/wearer) | — |

Any €3–6/month tier works (Hetzner CX22/CPX11, Netcup, OVH,
DigitalOcean basic). Pick a region near the wearer for latency
sanity, though nothing here is latency-critical. OS: **Debian 12**
or **Ubuntu 24.04 LTS** (boring, long support). Software: Docker +
compose plugin, git — everything else lives in containers.

### Information security

Already in place: bearer-token API auth per wearer, separate admin
token, dashboard session auth, secrets via `.env`.

VPS hardening checklist (one-time, ~1 hour):

1. **SSH:** key-only login (`PasswordAuthentication no`), non-root
   user with sudo, optionally move off port 22. fail2ban for the
   rest.
2. **Firewall:** ufw default-deny; allow 22, 80, 443 only. 8080
   stays loopback-bound.
3. **TLS everywhere; no plain HTTP** (Caddy redirects 80→443).
4. **Secrets:** `.env` chmod 600, never in git (already the repo
   rule); rotate `CM_ADMIN_TOKEN` if ever pasted anywhere.
5. **Privacy note (honest tradeoff):** the DB holds heart-rate
   flags, battery, and last-known location. On a VPS the provider
   can technically read the disk. That is the self-hosting-on-
   rented-hardware tradeoff; mitigations: a provider you trust,
   full-disk-encryption offerings, or keeping the server at home on
   a UPS and accepting home-grade availability. Both are valid —
   the external dead-man (Part 1.1) matters more than the location
   choice.

### Security updates (the boring, load-bearing part)

- **OS:** `unattended-upgrades` (Debian/Ubuntu) — automatic security
  patches, zero routine work. Enable reboot-if-required on a fixed
  hour with the dead-man covering the reboot window.
- **Containers:** monthly routine (calendar reminder or cron):
  `docker compose build --pull && docker compose up -d` — refreshes
  the Python base image and ntfy/Caddy images. The app itself
  updates via `git pull` + the same command.
- **Dependencies:** `pip` pins live in the image build; bump on the
  monthly rebuild; GitHub Dependabot alerts on the repo cover the
  CVE feed for free.
- **Audit trail:** `docker compose logs` + the server's own event
  feed; forward auth failures count to the dashboard (already
  logged).
