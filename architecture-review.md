# Architecture review – Aura Shots (MODE 2)

Date: 2026-10-29 (resolution status added the same day, after the design amendment pass and QA report 2). Reviewed: `src/aurashots/`, `deps.edn`, `bb.edn`, `resources/`, `scripts/backend/`, `scripts/check/`, `test/`, `web/`, `scripts/web/build.clj`, against `architecture.md` (including "Owner answers (29 October 2026)"), `engineering-standards.md` and `api-contract.md`.

Method and limits. I had no shell in this review, so I could not run the `bb scripts/check/*.clj` scripts, clj-kondo, the tests or the browser checks (F13-F17, F30-F36, F37-F43 are therefore "not run by me"). I ran the grep-based checks (F2, F5, F6, F8, F10, F19-F22, F25, F27, F29) with ripgrep and read in full: `system.clj`, `platform/{db,config,migrations,mailer,housekeeping,server,crypto}.clj`, `platform/http_core/{interceptors,store,limits,errors,validate}.clj`, `features/pages/{routes,core}.clj`, `features/booking/api.clj`, `features/merch/core.clj`, `shared/slots.clj` (rules part), `cli.clj`, `scripts/**`, `scripts/web/build.clj`, `web/js/api.js`, the migration schema. Other files were checked by grep (requires, store calls, headers) and sampled, not line-reviewed. `media_store.clj`, `outbox_worker.clj`, `logger.clj` and `images.clj` were not read beyond grep.

Result: 59 conforms, 15 deviates, 1 not verifiable (directory ownership needs git authorship, F26). The findings below are the original review text; the section "Resolution status" directly after this paragraph is the current state.

---

## Resolution status (amendment pass, 2026-10-29)

Evidence for "verified": `qa-report.md` (QA report 2, live backend, re-run after fixes: `bb test` 59 tests / 873 assertions / 0 failures, `bb lint` 0 errors, `bb check` 104 routes OK, contract and route table agree, boundaries hold with no cycles). I did not re-run anything in this pass; it edited design documents only.

| # | Status | Note |
|---|---|---|
| D1 | pending backend | Response-schema checking (`:response` Malli schema per route, checked in dev/test, logged in prod) is being implemented by the backend; architecture.md section 5 keeps the requirement. Not yet verified. |
| D2 | pending backend | Weekly logical export in the housekeeping timer (keep 8) is being implemented; architecture.md 2.2 and section 8 state it. Not yet verified. |
| D3 | design amended | architecture.md 3.2 now describes aggregates as nested maps, settings as one object, acceptance / document versions / messages as separate entities, history append-only by convention; decision D15; new check F44 (greps plus a prefix test, the test is to be added by the backend if absent); owner informed (open question 14). |
| D4 | code fixed, verified by QA | QA report 2 item 3 PASS: option toggle and edit-form re-enable bring the product back to `inStock` in API, admin card and storefront. Contract rule written (api-contract.md 2.5, A2 item 4); new check F51. |
| D5 | design amended | architecture.md 2.2 / 2.3 / 2.4 list `pages -> media.api`, the functions injected by `system.clj` and the platform helper namespaces; F24 names the edge. `boundaries.clj` already matches (QA: 28 deps, no cycles). |
| D6 | design amended | F7 reworded: `#profile` has only `:dev` and `:default`; `APP_ENV` validated as dev, staging or prod; staging and prod differ only by environment variables. |
| D7 | design amended | architecture.md section 5 and F35: CSP on HTML responses only, matching api-contract.md section 6. |
| D8 | design amended | architecture.md 2.5, section 5 and decision D13: the build concatenates files (IIFE, `window.AS`), no native ES modules, CSP-safe; new check F50. |
| D9 | code fixed (help text), verified by QA | QA report 2 "previously reported help mismatches": add-service step 6 now says Move up / Move down / Take out, matches the screens. (Stale sentence in `mark-sold-out` help is a new ux-designer item, below.) |
| D10 | code fixed, verified by QA | QA report 2 item 2: builds with `APP_ENV=prod` or `staging` and no `PUBLIC_BASE_URL` fail; zero `aura-shots.com` in `dist/web`. Rule and check F46. Residual: `resources/config.edn` still defaults `:public-base-url` to localhost for every profile; the design now requires it for staging and prod at runtime (backend). |
| D11 | design amended | architecture.md 3.1 pins Datalevin 1.2.10 with the add-opens flags; restore drill after every upgrade; check F49. Owner informed (the owner accepted Datalevin, not a version). |
| D12 | design amended | F40 is a deployment-run check, script `bb memory-check`, run by deployment-engineer on the host. The script is pending backend. |
| D13 | pending backend | F41 is `bb restore-drill`; `scripts/backend/restore-drill.clj` and the `bb.edn` task are being written. Needed before launch and after every Datalevin upgrade. |

