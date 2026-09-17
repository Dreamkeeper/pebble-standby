# Tasks

- [x] 1. `docker-compose.vps.yml` overlay (Caddy, loopback-pinned app
       and ntfy ports, proxy trust) and `Caddyfile`.
- [x] 2. Base compose: `FORWARDED_ALLOW_IPS` passthrough, container
       healthcheck; `.env.example` documents the new variables.
- [x] 3. Tests: per-client throttle behind a trusted proxy, spoofed
       header ignored from an untrusted peer, audit event records the
       forwarded address.
- [x] 4. `scripts/backup.sh` (online SQLite backup, 14 rotations).
- [x] 5. `docs/DEPLOY-VPS.md`; pointers from the README and the
       survivability plan; server 0.3.2.
- [x] 6. Compose files validated with `docker compose config`.
- [ ] 7. Owner verification: a throw-away VPS following DEPLOY-VPS.md
       end to end (certificate issued, enroll a phone, test alarm
       acknowledged, backup + restore). Optional for the NAS: set
       `CM_FORWARDED_ALLOW_IPS` to the bridge gateway and confirm the
       dashboard audit shows real client addresses.
