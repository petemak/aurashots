# Deployment handover – non-binding leads from the architect

These are technical facts found while designing, not decisions. The binding requirements are in architecture.md section 8. No provider, plan or price research was done by the architect.

## Checked on 2026-10-29
- Datalevin: latest release found is 0.10.18 (28 May 2026). It is embedded (LMDB-based) and ships a command-line `copy` that makes a compacted backup of a database directory, for example `dtlv -d /src/dir -c copy /backup/dir`. An embedded-only artefact (`org.datalevin/datalevin-embedded`) with bundled native libraries exists on Clojars. Deployment must confirm that the host CPU architecture and operating system are among the platforms with bundled native libraries, and which JDK version and `--add-opens` flags the current README requires.
- Pedestal: 0.8.0 (Sep 2025) and 0.8.1 (Oct 2025) are current; the new default router is Sawtooth, static files are now routed through the router, and `io.pedestal.http` was replaced by `io.pedestal.connector`.
- Datomic (not chosen): documentation shows a dev transactor default heap of 1 GB and dev storage running inside the transactor JVM, which is why it was rejected for the memory budget.

## Things to verify when researching hosts
- The host allows outbound SMTP over TLS to Gmail (ports 465 or 587); many low-cost hosts restrict outbound mail ports.
- It offers a persistent disk (not an ephemeral container filesystem) of at least 5 GB inside the 15 USD limit, and a way to take or copy off-host backups automatically.
- It forwards the real client address in `X-Forwarded-For` and terminates TLS with automatic renewal.
- Free or cheap options for the domain (owner request: cheapest way to register and run aura-shots.com) are a separate research task; nothing was investigated here.
- Gmail free-account sending limits (a few hundred messages a day) were accepted by the owner for launch; expected volume is far lower.

## Useful operational notes
- The application writes its own nightly snapshot to `BACKUP_DIR`; the host's job is copying it and `uploads/` off the machine and testing restore.
- Deploys are stop-then-start (one process owns the database directory); schedule them outside opening hours.
- Environment variable names are listed in architecture.md section 8.

## Deployment engineer, 30 Oct 2026: decisions and open questions
Hosting is decided: a dedicated OVH VPS-1 (monthly), staging and production on the one server, staging at https://vps-e7fdfda9.vps.ovh.net. Fly is not used. Details: deploy/DECISIONS.md, prices deploy/COSTS.md, operations deploy/ovh/RUNBOOK.md.

Still open, to be answered before production (recommendation marked *):
1. Domain: (a) Cloudflare Registrar about 10.46 USD per year, needs a card *; (b) Porkbun 11.08; (c) Spaceship about 9.98 renewal (unverified).
2. DNS and CDN: Cloudflare free proxy in front of the site * or plain DNS. (With the proxy the server must trust two hops.)
3. Off-site backup (required by the architecture before launch): (a) Cloudflare R2, free up to 10 GB, needs a payment method on the Cloudflare account and an API token *; (b) Backblaze B2; (c) Google Drive, no card.
4. Email: a Gmail address and an app password, or leave email off for launch *.
5. Staging review: open the staging site (login in the lead's report) and approve for production or list changes.
