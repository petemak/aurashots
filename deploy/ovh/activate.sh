#!/bin/bash
# Installed as /usr/local/sbin/aurashots-activate. Usage: aurashots-activate <staging|prod> <release-id>
# Stops the service (one process owns the database), points `current` at the release, starts it and checks health.
# If the health check fails, the previous release is restored and started; exit code 1.
# Note: rollback restores the previous CODE only. If the new release migrated the database forward, restore from a
# snapshot instead (RUNBOOK).
set -euo pipefail
ENV_NAME="${1:?staging|prod}"; REL="${2:?release id}"
case "$ENV_NAME" in staging|prod) ;; *) echo "bad env"; exit 2;; esac
BASE="/opt/aurashots/$ENV_NAME"; UNIT="aurashots-$ENV_NAME"
[ -f "$BASE/releases/$REL/aurashots.jar" ] || { echo "release $REL not found"; exit 2; }
PORT="$(grep -E '^PORT=' /etc/aurashots/$ENV_NAME.env | cut -d= -f2)"
chown -R root:aurashots "$BASE/releases/$REL"; chmod -R u=rwX,g=rX,o= "$BASE/releases/$REL"
PREV="$(readlink -f "$BASE/current" 2>/dev/null || true)"
healthy() { for _ in $(seq 1 45); do curl -fs "http://127.0.0.1:$PORT/api/health" | grep -q '"ok"' && return 0; sleep 2; done; return 1; }
systemctl stop "$UNIT" || true
ln -sfn "$BASE/releases/$REL" "$BASE/current"
systemctl start "$UNIT"
if healthy; then
  systemctl enable "$UNIT" >/dev/null 2>&1 || true
  echo "release $REL is healthy on port $PORT"
  ls -1dt "$BASE"/releases/*/ | tail -n +4 | xargs -r rm -rf   # keep newest 3
  exit 0
fi
echo "release $REL FAILED the health check; rolling back" >&2
journalctl -u "$UNIT" -n 40 --no-pager >&2 || true
systemctl stop "$UNIT" || true
if [ -n "$PREV" ] && [ -d "$PREV" ]; then
  ln -sfn "$PREV" "$BASE/current"; systemctl start "$UNIT"
  healthy && echo "rolled back to $(basename "$PREV")" >&2 || echo "ROLLBACK ALSO UNHEALTHY" >&2
else
  echo "no previous release to roll back to" >&2
fi
exit 1
