#!/bin/bash
# Run on the owner's machine. Builds web bundle + uberjar locally, copies a release to the VPS, activates it
# (stop, switch, start, health check, automatic rollback).
#   staging:  STAGING_URL=https://vps-xxxx.vps.ovh.net deploy/ovh/deploy.sh staging ubuntu@HOST
#   prod:     AURASHOTS_PROD_APPROVED=yes PROD_URL=https://aura-shots.com deploy/ovh/deploy.sh prod ubuntu@HOST
# Deploys stop the service for a few seconds: run outside opening hours (Mon-Sat 08:00-22:00, Sun 10:00-21:30 Kigali).
set -euo pipefail
ENV_NAME="${1:?staging|prod}"; TARGET="${2:?user@host}"
cd "$(cd "$(dirname "$0")/../.." && pwd)"
case "$ENV_NAME" in
  staging) URL="${STAGING_URL:?set STAGING_URL (the https origin of staging)}" ;;
  prod) [ "${AURASHOTS_PROD_APPROVED:-}" = yes ] || { echo "refusing: production needs the owner's explicit approval (AURASHOTS_PROD_APPROVED=yes)"; exit 1; }
        URL="${PROD_URL:?set PROD_URL}" ;;
  *) echo "env must be staging or prod"; exit 2 ;;
esac
REL="$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"
echo "== build (APP_ENV=$ENV_NAME PUBLIC_BASE_URL=$URL)"
APP_ENV="$ENV_NAME" PUBLIC_BASE_URL="$URL" bb scripts/web/build.clj
clojure -T:build uber
STAGE="$(mktemp -d)"; trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/business/images" "$STAGE/dist"
cp target/aurashots.jar "$STAGE/"; cp -r dist/web "$STAGE/dist/"; cp -r business/images/shots "$STAGE/business/images/"
cp -r scripts "$STAGE/scripts"   # for bb restore-drill / memory-check on the host
echo "== copy release $REL"
ssh "$TARGET" "sudo install -d -o \$(id -un) /opt/aurashots/$ENV_NAME/releases/$REL"
rsync -a "$STAGE/" "$TARGET:/opt/aurashots/$ENV_NAME/releases/$REL/"
echo "== activate"
ssh "$TARGET" "sudo /usr/local/sbin/aurashots-activate $ENV_NAME $REL"
echo "deployed $REL to $ENV_NAME; now run the acceptance checks (deploy/ovh/ACCEPTANCE-CHECKLIST.md)"
