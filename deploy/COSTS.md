# Hosting costs - Aura Shots

## UPDATE 30 Oct 2026: decision taken, see DECISIONS.md
Production and staging both run on one new OVH VPS-1 (2 vCore, 4 GB, 40 GB NVMe, Europe). Fly is not used for Aura Shots, so the Fly rows below are historical comparison only.
Running cost now: VPS-1 5.35 USD per month ex tax on a month-to-month term, or 4.54 with a 12-month commitment (OVH page https://www.ovhcloud.com/en/vps/ shows 4.54 on 12 months; monthly price from https://www.vpsbenchmarks.com/hosters/ovhcloud_us/plans/vps-1-2027; both checked 30 Oct 2026; owner confirmed 30 Oct 2026: ordered MONTHLY, no 12-month commitment, so 5.35 USD per month ex tax applies; total about 6.2 USD per month with the domain). Staging on the same VPS adds 0. Domain (deferred) about 0.87 per month (Cloudflare Registrar 10.46 per year) when bought; Cloudflare proxy, DNS free; off-site backup R2 free tier (deferred). Expected total about 5.4 to 6.2 USD per month before tax, against the 15 USD ceiling. Fly staging was removed on 30 Oct 2026 and is not part of the plan. Memory on 4 GB: two JVMs capped by systemd at 1.1 GB (staging) and 1.6 GB (prod) plus 2 GB swap leaves headroom; the F40 run on the host must confirm this.

Prices checked 30 Oct 2026 (pages named in each row). USD, excluding VAT/tax unless stated. Re-check at purchase time. Budget ceiling: 15 USD per month for hosting (business-context.md); the domain is counted as yearly price / 12.

## Requirements that drive the choice (architecture.md section 8)
One JVM (-Xmx512m), exactly one instance, always on (embedded Datalevin background jobs: nightly snapshot, email worker). At least 1 GB RAM (local F40 peak 817-881 MB, idle ~465 MB, so a 1 GB host needs swap or a larger size), persistent disk of at least 5 GB, HTTPS with renewal, X-Forwarded-For/Proto, automatic restart, outbound SMTP 587/465, nightly copy of backups and uploads to another provider.

## What the owner already has (read-only check, 30 Oct 2026)
- Fly.io: flyctl installed and logged in (org "Petemak", personal). Apps: kigali-keys (running), kigali-keys-staging, esther-botique-db, esther-botique-staging (all stopped/suspended) and aurashots-staging (created by an earlier deployment run today, stopped). Fly requires a card on file for every org ("All organizations require a credit card", docs.fly.io/about/pricing); the account is already in use, so a card is on it. There is no free allowance for machines, so these resources are not free capacity: they are separate apps; the aurashots production app would be new and billed on its own. I changed nothing.
- Netlify: CLI installed and logged in (team "petemak's team"). Sites: esther-botique, esther-botique-staging, kigali-keys (custom domain) and others. Static hosting/functions only.
- OVH: no ovh CLI installed, so I could not check the account. The owner reports an existing OVH account with a payment method; I could not verify any plan, voucher or credit. Ask the owner to confirm what is unused (see questions).

## Production host options
| Option | Monthly price | Meets requirements | Always on / sleeps | Payment | Source (checked 30 Oct 2026) |
|---|---|---|---|---|---|
| Fly.io jnb (Johannesburg), shared-cpu-1x 1 GB + 5 GB volume | Compute 6.70 (iad) x 1.303 = 8.73; volume 5 x 0.15 = 0.75; total 9.48. Billed per second, no commitment. Shared IPv4 and 10 certs free, inbound free, snapshots 0.08/GB with first 10 GB free. Outbound from Africa 0.12 per GB (see risk below). | Yes: persistent volume, auto TLS, XFF, restart on crash, graceful stop. Needs `swap_size_mb` because of the 832 MB F40 peak on 1 GB; F40 must be repeated there. Snapshots are same provider, so off-site copy still needed. | Warm if `min_machines_running = 1` and auto-stop off. Stopped machines wake in 15-25 s (JVM start) and skip background jobs: do not use for production. | Card (already on account). | https://fly.io/pricing.md , https://docs.fly.io/about/pricing |
| Fly.io ams (Amsterdam), same size | 6.70 x 1.038 = 6.95 + 0.75 = 7.70. Outbound Europe 0.02 per GB | Yes, same as above | same | same | https://fly.io/pricing.md |
| Fly.io jnb with 1.5 GB (more memory, no swap reliance) | 1.5 GB = 2.19 + 1.25 GB extra x 6.00 = 9.69 in iad x 1.303 = 12.63 + 0.75 = 13.38 | Yes, leaves about 1.6 USD under the ceiling before the domain, so only if F40 fails with swap | same | same | https://fly.io/pricing.md |
| OVHcloud VPS-1 (2 vCore, 4 GB, 40 GB NVMe, unlimited traffic) | 5.35 month-to-month; 4.54 with a 12-month commitment (sources disagree whether the 12 months are paid up front or monthly: confirm at checkout). Owner reports about 4.66 incl. tax. | Yes on paper and with ample memory (no swap needed). You run it: Docker or systemd, Caddy for TLS, updates, firewall, backups, monitoring. Datacentre location not confirmed (probably Europe, not Africa). Included daily backup is same provider. | Always on. | Card or PayPal (secondary source: vpsbenchmarks / itechguides, not OVH page). Existing account. | https://www.ovhcloud.com/en/vps/ , https://www.vpsbenchmarks.com/hosters/ovhcloud_us/plans/vps-1-2027 |
| Hetzner Cloud, cheapest shared plans | From EUR 5.99 (cost-optimized) and 6.49 (regular), ex VAT. DE, FI, US, SG only. Spec table not on fetched page. | Probably, self-managed like OVH. No clear advantage over OVH, needs a new account. | Always on. | Not verified; new account. | https://www.hetzner.com/cloud/ |
| Netlify | Not a host for this app | See below | n/a | n/a | https://docs.netlify.com/manage/routing/redirects/rewrites-proxies/ |

### Netlify: what role can it play?
The app is one JVM serving pages, API and images from one origin. Netlify has no persistent disk and cannot run it. It could front the app with a status-200 rewrite (proxy) to the backend, but that adds a hop and a second deployable, the proxy times out at 26 s (photo uploads), the backend would see Netlify's address instead of the visitor (rate limiting uses X-Forwarded-For; TRUSTED_PROXY_HOPS and F-checks assume one known edge) and it duplicates what Cloudflare does for free. Netlify DNS would work but the registrar/CDN below does the same. Conclusion: no role; leave the owner's Netlify sites untouched.

### Risk: Fly egress from Johannesburg
Fly charges 0.12 USD per GB outbound from Africa (0.02 from Amsterdam). Photo pages are heavy. Rough guide: 5,000 visits a month at 3 MB each is 15 GB, about 1.80 USD; 50,000 visits would be about 18 USD and break the ceiling. Mitigation: put the free Cloudflare proxy/CDN in front (images are immutable for a year, so most traffic is served from the edge), or choose Amsterdam where the same traffic costs a sixth. Cloudflare in front also means TRUSTED_PROXY_HOPS must count two hops (Cloudflare, then Fly); I would verify the rate-limit check (acceptance check 3) after enabling it.

## Domain (yearly, aura-shots.com)
| Registrar | .com first year / renewal | Payment | Source (checked 30 Oct 2026) |
|---|---|---|---|
| Cloudflare Registrar (at cost, no markup, same price every year) | 10.46 / 10.46 (another list says 10.44) = about 0.87 per month | Card (also needed to enable R2). Requires Cloudflare nameservers (fine, free DNS/CDN). | https://porkbun.com/about/domain-cost-calculator/com.txt (comparison page; domains.cloudflare.com returned 403 to my fetch), https://domaindetails.com/registrars/cheapest |
| Porkbun | 11.08 / 11.08 (another list: 10.37 + 0.18 ICANN) | Card; PayPal reported by third parties, not verified | same |
| Spaceship | renewal 9.98 reported (secondary source), lowest on the lists; first-year promos vary | Not verified | https://dupple.com/learn/cheapest-domain-registrars |
| Namecheap / GoDaddy | 11.48 / 18.68 and 13.19 / 23.19 | not relevant | porkbun comparison above |
No .com is cheaper than about 10 USD a year (registry and ICANN fees), so the owner's 12 USD "too expensive" line cannot be beaten by much; the saving is at most about 0.5 USD a year between the three. A different ending (.shop, .store, etc.) would change the name aura-shots.com and is not recommended.

## DNS and CDN
| Option | Price | Notes | Source |
|---|---|---|---|
| Cloudflare DNS + proxy (free plan) | 0 | Owner already has an account. Honours origin Cache-Control (CDN rules in architecture section 8 apply: never cache /api/admin, /admin, /api/availability, /api/quotes, Set-Cookie responses). Cuts Fly egress. Always-on, no sleeping. | https://developers.cloudflare.com/ |
| Netlify DNS | 0 | Works, but no benefit over Cloudflare. | https://docs.netlify.com/ |
| Registrar DNS only (no CDN) | 0 | Simplest. CDN is optional per architecture. | |

## Off-site backup destination (needs different provider from the host)
| Option | Price | Card? | Notes | Source |
|---|---|---|---|---|
| Cloudflare R2 | Free tier 10 GB-month storage, 1 M Class A, 10 M Class B ops, free egress; beyond: 0.015 per GB-month | A payment method must be attached before the first bucket (third-party reports; Cloudflare page does not say). Never charged within the free tier. | Expected volume: db under 200 MB plus uploads up to 3 GB plus 14 daily and 8 weekly versions; nightly sync of uploads stores only changes. At the 3 GB maximum with versions it may exceed 10 GB: about 0.015 per GB beyond, cents per month. | https://developers.cloudflare.com/r2/pricing/ |
| Backblaze B2 | First 10 GB free, then 6.95 per TB-month (0.007 per GB); 3x egress free | Card or invoice; the page does not state whether signup needs a card | Cheaper per GB; separate company from the host. | https://www.backblaze.com/cloud-storage/pricing |
| Owner's Google Drive via rclone (15 GB free, encrypted with restic or rclone crypt) | 0 | No card | Unverified, extra OAuth setup, less robust for automation. Fallback if no card should be used. | not researched in depth |
Fly volume snapshots are not accepted as the off-site copy (same provider).

## Staging hostname
| Option | Price | Notes |
|---|---|---|
| vps-e7fdfda9.vps.ovh.net (in use) | 0 | OVH default hostname with automatic HTTPS; shares the VPS. |
| staging.aura-shots.com (after domain exists) | 0 extra | Needs the domain and a DNS record; Caddy block and web bundle rebuilt for that origin. Not needed to launch. |

## Combinations against the 15 USD ceiling (monthly, ex tax). Historical: owner chose C on 30 Oct 2026, billed monthly at 5.35
| Combination | Host | Domain /12 | Backups | CDN/DNS | Staging | Total |
|---|---|---|---|---|---|---|
| A. Fly jnb 1 GB + swap, Cloudflare domain, R2, Cloudflare proxy (RECOMMENDED) | 9.48 | 0.87 | 0 (R2 free tier) | 0 | about 1.0 | about 11.4 (10.35 without staging) |
| B. Fly ams 1 GB + swap, same | 7.70 | 0.87 | 0 | 0 | about 1.0 | about 9.6 |
| C. OVH VPS-1 monthly term (12-month commitment), Cloudflare domain, R2, Cloudflare proxy | 5.35 (4.54) | 0.87 | 0 | 0 | about 1.0 on Fly | about 7.2 (6.4) |
| D. Fly jnb 1.5 GB (fallback if F40 fails) | 13.38 | 0.87 | 0 | 0 | about 1.0 | about 15.3: over; without staging 14.25 |
All except D fit under 15 USD. VAT is not included; the owner reported OVH 4.66 with Swiss tax, which suggests 3 to 8 percent tax. Fly shows prices without tax; add any tax on the invoice.

## Fly staging (removed 30 Oct 2026, historical)
Fly app aurashots-staging (destroyed on the owner's instruction, with its volume), jnb, shared-cpu-1x 1 GB, 5 GB volume 0.75 per month, stops when idle (about 8.73/730 = 0.012 per hour running), about 1 USD per month.

## Not yet verified
Whether the Cloudflare account already has a payment method (needed for R2 and the registrar); OVH datacentre location and billing terms at checkout; what unused OVH credit/plans the owner holds; Gmail SMTP sending (needs app password).
