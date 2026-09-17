# Standby — server

Self-hosted backend (Python 3.12, FastAPI, SQLite, Docker). Optional but
recommended: it is what turns an alarm into *someone actually
responding*.

What it does:

- **Alarm intake and escalation** — the phone posts an alarm; the
  server alerts contacts tier by tier over Telegram, ntfy and e-mail,
  each message carrying a one-tap **Acknowledge**; unacknowledged tiers
  are retried and promoted. Intake is idempotent, so a retried phone
  request never doubles an escalation.
- **Phone dead-man** — phones heartbeat every 5 minutes. A phone that
  goes silent raises a graduated warning to the contacts (softened for
  announced offline windows and low battery), and an all-clear when it
  comes back.
- **Multi-wearer** — one server serves a family or response group.
  Phones enroll with a single-use code and receive their own token;
  each wearer's events and contacts are isolated.
- **Web dashboard** at `/ui/` — fleet and wearer status, contacts,
  enrollment codes, operators (`admin` / `responder`), audit trail.
- State (open alarms, dead-man, leased commands) survives restarts.

## Run it

```bash
cp .env.example .env && chmod 600 .env     # see comments inside
docker compose up -d --build               # API + dashboard on :8080
```

| Guide | For |
|---|---|
| [docs/DEPLOY-VPS.md](../docs/DEPLOY-VPS.md) | a public VPS with automatic HTTPS (Caddy overlay, backups, hardening) |
| [docs/DEPLOY-SYNOLOGY.md](../docs/DEPLOY-SYNOLOGY.md) | Synology Container Manager behind DSM's reverse proxy |
| [docs/TELEGRAM-BOT.md](../docs/TELEGRAM-BOT.md) | creating the Telegram bot and getting contacts' chat ids |
| [docs/SERVER-DEPLOYMENT.md](../docs/SERVER-DEPLOYMENT.md) | survivability plan, security and update rationale |

Behind any reverse proxy set `CM_FORWARDED_ALLOW_IPS` to the proxy's
address (the VPS overlay does this itself); otherwise every caller
shares one login/enrollment rate-limit bucket.

## Layout

| Path | Role |
|---|---|
| `app/main.py` | API routes, the escalation/dead-man pump, acknowledge endpoints |
| `app/escalation.py`, `app/deadman.py` | pure state machines (unit-tested without I/O) |
| `app/channels.py`, `app/telegram_poll.py` | Telegram / ntfy / e-mail delivery; Telegram acknowledgement long-poll |
| `app/wearers.py`, `app/operators.py`, `app/ui.py` | enrollment and contacts API, dashboard accounts and sessions, dashboard pages |
| `app/store.py` | SQLite persistence (`data/cryomonitor.db`) |
| `scripts/backup.sh` | consistent online backup, 14 rotations |
| `docker-compose.yml`, `docker-compose.vps.yml`, `Caddyfile` | deployment |

## Tests

```bash
python -m venv .venv && .venv/bin/pip install -r requirements.txt pytest
.venv/bin/python -m pytest tests/ -q          # 76 checks
```

Internal names (`cryomonitor` database, logger and container names)
predate the rename to Standby and are kept so existing deployments
upgrade in place.