New items from QA report 2 that are not design deviations (owners are named; none is mine to edit):
- Backend: `/assets/og-image.png` returned 404 on backend-rendered pages. Design now fixes the URL (stable, unhashed, check F47); the code/build change is pending backend and frontend (build emits the unhashed copy).
- Frontend: block-dates `affectedSessions` warning is wiped by the reload (`web/js/admin/40-ops.js`).
- ux-designer: stale sentence in `admin-help.md` topic `mark-sold-out`; policies "Draft - needs review" badge is an image, not text (accessibility already satisfied via alt text).
- Backend/frontend minor: add `og:image:width/height/alt` for photo-based pages; add a 20-character floor on legal meta descriptions (now in the contract, section 6).

Contract rulings (the ten "assumptions" below) are all written into api-contract.md as Amendment A2; the frontend note there lists what changes for it.

---

## Platform components (architecture.md 2.2)

- config (`platform/config.clj`): conforms. Aero read, Malli validation of the whole map, fail fast, secrets only via `#env`; dev pepper generated into the data dir, required in staging/prod.
- logger (`platform/logger.clj`): conforms. Timbre with jsonista output; no prints outside `cli.clj` (F10 passes).
- clock (`platform/clock.clj`): conforms. Injectable `now-fn`, used by `base-interceptor` and tests.
- db (`platform/db.clj`): conforms. Opens Datalevin, `in-tx` wrapper, `snapshot!` via `copy`, `export-edn!`. Inline `:clj-kondo/ignore [:unresolved-symbol]` on line 30 is acceptable (see Libraries).
- migrations (`platform/migrations.clj`, `resources/migrations/0001..0002`): conforms. Names match `NNNN-name.edn`, consecutive, version checked against file name, applied once and recorded (F9). Schema statements appear only here, `db.clj` bootstrap and `migrations_seed.clj` (allowed).
- mailer (`platform/mailer.clj`): conforms. Unconfigured mailer throws, so the outbox keeps messages queued (owner answer Q1); `system.clj` also skips sending when no contact email is known.
- media-store (`platform/media_store.clj`): conforms (sampled). Config-driven `upload-dir`.
- http-core (`platform/http_core/*`): DEVIATES. Request-side items conform (request id, error mapping, closed Malli schemas with coercion, auth, role, CSRF, idempotency, rate limit, security headers). Response-schema checking in dev/test (architecture 5) is not implemented; there is no `:response` key in the route spec and no response validation anywhere (`rg` finds none). Rule: architecture 5 "Validation" and engineering-standards "Schema Validation: ... incoming/outgoing data payloads". Fix: code (see D1). [Status: pending backend.]
- server (`platform/server.clj`): conforms. Pedestal 0.8 connector with Sawtooth router, HEAD twins, duplicate-route detection, only platform file besides `system.clj` that requires slices' `routes` (F25 passes).
- outbox-worker (`platform/outbox_worker.clj`): conforms. 15 s interval from config, tick function injected by `system.clj`; back-off 1 min, 5 min, 30 min, 2 h, 12 h then failed (`notifications/core.clj`).
- housekeeping (`platform/housekeeping.clj`): DEVIATES. Purges (7 days idempotency, 1 hour rate counters, sessions and tokens) and the 14-snapshot nightly copy conform. The weekly logical export required by architecture 8 ("Backup") is not scheduled; `export` exists only as a CLI command that needs the server stopped. Fix: code (D2). [Status: pending backend.]
- Platform helpers not in the design list (`crypto.clj`, `images.clj`, `migrations_seed.clj`, `http_core/{json,schemas,errors,validate,limits,response,store}.clj`, `shared/{time,errors,whatsapp}.clj`): conforms in spirit (no slice requires, one-way). The architecture's component list is incomplete; add them (D5). [Status: design amended.]

