# Tasks

- [x] 1. Server 0.3.7: caps, pump isolation and budget, disable resolves
       escalations, login throttle keyed per account-and-address, atomic
       enrol burn, username rule, heartbeat validation and tolerant
       dashboard, validation-error handler, CSRF on the admin API cookie
       path, Telegram poller per-update handling, diagnostics quota,
       loopback ports, Caddy placeholder 404. `tests/test_audit_2026_10_26.py`
       (13 regression tests; 107 total).
- [x] 2. Spec deltas: escalation-and-deadman, wearer-management,
       web-dashboard, diagnostics-sharing, server-deployment.
- [ ] 3. Deploy 0.3.7 to the NAS (app/ only; the NAS compose keeps its
       local port and proxy settings) and verify `/api/v1/health` reports
       0.3.7.
- [ ] 4. Owner checks from the report's NEEDS-VALIDATION table that no
       code can settle: `docker compose exec monitor printenv
       FORWARDED_ALLOW_IPS` and the audit page's client addresses; `ss
       -ltnp` for 8081/8090 bindings; `printenv CM_NTFY_URL`; whether any
       contact uses an ntfy topic.
