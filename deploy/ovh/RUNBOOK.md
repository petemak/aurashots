# Runbook - Aura Shots on the OVH VPS (staging and production on one server)

Server: OVH VPS-1 (2 vCore, 4 GB, NVMe), Ubuntu 26.04 LTS (as delivered), Europe. Two separate services on one box:

| | staging | production |
|---|---|---|
| unit | aurashots-staging | aurashots-prod |
| internal port | 8081 | 8080 (neither is reachable from outside; the firewall allows only 22, 80, 443) |
| env file | /etc/aurashots/staging.env | /etc/aurashots/prod.env |
| data | /var/lib/aurashots/staging | /var/lib/aurashots/prod |
| code | /opt/aurashots/staging/current (symlink to releases/<id>) | /opt/aurashots/prod/current |
| public name | the VPS hostname vps-xxxxxxxx.vps.ovh.net | aura-shots.com (later; block is commented in the Caddyfile) |
Each runs as the unprivileged user `aurashots`, `java -Xmx512m ...` as in scripts/backend/run.clj, with MemoryHigh/MemoryMax (staging 900M/1100M, prod 1300M/1600M), restart always, 30 s graceful stop. Caddy terminates TLS (automatic certificates, HTTP redirects to HTTPS, gzip/zstd).

## First-time setup
1. `scp -r deploy/ovh <user>@<host>:` then on the server `cd ovh && sudo STAGING_HOST=vps-xxxxxxxx.vps.ovh.net ./provision.sh` (safe to repeat).
2. Check `/etc/aurashots/staging.env` (PUBLIC_BASE_URL is filled in). Copy TOKEN_PEPPER of each env to a password manager: it is generated on the server, is not in any backup, and must never change after launch.
3. From your machine: `STAGING_URL=https://vps-xxxxxxxx.vps.ovh.net deploy/ovh/deploy.sh staging <user>@<host>`.
4. Create the first owner (service stopped; the link goes to a root-only file, not the log):
   `sudo systemctl stop aurashots-staging; sudo bash -c 'umask 077; systemd-run --quiet --wait --pipe -p User=aurashots -p EnvironmentFile=/etc/aurashots/staging.env -p WorkingDirectory=/opt/aurashots/staging/current -p ReadWritePaths=/var/lib/aurashots/staging -p ProtectSystem=strict -p PrivateTmp=true /usr/bin/java -Xmx512m --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -cp aurashots.jar clojure.main -m aurashots.cli create-owner --name "Name" --email owner@example.com > /root/owner-link.txt'; sudo systemctl start aurashots-staging`. The same pattern with `owner-link` resets a lost password. Delete /root/owner-link.txt once used. (Plain `sudo -u` does not carry the environment file; use systemd-run.)
5. SSH hardening (done on this server): `sudo SSH_HARDEN=yes ./provision.sh`, then immediately open a second connection with `ssh -o PreferredAuthentications=publickey -o PasswordAuthentication=no user@host`. If it fails, from the first session: `sudo rm /etc/ssh/sshd_config.d/10-aurashots.conf && sudo systemctl reload ssh`.

## Memory check and restore drill on the host
`sudo bash -c 'cd /opt/aurashots/staging/current && sudo -u aurashots env HOME=/tmp bb scripts/backend/memory-check.clj --seconds 300 --workers 20 --limit-mb 1100 --jar aurashots.jar'` (needs the JDK package, installed by provision.sh, for the test upload). Restore drill: same prefix with `env HOME=/tmp DATA_DIR=/var/lib/aurashots/<env> bb scripts/backend/restore-drill.clj --jar aurashots.jar --data-dir /var/lib/aurashots/<env>`.

## Deploy, roll back
`deploy/ovh/deploy.sh <staging|prod> <user@host>` builds the web bundle for that environment's origin (the API is same-origin, so the staging bundle never points at production), builds the uberjar, copies a release, then `aurashots-activate` stops the service, switches `current`, starts it and polls /api/health for 90 s. On failure it switches back to the previous release automatically. Manual rollback: `sudo ln -sfn /opt/aurashots/<env>/releases/<old> /opt/aurashots/<env>/current && sudo systemctl restart aurashots-<env>`. Code rollback does not undo a database migration: if the new release migrated data, restore a snapshot instead. Deploy outside opening hours (a few seconds of downtime). Production deploy needs `AURASHOTS_PROD_APPROVED=yes`, which is only set after the owner approves staging.

## Logs, status
`systemctl status aurashots-prod`, `journalctl -u aurashots-prod -f`, `journalctl -u caddy`, `free -m`, `systemctl list-timers`. Backup job result: `journalctl -u aurashots-backup`; a failed unit means a stale snapshot, a failed off-site copy or disk over 80 percent.

## Backups and restore
The app writes `db-YYYYMMDD` (14 kept) and a weekly `export-YYYYMMDD.edn` (8 kept) to `<data>/backups`. `aurashots-backup` (nightly 01:30 UTC) checks they are fresh, archives them to /var/backups/aurashots/<env>/daily (14 kept), mirrors uploads, runs OFFSITE_CMD if set, and flags disk above 80 percent. **Until /etc/aurashots/backup.conf has an OFFSITE_CMD these copies are on the same server and do not survive losing it.** The VPS provider's own backup option is the same provider, not a substitute. Restore drill: `cd /opt/aurashots/prod/current && DATA_DIR=/var/lib/aurashots/prod bb restore-drill` (safe next to the running server). To restore for real: stop the unit, move the db directory aside, copy the snapshot in, copy uploads, start, check.

## Reboots and updates
Security updates install automatically; kernel updates need a reboot: `sudo reboot` at a quiet hour; units and Caddy start by themselves (acceptance check 4). Java is updated with apt (`temurin-25-jre`); after any Datalevin upgrade run the restore drill first.

## Adding the domain later (production)
Register aura-shots.com, point its A record at the VPS, uncomment the production block in /etc/caddy/Caddyfile (and `www` redirect), `sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy`, deploy prod with PROD_URL=https://aura-shots.com (APP_ENV=prod serves normal robots.txt and a sitemap on that origin). Optionally set `PRODUCTION_BASE_URL=https://aura-shots.com` in staging.env so the staging sitemap names it. If Cloudflare proxy is switched on, set TRUSTED_PROXY_HOPS=2 and see the Caddyfile comment.

## Known limits
- One VPS holds both environments: a staging memory spike can affect production; the cgroup limits reduce but do not remove this. A server loss loses both until restored from an off-site copy.
- SMTP: the VPS must reach smtp.gmail.com:587/465 (OVH may block port 25 only); verified in the checklist.
- The app binds 0.0.0.0 (config has no host variable outside dev); only the firewall keeps 8080/8081 private. Keep ufw enabled.
