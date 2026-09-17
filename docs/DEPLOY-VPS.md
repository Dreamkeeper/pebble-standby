# Deploying the Standby server on a VPS

From a fresh Debian 12 / Ubuntu 24.04 machine to a working HTTPS
dashboard in about fifteen minutes. Any small tier works (1 vCPU, 1 GB
RAM, 10 GB disk — €3–6/month); the stack is FastAPI + SQLite + Caddy.

You do **not** need a server to try Standby: the companion can alert a
Telegram chat directly. The server adds what matters for real use —
tiered contacts with one-tap acknowledgement and retries, a dead-man
alarm when the *phone* goes silent, and a dashboard.

## 0. Before you start

- A DNS name pointing at the VPS: an `A` (and `AAAA`) record such as
  `standby.example.org`. Certificates cannot be issued for a bare IP.
- A Telegram bot token from [@BotFather](https://t.me/BotFather) if
  contacts should be alerted over Telegram (recommended).

## 1. Install Docker

Follow <https://docs.docker.com/engine/install/debian/> (or `/ubuntu/`).
Compose **2.24 or newer** is required (`docker compose version`); the
packages from Docker's own repository are current.

## 2. Get the server

```bash
git clone https://github.com/Dreamkeeper/pebble-standby.git
cd pebble-standby/server
cp .env.example .env && chmod 600 .env
```

## 3. Configure `.env`

Set at least:

```ini
CM_DOMAIN=standby.example.org
CM_PUBLIC_URL=https://standby.example.org
CM_TELEGRAM_BOT_TOKEN=123456:ABC...
# first-boot dashboard admin — remove both lines after the first login
CM_UI_ADMIN_USER=admin
CM_UI_ADMIN_PASSWORD=<a long random password>
# lets plain `docker compose ...` use both files
COMPOSE_FILE=docker-compose.yml:docker-compose.vps.yml
```

Leave `CM_UI_INSECURE_COOKIES` empty (cookies must be HTTPS-only) and
`CM_API_TOKEN` empty (phones enroll with a code instead).

## 4. Start it

```bash
docker compose up -d --build
docker compose ps            # monitor should become "healthy"
curl https://standby.example.org/api/v1/health
```

The first request may take a few seconds while Caddy obtains the
certificate. If it fails, check that ports 80 and 443 reach the VPS and
that the DNS record has propagated: `docker compose logs caddy`.

## 5. First login, first wearer

1. Open `https://standby.example.org/ui/` and sign in with the
   bootstrap admin. Then delete `CM_UI_ADMIN_USER` and
   `CM_UI_ADMIN_PASSWORD` from `.env` (they are consumed once).
2. Create a wearer and issue an **enrollment code** (shown once, valid
   24 h, single use).
3. In the Standby Android app: *Enroll* → server URL + the code. The
   phone receives its own token; no secret is ever typed twice.
4. Add contacts and tiers for the wearer. A Telegram contact sends
   `/start` to your bot to learn their chat id.
5. Run a **test alarm** from the app (it is labelled `[TEST]` end to
   end) and have a contact press *Acknowledge*.

## 6. Harden the machine (one-time, ~30 min)

- **SSH:** key-only login (`PasswordAuthentication no`), a non-root
  sudo user; `fail2ban` if you keep port 22.
- **Firewall:** `ufw default deny incoming`, allow `22`, `80`, `443`.
  Note that **Docker-published ports bypass ufw** — this is why the
  VPS overlay itself binds the application (8080) and ntfy (8090) to
  `127.0.0.1`; do not remove those bindings.
- **Automatic security updates:** `apt install unattended-upgrades`
  and enable it, with automatic reboot at a fixed quiet hour.
- **Secrets:** `.env` stays `chmod 600` and out of git.

## 7. Backups

```bash
chmod +x scripts/backup.sh
./scripts/backup.sh                      # writes data/backup/cryomonitor-<stamp>.db
crontab -e
# 15 3 * * * /home/<you>/pebble-standby/server/scripts/backup.sh >/dev/null
```

The script uses SQLite's online backup API, so it is safe while the
server runs, and keeps the newest 14 copies. **Copy them off the
machine too** (`rclone`, `scp` to another host): a backup on the same
disk does not survive losing the VPS. Restore = stop the stack, copy a
backup over `data/cryomonitor.db`, start the stack.

## 8. Updates

```bash
cd pebble-standby && git pull
cd server && docker compose up -d --build
# monthly: refresh base images as well
docker compose build --pull && docker compose up -d
```

Alarm and dead-man state survive restarts; a restart takes seconds.

## 9. Who watches the watcher?

The server alarms when a *phone* goes silent, but nothing inside it can
report that the *server* is down. Point any external uptime monitor
(UptimeRobot, healthchecks.io, a second machine's cron + curl) at
`https://<domain>/api/v1/health` and have it notify you. The companion
also shows "SERVER: …" in its notification when heartbeats fail, and
falls back to alerting Telegram directly from the phone if an alarm
fires while the server is unreachable.

## Privacy note

The database holds wearer status, battery, heart-rate flags and
last-known location at alarm time. On rented hardware the provider can
technically read the disk. Choose a provider you trust, or keep the
server at home ([Synology guide](DEPLOY-SYNOLOGY.md)) and accept
home-grade availability — both are valid; the external check in step 9
matters more than the location.

## Optional: self-hosted ntfy for contacts

Set `CM_NTFY_DOMAIN=ntfy.example.org` (second DNS record), and
`CM_NTFY_URL=http://ntfy` so the server publishes internally; contacts
subscribe to their topic at `https://ntfy.example.org`. Without it the
bundled ntfy stays private to the VPS and `https://ntfy.sh` is used.
