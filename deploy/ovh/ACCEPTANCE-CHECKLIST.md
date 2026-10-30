# Acceptance checklist (run after the VPS exists; results go in deployment-handover.md as PASS or FAIL)
BASE = https://vps-xxxxxxxx.vps.ovh.net (staging now), later https://aura-shots.com (prod).

Server and staging (Phase 1)
- [ ] 0. Provision script runs twice without errors (idempotent); `ufw status` shows only 22, 80, 443; `ss -tlnp` shows 8080/8081 not reachable from another machine (`nc -vz HOST 8081` fails).
- [ ] 1. `curl -sI http://HOST/` redirects to https; certificate valid (`curl -sv`); `curl -s $BASE/api/health` gives `{"status":"ok"}`. (architecture s8 check 1)
- [ ] 2. `curl -sI $BASE/` shows F35 security headers and unchanged origin Cache-Control; compression on text (`curl -sH 'Accept-Encoding: gzip' -I`).
- [ ] 3. Real client IPs: 11 enquiry POSTs from one address, the 11th is 429, the journal shows my address (TRUSTED_PROXY_HOPS=1); also send a forged `X-Forwarded-For` and confirm it is ignored.
- [ ] 4. `sudo reboot`: both units and Caddy back without manual action, data intact.
- [ ] 5. kill -9 during a booking POST, restart: no half-written booking; replay of the same Idempotency-Key returns one booking.
- [ ] 6a. F40 full run on the host: `bb memory-check --seconds 300 --workers 20` (stop the other environment's load meanwhile, then repeat with both running); peak RSS per service under the unit limits, no OOM kill in `journalctl -k`, swap use noted.
- [ ] 6b. F41: `bb restore-drill` on the host, and again from an off-site copy once a target exists.
- [ ] 6c. F42: Gmail mail sent and received (only when OWNER_EMAIL and an app password exist; otherwise recorded as deferred by the owner).
- [ ] 7. Disk alert: set DISK_ALERT_PERCENT=1 temporarily, run `systemctl start aurashots-backup`, unit fails with the ALERT line; restore the value.
- [ ] 8. Monthly cost listed line by line from COSTS.md (VPS-1 plus domain /12) at or below 15 USD.
- [ ] 9. F45 on staging: `curl -s $BASE/robots.txt` is `User-agent: *` and `Disallow: /`; `curl -sI $BASE/` has `X-Robots-Tag: noindex`; sitemap empty or lists only production URLs, never staging.
- [ ] 10. F47: `curl -sI $BASE/assets/og-image.png` returns 200 image/png; canonical and og:image on pages use the staging origin.
- [ ] 11. Staging wiring: pages load and API calls succeed in a browser (same origin, nothing points to a production host); `systemctl is-active aurashots-prod` is inactive or serving only its own data.
- [ ] 12. Backup timer: `systemctl list-timers` shows it; one manual run succeeds; snapshot and uploads copy present under /var/backups/aurashots.
Production (Phase 2, only after owner approval): checks 1-10 again against aura-shots.com, with check 9 reversed (normal robots.txt with a `Sitemap:` line on the production origin, no noindex on `/`), plus www to apex redirect, off-site backup restored in a drill, and the owner submitting the sitemap in Search Console.
