# Deploying the server on a Synology NAS

Tested against DSM 7.2 with **Container Manager** (the successor of the
Docker package). Any x86-64 or ARM64 Synology that supports Container
Manager works; the server is a single small Python container.

## 1. Copy the server folder

Copy `server/` to the NAS, e.g. to `/volume1/docker/cryomonitor/`
(File Station upload, or `git clone` via SSH).

```bash
ssh admin@nas
mkdir -p /volume1/docker/cryomonitor
# then upload the server/ directory contents there
```

## 2. Configure secrets

```bash
cd /volume1/docker/cryomonitor
cp .env.example .env
vi .env        # set CM_PUBLIC_URL, CM_TELEGRAM_BOT_TOKEN (see TELEGRAM-BOT.md),
               # CM_UI_ADMIN_USER / CM_UI_ADMIN_PASSWORD for the first login, SMTP.
               # CM_API_TOKEN is legacy: leave it empty, phones enroll with a code.
```

## 3. Create the project in Container Manager

Container Manager → **Project** → **Create**:
- Project name: `cryomonitor`
- Path: `/volume1/docker/cryomonitor`
- Source: *Use existing docker-compose.yml*

Build + start. Two containers come up:
- `monitor` on port **8080** (the API + dashboard)
- `ntfy` on port **8090** (self-hosted push; optional — remove from
  docker-compose.yml if you use ntfy.sh or Telegram only)

CLI alternative: `docker compose up -d --build` over SSH.

## 4. HTTPS via DSM reverse proxy (recommended)

The companion app talks to the server over the internet — put DSM's
reverse proxy with a publicly trusted certificate in front. This mirrors
how other self-hosted apps are typically exposed on DSM (one hostname per
app, TLS terminated by DSM, backend on `127.0.0.1`):

1. **DNS**: create an `A` record for your hostname pointing at your
   household public IP (DNS-only / "gray cloud" if you use Cloudflare and
   want DSM to hold the certificate).
2. **Router**: forward **TCP 443 only** to the NAS. Never forward the
   container's own port.
3. **Certificate**: Control Panel → Security → Certificate → Add → get a
   Let's Encrypt certificate for the hostname, then **Settings** → map
   that hostname's service to the new certificate.
4. **Reverse proxy**: Control Panel → Login Portal → Advanced → Reverse
   Proxy → Create:
   - Source: HTTPS, `cm.example.com`, port 443, HSTS on
   - Destination: HTTP, `localhost`, port 8081 (the published host port)
5. **Verify** from outside the LAN:
   `curl -sS https://cm.example.com/api/v1/health` → `{"status":"ok",…}`

### Endpoint exposure

Public exposure changes what may be readable without a token:

| Endpoint | Auth | Why |
|---|---|---|
| `GET /api/v1/health` | none | liveness only, no wearer data — safe for uptime monitors |
| `GET /api/v1/status` | token | contains GPS, contact ids, event log |
| `POST /api/v1/*` | token | heartbeats, alarms, resolution |
| `GET /api/v1/ack/{ack_token}` | unguessable token in the URL | contacts must be able to acknowledge from a message without an account |

Recommended hardening once the hostname is public: a DSM/nginx rate limit
on `/api/v1/`, and keeping the container's host port published only on
the LAN/VPN interface so the sole public path is DSM's TLS terminator.
Keeping a private path (VPN/tailnet) working alongside the public
hostname gives the companion a manual fallback if DNS or the router
fails.

## 5. Point the Android companion at it

In the companion app settings:
- Server URL: `https://cryomonitor.your-ddns.synology.me`
- API token: the `CM_API_TOKEN` value

Then run a **fire-drill TEST alarm** from the app and confirm the
escalation messages arrive and the dashboard
(`https://…/api/v1/status`) shows the event.

## 6. Keep it alive

- Container Manager restarts the containers on boot
  (`restart: unless-stopped` is set in the compose file).
- Add an uptime check (e.g. UptimeRobot on `/api/v1/status`, or a second
  ntfy topic) so YOU are alerted when the NAS or tunnel is down — the
  phone-side mutual watchdog will also warn the wearer.
- DSM auto-update: keep minor updates on; Container Manager projects
  survive DSM restarts.

## Debug logging

The server's debug mode is controlled by one environment variable — no
rebuild needed:

1. Edit `/volume1/docker/cryomonitor/.env` and set `CM_LOG_LEVEL=DEBUG`.
2. Container Manager → Project → `cryomonitor` → **Action → Build/Restart**
   (or `docker compose up -d` over SSH). Set back to `INFO` when done —
   DEBUG logs every phone heartbeat.

**Viewing logs** (three options):
- Container Manager → **Container** → `cryomonitor-monitor-1` → **Logs**
  tab — live view, filterable, exportable from DSM.
- SSH: `docker compose -f /volume1/docker/cryomonitor/docker-compose.yml logs -f monitor`
- The in-app event log survives at `GET /api/v1/status` (`recent_events`)
  regardless of log level — alarms, sends, ACKs, dead-man transitions.

What each level shows: `INFO` = events that matter (alarms, escalation
sends, ACKs, dead-man transitions, config changes). `DEBUG` = adds every
heartbeat with battery/watch-age payloads and per-cycle escalation
evaluation — use it when diagnosing a specific incident, not permanently
(log volume, and DSM rotates container logs by size).

