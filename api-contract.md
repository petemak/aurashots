# API Contract – Aura Shots

Backend: Clojure (Pedestal, Malli, Datalevin), one service on one origin. Frontend: plain HTML5/CSS/JS files served by that same service; the browser calls only the endpoints below (section 6 defines how the service serves the pages). Amended by architect, see the change list at the end of architecture.md.

## 1. Conventions

- Base path `/api`. JSON only (`Content-Type: application/json`) except image upload (`multipart/form-data`).
- Keys camelCase. Dates `YYYY-MM-DD`; times `HH:mm` (24-hour, local); instants ISO 8601 with offset (e.g. `2026-11-14T10:30:00+02:00`). Business time zone `Africa/Kigali`.
- Money: integer RwF, field names end in `Rwf` (e.g. `priceRwf: 50000`). Never decimals.
- IDs: opaque strings (`id`). Public references: `AS-S-` + 8 characters for sessions, `AS-M-` + 8 characters for merchandise (uppercase letters and digits, no I/O/0/1).
- **Error shape everywhere:** `{ "error": { "code": "...", "message": "...", "fields": { "fieldName": "message" } } }` (`fields` only on validation errors; it is an optional extension to the standard `{error:{code,message}}`).
- Error codes: `validationFailed` 400, `unauthenticated` 401, `forbidden` 403, `notFound` 404, `conflict` 409, `slotUnavailable` 409, `itemUnavailable` 409, `agreementVersionStale` 409, `idempotencyKeyReused` 422, `rateLimited` 429, `serverError` 500. A second request that arrives with the same `Idempotency-Key` while the first is still being processed gets 409 `conflict` with header `Retry-After: 2`. The `fields` member is an accepted optional extension of the standard error shape; no other members are allowed.
- **Media:** every `Image.url` / `thumbUrl` is a same-origin path of the form `/media/{imageId}/{size}.jpg` (sizes: `thumb` 400 px, `card` 800 px, `large` 1600 px long edge; the original is never served). Media responses carry `Cache-Control: public, max-age=31536000, immutable` (image ids never change content). `Image.url` is the `large` size, `thumbUrl` the `thumb` size.
- **Client IP:** rate limits use the client address supplied by the hosting edge in `X-Forwarded-For`, trusting only the configured number of proxy hops.
- **Idempotency:** every public POST that creates something (`booking-requests`, `merchandise-orders`, `enquiries`) requires header `Idempotency-Key: <uuid>`. The backend stores key + request hash + response in the database. Same key and same body returns the original response (status identical, header `Idempotent-Replay: true`); same key with a different body gives `idempotencyKeyReused`. Admin state-changing POSTs (confirm, approve, decline, mark-paid, etc.) are idempotent by design: repeating the same call with the same body when the record is already in the resulting state returns 200 with the current record (for example `confirm` with the same date and time on an already confirmed request; `confirm` with a different slot is a `reschedule` and gives `conflict`); any other illegal transition gives `conflict`. `mark-paid` repeated with identical method and amount returns 200; with different values it gives `conflict`.
- **Validation:** all bodies and query strings validated with Malli at the boundary; unknown keys rejected. Text fields trimmed. Limits stated per field.
- **Rate limiting** (counters in the database): public POSTs 10/hour per IP; login 5 failed attempts per 15 minutes per email+IP. Honeypot field `website` must be absent or an empty string; a non-empty value is rejected with 400 `validationFailed` (generic message, no `fields` entry) and nothing is stored.
- **Admin auth:** `POST /api/admin/auth/login` sets an HttpOnly, Secure, SameSite=Lax session cookie (`session`) stored server-side in the database. All state-changing admin requests must also send `X-CSRF-Token` (value from login/`me` response). Every `/api/admin/*` endpoint except login, forgot-password and reset-password requires a valid session; role checked server-side. Roles: `owner`, `support`. "Owner only" endpoints return `forbidden` for support.
- **Caching:** public GETs for catalogue content send `Cache-Control: public, max-age=60, stale-while-revalidate=300` plus `ETag`; availability and quotes `no-store`.
- **Pagination** (where stated): `page` (default 1), `pageSize` (default 24, max 100); response `{ items: [...], page, pageSize, total }`.
- **Notifications** are sent by the backend asynchronously from a database outbox (retry with back-off). The API never fails because an email failed; the failure shows in `messages` on the record.

