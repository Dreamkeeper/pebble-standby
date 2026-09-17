#!/bin/sh
# Consistent online backup of the Standby server database.
#
# Uses SQLite's backup API inside the running container, so it is safe
# while the server is writing (a plain `cp` of a live SQLite file is not).
# Keeps the newest 14 copies in data/backup/. Copy them OFF the machine as
# well — a backup on the same disk protects against mistakes, not against
# losing the VPS.
#
# Cron example (daily 03:15):
#   15 3 * * * /home/standby/pebble-standby/server/scripts/backup.sh >/dev/null
set -eu
cd "$(dirname "$0")/.."
mkdir -p data/backup
STAMP=$(date +%Y%m%d-%H%M%S)

docker compose exec -T monitor python - <<'PY'
import sqlite3
src = sqlite3.connect("/srv/data/cryomonitor.db")
dst = sqlite3.connect("/srv/data/backup/.inprogress.db")
with dst:
    src.backup(dst)
dst.close()
src.close()
PY

mv data/backup/.inprogress.db "data/backup/cryomonitor-$STAMP.db"
# keep the newest 14
ls -1t data/backup/cryomonitor-*.db | tail -n +15 | xargs -r rm -f
echo "backup written: data/backup/cryomonitor-$STAMP.db"
