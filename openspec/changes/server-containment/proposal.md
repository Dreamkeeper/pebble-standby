# Server containment after the 2026-10-26 security hunt

## Why

A source-only hunt of `server/` (docs/security-audit-2026-10-26) found no
cross-tenant read or write, but it found that the server trusts a wearer
token and an admin's intent further than the beta allows: one wearer
token could drive unbounded fan-out through the operator's channels and
delay every other wearer's alerts; the admin's disable did not stop
queued paging; five anonymous wrong passwords locked a named operator
out; an enrol code could be exchanged twice under a race; an operator
name with a slash could never be revoked; a heartbeat with `Infinity`
took every dashboard page down; the admin API took a session cookie
without CSRF; one failing Telegram update wedged the poller; and
diagnostics uploads had no per-wearer quota on the volume that holds the
database. The beta multiplies the principals who could trigger each of
these.

## What changes

- **Caps on what one wearer token can cause.** At most 10 contacts per
  wearer, 3 open escalations per wearer (a further alarm folds into the
  newest with an `alarm_collapsed` event), detector text of 1–64
  printable characters, phone-origin kinds only, 20 sends per wearer per
  pump cycle (the rest go out on the next cycles, audited as
  `send_suppressed`), battery and age fields bounded.
- **The pump isolates failures.** A transport that raises counts as a
  failed send; one escalation's exception never aborts the cycle.
- **Disable stops paging.** Disabling a wearer resolves its open
  escalations with resolution `wearer_disabled`, immediately and on the
  next cycle, and a restart cannot resurrect them.
- **Login throttle per account and address.** A stranger's failures for
  a username no longer block the operator from another address; sustained
  guessing is audited as `login_guessing_suspected`. Password hashing runs
  off the event loop.
- **Atomic enrol-code burn.** The conditional update is the uniqueness
  check; a concurrent exchange mints nothing.
- **Routable operator names.** Letters, digits, `. _ -`, up to 32; the
  operators page URL-encodes names.
- **Finite heartbeat values.** `suspended_until` must be a finite epoch
  time; a stored non-finite value renders as not suspended instead of
  raising; validation errors never echo the offending input.
- **CSRF on the cookie path of the admin API.** A mutation authenticated
  by the session cookie needs the session's token in `X-CSRF`.
- **Self-healing Telegram poller.** Each update's Bot API failure is
  logged and skipped; the offset always advances past the batch.
- **Diagnostics quota.** At most 20 bundles and 200 MiB per wearer; the
  knobs are passed through compose.
- **Deployment defaults.** The base compose binds the app and ntfy to
  loopback; the VPS Caddyfile answers 404 under the placeholder ntfy name.

## Out of scope

The owner-observed deployment checks in the report (the NAS `.env` value
of `CM_FORWARDED_ALLOW_IPS`, the live port bindings, which ntfy receives
alerts) and the six coverage units the critic deferred to a later run.