Shared shapes

```
Image      { id, url, thumbUrl, width, height, alt }
Money      integer RwF
Slot       { date: "2026-11-14", startTime: "10:30" }
Customer   { name (2-100), phone (7-20, +digits/spaces), email (valid, <=254), address? (<=300) }
Message    { id, channel: "email", template, to, sentAt|null, status: "queued"|"sent"|"failed", error? }
```

## 2. Public endpoints (no authentication)

### 2.1 GET /api/site-info
Response 200
```
{
  "name": "Aura Shots", "tagline": "Not just photos…it's an aura",
  "address": "Kanombe, Kigali, Rwanda",
  "phone": "+250 790 114 749", "whatsapp": "+250790114749",
  "email": "OWNER_EMAIL",
  "social": { "instagram": "https://www.instagram.com/aura_shxts/", "tiktok": null, "facebook": null },
  "openingHours": [ { "days": ["mon","tue","wed","thu","fri","sat"], "open": "08:00", "close": "22:00" },
                    { "days": ["sun"], "open": "10:00", "close": "21:30" } ],
  "timeZone": "Africa/Kigali",
  "booking": { "minNoticeHours": 24, "windowDays": 180, "slotMinutes": 30, "defaultSessionMinutes": null },
  "deliveryAreas": [ { "id": "...", "name": "Kigali – Kanombe", "feeRwf": null } ],
  "portfolioCategories": [ { "id": "portraits", "name": "Portraits" } ]
}
```
`null` means `<TBD>`; the frontend shows `<TBD>` for social placeholders it must display, and hides empty links in the footer.

### 2.2 GET /api/services
Query: none. Response 200 `{ items: [Service] }`, only published services with a price, ordered by `sortOrder`.
```
Service {
  id, slug, name, shortDescription, priceRwf, sessionMinutes|null, featured: bool,
  mainImage: Image, 
  contents: { editedPictures: int|null, videos: int|null, outfits: int|null,
              deliveryTime: string|null, locations: string|null }
}
```

### 2.3 GET /api/services/{slug}
Response 200: Service plus `{ description, conditions, images: [Image], relatedProducts: [ProductSummary], relatedServices: [Service] }`. 404 `notFound` if unpublished.

### 2.4 GET /api/portfolio
Query: `category?`, `featured?` (bool), `page`, `pageSize`. Response 200 paginated `{ items: [ { id, image: Image, caption, description, categoryId, categoryName } ], page, pageSize, total }`.

### 2.5 GET /api/products
Query: `category?` (`clothing`|`prints`|`framed-art`), `sort?` (`newest`|`priceAsc`|`priceDesc`), `featured?`, `page`, `pageSize`.
Response 200 paginated:
```
ProductSummary { id, slug, name, category, priceRwf, mainImage: Image, isNew: bool, availability: "inStock"|"soldOut" }
```
`availability` is `soldOut` when the item (or all of its options) is flagged sold out.

### 2.6 GET /api/products/{slug}
Response 200: ProductSummary plus
```
{ description, details, images: [Image],
  optionGroups: [ { name: "Size", options: [ { id, label: "M", inStock: true } ] },
                  { name: "Colour", options: [ ... ] } ],
  relatedServiceIds: [string] }
```
Note: stock is not counted; `inStock` is a staff-set flag only.

