# Architecture – Aura Shots

Status: MODE 1 design, written 2026-10-29; amended 2026-10-29 after the MODE 2 review and QA report 2 (amendments D3, D5, D6, D7, D8, D11, D12, D13 and the staging/base-URL rules; list in section 11). Inputs read: engineering-standards.md, business-context.md, brand-brief.md, scope-checklist.md, design-spec.md, user-journeys.md, api-contract.md (amended, see sections 10 and 11), admin-help.md, visual-guide.md, `.clj-kondo/config.edn`, the frontend-html and backend-clojure agent briefs, `.gitignore`, architecture-review.md, qa-report.md.

Owner-confirmed inputs that shape this document: builders are frontend-html (plain HTML5/CSS/JS) and backend-clojure; backend stack is Integrant, Aero, Pedestal (with its router), Malli, Timbre and Datalevin or Datomic; Ring and Reitit are not used; no online payment; guest checkout only; roles owner and support; idempotent booking and merchandise submissions; email from the owner's Gmail; image uploads need storage and backup; hosting at most 15 USD per month in total; seed prices are correct.

---

## 1. Context

### What the system must do
From scope-checklist.md, categories marked "applies":

| Category | What the system does |
|---|---|
| Catalog / inventory | Service catalogue (5 seed packages with contents, conditions, price required to publish), merchandise catalogue with option flags (In stock / Sold out, no counting), portfolio of curated photos with categories. All managed in the admin. |
| Payments / checkout | Request-and-approve only. Two separate checkouts: session booking requests and merchandise orders. No online payment; staff record cash or mobile money after approval. Guest only. |
| Accounts / auth | Admin only: owner (root) and support staff. Owner manages staff and policy texts. No customer accounts. |
| Admin / back-office | Requests, orders, catalogue, portfolio, calendar blocks, enquiries, settings, staff, policies, history. Staff cannot create bookings. |
| Search / discovery | Category filters and sort (no free-text search). SEO: unique head tags, canonical, Open Graph and JSON-LD in the first HTML response, sitemap. |
| Notifications | Automatic email from the owner's Gmail (receipts, decisions, alerts, invitations, password resets). WhatsApp is manual via prefilled links. |
| Legal / compliance | Six versioned documents; placeholders reviewed by a human before launch. |
| Customer agreements | Acceptance (who, when, version, text hash) recorded immutably with each booking request and merchandise order. |

Goals from engineering-standards.md: the simplest architecture that satisfies the requirements (one backend service, one database); maintainable by a single developer; WCAG 2.1 AA.

### Constraints that shape everything else
- Team: one human owner plus two AI builders (frontend-html, backend-clojure). They share no files; the only interface is api-contract.md.
- Scale: hundreds of customers, rising to thousands. Mostly read-only browsing; writes are a handful of requests per day. A single process is more than enough.
- Budget: 15 USD per month for everything hosted (compute, storage, backups). Domain registration is handled separately (owner decision, out of scope here).
- Hosting: one small Linux host/container running one JVM process with a persistent disk. Provider is chosen later by deployment-engineer and the owner.
- Users: public site is mobile-first for tourists and professionals; admin is used by non-technical staff.
- Non-negotiable (engineering-standards.md): server-side validation and authorization, no hardcoded secrets, versioned migrations, idempotent mutations, no micro-frontends.

### Topology decision in one paragraph
There is one deployable service: a single Clojure/Pedestal process that serves the JSON API, the images, and the static HTML/CSS/JS built by frontend-html, and it embeds one Datalevin database in the same process. There is no separate frontend server, because the frontend is plain HTML (the ClojureScript-only SSR and Replicant/Portfolio rules do not apply). Public pages that need data-dependent SEO tags get them injected into the first HTML response by the backend (api-contract.md section 6).

---

## 2. Components

### 2.1 Process view

```
Browser ──HTTPS──> [hosting edge: TLS, compression, X-Forwarded-For]
                       │
                       ▼
              ┌─────────────────────────────── one JVM process ───────────────────────────────┐
              │ Pedestal connector + Sawtooth router                                           │
              │   /api/**  -> feature slices      /media/** -> media files    pages -> pages   │
              │   /assets /css /js /fonts -> static files from WEB_ROOT                         │
              │ Integrant components: config, logger, clock, db (Datalevin), migrations,       │
              │   mailer, outbox-worker, housekeeping (expiry, backup), http server            │
              └───────────────┬───────────────────────────────────────────────┬───────────────┘
                              ▼                                               ▼
                   DATA_DIR/db (Datalevin/LMDB)                    DATA_DIR/uploads (images)
```

### 2.2 Platform components (Integrant keys; each has one job)

| Component | Single responsibility | Depends on |
|---|---|---|
| `config` | Reads `resources/config.edn` with Aero, validates the whole map with Malli at startup, fails fast. `APP_ENV` is read as a keyword and validated as `dev`, `staging` or `prod`; `#profile` distinguishes only `:dev` from `:default` (staging and prod differ from each other only by environment variables). Secrets only via `#env`. | none |
| `logger` | Configures Timbre (structured output to stdout, level per profile). | config |
| `clock` | Supplies "now" and the Africa/Kigali zone; injectable so tests control time. | config |
| `db` | Opens and closes the Datalevin connection; `in-tx` wrapper, `snapshot!` (Datalevin `copy`), `export-edn!`; exposes the connection to stores only. | config |
| `migrations` | Applies versioned migrations in order at startup, records applied versions in the database. | db |
| `mailer` | Sends one email over SMTP (Gmail) when asked; no queueing logic. Unconfigured, it refuses to send (the caller keeps the message queued). | config, logger |
| `media-store` | Reads and writes files under `UPLOAD_DIR` (atomic write, path-safe names). | config |
| `http-core` (namespaces, not a component) | Shared interceptors: request id, error mapping to `{error:{code,message,fields?}}`, Malli request validation and coercion, response checks, authentication, role check, CSRF, idempotency, rate limit, security headers. It receives its database and session functions by injection from `system.clj`, never by requiring a slice. Its own store namespace (`http_core/store.clj`) owns idempotency and rate-limit records. | db, clock |
| `server` | Starts and stops the Pedestal connector with the route table assembled from all slices. The only platform namespace that requires slices' `routes`. | config, http-core, all slice routes |
| `outbox-worker` | Every 15 seconds takes due outbox messages and hands each to `mailer`; records result and next retry. Its tick function is injected by `system.clj`. | mailer, clock (notifications via injected function) |
| `housekeeping` | Runs on a timer: deletes expired idempotency records (7 days), rate-limit counters (1 hour), expired sessions and reset tokens; writes the nightly database snapshot (keep 14) and the weekly logical EDN export (keep 8) to `BACKUP_DIR`. | db, clock, config |
| platform helper namespaces (not components) | Pure or stateless helpers used by components and slices: `platform/crypto.clj` (hashing, constant-time compare, token generation), `platform/images.clj` (decode, re-encode, single-decode semaphore), `platform/migrations_seed.clj` (seed data used by migration 0002), `platform/http_core/{json,schemas,errors,validate,limits,response,store}.clj`, `shared/{time,errors,whatsapp}.clj`. They require no slice and no slice requires anything of them except through the allowed list in 2.4. | config or nothing |
| `cli` (`platform/cli.clj`) | Command line tasks: `create-owner`, `owner-link`, `backup`, `export`. The only namespace allowed to print. | config, db, auth.api |

### 2.3 Feature slices (backend, `src/aurashots/features/<slice>/`)

Every slice has the same shape: `routes.clj` (thin handlers plus Malli schemas per route), `core.clj` (pure functions, no I/O), `store.clj` (the one data-access namespace; the only place queries and transactions are written), `api.clj` (the functions other slices may call; everything else is private to the slice). `dashboard`, `storefront` have no store.

