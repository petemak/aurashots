# Scope Checklist – Aura Shots

Sources: BC = business-context.md (wins on operating facts), BB = brand-brief.md, ES = engineering-standards.md, OD = owner decisions confirmed in chat (also in BC), A = my assumption.

## What kind of site this is

A private, appointment-only photography service with a small secondary merchandise shop, run from Kigali. Two separate transaction types, both request-and-approve with offline payment:

1. Photography session booking requests (a service, not a physical good).
2. Merchandise orders (clothing, prints, framed art), delivered after approval.

There is no online payment and no customer login. Staff (owner plus support) run everything from an admin. Customers: Kigali locals, urban professionals, international tourists (BB section 2). Admin users: the owner and support staff. Their computer comfort is not stated in BC, so I assume they are NOT technical (A, as admin-help.md requires): plain labelled forms, one task per screen, confirmation before anything is removed.

## Capability categories

| Category | Verdict | Reason and source |
|---|---|---|
| Catalog / inventory | APPLIES | Service catalogue (5 seed packages with contents and conditions) and merchandise catalogue with variants (BC "Products and catalog"). Managed by owner and support in the admin (BC owner/support lists). Services: a price is required before a service can be published (BC "Further rules"). Merchandise stock is NOT counted: staff set an In stock / Sold out status per item or variant (BC rules). Portfolio is also curated content managed by staff (OD; BC). |
| Payments / checkout | APPLIES, request-and-approve only | One-time purchases, no subscriptions. No online payment: staff approve first, then the customer pays cash or mobile money (BC "Orders and running the site", OD). So "checkout" = submitting a request; the system records payment status (unpaid / paid, method) set by staff. Two separate checkouts: sessions and merchandise, never mixed (BC, OD). Guest only. |
| Accounts / auth | Customer: DOESN'T APPLY. Admin: APPLIES | Guest checkout only, no customer accounts (OD). Admin sign in/out for owner (root) and support staff; only the owner manages staff accounts (BC, OD). Authorisation is enforced on the server (ES). |
| Admin / back-office | APPLIES | Bookings/orders, catalogue, portfolio, calendar, settings (owner and support); staff accounts and policy texts (owner only; see "Roles and permissions" below) (BC). Staff cannot create bookings for customers (BC rule), so there is deliberately no "new booking" admin screen. |
| Search / discovery | APPLIES (light) | Catalogue is small (5 services, a modest merchandise range). Category filters and sorting on the shop, category filter on the portfolio, no free-text search at launch (A). SEO discovery matters because the goal is to reach people who do not use social media (BC): per-page titles, search intents and schema.org markup. Revisit free-text search if merchandise grows beyond roughly 40 items. |
| Notifications | APPLIES | Customers "expect response via mail or WhatsApp" (BC). Email is automated, sent from the owner's Gmail address (BC; OWNER_EMAIL placeholder, OD). WhatsApp is manual: staff tap a prefilled click-to-chat link (A: avoids a paid WhatsApp Business API and keeps within 15 USD/month). Events: request received, confirmed / time changed / declined, merchandise approved / rejected / delivered, and an alert to the owner for each new request or enquiry. No newsletter or marketing email (OD, BB). |
| Legal / compliance | APPLIES | Terms of sale, privacy policy, refund policy (strict no-refund, BB section 7.9), order terms – all drafted as placeholders for human review before launch (BC). Privacy policy must mention stored contact details and acceptance records. Rwanda data-protection law (A: Law 058/2021) to be checked by the human reviewer. |
| Customer agreements | APPLIES | A session booking is a commitment with a strict no-refund policy (BC "Policy"), and merchandise orders have order terms (BC). These must be shown and explicitly accepted at the moment the request is submitted, and the acceptance recorded (who, when, which version). Two agreements: **Session Booking Agreement** and **Merchandise Order Terms**. Distinct from the static footer policy pages. Appears as a first-class step in user-journeys.md and as an acceptance-recording field on the submit endpoints in api-contract.md. |

## Roles and permissions