### 2.7 GET /api/availability
Query: `from` (date), `to` (date), max span 62 days; optional `date` (single day, with times); optional `serviceIds` (comma-separated, 1-10). The session length used for slot rules is the sum of the `sessionMinutes` of the given services (each falling back to `booking.defaultSessionMinutes`); with no `serviceIds`, or when no length is known, the default session length applies, and 60 minutes when that is also unset. The same function is used when a request is submitted and when staff confirm.
Response 200
```
{ "timeZone": "Africa/Kigali",
  "days": [ { "date": "2026-11-14", "status": "available"|"blocked"|"full"|"past"|"tooSoon"|"closed",
              "slots": [ { "startTime": "10:30", "available": true } ] } ] }
```
`slots` is included only when `date` is given, or for every day if the span is <= 7 days. Slot rules (computed on the server): within opening hours, on the 30-minute grid, last start = close minus session length, not inside a block, not overlapping a confirmed session, not earlier than now + `minNoticeHours`, not beyond `windowDays`. Pending requests do not block.

### 2.8 POST /api/quotes/sessions
Used by the basket to re-check prices. Request `{ "serviceIds": ["..."] }` (1-10). Response 200
```
{ "items": [ { "serviceId", "name", "priceRwf", "available": true, "reason": null, "mainImage": Image } ],
  "totalRwf": 150000 }
```
`available:false` with `reason: "unpublished"|"noPrice"|"removed"`.

### 2.9 POST /api/quotes/merchandise
Request `{ "items": [ { "productId", "optionIds": ["..."], "quantity": 1 } ], "deliveryAreaId": "..."? }` (1-20 lines, quantity 1-10).
Response 200
```
{ "items": [ { "productId", "name", "optionLabels": ["M","Black"], "unitPriceRwf", "quantity", "lineTotalRwf", "available": true, "reason": null } ],
  "subtotalRwf": 0, "deliveryFeeRwf": null, "totalRwf": null, "feeNote": "toBeConfirmed"|null }
```

### 2.10 GET /api/documents/{slug}
Slugs: `terms-of-sale`, `refund-policy`, `privacy`, `order-terms`, `session-booking-agreement`, `merchandise-order-terms`. Optional `?version=n` returns an older version (used by the "view accepted text" links).
Response 200
```
{ "slug", "title", "version": 3, "publishedAt": "2026-10-01T09:00:00+02:00",
  "requiresAcceptance": true, "summaryPoints": ["..."], "body": "markdown text" }
```
The body is a restricted Markdown subset (headings, paragraphs, lists, bold, links), rendered by the frontend without raw HTML.

### 2.11 POST /api/booking-requests  (session request; records agreement acceptance)
Headers: `Idempotency-Key`. Request
```
{
  "customer": Customer,
  "contactPreference": "email"|"whatsapp",
  "serviceIds": ["..."],                       // 1-10, unique
  "preferredSlot": { "date": "2026-11-14", "startTime": "10:30" },
  "notes": "string <=1000"|null,
  "agreement": { "slug": "session-booking-agreement", "version": 3, "accepted": true },
  "website": ""                                 // honeypot
}
```
Server behaviour (single transaction):
1. Validate; `agreement.accepted` must be `true` and `version` must equal the current published version, else 409 `agreementVersionStale` (body includes current version) or 400.
2. Re-check every service is published with a price (`itemUnavailable` otherwise) and the slot is currently available (`slotUnavailable` otherwise).
3. Create the booking request with status `pending`, snapshot service names and prices.
4. **Write an acceptance record** (immutable): `{ id, documentSlug, version, bodyHash (sha-256 of text accepted), subjectType: "bookingRequest", subjectId, subjectReference, customerName, customerEmail, customerPhone, acceptedAt (server clock, ISO 8601), ipAddress, userAgent }`.
5. Queue emails (customer receipt, owner alert).
Response 201
```
{ "id", "reference": "AS-S-7K2F9QXA", "accessToken": "opaque",
  "status": "pending", "createdAt",
  "services": [ { "serviceId", "name", "priceRwf" } ], "totalRwf",
  "preferredSlot": Slot, "contactPreference", "durationMinutes",
  "agreementAcceptance": { "slug", "version", "acceptedAt" } }
```
`durationMinutes` is snapshotted at submit (sum of service lengths or the default) and is the time a confirmed session blocks.
Errors: 400 `validationFailed`, 409 `slotUnavailable` (standard error body only, no alternatives list; the frontend re-fetches availability and returns the customer to step 1 with their details kept), 409 `itemUnavailable` (message lists names), 409 `agreementVersionStale`, 429.

