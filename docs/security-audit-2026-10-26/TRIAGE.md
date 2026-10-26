# Triage of the 2026-10-26 server hunt (owner view)

Status as of server 0.3.7. "Fixed" means a regression test in
`server/tests/test_audit_2026_10_26.py` now asserts the invariant; the
leads' local plans were run against 0.3.6 first and every one of them
reproduced (probe results in the session of 2026-10-26).

| # | Finding or lead | Verdict | Status |
|---|---|---|---|
| 1 | Unbounded per-wearer fan-out through the shared pump | confirmed, medium | **Fixed**: 10 contacts, 3 open escalations (fold), 64-char printable detector, phone kinds only, 20 sends per wearer per cycle, per-send isolation |
| 2 | Disable does not stop open escalations | confirmed, low | **Fixed**: disable resolves them (`wearer_disabled`), pump does the same |
| 3 | Login throttle locks a named operator out | confirmed, low | **Fixed**: account bucket bound to the address; guessing audited |
| 4 | Enrol-code exchange race | confirmed, low | **Fixed**: conditional update with rowcount check |
| 5 | Unroutable operator username | confirmed, low | **Fixed**: username rule at creation, URL-encoded actions |
| 6 | `suspended_until = Infinity` breaks every dashboard page | lead, reproduced | **Fixed**: finite range validation, tolerant rendering, 422 without echo |
| 7 | Admin API cookie path without CSRF | lead, reproduced | **Fixed**: `X-CSRF` required on cookie-authenticated mutations |
| 8 | Surrogate in alarm text stalls the pump | lead, reproduced (entry path open; the 422 itself crashed) | **Fixed**: detector validation plus the error handler; pump isolation covers any other raising transport |
| 9 | Poison update wedges the Telegram poller | lead, reproduced | **Fixed**: per-update handling, offset always advances |
| 10 | Diagnostics uploads unbounded per wearer | lead, reproduced | **Fixed**: 20 bundles / 200 MiB per wearer, knobs passed through compose |
| 11 | Proxy identity collapse behind DSM (`FORWARDED_ALLOW_IPS`) | lead, deployment fact | **Owner check** (below); the login fix removes the worst effect, enrolment still shares one bucket if the proxy is untrusted |
| 12 | Base compose publishes ntfy and the plaintext app port on all interfaces | lead, deployment fact | **Fixed in the repo** (loopback bindings); **owner check** on the NAS's own compose copy |
| 13 | VPS Caddyfile serves ntfy under `ntfy.localhost` on public 443 | lead, deployment fact | **Fixed in the repo** (placeholder answers 404); no VPS is known to be deployed |

## Owner checks (NAS, read-only, nothing sent to the service)

```bash
docker compose exec monitor printenv FORWARDED_ALLOW_IPS CM_NTFY_URL
```

```bash
sudo ss -ltnp | grep -E ':(8081|8080|8090)\b'
```

Then on the dashboard's Audit page: if every `login` and `login_failed`
event shows the same 172.x address, set `CM_FORWARDED_ALLOW_IPS` to the
bridge gateway per docs/DEPLOY-SYNOLOGY.md; if the `ss` output shows
`0.0.0.0:8090` or `0.0.0.0:8081`, change the NAS compose copy to
`127.0.0.1:` bindings and put a DSM reverse-proxy name in front of ntfy
for contacts.

## Not findings, kept as hardening (60 notes in REPORT.md section 6)

The notes most worth a later pass: ack tokens never expire or prune;
`CM_API_TOKEN` and `CM_UI_ADMIN_*` left in `.env` after bootstrap; the
6 coverage units the critic deferred (ntfy topic strength, event-row
growth, login pre-auth cost, template contexts, cryptography, the
diagnostics decline route).