| Slice | Responsibility | Endpoints (api-contract.md) |
|---|---|---|
| `site` | Settings: contact data, social links, opening hours, booking rules, delivery areas, portfolio categories, held as one settings object (3.2). | 2.1, 3.11 |
| `media` | Images: upload validation, re-encode, strip metadata, sizes, delete-if-unused, serving `/media/**`. | 3.7 |
| `catalog` | Service packages (CRUD, price-to-publish rule, soft remove, ordering, public read of list). | 2.2, 2.8, 3.5 |
| `merch` | Products with option groups and in-stock flags (CRUD, availability toggle, sold-out rule, public list). | 2.5, 3.6 |
| `storefront` | Read-only composition for the detail endpoints that need both catalogue slices (service detail with related products, product detail with related service ids). No store of its own. | 2.3, 2.6 |
| `portfolio` | Portfolio items, ordering, public list. | 2.4, 3.8 |
| `calendar` | Date and time blocks only (create, list, delete). | 3.9 (blocks part) |
| `booking` | Session booking requests: submit, state machine, confirm/reschedule with slot check, payment note, internal notes, history; availability computation; admin calendar view; dashboard figures for sessions. | 2.7, 2.11, 2.12, 3.3, 3.9 (GET), 3.2 (part) |
| `orders` | Merchandise orders: quote, submit, state machine, payment, notes, history. | 2.9, 2.13, 2.14, 3.4 |
| `enquiries` | Contact form messages and "answered" flag. | 2.15, 3.10 |
| `documents` | Versioned policy and agreement texts; immutable acceptance records. | 2.10, 3.13 |
| `notifications` | Outbox of messages, template rendering, resend, retry policy. No sending itself (that is `mailer`). | resend endpoints in 3.3, 3.4 |
| `auth` | Admin sign in/out, sessions, CSRF token, password hashing, reset and invitation tokens, staff accounts, the owner bootstrap and owner-link commands. | 3.1, 3.12 |
| `dashboard` | Reads counts from other slices' `api`; no store. | 3.2 |
| `pages` | Serves HTML files, rewrites the SEO region and the initial-data block, `sitemap.xml`, `robots.txt`, 404 page, non-production noindex behaviour. Holds the route table of api-contract.md section 6 and the SEO title/description patterns of design-spec Part 6. | section 6, 2.16, 2.17 |

Shared pure code lives in `src/aurashots/shared/` (`slots.clj`: slot grid and availability rules as pure functions of data; `money.clj`; `refs.clj`: reference code generator, uses random ids and is called only from `api.clj`, never from `core.clj`; `time.clj`, `errors.clj`, `whatsapp.clj`; `markdown_subset.clj` if needed). Shared code requires no slice.

### 2.4 Dependency list (arrows mean "requires"; one direction only)

```
platform:
  config      -> (none)
  logger      -> config
  clock       -> config
  db          -> config
  migrations  -> db
  mailer      -> config, logger
  media-store -> config
  http-core   -> db, clock            (auth/session lookups injected as functions)
  server      -> config, http-core, every slice's routes
  outbox-worker -> mailer, clock      (tick function injected)
  housekeeping  -> db, clock, config
  cli         -> config, db, auth.api
  helpers (crypto, images, migrations_seed, http_core/*, shared/*) -> config or nothing; never a slice

slices (X.api only; never X.core/X.store/X.routes of another slice):
  site          -> (none)
  media         -> media-store                 (usage check is a reverse-reference query, see 3.2)
  merch         -> media.api
  catalog       -> media.api, merch.api        (related product ids must exist)
  portfolio     -> media.api, site.api         (category must exist)
  storefront    -> catalog.api, merch.api, media.api
  calendar      -> site.api
  notifications -> site.api                    (owner address, sender)
  documents     -> (none)
  auth          -> notifications.api           (invitation and reset emails)
  booking       -> catalog.api, calendar.api, site.api, documents.api, notifications.api, shared.slots
  orders        -> merch.api, site.api, documents.api, notifications.api
  enquiries     -> site.api, notifications.api
  dashboard     -> booking.api, orders.api, enquiries.api
  pages         -> site.api, catalog.api, merch.api, storefront.api, documents.api, media.api   (media.api: default og:image and featured image lookup)
  every slice's routes -> http-core
shared/*      -> (none)

injected by system.clj (function values, not requires; each keeps the graph acyclic):
  auth.api/resolve-session      -> http-core          (authentication)
  category-in-use?              -> portfolio gives it to site's settings update (a category used by portfolio items cannot be removed)
  sessions-between              -> booking gives it to calendar's block warning (affectedSessions)
  featured-image                -> portfolio gives it to pages (og:image fallback)
  not-found-fn                  -> pages gives it to server (404 body)
  notifications tick            -> outbox-worker
```

Cycles: none. Four places where a cycle would have appeared and how each was avoided (the builder must not reintroduce them):
1. catalog and merch relate to each other (related products on services, related services on products). Resolved by storing the relation once on the service, letting `catalog` depend on `merch` (one way), and putting the reverse lookup (`relatedServiceIds`) in `storefront`, which depends on both.
2. availability needs blocks and confirmed sessions, booking needs availability. Resolved by `calendar` owning only blocks (depends on `site` alone), and `booking` owning availability and the admin calendar view, composing blocks (from `calendar.api`) and its own confirmed sessions.
3. `auth` session lookup is needed by the platform authentication interceptor, but `auth` is a slice. Resolved by dependency injection from `system.clj`.
4. site settings need to know whether a portfolio category is in use, while portfolio depends on site. Resolved by injecting `category-in-use?` from `system.clj`. The same pattern serves `sessions-between` (calendar needs booking data), `featured-image` and `not-found-fn`.

The table `allowed` in `scripts/check/boundaries.clj` is kept equal to this list; the script is changed only after this section is.

### 2.5 Frontend (plain HTML5/CSS/JS, `web/`)

No framework, no bundler, no Node. One build, one set of static files, one admin shell. Browser-only responsibilities:

| Area | Responsibility | Depends on |
|---|---|---|
| `site` pages | The public pages of design-spec Part 1 as separate HTML files plus per-page scripts; render from the API (or the injected initial data); keep baskets in localStorage; step flow for booking and checkout; send the idempotency key (generated once per submission attempt, reused on retry). | api client, shared UI |
| `admin` shell | One HTML file, a small JS router, screens A1-A21, help drawer. Never enforces permissions by itself; hides what the role cannot use for convenience only. | api client, shared UI |
| `shared` UI | Header/footer, buttons, cards, calendar picker, time-slot grid, gallery, lightbox, agreement panel, stepper, dialogs, toasts, form helpers, minimal Markdown-subset renderer (no raw HTML). | tokens, assets |
| `api client` | The only module that calls `fetch`; same-origin `/api` only; adds CSRF header; maps the error shape. | none |
| `styles` | CSS using only `design-tokens.css` custom properties. | tokens |
| `build` | Babashka script `scripts/web/build.clj`: concatenates the ordered script files (IIFE style, one `window.AS` namespace; no native ES modules, no `import`) into `site-common`, `admin-app` and per-page bundles; copies tokens, assets, fonts; converts `admin-help.md` to a script of topic data; fingerprints file names with content hashes (plus the one unhashed `assets/og-image.png`, section 5); fails the build on inline scripts, inline handlers, `<style>` or `style=`; writes `dist/web/`. | root assets |

The frontend never calls third-party APIs and holds no secrets (non-negotiable rule from standards). WhatsApp, map and social links are plain outbound anchors.

---

## 3. Data model

### 3.1 Datastore: Datalevin (embedded, in the same JVM)

Choice and reasons (first searched 2026-10-29; Datomic's docs show the dev transactor default heap is 1 GB and dev storage runs H2 inside the transactor's JVM):
- Datalevin runs inside the application process: no second server, no second JVM. That fits one service and one database inside 15 USD per month. Datomic would need a transactor process (about 1 GB heap by default) beside the application JVM, which is the largest single cost in a small host's memory budget.
- The data is small and relational-light (hundreds of orders a year). Datalog queries, schema with refs and unique identities cover it.
- Datomic's built-in history is its main extra benefit. Here the audit need is explicit and small (history entries, acceptance records, price snapshots), and it is met by append-only data designed into the model (3.2). Nothing depends on time-travel queries.
- Backups are file copies of a consistent snapshot; restore is copy back.
- Risks accepted: Datalevin is a younger project than Datomic with a small maintainer base; one process owns the database files, so the service runs as a single instance (PE2); mitigated by nightly snapshots plus a weekly logical EDN export (section 8).
- Not chosen: Datomic (cost and memory above, rejected for budget), any SQL database (forbidden by standards). The owner accepted Datalevin (29 October 2026).