### 2.12 GET /api/booking-requests/{reference}?token={accessToken}
Response 200: same fields as 2.11 response plus `status` current (`pending`|`confirmed`|`declined`|`completed`) and, when confirmed, `confirmedSlot: Slot`, `customerNote`. 404 if token wrong (no hint). Contains no internal notes.

### 2.13 POST /api/merchandise-orders  (records agreement acceptance)
Headers: `Idempotency-Key`. Request
```
{
  "customer": { "name", "phone", "email" },
  "delivery": { "areaId": "..."|null, "areaText": "string <=100"|null, "address": "string 5-300", "landmark": "string <=100"|null, "notes": "string <=500"|null },
  "items": [ { "productId", "optionIds": ["..."], "quantity": 1 } ],
  "agreement": { "slug": "merchandise-order-terms", "version": 2, "accepted": true },
  "website": ""
}
```
Server: validates (either `areaId` or `areaText` required); every item published, in stock (`itemUnavailable`); creates order `pending` with price snapshot and delivery fee snapshot (null when TBD); writes the acceptance record (as 2.11, `subjectType: "merchandiseOrder"`); queues emails.
Response 201
```
{ "id", "reference": "AS-M-4H8T2NWB", "accessToken", "status": "pending", "createdAt",
  "items": [ { "productId", "name", "optionLabels": [], "unitPriceRwf", "quantity", "lineTotalRwf" } ],
  "subtotalRwf", "deliveryFeeRwf": null, "totalRwf": null,
  "delivery": { ... }, "agreementAcceptance": { "slug", "version", "acceptedAt" } }
```

### 2.14 GET /api/merchandise-orders/{reference}?token={accessToken}
Response 200: as 2.13 plus current `status` (`pending`|`approved`|`rejected`|`paid`|`delivered`). Rejected orders show `customerNote` only.

### 2.15 POST /api/enquiries
Headers: `Idempotency-Key`. Request `{ "name": "2-100", "email": "valid", "phone": "string|null", "message": "10-2000", "website": "" }`.
Response 201 `{ "id", "receivedAt" }`. Stored, owner emailed, acknowledgement queued.

### 2.16 GET /sitemap.xml and GET /robots.txt (not under /api)
Generated by the backend from published services and products plus the static public pages (section 6). `robots.txt` disallows `/admin`, `/api/` and the noindex pages and points to `/sitemap.xml`. Replaces the earlier `GET /api/sitemap`, which is removed because there is no separate frontend server.

### 2.17 GET /api/health
Response 200 `{ "status": "ok" }`.

## 3. Admin endpoints (`/api/admin/*`, session cookie plus CSRF)

### 3.1 Authentication
- `POST /api/admin/auth/login` `{ email, password }` -> 200 `{ user: { id, name, email, role }, csrfToken }`; 401 `unauthenticated` (same message for unknown email or wrong password); 429.
- `POST /api/admin/auth/logout` -> 204.
- `GET /api/admin/auth/me` -> 200 `{ user, csrfToken }` or 401.
- `POST /api/admin/auth/forgot-password` `{ email }` -> always 202 `{ ok: true }`. Sends a single-use link valid 60 minutes.
- `POST /api/admin/auth/reset-password` `{ token, newPassword }` (min 12 chars) -> 204; 400 on bad/expired token. Also used by staff invitations to set the first password. Invalidates all sessions of that user.
- `POST /api/admin/auth/change-password` `{ currentPassword, newPassword }` -> 204; 400 on wrong current password.

### 3.2 Dashboard
`GET /api/admin/dashboard` -> 200
```
{ "pendingSessionRequests": 3, "pendingOrders": 1, "unansweredEnquiries": 2,
  "upcomingSessions": [ { "id", "reference", "customerName", "date", "startTime", "services": ["Single Session"] } ] }
```