There are two admin roles: **owner** (the business owner, root account; exactly one) and **support** (staff appointed by the owner). Customers have no accounts. Every rule below is enforced on the server for every request (ES), not only by hiding buttons; a support user calling an owner-only endpoint gets `403 forbidden`.

| Task | Owner | Support | Source |
|---|---|---|---|
| Sign in / sign out, change own password | yes | yes | BC |
| **Create, deactivate or remove staff accounts** | **yes** | **no** | BC ("Add/remove support employee account" is listed for the owner only) |
| Manage services (add, remove, edit descriptions, prices, contents, images) | yes | yes | BC |
| Manage merchandise and stock status | yes | yes | BC |
| Manage portfolio, including categories | yes | yes | BC, OD |
| Manage calendar (block / unblock dates and times) | yes | yes | BC |
| View and process session requests (confirm, change time, decline, mark paid) | yes | yes | BC |
| View and process merchandise orders (approve, reject, mark paid, mark delivered) | yes | yes | BC |
| Edit site settings (contact details, opening hours, delivery areas, session defaults) | yes | yes | OD |
| Edit policy and agreement texts | yes | no | A, confirmed by owner |
| Create a booking or order on behalf of a customer | no | no | BC ("Staff cannot enter bookings on behalf of customers") |

Additional rules:
- The first owner account is created once at install time (command line task, see architecture.md); it cannot be created, deactivated or deleted through the admin.
- Accounts created in the admin always get the **support** role; there is no way to create a second owner or promote support staff to owner.
- The owner cannot remove or deactivate their own account.
- Support staff do not see the Staff pages, the staff menu item, or the staff topics in admin-help.md.

## Assumptions (BC is silent; owner to confirm)

1. Admin users are not technical (see above).
2. Session length: each service has an optional "Session length (minutes)". If empty, a site-wide default of 60 minutes applies (OD), editable in Settings. A request with several services uses the sum of their lengths (OD). Confirmed sessions block their start time plus length.
3. Time slots: start times on a 30-minute grid within opening hours (Mon-Sat 08:00-22:00, Sun 10:00-21:30, Africa/Kigali). Minimum notice default 24 hours and booking window default 180 days, both editable in Settings.
4. Pending requests do NOT block a slot (several customers may ask for the same time). A confirmed session blocks it. Staff resolve conflicts by confirming one and changing or declining the other.
5. A session request contains one or more services from the session cart but only ONE preferred date and start time.
6. The "address" field in BC's session flow is kept but optional and labelled "Address or area for the shoot", since tourists may not know yet. Merchandise delivery address is required.
7. After submission the customer gets a reference code and a confirmation page. There is no customer order-tracking page; status updates arrive by email or WhatsApp.
8. Policy documents and agreement texts are edited by the owner only (support cannot change contract wording). Settings (delivery areas and fees, social links, defaults) are editable by owner and support, since BC says missing information is for "business owner or staff" to fill.
9. Stock sizes and colours for clothing are defined by staff per item; none are given in BC.
10. Images are stored with the backend (object/volume storage within hosting budget) and uploaded through the admin; resized variants are produced server-side.
11. Hosting budget 15 USD/month, domain aura-shots.com registered later; cheapest-registrar research is outside the scope of this design.

## Conflicts and open questions flagged

- None blocking. Seed prices (Besties 12,000, Special 10,000, Weekend 15,000 RwF) are confirmed correct by the owner and are editable; not re-flagged.
- Open `<TBD>` items left as placeholders: TikTok and Facebook URLs, delivery areas and fees, owner email (OWNER_EMAIL; the site must work with email unconfigured, see architecture.md), policy wording.
- Datastore: Datalevin, embedded in the backend (OD, architecture.md section 3). Acceptance records and booking history are written as append-only entities, so no time-travel history is needed. The service runs as a single instance (PE2, accepted by the owner).
- No separate frontend server (PE1, approved by the owner): the frontend is static HTML/JavaScript running in the browser, served by the backend, which inserts the SEO head tags into public pages before sending them (architecture.md, api-contract.md section 6). Public pages call the public endpoints; the admin app calls the admin endpoints with a session cookie plus CSRF header.