## Feature slices (2.3)

- site: DEVIATES. `features/site/store.clj` keeps the whole settings (hours, booking rules, delivery areas, categories) in one `:setting/data` map, not as `deliveryArea` and `portfolioCategory` entities. Contract shape is unaffected. Fix: design (D3). [Status: design amended.]
- media: conforms. Re-encode, 10 MB and 6000 px limits, single-decode semaphore (`images.clj`), immutable caching (`media/routes.clj:22`).
- catalog: conforms. Price-to-publish and slug rules; requires only `media.api` and `merch.api`.
- merch: DEVIATES. Sold-out rule is stricter than the contract (see assumptions), and there is a latent defect: `merch/core.clj` `set-availability` with an `optionId` flips only that option and leaves the product-level `:inStock false`, and `build` (line 36) preserves the existing flag on edit. After "Mark as sold out" a staff member who re-enables options in the edit form (or via the per-option toggle) still gets a product reported `soldOut`. Fix: code (D4). [Status: code fixed, verified by QA.]
- storefront: conforms. No store; depends on `catalog.api`, `merch.api`.
- portfolio: conforms. Category-in-use check is injected from `system.clj` (no cycle).
- calendar: conforms. Blocks only; depends on `site.api`.
- booking: conforms. Availability and admin calendar live here; confirm/reschedule re-check inside `store/in-tx` (booking/api.clj `transition!`); submit commits booking, acceptance, history, outbox messages and the idempotency record in one transaction (line 107). Note: the slot re-check on submit (line 89) runs before that transaction. Pending requests do not block slots, so a race can only produce a pending request on a slot staff later confirm-checks; acceptable.
- orders: conforms. Same single-transaction pattern (`orders/api.clj:74`).
- enquiries: conforms (`enquiries/api.clj:23`).
- documents: conforms. Acceptance and versions are separate entities; no retract in `documents/*` (F18); no PUT/PATCH/DELETE route on acceptances.
- notifications: conforms. Outbox entity, retry schedule, resend; sending stays in `mailer`.
- auth: conforms. Hashed session ids and tokens (HMAC with pepper), argon2id, 12-character minimum, cookie `HttpOnly; SameSite=Lax; Secure` (not in dev), 5 failures per 15 minutes.
- dashboard: conforms. No store; reads three `api` namespaces.
- pages: conforms. SEO region and data marker rewrite, real 404s, noindex kinds, sitemap, robots, health, path-traversal guard in `serve-static`. (Its extra edge to `media.api` is recorded under boundaries.)

## Data model (section 3)

- Entity model: DEVIATES. `0001-initial-schema.edn` stores each aggregate as one opaque `:x/data` map (nippy, `:db/noindex`): `booking/data`, `order/data`, `service/data`, `product/data`, `setting/data`, `message/data`, `user/data`. Booking and order lines, notes and history entries are nested inside the aggregate, not separate entities as in architecture 3.2. Only identity and query fields (status, dates, references, hashes, refs for images) are real attributes. This is simpler and fits the scale, but "append-only history" for booking and order is now enforced by code convention (`core.clj` functions only conj), not by the data model. Fix: design (D3). [Status: design amended; check F44.]
- Write ownership: conforms. Every `d/transact!`, `d/q`, `d/pull` is in a `store.clj`, `platform/db.clj`, `platform/migrations*.clj` or `http_core/store.clj` (F21 grep returns only those files).
- Acceptance records: conforms. Own entity (`:acceptance/*`), written in the creating transaction, no update or delete function.
- Migrations and seed: conforms. Seed is migration 0002 and contains no secrets (F8 grep empty). `:seed-images-dir "business/images/shots"` is read at first start; if the directory is absent only the placeholder image is created (logged). Deployment must ship that directory for the first start (note for D7 in deployment).