### 3.3 Session requests (owner, support)
- `GET /api/admin/booking-requests?status=pending|confirmed|declined|completed&page&pageSize` -> paginated list items `{ id, reference, status, customerName, serviceNames: [], preferredSlot, confirmedSlot|null, createdAt }`. No status = all.
- `GET /api/admin/booking-requests/{id}` -> 200
```
{ id, reference, status, createdAt, customer: Customer, contactPreference, whatsappLink: "https://wa.me/250...?text=...",
  services: [ { serviceId, name, priceRwf } ], totalRwf, notes, preferredSlot, confirmedSlot|null, durationMinutes,
  slotConflict: null|"blocked"|"alreadyConfirmed",
  payment: { status: "unpaid"|"paid", method: "cash"|"mobileMoney"|null, amountRwf|null, paidAt|null },
  agreementAcceptance: { slug, version, acceptedAt, customerName, customerEmail, bodyHash, documentUrl },
  messages: [Message], internalNotes: [ { id, text, authorName, createdAt } ],
  history: [ { at, byName, action, detail } ] }
```
- `POST /api/admin/booking-requests/{id}/confirm` `{ date, startTime, noteToCustomer|null, force?: false }` -> 200 record. Only from `pending`. Final slot may differ from the request. If it clashes with a block or confirmed session returns 409 `slotUnavailable` unless `force: true` (explicit override by staff). Confirming creates a calendar hold for the session length; queues "confirmed" or "time changed" email to the customer.
- `POST /api/admin/booking-requests/{id}/decline` `{ reason }` -> 200. From `pending` only. Queues email.
- `POST /api/admin/booking-requests/{id}/reschedule` `{ date, startTime, noteToCustomer|null, force?: false }` -> 200. From `confirmed` only; moves the hold; emails customer.
- `POST /api/admin/booking-requests/{id}/mark-paid` `{ method, amountRwf }` -> 200. From `confirmed` or `completed`.
- `POST /api/admin/booking-requests/{id}/complete` -> 200. From `confirmed`; frees nothing, keeps record.
- `POST /api/admin/booking-requests/{id}/notes` `{ text (1-1000) }` -> 201 note.
- `POST /api/admin/booking-requests/{id}/messages/{messageId}/resend` -> 202.
There is intentionally NO endpoint to create a booking request on behalf of a customer.

### 3.4 Merchandise orders (owner, support)
Naming: admin paths use the same resource names as the public ones (`booking-requests`, `merchandise-orders`); the earlier `/api/admin/orders` is renamed `/api/admin/merchandise-orders` everywhere below.
- `GET /api/admin/merchandise-orders?status=pending|approved|paid|delivered|rejected&page&pageSize` -> list `{ id, reference, status, customerName, itemCount, totalRwf|null, createdAt }`.
- `GET /api/admin/merchandise-orders/{id}` -> 200 `{ id, reference, status, createdAt, customer, whatsappLink, delivery, items: [...], subtotalRwf, deliveryFeeRwf|null, totalRwf|null, payment, agreementAcceptance, messages, internalNotes, history }`.
- `POST /api/admin/merchandise-orders/{id}/approve` `{ deliveryFeeRwf: int|null, noteToCustomer|null }` -> 200. From `pending`. `deliveryFeeRwf` required when the stored fee is null. Queues email.
- `POST /api/admin/merchandise-orders/{id}/reject` `{ reason }` -> 200. From `pending`.
- `POST /api/admin/merchandise-orders/{id}/mark-paid` `{ method: "cash"|"mobileMoney", amountRwf }` -> 200. From `approved`.
- `POST /api/admin/merchandise-orders/{id}/mark-delivered` -> 200. From `paid` (or `approved` if paid on delivery: allowed only with payment recorded in the same call: body `{ method?, amountRwf? }`).
- `POST /api/admin/merchandise-orders/{id}/notes`, `POST /api/admin/merchandise-orders/{id}/messages/{messageId}/resend` as above.