Companion pieces: the Android app has a **Debug mode** switch in settings
(extensive logs to a ring buffer + daily file, viewable/shareable via
**View logs** in the app; files under
`Android/data/org.cryomonitor.companion/files/logs/`). The same switch
pushes the toggle to the watch, whose debug output is readable with
`pebble logs` over the developer connection.

## Multi-wearer administration (server v0.2+)

The server hosts multiple wearers (family / response group).

**Wearer onboarding is app-first**: an operator issues an enrollment
code (below), the wearer taps "Enroll with a code" in the Android app,
and manages contacts, tiers, and self-notification from the app's
"Contacts & safety net" screen. The curl examples below are
**operator-only** (wearer creation, code issuance) and are superseded by
the web dashboard (below) — day-to-day contact upkeep happens in the
app or the dashboard, not over SSH. Contacts discover their Telegram
chat id by pressing Start on the deployment's bot, which replies with it
([TELEGRAM-BOT.md](TELEGRAM-BOT.md)).

Operator administration with `CM_ADMIN_TOKEN` from `.env`:

```bash
BASE=https://cm.example.com
AUTH="Authorization: Bearer $CM_ADMIN_TOKEN"

# Create a wearer and hand them an enrollment code (shown once, 24 h TTL,
# single use — the Android app exchanges it for its own token):
curl -sX POST $BASE/api/v1/admin/wearers -H "$AUTH" \
     -H 'Content-Type: application/json' -d '{"id":"alice","name":"Alice"}'
curl -sX POST $BASE/api/v1/admin/wearers/alice/enroll-code -H "$AUTH"

# Contacts and tiers for any wearer (wearers manage their own from the
# app with their own token; admin uses ?wearer_id=):
curl -sX POST "$BASE/api/v1/contacts?wearer_id=alice" -H "$AUTH" \
     -H 'Content-Type: application/json' \
     -d '{"name":"Bob","telegram_chat_id":"123456789","tier_name":"primary"}'
curl -s "$BASE/api/v1/contacts?wearer_id=alice" -H "$AUTH"

# Fleet status (all wearers) vs a wearer's own status:
curl -s $BASE/api/v1/status -H "$AUTH"
```

**DEGRADED**: a wearer with no contact that has at least one channel
address cannot escalate to anyone. Their own status says so
(`"degraded": true`) and the public `/api/v1/health` reports only a
count (`wearers_degraded`) — never names. Fix it by adding a contact;
no restart needed.

**Upgrading an existing single-token deployment**: keep `CM_API_TOKEN`
in `.env` for the first boot — the server migrates it into wearer
`default` automatically and the already-configured phone keeps working.

## Web dashboard (server v0.3+)

The operator dashboard lives at `https://<your-host>/ui/` — same process,
same hostname, TLS from DSM, nothing new to expose.

**First login**: set `CM_UI_ADMIN_USER` and `CM_UI_ADMIN_PASSWORD` in
`.env`, restart the project once — the initial admin account is created
from them exactly once — then **remove both lines from `.env`**. Log in
at `/ui/login` and manage further accounts under Operators.

**Roles**: `admin` (everything) and `responder` (view fleet + wearer
detail, acknowledge and resolve escalations; no administration).
Create one responder account per response-group member.

**CM_ADMIN_TOKEN is retired** the moment the first admin account exists:
API admin calls with the env token return 403 with a pointed message.
Admin API routes accept operator sessions instead; remove
`CM_ADMIN_TOKEN` from `.env` after the dashboard bootstrap.

**Behind DSM's reverse proxy** set `CM_FORWARDED_ALLOW_IPS` in `.env` to
the docker bridge gateway the proxy connects from (visible in the
container log, e.g. `172.23.0.1`). Without it every caller appears as
the proxy: the per-address limits below become one shared bucket and
the audit trail records the proxy. Port 8080 is published on the NAS,
so LAN clients could spoof the forwarding header — bind it to the NAS
(`127.0.0.1:8080:8080`) if that matters on your network.

**Hardening**: login is rate-limited (5 attempts / 15 min per account
and per address) and every failure is audited (Audit page). Passwords
are scrypt-hashed; sessions are HttpOnly+Secure cookies revocable
server-side (password reset and account disable kill live sessions).
Keep the dashboard behind HTTPS only.

## Backing up the database

State (wearers, contacts, escalations, ACK tokens, event log) lives in
`CM_DATA_DIR/cryomonitor.db` with SQLite WAL. Never copy the live
`*.db`/`-wal`/`-shm` files directly. Either stop the container and copy,
or take an online-consistent snapshot:

```bash
docker exec cryomonitor-monitor-1 python - <<'PY'
import sqlite3
src = sqlite3.connect("/srv/data/cryomonitor.db")
dst = sqlite3.connect("/srv/data/backup-cryomonitor.db")
src.backup(dst); dst.close(); src.close()
PY
```

then archive `backup-cryomonitor.db` off the volume. Add this to the
NAS's daily backup task; the deployment is not production-trustworthy
until restarts AND disk loss are both survivable.

## Notes

- Data is currently in-memory (v0.1 scaffold); SQLite persistence lands in
  M2 and will live in the mounted `./data` volume, surviving container
  rebuilds.
- The `ntfy` container needs no account: contacts install the ntfy app and
  subscribe to their topic URL, e.g. `https://nas:8090/cm-relative1`.
