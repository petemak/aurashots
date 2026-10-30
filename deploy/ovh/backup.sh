#!/bin/bash
# Installed as /usr/local/sbin/aurashots-backup, run nightly by aurashots-backup.timer. The application itself writes
# the nightly snapshot (db-YYYYMMDD, newest 14 kept) and the weekly export to BACKUP_DIR while it runs; this job
#  1. fails loudly if the newest snapshot is missing or too old (systemd shows the unit as failed, logged to the journal)
#  2. archives BACKUP_DIR and mirrors uploads/ into /var/backups/aurashots/<env>/ (14 dailies, mode 0700: customer data)
#  3. runs OFFSITE_CMD if set (target not chosen yet; the local copy is NOT off-site)
#  4. alerts (fails) when the data disk is above DISK_ALERT_PERCENT
set -uo pipefail
. /etc/aurashots/backup.conf
umask 077; rc=0
for e in $ENVIRONMENTS; do
  src="/var/lib/aurashots/$e"; dst="/var/backups/aurashots/$e"
  [ -e "/opt/aurashots/$e/current" ] && [ -d "$src/backups" ] || { echo "$e: not deployed yet, skipping"; continue; }
  mkdir -p "$dst/daily" "$dst/uploads"
  newest="$(ls -1d "$src"/backups/db-[0-9]* 2>/dev/null | grep -v '\.counts\.edn$' | sort | tail -n1)"
  if [ -z "$newest" ] || [ -n "$(find "$newest" -maxdepth 0 -mmin +$((SNAPSHOT_MAX_AGE_HOURS*60)))" ]; then
    echo "ALERT $e: newest snapshot missing or older than ${SNAPSHOT_MAX_AGE_HOURS}h (${newest:-none})" >&2; rc=1
  fi
  tar -C "$src" -czf "$dst/daily/backups-$(date -u +%Y%m%d).tar.gz" backups && \
    ls -1t "$dst"/daily/backups-*.tar.gz | tail -n +$((KEEP_DAILY+1)) | xargs -r rm -f
  rsync -a --delete "$src/uploads/" "$dst/uploads/" || rc=1
done
if [ -n "${OFFSITE_CMD:-}" ]; then bash -c "$OFFSITE_CMD" || { echo "ALERT: off-site copy failed" >&2; rc=1; }
else echo "note: OFFSITE_CMD not set, backups exist on this server only"; fi
use="$(df --output=pcent /var/lib/aurashots | tail -1 | tr -dc 0-9)"
[ "$use" -ge "$DISK_ALERT_PERCENT" ] && { echo "ALERT: disk ${use}% used (limit ${DISK_ALERT_PERCENT}%)" >&2; rc=1; }
exit $rc