### 3.5 Services (owner, support)
- `GET /api/admin/services?status=published|hidden|removed` -> `{ items: [ { id, name, priceRwf|null, status: "published"|"hidden", needsPrice: bool, mainImage|null } ] }` (removed excluded unless asked).
- `GET /api/admin/services/{id}` -> full record.
- `POST /api/admin/services` -> 201 and `PUT /api/admin/services/{id}` -> 200, same body:
```
{ "name": "1-100", "shortDescription": "<=200", "description": "<=5000", "priceRwf": int>=0 | null,
  "sessionMinutes": int|null, 
  "contents": { "editedPictures": int|null, "videos": int|null, "outfits": int|null, "deliveryTime": "string|null", "locations": "string|null" },
  "conditions": "<=3000", "relatedProductIds": ["..."],
  "images": [ { "imageId": "...", "alt": "1-200" } ],   // order = display order, first = main
  "featured": false, "status": "published"|"hidden", "sortOrder": 0 }
```
Rule: `status: "published"` with `priceRwf` null or 0 -> 400 `validationFailed` (`fields.priceRwf`). Slug generated from name, unique; the slugs `checkout` and `received` are reserved (they would collide with page routes).
- `PUT /api/admin/service-order` `{ ids: [ordered ids] }` -> 204. (Renamed from `/services/order`, which collided with `/services/{id}`.)
- `DELETE /api/admin/services/{id}` -> 204. Soft remove (status `removed`): disappears from the shop; existing booking requests keep their snapshots.
Price changes affect only new requests; existing requests keep their snapshot.

### 3.6 Merchandise (owner, support)
- `GET /api/admin/products`, `GET /api/admin/products/{id}`, `POST /api/admin/products`, `PUT /api/admin/products/{id}`, `DELETE /api/admin/products/{id}` (soft remove, 204).
- Body:
```
{ "name", "category": "clothing"|"prints"|"framed-art", "description", "details", "priceRwf": int>=1,
  "optionGroups": [ { "name": "Size", "options": [ { "id"?: "...", "label": "M", "inStock": true } ] } ],
  "images": [ { "imageId", "alt" } ], "isNew": false, "featured": false, "status": "published"|"hidden" }
```
Product slugs: generated from name, unique; `checkout` and `received` are reserved.
- `PATCH /api/admin/products/{id}/availability` `{ optionId?: "...", inStock: bool }` -> 200 (quick toggle; without `optionId` toggles all options).

### 3.7 Images (owner, support)
- `POST /api/admin/images` multipart `file` (JPEG/PNG/WebP, max 10 MB, max 6000 px) -> 201 `Image` (server re-encodes, strips location metadata, creates sizes). 400 on wrong type.
- `DELETE /api/admin/images/{id}` -> 204; 409 `conflict` if still used.

### 3.8 Portfolio (owner, support)
- `GET /api/admin/portfolio?page&pageSize` -> items `{ id, image, caption, description, categoryId, featured, sortOrder }`.
- `POST /api/admin/portfolio` (201), `PUT /api/admin/portfolio/{id}` (200), body `{ imageId, caption (<=200), description (alt text, 1-300), categoryId, featured }`.
- `PUT /api/admin/portfolio-order` `{ ids }` -> 204. (Renamed from `/portfolio/order`, same reason.)
- `DELETE /api/admin/portfolio/{id}` -> 204.
Seeding: the sample images from `business/images/shots` are imported into the portfolio by a migration/seed script, with alt text for staff to refine.

### 3.9 Calendar (owner, support)
- `GET /api/admin/calendar?from&to` -> 200
```
{ "blocks": [ { "id", "date", "startTime": null, "endTime": null, "reason", "createdByName" } ],
  "sessions": [ { "id", "reference", "date", "startTime", "endTime", "customerName", "status": "confirmed" } ],
  "pending":  [ { "id", "reference", "date", "startTime", "customerName" } ] }
```
(`startTime`/`endTime` null = whole day.)
- `POST /api/admin/calendar/blocks` `{ fromDate, toDate (default fromDate), startTime|null, endTime|null, reason (<=200)|null }` -> 201 `{ items: [blocks] }`. Warns, does not fail, if confirmed sessions exist in the range: response includes `affectedSessions: [...]`.
- `DELETE /api/admin/calendar/blocks/{id}` -> 204.

