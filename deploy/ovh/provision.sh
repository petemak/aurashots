#!/bin/bash
# Idempotent provisioning of the Aura Shots OVH VPS (Ubuntu 24.04 or 26.04 LTS; 26.04 tested). Run ON THE SERVER as root, from a copy of
# this directory:   sudo STAGING_HOST=vps-xxxxxxxx.vps.ovh.net ./provision.sh
# Safe to run again: every step checks before it changes anything. Secrets are generated on the server on first run
# only and never printed. Does not start the application (there is no release yet; use deploy.sh).
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)"; exit 1; }
: "${STAGING_HOST:?set STAGING_HOST to the VPS default hostname, e.g. vps-1a2b3c4d.vps.ovh.net}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SVC_USER=aurashots
JDK_PKG="${JDK_PKG:-temurin-25-jre}"   # staging image used Temurin 25 and QA ran JDK 25; Datalevin 1.2.10 verified there
BB_VERSION="${BB_VERSION:-}"           # babashka for the restore drill and memory check; empty = latest release

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl gnupg apt-transport-https ufw unattended-upgrades fail2ban rsync \
  debian-keyring debian-archive-keyring openssl unzip

# --- Java runtime (Adoptium apt repository) ---
if ! dpkg -s "$JDK_PKG" >/dev/null 2>&1; then
  install -d -m 0755 /etc/apt/keyrings
  [ -f /etc/apt/keyrings/adoptium.gpg ] || curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo "$VERSION_CODENAME") main" > /etc/apt/sources.list.d/adoptium.list
  apt-get update -y
  apt-get install -y "$JDK_PKG"
fi

# The F40 memory check generates its 6000 px test upload with `java Gen.java`, which needs the JDK compiler module that
# the JRE lacks. Installing the matching JDK keeps the check complete; the services run the same version either way.
dpkg -s temurin-25-jdk >/dev/null 2>&1 || apt-get install -y temurin-25-jdk

# --- Caddy (official Cloudsmith repository) ---
if ! command -v caddy >/dev/null; then
  [ -f /usr/share/keyrings/caddy-stable-archive-keyring.gpg ] || curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y
  apt-get install -y caddy
fi

# --- babashka (for `bb restore-drill` and `bb memory-check` on the host) ---
if ! command -v bb >/dev/null; then
  curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install -o /tmp/bb-install
  bash /tmp/bb-install --dir /usr/local/bin ${BB_VERSION:+--version "$BB_VERSION"}
  rm -f /tmp/bb-install
fi

# --- service user and directories ---
id -u "$SVC_USER" >/dev/null 2>&1 || useradd --system --home-dir /opt/aurashots --shell /usr/sbin/nologin "$SVC_USER"
for env in staging prod; do
  install -d -o root -g "$SVC_USER" -m 0755 "/opt/aurashots/$env" "/opt/aurashots/$env/releases"
  for d in "" /uploads /backups; do install -d -o "$SVC_USER" -g "$SVC_USER" -m 0750 "/var/lib/aurashots/$env$d"; done
done
install -d -o root -g "$SVC_USER" -m 0750 /etc/aurashots
install -d -o root -g root -m 0750 /var/backups/aurashots

# --- environment files: created once from the templates; TOKEN_PEPPER generated here, per environment ---
for env in staging prod; do
  f="/etc/aurashots/$env.env"
  if [ ! -f "$f" ]; then
    install -o root -g "$SVC_USER" -m 0640 "$HERE/$env.env.example" "$f"
    sed -i "s|^TOKEN_PEPPER=.*|TOKEN_PEPPER=$(openssl rand -hex 32)|" "$f"
    echo "created $f (edit PUBLIC_BASE_URL; keep a copy of TOKEN_PEPPER somewhere safe: it is not in the backups)"
  fi
done
sed -i "s|^PUBLIC_BASE_URL=.*|PUBLIC_BASE_URL=https://$STAGING_HOST|" /etc/aurashots/staging.env
[ -f /etc/aurashots/backup.conf ] || install -o root -g root -m 0640 "$HERE/backup.conf.example" /etc/aurashots/backup.conf

# --- systemd units, activation and backup scripts ---
install -m 0644 "$HERE/aurashots-staging.service" "$HERE/aurashots-prod.service" \
  "$HERE/aurashots-backup.service" "$HERE/aurashots-backup.timer" /etc/systemd/system/
install -m 0755 "$HERE/activate.sh" /usr/local/sbin/aurashots-activate
install -m 0755 "$HERE/backup.sh" /usr/local/sbin/aurashots-backup
systemctl daemon-reload
systemctl enable aurashots-staging.service aurashots-backup.timer >/dev/null
# aurashots-prod is enabled by aurashots-activate on its first successful release (no release yet: it would crash-loop)
systemctl start aurashots-backup.timer

# --- Caddy: staging block now; the production block stays commented in the template until the domain exists ---
sed "s|@@STAGING_HOST@@|$STAGING_HOST|g" "$HERE/Caddyfile.template" > /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl enable caddy >/dev/null
systemctl reload-or-restart caddy

# --- swap: 2 GB file, low swappiness ---
if ! swapon --show=NAME --noheadings | grep -q '^/swapfile$'; then
  [ -f /swapfile ] || { fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile; }
  swapon /swapfile
fi
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
echo 'vm.swappiness=10' > /etc/sysctl.d/90-aurashots.conf; sysctl -q -p /etc/sysctl.d/90-aurashots.conf

# --- firewall: only SSH, HTTP, HTTPS in (the app ports 8080/8081 are not reachable from outside) ---
ufw default deny incoming >/dev/null; ufw default allow outgoing >/dev/null
ufw allow 22/tcp >/dev/null; ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null

# --- unattended security updates (no automatic reboot: reboot in a quiet hour, see RUNBOOK) ---
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CONF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
CONF

# --- SSH: key-only login. Only applied with SSH_HARDEN=yes, and only when a key is installed for the login user.
# The operator must then open a SECOND connection to prove key login works (see RUNBOOK); revert with
#   sudo rm /etc/ssh/sshd_config.d/10-aurashots.conf && sudo systemctl reload ssh
LOGIN_USER="${SUDO_USER:-root}"; LOGIN_HOME="$(getent passwd "$LOGIN_USER" | cut -d: -f6)"
if [ "${SSH_HARDEN:-no}" = yes ] && [ -s "$LOGIN_HOME/.ssh/authorized_keys" ]; then
  printf 'PasswordAuthentication no\nPermitRootLogin prohibit-password\n' > /etc/ssh/sshd_config.d/10-aurashots.conf
  sshd -t && systemctl reload ssh
  echo "SSH password login disabled; verify a second key-based connection NOW before closing this session"
else
  echo "SSH hardening skipped (set SSH_HARDEN=yes to apply)"
fi
systemctl enable --now fail2ban >/dev/null

echo "provisioned. Next: deploy.sh staging <user@host>"