## Boundaries (section 2.4 and 7)

- Slices talk through `api.clj` only: conforms. Every cross-slice `require` found by grep is `…<other>.api`; `server.clj` and `system.clj` are the only wiring files.
- Dependency graph equals section 2.4: DEVIATES. `pages/routes.clj` requires `media.api` (design lists pages -> site, catalog, merch, storefront, documents). The builder widened the `allowed` table in `scripts/check/boundaries.clj` to include it. Also `system.clj` injects `category-in-use?`, `sessions-between`, `featured-image`, `not-found-fn` between slices; these are not in the design. Edge is acyclic and harmless. Fix: design (D5); keep the script table in step. [Status: design amended.]
- No cycles: conforms (the script's cycle check is part of `bb check`; edges read by me are acyclic).
- Platform does not require slices except `server.clj`: conforms (F25).
- Data access only in `store.clj`: conforms (F21).
- Core namespaces pure: conforms. F22 grep over `features/*/core.clj` and `shared/` finds no datalevin, pedestal, java.io, net, timbre or clock reads. `shared/refs.clj` uses `random-uuid` for ids but is called from `api.clj`, not from `core`.
- Frontend: only `web/js/api.js` calls `fetch`, all targets begin `/api` (F27): conforms.
- Frontend never calls third parties or holds secrets: conforms. Only outbound anchors (wa.me, Google Maps, Instagram). No secrets, tokens or keys in `web/`.
- Directory ownership (F26): not verifiable by me without git author data. The tree layout matches architecture 4 and no file outside the two builders' areas is new.
- Contract conformance: conforms in structure. `scripts/check/contract.clj` and `test/.../contract_test.clj` exist; the `ignored` set holds only the two paths the architect removed or renamed. There is no `POST /api/admin/booking-requests` (F19 conforms). Contract text gaps are listed under "Contract assumptions".

## Cross-cutting decisions (section 5)

- Authentication, sessions in the database, cookie flags: conforms.
- CSRF: conforms. `auth-interceptor` checks `X-CSRF-Token` with constant-time compare on post/put/patch/delete.
- Authorization default deny: conforms. `assert-spec!` throws at startup without `:public` or `:roles`, and without `:schema` or `:no-input`.
- Passwords: conforms. argon2id via buddy-hashers, dummy hash for unknown emails.
- Customer access tokens: conforms. 128-bit random, keyed hash, constant-time compare, nil on mismatch (404).
- Error shape: conforms. Single `errors/body`; 500 generic, detail logged with request id.
- Request validation: conforms. Closed Malli maps, trimming (not for passwords), coercion for query, honeypot rule.
- Response validation: DEVIATES (same finding as http-core, D1). [Status: pending backend.]
- Configuration per environment: conforms for secrets and validation. `resources/config.edn` has `#profile` only for dev versus `:default`; staging and prod are not separate keys.
- Profiles dev/staging/prod: DEVIATES (minor). Standards ask for dev, staging and prod profiles and F7 expects all three keys. `APP_ENV` is validated as `:dev|:staging|:prod`, and only dev differs, so behaviour is right. Fix: design (D6) unless the owner wants explicit keys. [Status: design amended.]
- Logging: conforms. One structured line per request with id, path (no query string), status, duration, user id; no bodies.
- Idempotency: conforms. Claim, mismatch 422, in-flight 409 with `Retry-After`, stored response inside the creating transaction, replay header. Admin repeats are state-based (`changed?`).
- Booking concurrency: conforms (single write transaction, `force` recorded).
- Email outbox: conforms (see mailer and worker).
- File storage: conforms (derivatives only, local volume, semaphore).
- Security headers and CSP: DEVIATES (minor). CSP (`default-src 'self'` and friends, `frame-ancestors 'none'`) is set on HTML responses only (`pages/routes.clj`, `pages/core.clj`); JSON and media responses carry `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options`, HSTS (https) but no CSP. Contract section 6 asks for HTML only, so behaviour is sufficient. Fix: design (D7). [Status: design amended.]
- Rate limiting: conforms (database counters, 10 per hour per IP, 5 failures per 15 minutes login, trusted proxy hops).
- Time handling: conforms.

## Frontend (section 2.5, D10, D13)

- Build: DEVIATES. D13 says "native ES modules"; `scripts/web/build.clj` concatenates IIFE-style files (`window.AS`) into `site-common`, `admin-app` and per-page bundles with content hashes. No bundler tool and no Node, so the intent (no heavy tooling) holds. Fix: design (D8). [Status: design amended.]
- Strict CSP, no inline script or style: conforms. F29 greps are empty (no `style=`, `onclick=`, `<script>` bodies, no colour literals in `web`); `build.clj` `check!` fails the build on any inline script, handler, `<style>` or `style=`; JSON-LD and the initial-data block are non-executing `application/json` types, which the CSP allows.
- Single fetch user: conforms (`web/js/api.js` only).
- One admin shell, one build: conforms (`web/admin/index.html`, `web/js/admin/00..40-*.js`, one `build.clj`; F20 greps for micro-frontend tokens are empty).
- Admin help compiled at build time: conforms. `admin-help.md` is converted to `js/admin-help.js`; the only `innerHTML` in `web/js` renders that trusted output (`00-core.js:101`), with HTML escaped in the build.
- Picture reordering by Move up / Move down buttons: DEVIATES from `admin-help.md` line 116 ("Drag to reorder"), not from the design. `design-spec.md` line 166 allows "Drag handle or Up/Down buttons" and `admin-help.md` line 185 already says buttons. Fix: the help text (ux-designer owns it), not the code (D9). [Status: fixed, verified by QA.]
- Fingerprinted assets, SEO region and data markers present on all indexable pages (16 HTML files, markers counted by grep): conforms.
- Build base URL: DEVIATES (minor). `build.clj` line 24 falls back to `https://aura-shots.com` when `PUBLIC_BASE_URL` is unset, which would bake an invented domain into static canonical and Open Graph tags. Architecture does not choose a domain. Fix: code (D10): fail the build in non-dev, or use `http://localhost:8080`. [Status: fixed, verified by QA.]

## Libraries and tools

- Added libraries: conforms. jsonista (JSON; Malli and Pedestal do not provide it, so no duplication), buddy-hashers 2.0.167 (argon2id; latest release about ten months ago, still the standard Clojure choice), angus-mail 2.0.3 (Jakarta Mail for Gmail SMTP), twelvemonkeys imageio-webp 3.12.0 (WebP decode; the JDK has none), tools.build 0.10.14 (`:build` alias only). Cognitect test-runner is a required library, not an addition. None is on the "Not allowed" list and none replaces Integrant, Aero, Pedestal, Malli, Datalevin, Timbre or the test-runner. Pedestal `pedestal.jetty` is not ring-jetty (F1/F2: no `ring`, `reitit`, `compojure` in `deps.edn` or `src`).
- Datalevin 1.2.10 instead of 0.10.18: DEVIATES from the text of architecture 3.1. I checked Clojars and the project's releases page today: 1.2.10 is current (released 2026-10-28), after 1.2.9 (27 Oct), 1.2.8 (26 Oct), 1.2.7 and 1.2.6 (21 Oct): ten releases in 17 days, 26 versions in total. My earlier note (0.10.18, 2026-05-28) is stale. I could not confirm a JDK 25 statement or the exact flags from the release notes (the page failed to load); the `--add-opens=java.base/java.nio` and `sun.nio.ch` plus `--enable-native-access` flags are the ones Datalevin's README has long required on JDK 17+, and they are set in the `:dev`, `:run`, `:test` aliases and in `scripts/backend/run.clj`. Fix: design (D11): accept 1.2.10, keep the exact pin, treat the fast release cadence as a risk, and run the restore drill after every Datalevin upgrade. `build.clj` does not put `Add-Opens` in the jar manifest, so the production command is `java -Xmx512m <the three flags> -jar target/aurashots.jar`; deployment must use it. [Status: design amended.]
- One inline `:clj-kondo/ignore [:unresolved-symbol]` in `db.clj`: conforms. It is local to the `d/with-transaction` macro binding. A `:lint-as` entry in `.clj-kondo/config.edn` would remove it, but that file needs the owner's approval; keep the ignore.
- Namespace docstrings (F5): conforms, 84 of 84 `.clj` files under `src` have a docstring right after the ns name. `:missing-docstring` itself was not run by me.
- No prints or direct env reads in `src` (F6, F10): conforms (prints only in `cli.clj`, which the docstring declares as the one exception).

## Fitness checks not scripted

- F40 memory budget: DEVIATES. No script. The check needs the real production flags and a real host; architecture 8 acceptance item 6 already assigns it to deployment-engineer. Fix: design (D12): mark F40 as a deployment-run check; optionally the backend adds `scripts/check/memory.clj` later. [Status: design amended; script is `bb memory-check`, pending backend.]
- F41 restore drill: DEVIATES. `scripts/backend/restore-drill.clj` does not exist; only `backup.clj` and `restore.clj` do. Fix: code (D13). It is needed before launch (acceptance item 6) and after any Datalevin upgrade. [Status: pending backend; task name `bb restore-drill`.]

---

## Deviation register and recommendations

| # | Deviation | Change | Owner approval | Status |
|---|---|---|---|---|
| D1 | No response-schema checking (http-core, section 5) | Code: add an optional `:response` Malli schema per route, validated in dev/test (log in prod), covering at least the three public POSTs, availability, catalogue reads and quotes. If the owner prefers not to, record a proposed exception and rely on `contract_test.clj`. Standards list "incoming/outgoing" validation under guidelines, not non-negotiables | Only if not implemented | Pending backend |
| D2 | Weekly logical export not automated (housekeeping) | Code: add a weekly `export-edn!` to the housekeeping timer, keep the last 8 | No | Pending backend |
| D3 | Aggregates stored as opaque `:x/data` maps; settings as one object; lines, notes, history nested | Design: amend 3.2 to say aggregates-per-entity with queryable fields as attributes; keep acceptance, document versions and messages as their own entities; state that history is append-only by code | Inform the owner (affects the audit claim) | Design amended (F44 added) |
| D4 | Product sold-out flag can stay false after option toggles | Code: `set-availability` with an option set to in-stock also sets product `:inStock true`; or drop the product-level flag when option groups exist. Add a test. Then amend the contract rule (below) | No | Code fixed, verified by QA |
| D5 | Dependency list incomplete (pages -> media; injected functions; extra platform helpers) | Design: amend 2.2 and 2.4; update `boundaries.clj` only after that | No | Design amended |
| D6 | No separate staging/prod keys in `config.edn` | Design: change F7 to "profile resolved from `APP_ENV`; staging and prod differ only by environment variables" | No | Design amended |
| D7 | CSP on HTML only | Design: amend section 5 to match contract section 6 | No | Design amended |
| D8 | Build concatenates files, not native ES modules | Design: amend D13 | No | Design amended |
| D9 | `admin-help.md` line 116 says drag | Fix the help text: ux-designer. Code stays | No | Fixed, verified by QA |
| D10 | Build default domain `https://aura-shots.com` | Code: no invented default outside dev | No | Code fixed, verified by QA |
| D11 | Datalevin 1.2.10 vs 0.10.18 | Design: amend 3.1 and D3 | Inform (the owner accepted Datalevin, not a version) | Design amended |
| D12 | F40 not scripted | Design: classify as deployment-run | No | Design amended (script pending backend) |
| D13 | F41 not scripted | Code: add `scripts/backend/restore-drill.clj` | No | Pending backend |

(P8 and X8 are both D1; P11 is D2; S1 and the model line are D3; S4 is D4; the boundary-graph line and platform helpers are D5; profiles are D6; CSP is D7; build is D8; picture help is D9; base URL is D10; Datalevin is D11; F40 is D12; F41 is D13. That is 15 deviating lines under 13 numbered items.)

## Contract assumptions made by backend-clojure: rulings

All ten are now written into api-contract.md and listed as Amendment A2 (29 October 2026). The frontend should be told, because items 1, 3 and 6 touch what it sends or reads.

1. Availability `from`/`to` optional when `date` is given (defaults to that day): amend 2.7. [Applied, A2 item 1.]
2. Slots per day only for spans of 7 days or fewer: already in 2.7 ("every day if the span is <= 7 days", plus `date`). No change; add that `date` outside `[from,to]` is ignored. [Applied, A2 item 1.]
3. Days beyond `windowDays` reported as `closed` (code `slots.clj:80`): amend 2.7 status list text. [Applied, A2 item 2.]
4. Quote reasons `soldOut` and `invalidOption`: amend 2.9 (reasons `unpublished|removed|soldOut|invalidOption`; section 2.8 stays `unpublished|noPrice|removed`). Check the frontend handles the string generically; `cart-merchandise.js` was not examined line by line. [Applied, A2 item 3.]
5. Sold-out rule "product `inStock` false OR any option group fully out": amend 2.5 text (the contract says "or all of its options"). The stricter rule is the sensible one, since a fully out size group makes the item unorderable. Pending D4 (now fixed). The admin frontend has a fallback in `30-catalog.js:179` using the old "all options" rule; it only applies when the server sends no `availability`, which it does, so remove the fallback. [Applied, A2 item 4; fallback removal is a frontend note.]
6. Exactly one option per group on quote and order lines: amend 2.9 and the order submit text. Frontend must send one `optionId` per group. [Applied, A2 item 5.]
7. Invitation token valid 72 hours, reset token 60 minutes: reset is already in 3.1; amend 3.12 for the invitation. [Applied, A2 item 6.]
8. Slugs stable on rename (`(or (:slug existing) slug)`): amend 3.5 and 3.6 (slug generated on create and never changes; SEO canonical stays valid). [Applied, A2 item 7.]
9. Admin product list paginated `{items,page,pageSize,total}`: amend 3.6 (3.5 `services` is `{items}` without pagination; state both). [Applied, A2 item 8.]
10. `agreementVersionStale` carries `error.fields.currentVersion` (string): amend 2.11 and the order submit text to name the field. The frontend only reloads the agreement and does not read the field. [Applied, A2 item 9.]

## Needs the owner's approval or knowledge

- D1 if the owner does not want response validation implemented (it would be a proposed exception to the standards' Malli guideline).
- D3 and D11 as information: the data model is simpler than designed and the Datalevin version moved to a new major line. (Recorded in architecture.md open question 14 and D15.)
- Nothing else in this review changes a non-negotiable (server-side validation and authorization, no secrets, versioned migrations, idempotent mutations, no micro-frontends all hold in the code I read).

## Closure update (29 October 2026, lead)

- D1 resolved: response schemas on all 104 routes, checked with `:throw` in tests, `:log` in dev, off in staging/prod; fitness check F53 (`scripts/check/responses.clj`, in `bb check`). Contract shape details it surfaced are recorded as api-contract.md Amendment A3.
- D2 resolved: weekly export scheduled in housekeeping (Sunday 03:00 Africa/Kigali, keeps 8).
- D12: `bb memory-check` exists; local 30 s runs peaked at 817-881 MB RSS (within the 1 GB host limit, above the 800 MB budget). The full F40 run on the real host is for deployment-engineer.
- D13 resolved: `bb restore-drill` passes (14 checks) against a populated dev database.
- F44 (history append-only) and F46 (PUBLIC_BASE_URL required in staging/prod) implemented and in `bb check` / tests.
- Uberjar manifest now carries Add-Opens and Enable-Native-Access, so `java -Xmx512m -jar target/aurashots.jar` runs without extra flags.