### 3.10 Enquiries (owner, support)
- `GET /api/admin/enquiries?status=new|answered&page&pageSize` -> items `{ id, name, email, phone, message, receivedAt, status, whatsappLink|null }`.
- `POST /api/admin/enquiries/{id}/answered` -> 200.

### 3.11 Settings (owner, support)
- `GET /api/admin/settings` -> same shape as `GET /api/site-info` plus ids.
- `PUT /api/admin/settings` body: `{ phone, whatsapp, email, address, social: {instagram, tiktok, facebook}, openingHours, booking: { minNoticeHours, windowDays, defaultSessionMinutes }, deliveryAreas: [ { id?, name, feeRwf: int|null, active: bool } ], portfolioCategories: [ { id?, name } ] }` -> 200. Removing a delivery area or category in use is allowed (history keeps snapshots); removing a category in use by portfolio items gives 409 `conflict`.

### 3.12 Staff accounts (owner only)
- `GET /api/admin/staff` -> `{ items: [ { id, name, email, role, active, invitationPending: bool, lastSignInAt|null } ] }` (`invitationPending` is true until the person has set a first password; the UI shows "Invitation sent").
- `POST /api/admin/staff` `{ name, email }` -> 201; role always `support`; sends invitation email. 409 `conflict` if email exists.
- `GET /api/admin/staff/{id}` -> record.
- `PATCH /api/admin/staff/{id}` `{ name?, active? }` -> 200. `active:false` ends their sessions at once. The owner account cannot be switched off.
- `POST /api/admin/staff/{id}/reset-password-link` -> 202 (emails link).
- `DELETE /api/admin/staff/{id}` -> 204. Cannot delete the owner or oneself. Past history entries keep the name.

### 3.13 Documents and agreements (owner only for write; list for owner)
- `GET /api/admin/documents` -> `{ items: [ { slug, title, currentVersion, publishedAt, reviewStatus: "draft"|"reviewed", requiresAcceptance } ] }`.
- `GET /api/admin/documents/{slug}` -> current text plus `versions: [ { version, publishedAt, publishedByName, changeNote } ]`.
- `POST /api/admin/documents/{slug}/versions` `{ body (markdown), summaryPoints: [string], changeNote }` -> 201 new version. Versions are immutable; earlier versions stay retrievable and attached to the acceptance records that reference them.
- `POST /api/admin/documents/{slug}/mark-reviewed` -> 200.
- `GET /api/admin/acceptances?subjectReference=AS-S-...` -> list of acceptance records (read-only; no update or delete endpoint exists).

### 3.14 Outbox (owner, support)
Covered via the `messages` array and `resend` endpoints above.

## 4. State machines (enforced server-side)

- Booking request: `pending -> confirmed | declined`; `confirmed -> confirmed (reschedule) | completed`. `declined` and `completed` are final. Payment recorded independently on confirmed/completed.
- Merchandise order: `pending -> approved | rejected`; `approved -> paid -> delivered`. `rejected` and `delivered` are final.
- Illegal transitions return 409 `conflict`.

## 5. Data retained for audit
Every state change appends a history entry (who, when, what). Acceptance records and history entries are append-only. Idempotency records and rate-limit counters expire after 7 days / 1 hour respectively.

## 6. Page serving (HTML files, SEO tags in the first response)

The backend serves the HTML files built by the frontend (the "web root") and is the only server. Frontend and backend meet only here and in sections 1-5.

**Route table** (owned by the backend; the frontend must create exactly these files in the web root):