**Version pin (D11).** `deps.edn` pins Datalevin to exactly **1.2.10** (checked on Clojars and the project's releases page on 2026-10-29: 1.2.10 released 2026-10-28; ten releases in the 17 days before, 26 versions in total; the 0.10.18 figure in the first draft of this section is stale). The release cadence is fast, so: no version ranges, no automatic upgrades, and `bb restore-drill` (F41) runs after every Datalevin upgrade before it is deployed. Datalevin on JDK 17+ needs JVM flags, set in the `:dev`, `:run`, `:test` aliases and in `scripts/backend/run.clj`, and required on the production command line: `--add-opens=java.base/java.nio=ALL-UNNAMED`, `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`, `--enable-native-access=ALL-UNNAMED` (the exact set in `scripts/backend/run.clj` is authoritative). The jar manifest carries no `Add-Opens`, so production runs `java -Xmx512m <flags> -jar target/aurashots.jar`.

### 3.2 Entities, relationships and write ownership (as built, amended)

Storage style (D3). Each aggregate is one entity whose full content is a single nested Clojure map in one attribute `:x/data` (nippy, not indexed), next to a few real attributes used for identity and queries (id, reference, status, dates, slug, hashes, refs to images). Lines, notes and history entries are elements nested in that map, not entities of their own. Acceptance records, document versions, outbox messages, sessions, tokens, idempotency and rate records are separate entities. Reason: at hundreds of records per year an aggregate is always read and written whole, queries need only a few fields, and one map per aggregate keeps transactions and snapshots trivial for one maintainer. Cost: nested elements cannot be queried individually, and append-only history is a code convention (below), not a property of the schema. Migration `0001-initial-schema.edn` in `resources/migrations/` is the authority for attribute names; this table is the design view.

Only the owning slice's `store.clj` transacts the attributes of its entities. Other slices read through the owner's `api`. IDs are opaque random strings (`:x/id`, unique identity); Datalevin entity ids are never exposed.

| Entity (indexed attributes; nested content) | Relations | Writes owned by |
|---|---|---|
| `setting` (one object in `:setting/data`): contact, social, `openingHours`, booking rules, `deliveryAreas` (id, name, feeRwf or nil, active) and `portfolioCategories` (id, name) as nested lists | referenced by id from portfolio items and orders (snapshots) | site |
| `image` (id, width, height, alt-default, createdAt, files by size) | referenced by ref from service, product, portfolio item | media |
| `service` (indexed: id, slug, status; nested in `:service/data`: name, descriptions, priceRwf or nil, sessionMinutes, contents, conditions, featured, sortOrder, ordered images with alt, related product ids) | image refs; ids of related `product`s | catalog |
| `product` (indexed: id, slug, status; nested in `:product/data`: name, category, descriptions, priceRwf, isNew, featured, option groups with options (id, label, inStock), the product-level `inStock` flag, ordered images) | image refs | merch |
| `portfolioItem` (id, image ref, caption, description/alt, category id, featured, sortOrder) | image ref | portfolio |
| `block` (id, date range, optional start/end time, reason, createdBy) | | calendar |
| `bookingRequest` (indexed: id, reference, status, preferred and confirmed date, access token hash; nested in `:booking/data`: customer snapshot, contactPreference, notes, slots, durationMinutes, payment, `lines` (service id, name and price snapshots), `notes`, `history`) | refs one `acceptance` | booking |
| `merchOrder` (indexed: id, reference, status, token hash, createdAt; nested in `:order/data`: customer and delivery snapshots, subtotal, deliveryFee, total, payment, `lines` (product id, name, option labels, unit price, quantity snapshots), `notes`, `history`) | refs one `acceptance` | orders |
| `enquiry` (id, name, email, phone, message, receivedAt, status) | | enquiries |
| `document` (slug, reviewStatus) and `documentVersion` (slug, version, body, summaryPoints, publishedAt, publishedBy, changeNote, bodyHash) | versions are immutable | documents |
| `acceptance` (id, documentSlug, version, bodyHash, subjectType, subjectId, subjectReference, customer name/email/phone, acceptedAt, ipAddress, userAgent) | own entity; append-only; no update or delete function exists | documents (written through `documents.api/record-acceptance`, in the same transaction as the booking or order) |
| `message` (indexed: id, status, nextAttemptAt, subject id; nested in `:message/data`: channel, template, to, attempts, error) | belongs to a booking, order, enquiry or user | notifications |
| `user` (indexed: id, email unique, role, active; nested in `:user/data`: name, passwordHash, invitationPending, lastSignInAt) | | auth |
| `session` (id hash, user ref, csrf token hash, createdAt, lastSeenAt, expiresAt) and `passwordToken` (hash, user ref, kind invitation/reset, expiresAt, used) | | auth |
| `idempotencyRecord` (key, endpoint, requestHash, status, response, createdAt) | | http-core |
| `rateCounter` (bucket key, count, windowStart) | | http-core |
| `schemaVersion` (version, appliedAt) | | migrations |

Entities not listed as aggregates (image, block, portfolioItem, enquiry, document) are described as in the first design; the review did not read their schema line by line, so migration 0001 decides if any is also a single `:x/data` map. The shape of the JSON in api-contract.md is unaffected either way.

Rules the model encodes:
- Prices, names and option labels are snapshotted into booking and order lines; later catalogue edits never rewrite history.
- Removal of services, products and staff is soft (status or active flag) because history refers to them; deleting a staff member keeps the name in history entries as text.
- **History is append-only by code convention.** The `history` vector inside `bookingRequest` and `merchOrder` only ever grows: `core.clj` functions add an entry with `conj` and never remove, reorder or rewrite earlier entries; no route edits history. The data model cannot enforce this, so fitness check F44 guards it (grep plus a test that every transition keeps the previous history as a prefix). Acceptance records are stronger: separate entities with no update or delete function (F18). Internal notes are append-only the same way.
- A new booking or order, its lines, its acceptance record, its first history entry, its outbox messages and its idempotency record are committed in one Datalevin transaction. That is what makes submission exactly-once.
- Pending requests do not block slots. A confirmed booking blocks `confirmedSlot` for `durationMinutes`.
- Images are referenced from services, products and portfolio items by ref attributes. `media.api/delete-image` checks for any inbound reference using a configured list of ref attributes (a data-level reverse lookup, not a code dependency).
- Slugs are generated on creation and never change on rename (canonical URLs stay valid).
- A product is sold out when its `inStock` flag is false or any option group has no option in stock; setting an option in stock clears the product flag (the rule lives in `merch/core.clj`).
- Money is integer RwF; instants are UTC instants in storage and rendered with the +02:00 offset; slot dates and times are local strings in Africa/Kigali.

### 3.3 Migrations
Versioned files `resources/migrations/NNNN-description.edn` (four-digit sequence, no gaps), each containing schema additions and optional data steps, applied once, in order, at startup by the `migrations` component and recorded in `schemaVersion`. Seed data (five services with seed prices, the six documents as placeholder drafts, default settings with `<TBD>` as nil, the six sample portfolio images) is itself migration 0002 and contains no secrets; it reads sample images from `seed-images-dir` (`business/images/shots`) at first start, so deployment ships that directory with the first deploy (without it only a placeholder image is created and a line is logged). The first owner account is created by a command line task (`bb create-owner`), which prints a one-time password-set link; no password lives in a file or environment variable. The owner's forgotten password is handled by `bb owner-link` (Amendment A1).

---

## 4. Ownership of directories

Two builders never edit the same file. Everything not listed is read-only to builders.

### backend-clojure
```
deps.edn  bb.edn
src/aurashots/system.clj            Integrant wiring, entry point
src/aurashots/platform/             config, logger, clock, db, migrations, mailer, media_store, http_core/*, housekeeping, cli, helpers
src/aurashots/features/<slice>/     routes.clj core.clj store.clj api.clj  (one folder per slice in 2.3)
src/aurashots/shared/               pure helpers
test/                               mirrors src; contract-conformance tests
resources/config.edn                Aero config (dev profile vs default)
resources/migrations/               versioned migrations and seed
resources/email/                    email templates (text and html)
scripts/backend/                    run, build uberjar, backup, restore, restore-drill, memory-check, export tasks
scripts/check/                      fitness-check scripts of section 7
data/                               runtime only: db/, uploads/, backups/  (never committed)
```
`bb.edn` is owned by backend-clojure and must define the task `build-web` as `bb scripts/web/build.clj` so the frontend script has a fixed entry point, and the tasks `owner-link`, `restore-drill` and `memory-check`.

### frontend-html
```
web/site/*.html                     one file per route row in api-contract.md section 6 (404.html included)
web/admin/index.html                the single admin shell
web/css/                            site.css, admin.css, components (tokens come from the root file)
web/js/                             api.js (the only fetch user), shared/, site/, admin/ (numbered files, concatenated by the build)
scripts/web/build.clj               builds dist/web/ (babashka): concatenates scripts, copies design-tokens.css, assets/, fonts,
                                    admin-help topics; fingerprints file names; no Node needed at runtime
dist/web/                           generated output, never committed; the backend serves it as WEB_ROOT
```

### Others (not builders)
- visual-designer: `design-tokens.css`, `assets/` (including `assets/og-image.png`, source `scripts/design/`), `visual-guide.md`. Frontend reads them, never edits them.
- ux-designer: `design-spec.md`, `user-journeys.md`, `admin-help.md`, `scope-checklist.md`, `brand-brief.md`; `api-contract.md` is drafted by ux-designer and amended by the architect (sections 10 and 11).
- architect: `architecture.md`, `architecture-review.md`, `deployment-handover.md`.
- Shared input: `business/images/` is read by the backend seed migration only.
- `.clj-kondo/config.edn` is not a builder file; a change needs the owner's approval.

The interface between the two builders is api-contract.md only: sections 1-5 for JSON, section 6 for how pages are served. If either builder needs something that is not there, they say so; they do not read each other's code.

`.gitignore` additions (done by the owner): `/data/` and `/dist/`. Note the existing pattern `public/js/` would hide any folder of that name; the layout above avoids it.

---

## 5. Cross-cutting decisions (one line each, with the reason)

- **Authentication:** server-side sessions in the database, opaque random ids stored hashed (HMAC with pepper), cookie `session` (HttpOnly, Secure, SameSite=Lax; `Secure` relaxed only in the dev profile). Reason: immediate revocation when staff are switched off, no signing keys to manage, one origin so no CORS.
- **CSRF:** `X-CSRF-Token` header on every state-changing admin request. Reason: cookie auth requires it; same origin keeps it simple.
- **Authorization:** every route carries explicit metadata `:public true` or `:roles #{...}`; a route with neither makes startup fail (default deny); the owner-only list is in the contract. Reason: non-negotiable server-side enforcement that cannot be forgotten.
- **Passwords:** argon2id (buddy-hashers), minimum 12 characters, 5 failures per 15 minutes per email and IP. Reason: contract and OWASP guidance.
- **Customer access tokens** for the "received" pages: 128-bit random, stored as a keyed hash, constant-time compare, 404 on mismatch. Reason: no customer accounts yet a reference alone must not expose personal data.
- **Staff links:** invitation links valid 72 hours, reset links 60 minutes, single use, stored only as hashes; the link is returned once in the admin response and never listed later (Amendment A1). `emailQueued` is true only when SMTP is configured; when email is unconfigured nothing is queued for these links and the response says `emailQueued: false`. Reason: the owner may have no mailbox at launch; the link must not exist only inside a queued email.
- **Error shape:** one `http-core` error interceptor produces `{error:{code,message[,fields]}}` for every failure, including 404 for unknown API paths and 500s (message generic; detail only in the log). Reason: one shape, no leaks.
- **Validation:** closed Malli schema on every route for path, query, headers and body, coercing JSON (camelCase keys, strings to dates), unknown keys rejected, before any handler runs. Every route declares a closed Malli response schema per status. Config key `:response-check`: `:throw` in tests (a mismatch becomes a 500 carrying the explanation, so the test fails), `:log` in dev (logged as an error with route, status and explanation), `:off` in staging and prod (no schemas compiled, no cost) (D1, implemented). Reason: boundary validation is non-negotiable; blocking a valid order because of a response-shape bug would be worse than missing it, and every route is exercised by the test suite, so drift is caught before release.
- **Configuration per environment:** Aero `config.edn`; `APP_ENV` (dev, staging or prod) is validated at startup; `#profile` separates only the dev profile (local defaults, `Secure` cookie off, generated dev pepper, debug log) from the default profile used by staging and prod; all secrets and paths via `#env`; the merged map is validated with Malli. Reason: staging must behave like production, so they share one profile and differ only by variables; fail fast on a missing variable.
- **Logging:** Timbre to stdout, one structured line per request (request id, path without query string, status, duration, user id if any) and errors with ex-data; no request bodies, no passwords, no tokens, email addresses only in the outbox store. No `println` outside `cli.clj`. Reason: structured logs with real context, privacy.
- **Idempotency:** a `http-core` interceptor on the three public creating POSTs reads `Idempotency-Key`, claims it (in-flight marker), and the handler stores the response record inside the same transaction that creates the booking, order or enquiry; replays return the stored response with `Idempotent-Replay: true`. Reason: a crash or retry can neither double-book nor lose the record. (There are no payments to double-charge; the same guarantee covers bookings and orders.)
- **Booking concurrency:** confirm and reschedule re-check blocks and confirmed sessions inside one write transaction (Datalevin serialises writers); `force` is an explicit staff override recorded in history. Reason: two staff members confirming the same slot cannot both succeed silently.
- **Time:** the injectable `clock` is the only source of "now"; storage in UTC instants; business rules in Africa/Kigali. Reason: testability and a documented time-zone decision.
- **Email:** transactional outbox in the database, worker with back-off (1 min, 5 min, 30 min, 2 h, 12 h, then `failed`), sent over SMTP from the owner's Gmail with an app password in `SMTP_PASSWORD`; a warning is logged near Gmail's daily sending limit. With SMTP unconfigured the site works and customer-facing messages stay `queued` (owner answer Q1). Reason: the API never fails on mail and nothing is lost on restart. No external queue is needed.
- **File storage:** images on the local persistent volume under `UPLOAD_DIR` as re-encoded JPEG derivatives (thumb 400, card 800, large 1600 px); the original upload is not kept, EXIF/GPS is dropped by re-encoding; files named by image id and size; served by the backend with immutable caching. Image decoding happens one at a time (a semaphore) with limits from the contract (10 MB, 6000 px). Reason: no object-storage service or cost; one disk to back up; protects the memory budget.
- **Security headers and CSP:** `Content-Security-Policy` (`default-src 'self'`, images and fonts self only, `frame-ancestors 'none'`) is set on HTML responses only, as api-contract.md section 6 says; JSON and media responses carry `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options`, and HSTS when `PUBLIC_BASE_URL` is https. Reason: the policy protects documents that run scripts; JSON and images run none, and the frontend loads no third-party code, so a strict policy is cheap.
- **Frontend build and CSP safety:** the build concatenates script files (no native ES modules, no `import`, no inline script, handler or style) and fails on violations. Reason: strict CSP, no bundler or Node dependency, one maintainer.
- **Public base URL:** `PUBLIC_BASE_URL` is the origin for canonical, Open Graph, JSON-LD and email links. It is required for the frontend build in staging and prod (the build fails without it; no invented default domain) and required at runtime for staging and prod; only dev may default to `http://localhost:8080`. Reason: a wrong baked-in domain would silently ruin SEO.
- **Non-production behaviour (staging):** when `APP_ENV` is not `prod`: `robots.txt` is exactly `User-agent: *` and `Disallow: /`; every HTML response carries `X-Robots-Tag: noindex`; `sitemap.xml` lists URLs built from `PRODUCTION_BASE_URL` (empty `<urlset>` when it is unset), never the staging host. In prod: normal robots rules and sitemap on `PUBLIC_BASE_URL`, no blanket noindex. Reason: a staging copy must never be indexed or compete with the real site.
- **Default share image:** the default Open Graph image is published at the stable, unhashed URL `/assets/og-image.png` (1200 x 630 PNG) in addition to any fingerprinted copy, so backend-rendered pages (home, contact, legal, items without a photo) can name it without reading the build manifest. It is served `Cache-Control: public, max-age=86400`, not immutable (the URL is not fingerprinted). Reason: QA found 404s on the hashed-only file; social crawlers need one URL that does not change.
- **Rate limiting:** counters in the database (contract section 1), client IP from `X-Forwarded-For` trusted only with `TRUSTED_PROXY_HOPS` > 0. Reason: standards say shared state lives in the database; also correct after a restart.
- **Documents:** stored as restricted Markdown, rendered by the browser without raw HTML. Reason: no HTML sanitiser needed server-side.
- **Accessibility:** the frontend owns WCAG 2.1 AA (labels, focus ring, contrast tokens, keyboard-operable calendar and lightbox); the backend contributes alt text as a required field. Reason: AA is a stated goal.

---

## 6. Decisions

| # | Decision | Alternatives rejected | Why |
|---|---|---|---|
| D1 | One Pedestal process serves API, images and static HTML | A Node/other SSR server in front; a CDN-only static site plus separate API | Standards' separate frontend server exists for ClojureScript SSR only. For plain HTML a second server adds cost, a second deployable and CORS for no gain. Static files are tiny; the hosting edge can cache them. |
| D2 | SEO tags are rewritten by the backend into the first HTML response on the pages that need live data (home, service detail, product detail, contact, legal); other pages carry static tags authored by the frontend. Detail pages also get the API JSON inlined. | (a) client-side JS sets title/meta and JSON-LD after load; (b) full server-side rendering of page bodies in Clojure; (c) build-time static generation per service/product | (a) fails the stated requirement that SEO tags are in the first response and breaks link previews for shared URLs. (b) means two renderers (Clojure and browser JS) to keep identical for one maintainer. (c) needs a rebuild and redeploy every time staff edit a price, which contradicts the admin-managed catalogue. Head rewriting is a small, pure string substitution, keeps one renderer (the browser), gives correct titles, canonical, Open Graph, JSON-LD, and real HTTP 404 for unknown slugs. Residual risk: page bodies are drawn by JavaScript; accepted by the owner (Q5). |
| D3 | Datalevin (embedded), version pinned to 1.2.10, restore drill after every upgrade | Datomic (dev storage or other); version ranges | Section 3.1. |
| D4 | Pedestal 0.8.x with its Sawtooth router (literal paths beat path parameters, routing conflicts are reported) and static files routed through it; routes defined as data per slice | Ring+Reitit (owner excluded); Pedestal 0.7 interceptor-based file serving | Pedestal 0.8.0 (Sep 2025) and 0.8.1 (Oct 2025) are current per the project changelog; built on the 0.8 `io.pedestal.connector` API. |
| D5 | Vertical slices with a fixed four-file shape and `api.clj` as the only cross-slice door; function injection from `system.clj` where a slice needs data owned by a slice that depends on it | Layered controllers/services; free cross-requires | Standards; also gives the fitness check in section 7 something mechanical to test. Injection keeps the graph acyclic. |
| D6 | `storefront` composition slice; `booking` owns availability; `calendar` owns blocks only | A "catalog+merch" mega-slice; two-way requires | Keeps the dependency graph acyclic (section 2.4). |
| D7 | Database outbox plus in-process worker for email | A message queue; synchronous send in the request | No new service; survives restarts; a failed mail never fails a booking. |
| D8 | Database-stored sessions and idempotency/rate-limit records | JWT; in-memory maps | Revocation, restart safety, and the standard's rule that shared state is in the database. |
| D9 | Images on a local volume, derivatives only | Object storage; keep originals | No provider or cost; one more thing to back up is avoided by dropping originals. The owner keeps masters. |
| D10 | One admin shell, client-side routing inside it, `/admin/**` all served by one file | One HTML file per admin screen | Twenty-one screens share the shell, session check, help drawer; one file avoids repeated chrome. It is still one build, not micro-frontends. |
| D11 | `/admin` pages are plain static files; only the API enforces access | Server-side gate on HTML | The HTML holds no secrets or data; the API is the real gate. Keeps a single enforcement point. |
| D12 | Single running instance; nightly snapshots | Multiple instances behind a balancer | Embedded database. Expected traffic is far below the capacity of one small JVM serving cached, mostly read-only pages. |
| D13 | Frontend is plain scripts concatenated by a Babashka build script (IIFE style, `window.AS`); no native ES modules, no bundler, no Node | Native ES modules; a framework or bundler | Allowed by the frontend brief; the concatenated bundles need no module loader, work with a strict CSP and keep fingerprinting simple. Amended from "native ES modules" to match the build. |
| D14 | Slot length: sum of the selected services' lengths, default from settings (60 minutes if unset) | One fixed length | The contract originally had no length input for availability; amended (section 10). |
| D15 | Aggregates (booking, order, service, product, settings, message, user) stored as one nested map plus a few indexed attributes; acceptances, document versions and messages separate | Fully normalised entities for lines, notes, history, delivery areas and categories | Fits the scale and a single maintainer; transactions and snapshots stay simple. Cost: history is append-only by convention and checked by F44, not enforced by schema. Informed to the owner. |
| D16 | Non-production environments are never indexable and never publish a sitemap of their own host | Relying on the host's authentication; same behaviour in all environments | A staging copy of a public site otherwise competes with production in search results. |

### Proposed exceptions to engineering-standards.md
- **PE1 (approved by the owner): no "frontend server".** The Goals line says "one backend service, one frontend server, one database". This design has no frontend server; the backend serves the static frontend. The standards' own topology section only requires a separate frontend server when the frontend is ClojureScript, which it is not.
- **PE2 (approved by the owner): the service is single-instance.** All shared state is in the database, but the database is embedded Datalevin, so only one process may open it. Horizontal scaling is not possible without moving Datalevin to server mode.
- **PE3: `fields` member in the error shape** (already in the contract draft): `{error:{code,message,fields?}}`. The standard says the error shape is a guideline; this adds one optional member for form field errors.
- **PE4 (pending, owner to approve only if D1 is not implemented):** response-schema checking is a guideline ("incoming/outgoing payloads") rather than a non-negotiable; D1 is being implemented, so no exception is needed unless that changes.
- Not exceptions, only applicability: Replicant, Portfolio and shadow-cljs rules apply to ClojureScript frontends and are not used. The "Portfolio scene per state" rule is replaced by the visual-guide.md component states, checked by the accessibility run in section 7. Slice paths are `src/aurashots/features/...` rather than `src/features/...` (a namespace root, same structure).

---

## 7. Fitness checks (for qa)

Run from the project root. "Pass" means the stated result. Scripts under `scripts/check/` are written by backend-clojure and are part of its definition of done; the one-line commands can be run as they stand. `rg` is ripgrep. `bb check` runs F11, F12, F23, F24.

### Standards and technology rules

| # | Rule | Check | Pass |
|---|---|---|---|
| F1 | Banned and required libraries (clj-kondo) | `clj-kondo --lint src test --config .clj-kondo/config.edn` | Zero errors (the config's `:discouraged-namespace` list for Reagent, re-frame, Rum, Compojure, ring-jetty, Mount, environ, Plumatic Schema, next.jdbc, clojure.java.jdbc, Ring and Reitit) |
| F2 | Ring and Reitit are not used | `rg -n "metosin/reitit\|ring/ring\|compojure" deps.edn` and `rg -n "\[(ring\|reitit)\." src test` | No output. Transitive Ring inside Pedestal is fine; direct use is not |
| F3 | Required libraries are present | `rg -n "io.pedestal\|integrant\|aero\|malli\|timbre\|datalevin" deps.edn` | Each of the six appears; no `datomic`, no SQL driver |
| F4 | Public vars documented (standards, enforced by kondo) | `clj-kondo --lint src --config .clj-kondo/config.edn 2>&1 \| rg -c "missing-docstring"` | `0` (rg prints nothing or 0) |
| F5 | **Every namespace has a docstring** (kondo does not check this) | `rg -U --files-without-match '\(ns\s+(\^\S+\s+)*[^\s)]+\s+"' src --glob '*.clj'` | No output (every `.clj` under `src` has a string right after the ns name). The docstring must also say which slice or platform component the namespace is (reviewer reads 5 samples) |
| F6 | Aero is the only config source; no env reads elsewhere | `rg -n "System/getenv\|System/getProperty\|environ" src` | No output |
| F7 | Config profiles and secrets (reworded to the build) | `rg -n "#profile" resources/config.edn` shows only `:dev` and `:default` keys; `rg -n "app-env" resources/config.edn` shows `APP_ENV` read through `#env` and `#keyword`; start the app with `APP_ENV=bogus` and with `APP_ENV=staging` and no `TOKEN_PEPPER`; `rg -n -i "(password\|secret\|pepper\|api.?key)" resources/config.edn` | Both bad starts exit non-zero with a Malli validation message naming the key; every secret-like key is followed by `#env` (no literal value) |
| F8 | No hardcoded secrets anywhere, including seed data | `rg -n -i "(password\|passwd\|secret\|pepper\|token)\s*[:=]\s*\"[^\"]{6,}\"" src resources web scripts` and, if installed, `gitleaks detect --no-git -s .` | No output / no findings (test fixtures under `test/` may use obviously fake values) |
| F9 | Versioned migrations only | `ls resources/migrations` matches `^[0-9]{4}-[a-z0-9-]+\.edn$`, numbers consecutive from 0001; `rg -n "update-schema\|:db/valueType" src` | Names and sequence valid; schema statements appear only in `platform/migrations*`, `platform/db.clj` bootstrap or the seed namespace |
| F10 | Logging is structured, no prints | `rg -n "\(println\|\(prn\|pprint\|\(print " src --glob '!**/cli.clj'` | No output |
| F11 | Malli at every boundary; default-deny auth | `bb scripts/check/routes.clj` prints the route table and exits non-zero if any route lacks a schema (or an explicit `:no-input true`) or lacks `:public true`/`:roles` | Exit 0 |
| F12 | Contract conformance: every method+path of api-contract.md exists in the route table and none extra | `bb scripts/check/contract.clj` | Exit 0, zero missing and zero extra |
| F13 | One error shape | Contract test run by `clojure -M:test` sends a bad body, bad auth, unknown path, unknown API path, a forced 500 | Every non-2xx body has exactly one key `error` with only `code`, `message`, optional `fields`; `code` is in the contract list |
| F14 | camelCase keys, ISO dates | Same test walks every JSON response of the seeded smoke run | All keys match `^[a-z][a-zA-Z0-9]*$`; every date matches `YYYY-MM-DD`, every time `HH:mm`, every instant ISO 8601 with offset |
| F15 | Idempotent mutations | Test: POST `/api/booking-requests` twice with one key and body, once with the same key and a changed body, and twice in parallel | Same response and `Idempotent-Replay: true` on the second; exactly one booking and one acceptance exist; changed body gives 422 `idempotencyKeyReused`; parallel call gives 409 `conflict` with `Retry-After` or the stored response. Repeat for merchandise orders and enquiries. Repeat `confirm`/`approve`/`mark-paid` and expect 200 with no second history entry |
| F16 | Authorization on every protected endpoint | Test generated from the route table: each `/api/admin/*` route without cookie gives 401; with a support session on owner-only routes (staff, documents, acceptances) gives 403; state-changing calls without `X-CSRF-Token` give 403 | All as stated, zero exceptions |
| F17 | Cookie flags | `curl -si -X POST .../api/admin/auth/login ...` on the staging profile | `Set-Cookie: session=...; HttpOnly; Secure; SameSite=Lax` |
| F18 | Acceptance records are append-only | `rg -n "retract\|db/retract" src/aurashots/features/documents/store.clj`; route check F11/F12 | No retract of `acceptance` attributes; no PUT/PATCH/DELETE route on acceptances |
| F19 | Staff cannot create bookings | The route table (F11 output) | No `POST /api/admin/booking-requests` |
| F20 | No micro-frontends | `rg -n -i "single-spa\|module-federation\|importmap\|<iframe" web` and `ls dist/web/admin/*.html` | No output; exactly one admin HTML file; one `scripts/web/build.clj` |

### Architectural boundaries

| # | Rule | Check | Pass |
|---|---|---|---|
| F21 | Handlers are thin; no queries outside `store.clj` | `rg -n "datalevin\|\(d/(q\|pull\|transact!\|with-transaction)" src --glob '!**/store.clj' --glob '!**/platform/db.clj' --glob '!**/platform/migrations*'` (the `http_core/store.clj` file is a `store.clj` and already excluded) | No output |
| F22 | Business logic is pure and testable without HTTP | `rg -n "datalevin\|io\.pedestal\|java\.io\|java\.net\|jakarta\|timbre\|clock/now" src/aurashots/features/*/core.clj src/aurashots/shared` | No output (time and ids are passed in as arguments) |
| F23 | Slices talk only through `api.clj` | `bb scripts/check/boundaries.clj` (fails on any `aurashots.features.Y.*` require from slice X, X≠Y, where the namespace is not `…Y.api`) | Exit 0 |
| F24 | The dependency graph in section 2.4 holds and has no cycles | Same script checks its `allowed` table (which must equal 2.4, including `pages -> media.api`) and runs a cycle detector | Exit 0, "no cycles" |
| F25 | Platform does not require slices (except `system.clj`/`server`) | `rg -n "aurashots\.features" src/aurashots/platform --glob '!**/server.clj'` | No output |
| F26 | Directory ownership | `git diff --name-only <base> HEAD` split by author or branch: files under `web/`, `scripts/web/` changed only by frontend-html; `src/`, `test/`, `resources/`, `deps.edn`, `bb.edn`, `scripts/backend/`, `scripts/check/` only by backend-clojure; nobody touches `design-tokens.css`, `assets/`, `api-contract.md`, `architecture.md` | No path outside its owner |
| F27 | Frontend never calls third parties or holds secrets | `rg -n "fetch\(\s*['\"\`]https?:" web`, `rg -n "<(script\|link)[^>]+(src\|href)=\"https?://" web`, `rg -n -i "api[_-]?key\|secret\|Bearer " web` | No output; all fetch targets begin `/api/` and only `web/js/api.js` calls `fetch` (`rg -l "fetch\(" web` lists only that file) |
| F28 | Logic is not only in the frontend | Contract tests F15/F16 and the following: submit a booking with an unpublished service, a past slot, a stale agreement version, a price-less service, a sold-out product | Each returns the contract's 409/400 even though the UI would block it |

### Frontend, design and SEO

| # | Rule | Check | Pass |
|---|---|---|---|
| F29 | Tokens only, no inline styles or scripts, no hard-coded colours | `rg -n "#[0-9a-fA-F]{3,8}\b" web --glob '*.css' --glob '*.html' --glob '*.js'`; `rg -n 'style="\|onclick=\|onload=' web`; `rg -n "<script>[^<]" web` | No output (SVG files in `assets/` are exempt) |
| F30 | SEO region present and complete on every indexable page | `bb scripts/check/seo.clj <base-url>` (or the equivalent `curl` loop, no JavaScript) for each indexable URL in the contract section 6 table plus one seeded service and product | Exactly one `<title>`, `meta description`, `link rel=canonical` equal to the page's own URL, `og:title`, `og:description`, `og:image`, one `application/ld+json` that parses as JSON, exactly one `<h1>`; title text matches design-spec Part 6; meta description at least 20 characters |
| F31 | Dynamic head is live data | `curl -s $BASE/services/single-session` then change the service name in admin and re-fetch after 60 seconds | Title, JSON-LD `name` and `offers.price`/`priceCurrency":"RWF"` change; initial-data script equals `GET /api/services/single-session` |
| F32 | Real 404s | `curl -s -o /dev/null -w "%{http_code}" $BASE/services/nope` and a hidden service slug; `$BASE/nope` | `404` each; body is the 404 page; `X-Robots-Tag: noindex` |
| F33 | Noindex and sitemap (prod) | `curl -sI` on `/book`, `/cart/sessions`, `/shop/checkout`, `/admin`; `curl -s $BASE/sitemap.xml`; `curl -s $BASE/robots.txt` | The four pages have `X-Robots-Tag: noindex`; sitemap lists published services/products and indexable static pages and none of the noindex ones; robots disallows `/admin`, `/api/` |
| F34 | Accessibility WCAG 2.1 AA | `npx pa11y --standard WCAG2AA --runner axe <url>` over every public page and admin screen (dev tool only, not shipped), plus keyboard walkthrough of calendar, lightbox, dialogs | Zero errors; walkthrough notes attached |
| F35 | CSP on HTML | `curl -sI $BASE/`, `$BASE/services/single-session`, `$BASE/nope`, `$BASE/admin`; load all pages in a browser with the console open | `Content-Security-Policy` present on every HTML response (JSON and media responses are not required to carry it); zero CSP violations logged |
| F36 | Fingerprinted assets cache | `curl -sI $BASE/css/<hashed>.css`, `curl -sI $BASE/media/<id>/card.jpg` | `Cache-Control: public, max-age=31536000, immutable` |

### Data, files, resources

| # | Rule | Check | Pass |
|---|---|---|---|
| F37 | Image upload limits and privacy | Upload an 11 MB file, a 7000 px file, a `.exe` renamed `.jpg`, and a JPEG with GPS EXIF; then `exiftool -gps:all /media/<id>/large.jpg` | First three give 400; the last gives no GPS tags; sizes thumb/card/large exist |
| F38 | Booking race | Two confirm calls for the same slot in parallel (different requests) | Exactly one 200 and one 409 `slotUnavailable`; with `force` true the second succeeds and history records the override |
| F39 | Money and time rules | Contract tests for: service cannot publish without price; price change leaves an existing booking snapshot unchanged; slot outside opening hours or inside the notice period unavailable; Sunday 21:30 close respected | All pass |
| F40 | Memory budget (**deployment-run check**: needs the real production flags and host; run by deployment-engineer, not in `bb check`) | On the host: `bb memory-check` (starts the app with the production JVM flags of section 3.1 and 8, runs the seeded smoke test plus 20 concurrent page/API readers for 5 minutes and one 6000 px upload, reads RSS from `ps -o rss= -p <pid>`) | Script exits 0 and reports peak RSS under 800 MB |
| F41 | Backup restores (runs on the developer machine before launch, on the host at acceptance, and after every Datalevin upgrade) | `bb restore-drill` (takes a backup, restores the snapshot and uploads into a temporary directory, starts the app on another port, runs the smoke test, cleans up) | Exit 0; restored instance serves the same services, bookings and images; takes under 15 minutes |
| F42 | Outbox robustness | Stop SMTP (wrong password), submit a booking, restore SMTP | Booking returns 201; message shows `failed`/`queued`; after fixing, the worker sends it; no duplicate emails |
| F43 | Logs | Trigger a 500 and a validation error | One structured log line each with request id; no password, token or request body present (`rg -i "password" logs` empty) |
| F44 | Booking and order history is append-only | `rg -n "retract" src/aurashots/features/booking src/aurashots/features/orders`; `rg -n "(dissoc\|assoc)[^)]*:history\|:history[^)]*(pop\|butlast\|subvec\|drop\|remove\|filter)" src/aurashots/features/booking src/aurashots/features/orders`; a unit test in `test/` that runs every state transition and asserts the old `history` and `internalNotes` are a prefix of the new ones (backend adds the test if absent) | Both greps empty (no hit other than reading); the test passes |
| F45 | Staging is never indexable | Start with `APP_ENV=staging`: `curl -s $BASE/robots.txt`; `curl -sI $BASE/`, `/services`, `/admin`, `/nope`; `curl -s $BASE/sitemap.xml` with and without `PRODUCTION_BASE_URL` set | robots is exactly `User-agent: *` newline `Disallow: /`; every HTML response has `X-Robots-Tag: noindex` (API responses do not need it); sitemap `<loc>` values all start with `PRODUCTION_BASE_URL`, and the sitemap is an empty `<urlset>` when it is unset; with `APP_ENV=prod` none of this applies (no blanket Disallow, no blanket noindex) |
| F46 | No invented base URL | `PUBLIC_BASE_URL= APP_ENV=prod bb build-web`; same with `APP_ENV=staging`; then build with `PUBLIC_BASE_URL=https://x.example.test` and `rg -c "x.example.test" dist/web/site`; `rg -n "aura-shots.com" dist/web src scripts resources` | The two unset builds exit non-zero with "PUBLIC_BASE_URL must be set"; every canonical and `og:url` uses the given base; no hard-coded domain anywhere. Runtime start with `APP_ENV=prod` and no `PUBLIC_BASE_URL` fails validation (pending backend: `config.edn` currently defaults it to localhost for all profiles) |
| F47 | Stable share image | `curl -s -o /dev/null -w "%{http_code} %{content_type}" $BASE/assets/og-image.png`; `curl -s $BASE/ \| rg -o 'og:image" content="[^"]*"'` and the same for `/contact`, `/legal/privacy`, a service without a photo; fetch every `og:image` URL found | `/assets/og-image.png` returns `200 image/png` at 1200 x 630; every `og:image` and JSON-LD image URL on every page returns 200 |
| F48 | `emailQueued` is honest | With SMTP unset: `POST /api/admin/staff` and `POST /api/admin/staff/{id}/reset-password-link` as owner, then list the outbox/messages; with SMTP set repeat | Unset: `emailQueued:false`, no `message` row created for the link, link returned in the response; set: `emailQueued:true` and exactly one message queued; the link is never present in any list or log (`rg -i "invitationUrl\|/reset" logs` empty) |
| F49 | Datalevin pin and JVM flags | `rg -n "datalevin" deps.edn`; `rg -n "add-opens\|enable-native-access" deps.edn scripts/backend/run.clj`; `bb restore-drill` after any change to the version | Exactly one Datalevin coordinate with the exact version `1.2.10` (no range, no `LATEST`); the three flags of 3.1 appear in the `:dev`, `:run`, `:test` aliases and `run.clj`; drill passes |
| F50 | Frontend build is concatenation, CSP-safe | `rg -n "type=\"module\"\|^\s*(import\|export)\s" web`; `bb build-web` then `rg -n "<script>[^<]\|style=\|onclick=" dist/web` | No output; build exits 0 (it fails by itself on an inline script, handler or style) |
| F51 | Sold-out rule | Contract test: product with two groups; set every option of one group out, product reports `soldOut` in list and detail; set one in stock, `inStock`; mark sold out via the product flag then re-enable an option via PATCH and via PUT, product reports `inStock` | All pass; list and detail always agree |
| F52 | Slugs stable; one option per group | Contract test: PUT a service and a product with a new name, GET; quote and order with zero and with two options of one group | Slug unchanged after rename; zero or two options of one group give 400 `validationFailed` (or quote `invalidOption`) |
| F53 | Every route has a response schema | `bb check` (runs `scripts/check/responses.clj`); `bb test` (the response sweep exercises all routes with `:response-check :throw`) | `OK: <n> routes, all declare a response schema`; tests pass |

---

## 8. What deployment must provide

Requirements only. Providers, plans, CDNs, registrars, domains and prices are not chosen here; deployment-engineer researches them after qa and the owner decides. Everything below must fit the 15 USD per month total.

**Processes**
- Exactly one application process: a JVM started as `java -Xmx512m --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -jar target/aurashots.jar` (the flag set in `scripts/backend/run.clj` is authoritative; the jar manifest does not carry them), with a JDK that Datalevin 1.2.10 supports on the chosen CPU architecture. No other long-running service is needed: no separate database server, queue, cache or frontend server.
- It must be restarted automatically on crash or reboot and given a graceful stop (it flushes the database; a stop signal followed by up to 30 seconds).
- Only one instance may open the database directory at a time, including during deploys (stop old, start new; a few seconds of downtime is acceptable). The command line tasks (`bb create-owner`, `bb owner-link`, `bb backup`, `bb export`) also need the server stopped.
- A build step (`bb build-web`, then the backend build) runs in CI or on the owner's machine; the production host receives the application artefact, the `dist/web` directory and the `business/images/shots` seed directory for the first start. No Node or build tools are needed on the host. The web build needs `PUBLIC_BASE_URL` set.

**Memory and CPU**
- Host memory of at least 1 GB, JVM heap capped at 512 MB (`-Xmx512m`), total resident memory target under 800 MB including the memory-mapped database pages that count in resident size. A small swap file is advisable. One shared vCPU is enough.

**Routing and TLS**
- HTTPS for the whole site with certificate renewal; redirect HTTP to HTTPS; the application listens on one internal HTTP port (`PORT`).
- The edge must pass `X-Forwarded-For` and `X-Forwarded-Proto`; the number of trusted proxy hops is configured in `TRUSTED_PROXY_HOPS` (must be at least 1 behind an edge, otherwise all clients share one rate-limit bucket). Rate limiting depends on it.
- Compression (gzip or brotli) of text responses at the edge (or the application can do it if the edge cannot).
- Only the one hostname is needed for production; `www` to apex (or the reverse) redirect so canonical URLs hold. A staging deployment, if any, uses another hostname and `APP_ENV=staging` and must not be given the production hostname.

**Caching**
- Honour the origin's `Cache-Control` headers; never cache `/api/admin/**`, `/admin/**`, `/api/availability`, `/api/quotes/**`, or any response with `Set-Cookie`. Public pages are `max-age=60-300`; `/media/**`, fingerprinted `/css/**`, `/js/**`, `/fonts/**`, `/assets/**` are immutable for a year; the unhashed `/assets/og-image.png` is `max-age=86400` and must not be forced to immutable. A CDN is optional, not required; if used it must follow these rules.

**Storage**
- A persistent disk (survives redeploys and reboots) of at least 5 GB holding `DATA_DIR`: `db/` (expected under 200 MB for years), `uploads/` (budget 3 GB: about 1 MB per photo across all sizes; roughly 2000 photos) and `backups/`. Disk usage alert at 80 percent.

**Backup**
- Nightly: the application writes a compacted consistent database snapshot to `BACKUP_DIR` (Datalevin `copy`, last 14 kept) and weekly a logical EDN export (last 8 kept; being added, D2); the host must additionally copy `BACKUP_DIR` and `uploads/` to a location off the host (different failure domain) each night, keep 14 daily and 8 weekly versions, and encrypt or restrict access to them (they contain customer contact details).
- A restore drill (`bb restore-drill`, F41) passes before launch, is repeated after any storage change and after every Datalevin upgrade.

**Environment and secrets** (names fixed; values supplied by the host, never committed)
`APP_ENV` (dev|staging|prod; validated at startup), `PORT`, `PUBLIC_BASE_URL` (origin used for canonical URLs, emails, sitemap in prod; required for the web build and for staging and prod), `PRODUCTION_BASE_URL` (staging only: origin used for the sitemap; optional), `DATA_DIR`, `UPLOAD_DIR`, `WEB_ROOT` (default `dist/web`), `BACKUP_DIR`, `OWNER_EMAIL` (optional until known; public contact address and mail sender), `SMTP_HOST` (smtp.gmail.com), `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD` (secret: Gmail app password; optional until email is set up), `TOKEN_PEPPER` (secret: random 32 bytes used when hashing tokens; required in staging and prod, and must not change after launch or all sessions and links stop working), `TRUSTED_PROXY_HOPS`, `COOKIE_SECURE`, `LOG_LEVEL`. Secrets are stored in the host's secret mechanism or a root-only file, not in the repository, image or logs.

**Email**: outbound SMTP over TLS to Gmail must be reachable from the host (some hosts block port 25/465/587 for new accounts; deployment-engineer verifies). The owner must enable 2-step verification and create an app password when a mailbox exists; until then the site works with emails queued (owner answer Q1) and staff links shown in the admin.

**Acceptance checks for deployment-engineer**
1. `curl -sI http://$HOST/` redirects to https; certificate valid; `curl -s $BASE/api/health` returns `{"status":"ok"}`.
2. `curl -sI $BASE/` shows the security headers of F35 and, behind the edge, `Cache-Control` unchanged from the origin.
3. Rate limit sees real client IPs: send 11 enquiry POSTs from one address; the 11th is 429; the log shows the client address, not the proxy.
4. Reboot the host: the service is back without manual action and the data is intact.
5. Restart during a booking POST (kill -9 then start): no half-written booking, replaying the same `Idempotency-Key` returns one booking.
6. F40 (`bb memory-check`) on the real host; F41 (`bb restore-drill`) from the off-host copy; F42 mail sent from the real Gmail account and received.
7. The disk alert fires on a test threshold.
8. A monthly cost check shows the total at or below 15 USD, listed line by line.
9. F45 on any staging deployment (robots, noindex, sitemap), and on production `curl -s $BASE/robots.txt` shows normal rules and a `Sitemap:` line with the production origin, with no `X-Robots-Tag: noindex` on `/`.
10. F47 against the live origin: `/assets/og-image.png` returns 200.

Non-binding leads from this design phase are in `deployment-handover.md`.

---

## 9. Open questions for the owner

1. **Owner email (OWNER_EMAIL)** is still unknown. Needed before launch for the public contact address and as the mail sender. Will you enable 2-step verification and create a Gmail app password for the site?
2. **Approve proposed exceptions PE1 and PE2?** Approved (see owner answers).
3. **Datalevin versus Datomic:** accepted (see owner answers). The pinned version is 1.2.10 (3.1).
4. **Original photos:** answered (see owner answers).
5. **Search visibility:** answered.
6. **Off-site backup destination:** deferred to deployment-engineer.
7. **Default session length:** answered (60 minutes; sum of lengths).
8. **Admin address:** answered.
9. **clj-kondo config:** answered.
10. **.gitignore:** done.
11. Items still `<TBD>` in business-context.md remain placeholders: TikTok and Facebook links, delivery areas and fees, policy wording (human review before launch).
12. Support staff permissions: answered.
13. **New.** Customer emails with SMTP unconfigured: owner answer Q1 keeps customer receipts and decisions queued in the outbox, while staff invitation and reset links are not queued at all when email is unconfigured (`emailQueued:false`). Confirm that difference is intended, and whether queued customer emails should be sent when SMTP is configured later (today: yes, the worker sends everything due).
14. **New.** The aggregate-as-one-map data model (D15) means booking and order history is append-only by convention and a test, not by the database. Acceptable for the audit need (acceptance records remain separate and immutable)?

---

### Owner answers (29 October 2026)

- Q1 OWNER_EMAIL: still unknown; the owner may not have a mailbox yet. Keep the OWNER_EMAIL placeholder. The site must start and work with email unconfigured: the outbox keeps messages queued and admin shows them as not sent, so staff contact customers by phone/WhatsApp until email is set up. The Gmail app password question is deferred to deployment.
- Q2 PE1 approved: the frontend is static HTML/JavaScript running in the browser, served by the backend; no separate frontend server.
- Q2 PE2 approved: the single-instance limit is accepted.
- Q9 approved: Ring middleware/adapters and Reitit are now banned in .clj-kondo/config.edn (ring.util.* stays allowed). The namespace-docstring check remains fitness check F5.
- Q10 done: /data/ and /dist/ are in .gitignore.
- Q12: support staff manage the portfolio (including its categories) and may edit all other settings (contact details, opening hours, delivery areas), as the contract already allows. Policies and staff stay owner-only.
- Q3: Datalevin accepted.
- Q4: yes, the owner keeps full-resolution originals; the site keeps only resized copies.
- Q5: yes, JavaScript-drawn page bodies with server-inserted head tags are acceptable for launch.
- Q6: off-site backup destination is left to deployment-engineer to propose for the owner's choice.
- Q7: yes, default session length 60 minutes; a request with several packages uses the sum of their lengths.
- Q8: yes, the admin stays at the public /admin path.

---

## 10. Changes made to api-contract.md

1. Header: backend described as one service on one origin; frontend is plain HTML served by it (the separate ClojureScript SSR server is removed).
2. Conventions: added the in-flight duplicate rule (409 `conflict` with `Retry-After`); stated that `fields` is the only allowed extension to the error shape; added the Media convention (`/media/{imageId}/{size}.jpg`, sizes, immutable caching, `Image.url` = large, `thumbUrl` = thumb); added the client-IP rule (`X-Forwarded-For`, trusted hops).
3. Idempotency: clarified admin repeat semantics (same call and body returns 200; `confirm` with a different slot is `conflict`; `mark-paid` repeated with different values is `conflict`).
4. Honeypot: replaced the garbled "empty or empty and ignored" with a testable rule (absent or empty; non-empty gives 400 `validationFailed`, nothing stored).
5. 2.7 availability: added optional `serviceIds` and defined session length (sum of service lengths, default from settings, 60 minutes if unset); the original contract could not apply "close minus session length" without it.
6. 2.11 response and 3.3 record: added `durationMinutes` (snapshotted; length of the hold).
7. 2.16: `GET /api/sitemap` removed; replaced by `GET /sitemap.xml` and `GET /robots.txt` generated by the backend (there is no frontend server to consume the JSON).
8. 3.4: admin paths renamed from `/api/admin/orders` to `/api/admin/merchandise-orders` to match the public resource name (consistent naming with `booking-requests`).
9. 3.5 and 3.8: `PUT /api/admin/services/order` renamed `PUT /api/admin/service-order` and `PUT /api/admin/portfolio/order` renamed `PUT /api/admin/portfolio-order`, because the literal `order` collided with `{id}` routes.
10. 3.5 and 3.6: slugs `checkout` and `received` reserved (they collide with page routes under `/shop`).
11. 3.12: staff list gained `invitationPending` so the UI can show "Invitation sent" (journey 5).
12. New section 6 "Page serving": route table (URL to file, static or dynamic head, noindex), the `<!--@seo:begin-->`/`<!--@seo:end-->` region, the `<!--@data-->` initial-data block, caching and security-header rules, fingerprinted assets, no inline scripts or styles. This is the only coupling between the two builders beyond the JSON endpoints.

Checked and left unchanged: money in integer RwF with `Rwf` suffix; camelCase keys; error codes and statuses; pagination shape; state machines; rate-limit numbers; documents and acceptance endpoints; admin auth (cookie plus CSRF). Note for ux-designer: design-spec P-pages list `/legal/*` as four pages and agreements inside P8/P11, which the route table follows; the admin-help topic ids are consumed by the frontend build only.

Later amendments to api-contract.md: Amendment A1 (staff links, owner decision) and Amendment A2 (rulings after the review, section 11 below), both at the end of that file.

---

## 11. Amendment pass (29 October 2026, after architecture-review.md and QA report 2)

Design changes in this file, by review item:

- D3: 3.2 rewritten to the data model as built (aggregates as nested maps; settings as one object; acceptance, document versions and messages separate; history append-only by convention); new check F44; new decision D15; new open question 14.
- D5: 2.2 (platform helpers, `cli`), 2.3 and 2.4 (pages -> media.api, the list of functions injected by `system.clj`, a fourth acyclic-by-injection case); F24 now names `pages -> media.api`.
- D6: 2.2 `config` row, section 5 "Configuration per environment" and F7 reworded to the build (`APP_ENV` validated; dev profile versus default).
- D7: section 5 CSP is on HTML responses only; F35 reworded.
- D8: 2.5 build row, section 5 "Frontend build and CSP safety", decision D13 reworded; new check F50.
- D11: 3.1 pins Datalevin to 1.2.10 with the add-opens flags and a restore drill after every upgrade; section 8 production command; F49; decision D3.
- D12: F40 classified as a deployment-run check with `bb memory-check`; D13: F41 is `bb restore-drill`.
- D1 and D2 remain code items (response schemas; weekly export); the design text already required them and now states them as "being implemented" (5, 2.2, 8).
- New rules: staging robots, noindex and sitemap behaviour; `PUBLIC_BASE_URL` required for the web build and for staging/prod; stable unhashed `/assets/og-image.png`; `emailQueued` true only when SMTP is configured (section 5; section 8 environment and acceptance items 9 and 10); checks F45 to F48, F51, F52; `PRODUCTION_BASE_URL` and `UPLOAD_DIR` added to the environment list.
- api-contract.md: Amendment A2 (ten rulings from the review plus the staging, og-image and `emailQueued` rules); list at the end of that file.
