# Deployment decisions - Aura Shots

Dated 30 Oct 2026. Options and prices with sources: COSTS.md. Operations: ovh/RUNBOOK.md.

| # | Decision | Status | Why |
|---|---|---|---|
| 1 | Production host: a NEW dedicated OVH VPS-1 (2 vCore, 3.7 GiB RAM, 38 GB disk, Europe), hostname vps-e7fdfda9.vps.ovh.net, IPv4 159.173.131.80. The OS delivered is Ubuntu 26.04 LTS (24.04 was planned). Not the Zandy Touch servers; Fly is not used for Aura Shots. | Owner decision | Cheapest option meeting architecture section 8, always on. |
| 2 | Billing term: monthly, no commitment. VPS-1 5.35 USD per month ex tax (source in COSTS.md, checked 30 Oct 2026); confirm against the OVH invoice. | Owner confirmed | |
| 3 | Staging and production share the VPS as two systemd services (ports 8081 and 8080), separate data dirs (/var/lib/aurashots/staging, /prod) and env files. | Owner confirmed | One bill; cgroup memory limits keep them apart. |
| 4 | Staging URL: https://vps-e7fdfda9.vps.ovh.net (OVH default hostname, Let's Encrypt via Caddy). APP_ENV=staging; PRODUCTION_BASE_URL unset so the sitemap is empty; robots Disallow /, X-Robots-Tag noindex (application generated, no files written by hand). | Done | No domain needed yet. |
| 5 | Stack on the VPS: Temurin 25 JRE from the Adoptium apt repository (it publishes a `resolute` suite for 26.04; same JDK major QA tested), Caddy from the official repository, babashka, ufw (22/80/443), fail2ban, unattended upgrades, 2 GB swap. Services run as the unprivileged user `aurashots` with systemd sandboxing (ProtectSystem=strict, PrivateTmp, NoNewPrivileges): it works with Datalevin without relaxation (checked). | Done | |
| 6 | SSH: key login only, password login disabled after a second key-based connection was proved. | Done | |
| 7 | Staging on Fly (app aurashots-staging) was removed on 30 Oct 2026 by the lead on the owner's instruction, with its files in deploy/. No Fly resource is used or planned for Aura Shots. | Done | |
| 8 | Staging owner account "Staging Owner", owner@aura-shots.test (made-up address; OWNER_EMAIL and SMTP unset, email queued). The one-time link is kept only in a root-only file on the server and given to the lead, never in the repo or logs. | Done | |
| 9 | Domain and registrar (research: Cloudflare Registrar about 10.46 per year, Porkbun 11.08, Spaceship about 9.98 renewal unverified) | OPEN, deferred to production | |
| 10 | Cloudflare proxy/CDN (needs TRUSTED_PROXY_HOPS=2 and Caddy trusted proxies if used) | OPEN, deferred | |
| 11 | Off-site backup target (options: Cloudflare R2, Backblaze B2, Google Drive via rclone). Until chosen, backups are on the VPS only. | OPEN, deferred. Blocks production launch (architecture section 8). | |
| 12 | Email: OWNER_EMAIL and Gmail app password | OPEN, deferred | |