| URL pattern | File | Kind | Backend rewrites |
|---|---|---|---|
| `/` | `site/index.html` | dynamic head | SEO region (title, description, canonical, OG, JSON-LD ProfessionalService from settings) |
| `/services` | `site/services.html` | static | nothing |
| `/services/{slug}` | `site/service.html` | dynamic head + data | SEO region (Service+Offer, BreadcrumbList) and initial-data script; real 404 status and `site/404.html` if unpublished or unknown |
| `/portfolio` | `site/portfolio.html` | static | nothing |
| `/shop` | `site/shop.html` | static | nothing |
| `/shop/{slug}` | `site/product.html` | dynamic head + data | SEO region (Product+Offer, BreadcrumbList) and initial-data script; 404 as above |
| `/shop/checkout`, `/shop/received/{reference}` | `site/checkout.html`, `site/order-received.html` | static, noindex | nothing. Literal paths win over `/shop/{slug}`; the slugs `checkout` and `received` are reserved |
| `/cart/sessions`, `/cart/merchandise`, `/book`, `/book/received/{reference}` | `site/cart-sessions.html`, `site/cart-merchandise.html`, `site/book.html`, `site/book-received.html` | static, noindex | nothing |
| `/about` | `site/about.html` | static | nothing |
| `/contact` | `site/contact.html` | dynamic head | SEO region (JSON-LD ProfessionalService from settings) |
| `/legal/terms-of-sale`, `/legal/refund-policy`, `/legal/privacy`, `/legal/order-terms` | `site/legal.html` (one template; the JS reads the slug from the path) | dynamic head | SEO region: title and description per document (design-spec Part 6, P15-P18) |
| any unknown path | `site/404.html` served with HTTP 404 | static, noindex | nothing |
| `/admin` and every `/admin/**` page path in design-spec Part 2 (including `/admin/help/{topic}`) | `admin/index.html` (single admin shell; the JS routes inside it) | static, noindex | nothing |
| `/assets/**`, `/css/**`, `/js/**`, `/fonts/**` | same-named folders in the web root | static files | nothing |

Document slug to URL mapping: `terms-of-sale` -> `/legal/terms-of-sale`, `refund-policy` -> `/legal/refund-policy`, `privacy` -> `/legal/privacy`, `order-terms` -> `/legal/order-terms`. The two agreements (`session-booking-agreement`, `merchandise-order-terms`) have no page of their own; they are shown inside P8 and P11 and via `GET /api/documents/{slug}`.

**SEO region markers.** Every public indexable HTML file contains, inside `<head>`, one region
```
<!--@seo:begin-->
 ...complete static fallback: title, meta description, canonical, og:*, twitter, JSON-LD...
<!--@seo:end-->
```
The frontend writes a complete, valid fallback inside the region on every page (so each page is correct when opened without the backend). For pages marked "dynamic head" the backend replaces everything between the markers with tags generated from live data and the SEO table in design-spec Part 6; for all other pages it serves the file unchanged. The `og:image` is the item's main image or the first featured portfolio image. The `<h1>`, body and all other parts of the page are never touched.

**Initial data.** For `/services/{slug}` and `/shop/{slug}` the backend also replaces a single `<!--@data-->` comment in `<body>` with `<script type="application/json" id="initial-data">{...}</script>`, containing exactly the JSON that `GET /api/services/{slug}` / `GET /api/products/{slug}` would return (script-safe encoding: `<` as `<`). The frontend renders from it when present and falls back to the API call when it is not (for example when the file is opened directly).

**Headers.** Dynamic-head pages: `Cache-Control: public, max-age=60, stale-while-revalidate=300`, `ETag`. Other HTML: `Cache-Control: public, max-age=300`. Files under `/css`, `/js`, `/fonts`, `/assets`: `max-age=31536000, immutable`, so the frontend must fingerprint file names (for example `site.3f9a1c.css`) at build time. Noindex pages and `/admin/**`: `X-Robots-Tag: noindex` (the frontend also adds `<meta name="robots" content="noindex">`); `/admin/**` is also `Cache-Control: no-store`. The backend sets a `Content-Security-Policy` on all HTML (`default-src 'self'`; images and fonts self only; no third-party origins), so the frontend must use no inline scripts, no inline event handlers and no inline styles. JSON-LD and the initial-data block are non-executed data blocks and are allowed.
