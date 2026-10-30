# Design Spec – Aura Shots

Schema.org type for the business: **ProfessionalService** (appointment-only service business with address, opening hours and sameAs links). Packages use `Service`/`Offer`; merchandise uses `Product`/`Offer`. Currency `RWF`.

Voice and visuals follow brand-brief.md: Noir `#0A0A0A` background, Pearl `#F6F1EF` text, Aura Pink `#E0457B` accents (Noir text on pink buttons), Cormorant Garamond headings, Inter body, British English, prices like "50,000 RwF", dates day-month-year (14-11-2026 or "14 November 2026"), no newsletter wording, tagline "Not just photos…it's an aura". Photography dominates. WCAG 2.1 AA throughout.

Placeholders shown on the site until filled: `<TBD>` (TikTok, Facebook, delivery fees); owner email shown as `OWNER_EMAIL`.

Conventions
- Public IDs P1-P18, admin IDs A1-A21.
- "Cart" for sessions = **Session basket**; for merchandise = **Merchandise bag**. They are separate, never combined.
- Responsive: mobile first, 1 column under 640px, 2 to 3 at tablet, up to 4 on desktop; max content width 1200px.

---

## Part 1 – Public pages

### P1 Home `/`
SEO title: "Aura Shots | Editorial Photography Sessions in Kigali". Search intent: "photographer Kigali", "photoshoot Kigali".

1. **Hero** – Purpose: set the mood, push to booking. Content: full-bleed portfolio image, logo, tagline, buttons "Book a session" (pink, to P2) and "See the portfolio" (outline, to P4). Layout: image fills viewport height, dark gradient bottom, text bottom-left.
2. **Signature line** – Purpose: positioning. Content: two or three sentences on editorial, cinematic, colour-led portraiture for locals, professionals, visitors. Layout: centred narrow column, generous space.
3. **Featured services** – Purpose: show packages fast. Content: 3 cards from the catalogue (dynamic, flagged "featured" by staff) with image, name, short description, "from" price, "View details". Link "All services". Layout: 3-column card row, stacked on mobile.
4. **Portfolio strip** – Purpose: proof of style. Content: 6 to 8 featured portfolio images (dynamic), link "View the portfolio". Layout: masonry strip, horizontal scroll on mobile.
5. **How it works** – Purpose: reassure tourists and professionals. Content: four steps – Choose a package, Pick your preferred date and time, We confirm with you by email or WhatsApp, Pay by cash or mobile money after confirmation. Plus note: appointment only, no walk-ins; no-refund policy stated politely with link to P16. Layout: four numbered columns.
6. **Merchandise teaser** – Purpose: cross-sell. Content: 3 featured items (dynamic) and link to P5. Layout: row of product cards.
7. **Visit and contact** – Purpose: practical facts. Content: address (Kanombe, Kigali, Rwanda), opening hours, WhatsApp, Instagram, link to contact page. Layout: two columns, text and a simple map link (outbound link only, no embedded third-party map to keep the page light and private).

### P2 Services `/services`
SEO title: "Photography Packages and Prices in Kigali | Aura Shots". Intent: "photography packages Kigali price", "couple photoshoot Kigali".

1. **Page header** – title, one-line intro, note "All prices in RwF".
2. **Package grid** – Purpose: browse. Content: one card per published service: image, name, short description, price, key facts line (edited pictures, delivery time), "View details", "Add to basket". Layout: 2 to 3 column grid. Dynamic: service list.
3. **What every session includes / policy note** – short reassurance block with no-refund note and link to P16.
4. **Empty state** – if none published, show message and contact link.

### P3 Service detail `/services/{slug}`
Title: "{Service name} – Photoshoot in Kigali | Aura Shots". Intent: "{service name} photoshoot Kigali".

1. **Gallery and summary** – left: image gallery (main image plus thumbnails, keyboard accessible); right: name, price in RwF, short description, session length if set, button "Add to basket" (turns into "In your basket" after adding), secondary "Ask a question" (to P14). Stacked on mobile with gallery first.
2. **Description** – detailed description.
3. **What's included** – structured list: number of edited pictures, number of videos, outfits, delivery time, locations, related merchandise (links to P6 items).
4. **Conditions** – conditions text, no-refund notice, link to booking agreement preview (opens P17 text).
5. **Related packages** – 2 to 3 cards.
Not orderable (button hidden, message "Currently unavailable") if unpublished or no price; unpublished pages return 404.

### P4 Portfolio `/portfolio`
Title: "Portfolio – Editorial Portraits and Lifestyle Shoots | Aura Shots". Intent: "photoshoot portfolio Kigali", "editorial portrait photographer Rwanda".

1. **Header and category filter** – chips: All, Portraits, Couples and Friends, Birthdays, Lifestyle (categories editable list in A13, seeded with these).
2. **Image grid** – masonry grid, each image with caption (description) and alt text. Click opens **lightbox** (keyboard focus trap, Esc to close, previous/next). Dynamic with "Load more" paging.
3. **Call to action** – "Create your own" with button to P2.

### P5 Merchandise `/shop`
Title: "Merchandise – Clothing, Prints and Framed Art | Aura Shots". Intent: "Aura Shots merchandise", "framed photo prints Kigali".

1. **Header** – title, intro, note "Ordered separately from sessions. Delivery within Rwanda `<TBD>`".
2. **Filter bar** – category chips (Clothing, Prints, Framed art), sort (Newest, Price low-high, Price high-low).
3. **Product grid** – cards with image, name, price, badge (In stock, Sold out, New), "View". Dynamic.
4. **Delivery and payment note** – approval first, then cash or mobile money, delivery areas and fees (from settings, `<TBD>` if empty).

### P6 Product detail `/shop/{slug}`
Title: "{Product name} | Aura Shots Merchandise". Intent: "{product name} Kigali".

1. **Gallery and buy box** – images; name, price; variant pickers (size, colour, frame option, depending on item – radio buttons shown as chips; sold-out variants disabled and labelled); quantity (1-10); stock label In stock / Sold out; button "Add to merchandise bag" (disabled with "Sold out" label when unavailable).
2. **Description and details** – material, dimensions, care.
3. **Delivery and payment** – short text, order is a request until approved.
4. **Related** – products or the related session package.

### P7 Session basket `/cart/sessions`
Noindex. Purpose: review selection before choosing a date.
1. **Basket list** – each service: image, name, price, remove button. Quantity fixed at 1. Price re-checked live; items that became unavailable are flagged and must be removed.
2. **Summary** – total in RwF with note "Payment after confirmation, by cash or mobile money".
3. **Actions** – "Continue browsing", "Choose date and time" (to P8). Empty state: friendly message and link to P2.
Basket is stored in the browser (localStorage); the server revalidates on submit.

### P8 Request a session `/book`
Noindex. A single page, three steps with a progress indicator, each step a heading section. Back and forward preserve entries.

1. **Step 1 – Date and time** – Purpose: pick a slot. Content: calendar widget (month view, previous/next month, today highlighted, days from tomorrow or per minimum notice up to the booking window). Blocked, fully booked or past days are greyed, `aria-disabled`, with a legend. Choosing a day loads start-time buttons on a 30-minute grid; blocked or taken times are disabled with text "Unavailable". Selected summary: "Saturday, 14 November 2026 at 10:30". Note: "This is your preferred time. We will confirm it or suggest another." Layout: calendar left, times right on desktop; stacked on mobile. Fully keyboard operable (arrow keys on the grid).
2. **Step 2 – Your details** – Form: full name (required), phone with country code (required, WhatsApp-capable), email (required), address or area for the shoot (optional), preferred contact method (Email or WhatsApp), notes and wishes (optional, 1000 characters). Inline validation, errors in text with `aria-describedby`. Hidden honeypot field.
3. **Step 3 – Review and accept the agreement** – Purpose: binding step. Shows: services list and total, chosen date and time, contact details with "Edit" links, then the **Session Booking Agreement** (scrollable full text, version number and date shown, key terms summarised in 3 bullets: bookings are commitments, no refunds, request is not confirmed until we confirm). Required unticked checkbox "I have read and accept the Session Booking Agreement (version X)". Submit button "Send booking request" is disabled until accepted; on submit sends the accepted version to the server, which records the acceptance. Below the button: "No payment now."
4. **States** – submitting (button busy, no double submit; retries reuse the same idempotency key), slot became unavailable (return to step 1 with a message, details kept), agreement version changed (page reloads the new text and asks for acceptance again), general error with retry.

### P9 Request received `/book/received/{reference}`
Noindex. Content: heading "Request received", reference code, summary of services, requested date and time, status "Pending – we will confirm shortly", what happens next (we reply by your chosen contact method; payment after confirmation), the agreement version accepted, WhatsApp and email contacts, "Back to home". Reachable with the access token from the submit response (kept in page state and in the URL query so a refresh works).

### P10 Merchandise bag `/cart/merchandise`
Noindex. Line items with image, name, chosen variant, price, quantity stepper, remove; subtotal; note "Delivery fee confirmed on approval" (or the fee when a delivery area is picked in checkout); buttons "Continue browsing", "Checkout". Sold-out items flagged and block checkout.

### P11 Merchandise checkout `/shop/checkout`
Noindex. Sections:
1. **Your details** – name, phone, email (required).
2. **Delivery** – delivery area (select from configured areas; if none configured: free-text district), street address or description, landmark (optional), delivery notes. Fee line (from the area, or "to be confirmed").
3. **Order summary** – items, subtotal, delivery fee, total (fee may read "To be confirmed").
4. **Payment note** – "Pay by cash or mobile money after we approve your order."
5. **Review and accept the Merchandise Order Terms** – full text, version shown, required checkbox, button "Place order request". Same recording behaviour and states as P8, with an out-of-stock state.

### P12 Order received `/shop/received/{reference}`
Noindex. Reference, items, address, status "Awaiting approval", next steps, contacts.

### P13 About and how it works `/about`
Title: "About Aura Shots | Private Photography in Kigali". Intent: "who is Aura Shots photographer Kigali".
1. **Story and style** – the editorial, colour-theory, dramatic lighting, "main character energy" approach. Photo-led.
2. **How booking works** – the four steps in detail and what to expect on the day.
3. **FAQ** – accordions: no walk-ins, no refunds, payment methods, how long until delivery, tourists, rescheduling, merchandise delivery.
4. **CTA** – to P2.

### P14 Contact `/contact`
Title: "Contact Aura Shots | Kigali Photographer". Intent: "contact Aura Shots", "Aura Shots phone number".
1. **Contact details** – address, opening hours table (Mon-Sat 8:00 AM-10:00 PM, Sun 10:00 AM-9:30 PM), phone and WhatsApp +250 790 114 749 (button "Message on WhatsApp"), email OWNER_EMAIL, social links (Instagram; TikTok `<TBD>`; Facebook `<TBD>`).
2. **Enquiry form** – name, email, phone (optional), message, honeypot. Success message; stored and emailed to the owner. Note: "We reply by email or WhatsApp".
3. **Appointment note** – "By appointment only. No walk-ins."

### P15 Terms of sale `/legal/terms-of-sale`
### P16 Refund policy `/legal/refund-policy` (strict no-refund, polite wording)
### P17 Privacy policy `/legal/privacy`
### P18 Order terms `/legal/order-terms` (merchandise order terms and session booking terms overview)
All use the **Legal document** template: title, version, "Last updated" date, rendered text from the backend. Titles "Terms of Sale | Aura Shots" and similar; indexable; intent e.g. "Aura Shots refund policy". Content is placeholder text reviewed by a human before launch (banner not shown to the public; flagged in admin A20 as "Draft – needs review" until the owner marks it reviewed).

### P19 Not found / error `/404`, error boundary
Friendly message in brand voice, links to Home, Services, Contact. Noindex.

---

## Part 2 – Admin pages (`/admin`)

Designed for non-technical staff: plain labelled forms rather than dense tables, one task per screen, big buttons, plain-language status words, a "How do I…?" link on every page (opens the matching help topic, see admin-help.md), and a confirmation dialog ("Are you sure? This cannot be undone") before anything is removed. Lists show cards or simple rows with the three facts staff need (who, what, when), not wide tables. Sidebar becomes a top menu on small screens. Roles: Owner sees everything; Support sees everything except A17-A20 (staff and policy pages). Admin pages are noindex and require sign-in.

Status words (plain): Session request – Waiting for you, Confirmed, Declined, Completed. Order – Waiting for you, Approved, Rejected, Paid, Delivered.

### A1 Sign in `/admin/sign-in`
Email, password, "Sign in", "Forgotten your password?". Error: "That email or password is not right." After 5 failed attempts: wait message. Redirects to A3.

### A2 Forgotten and reset password `/admin/forgot-password`, `/admin/reset-password?token=`
Two screens. Forgot: email field, "Send me a reset link" (always shows the same confirmation). Reset: new password and repeat, rules shown (at least 12 characters), "Save password" then A1.

### A3 Dashboard `/admin`
Purpose: what needs doing today. Content: big cards "Session requests waiting for you (n)", "Merchandise orders waiting for you (n)", "Messages to answer (n)", "Coming up: confirmed sessions this week" (list of the next 5 with date, time, name). Shortcuts: Add a service, Add a photo, Block a date. Each card links to its list.

### A4 Session requests `/admin/session-requests`
Filter chips: Waiting for you (default), Confirmed, Declined, Completed, All. Each row/card: reference, customer name, services, requested date and time, received date, status chip. Click opens A5. Paging.

### A5 Session request `/admin/session-requests/{id}`
One task per screen. Sections top to bottom: **Summary** (reference, status, received); **Customer** (name, phone with "Message on WhatsApp" link, email, address or area, contact preference); **What they asked for** (services, total, preferred date and time, notes); **Agreement accepted** (read-only: name, date and time, agreement version, link to view that exact text); **Decision** – buttons that depend on status: when Waiting – "Confirm this date and time" (opens a small form with date and time pre-filled from the request that the staff can change, a note to the customer, and shows a warning if the slot clashes with a block or a confirmed session; confirming blocks the slot), "Decline request" (reason field, confirmation); when Confirmed – "Change date or time" and "Mark as paid" (method cash/mobile money, amount) and "Mark as completed"; **Messages sent** (list of emails sent and failure marker with "Send again"); **Internal notes** (staff-only text, add note). After confirm or change: "The customer will be emailed. You can also message them on WhatsApp" with the prefilled link.

### A6 Merchandise orders `/admin/orders`
Same pattern as A4 with chips Waiting for you, Approved, Paid, Delivered, Rejected, All.

### A7 Merchandise order `/admin/orders/{id}`
Summary; Customer (WhatsApp link); Delivery address and area; Items with variants and prices; Totals with delivery fee (editable field "Delivery fee (RwF)" when the area fee is `<TBD>`); Agreement accepted (read-only); Decision – Waiting: "Approve order", "Reject order" (reason, confirmation); Approved: "Mark as paid" (cash or mobile money), then Paid: "Mark as delivered"; messages sent; internal notes.

### A8 Services `/admin/services`
Cards: image, name, price, status chip (Published / Hidden / Needs a price), "Edit". Button "Add a service". Hide-from-shop toggle with explanation. "Remove" per service lives in A9.

### A9 Add or edit a service `/admin/services/new`, `/admin/services/{id}`
Plain form in labelled groups: Name; Short description (for cards); Detailed description; **Price (RwF)** (required to publish; helper "Customers cannot order a service without a price"); Session length (minutes, optional); **What's included** – Number of edited pictures, Number of videos, Outfits, Delivery time, Locations; Conditions; Related merchandise (pick from list); Pictures (upload, drag to reorder, first is main, alt text field for each); Show on home page (Yes/No); Status: Published / Hidden. Buttons "Save", "Cancel". "Remove this service" at the bottom with confirmation; removal hides it for ever from the shop but keeps old requests readable. Saving shows a green "Saved" message with link "View on the website".

### A10 Merchandise `/admin/merchandise`
Cards with image, name, price, availability chip (In stock / Sold out / some sizes sold out). "Add an item". Quick "Mark as sold out" / "Mark as in stock" button on each card (one tap, no confirm needed for this reversible action).

### A11 Add or edit merchandise item `/admin/merchandise/new`, `/admin/merchandise/{id}`
Name, Category (Clothing, Prints, Framed art), Description, Details, Price (RwF), **Options** (a simple list: add rows such as "Size: M", "Colour: Black" – each option row has an "In stock" tickbox; no quantities counted), Pictures, Show on home page, Status Published / Hidden, "New" badge tickbox. "Remove this item" with confirmation.

### A12 Portfolio `/admin/portfolio`
Photo grid with caption, category, order; "Add a photo". Drag handle or Up/Down buttons to reorder. Tick "Show on home page".

### A13 Add or edit a photo `/admin/portfolio/new`, `/admin/portfolio/{id}`
Upload (JPG/PNG/WebP, size limit shown), preview, Caption, Description for screen readers (alt text, required), Category, Show on home page, "Save", "Remove this photo" (confirm).

### A14 Calendar `/admin/calendar`
Month view showing: blocked days (red), partly blocked (striped), confirmed sessions (pink dots with time and name), and waiting requests (outline). Click a day opens a side panel: list for that day plus "Block this whole day" and "Block certain hours" (from time, to time, reason) buttons; each existing block has "Unblock" (confirmation). "Block several days" form (from date, to date, reason). Message: "Customers cannot pick blocked dates or times".

### A15 Messages `/admin/messages`
Enquiries from the contact form: cards with name, date, snippet, "Waiting / Answered". Detail inline: full message, contact details with "Reply by email" and "Message on WhatsApp" links, "Mark as answered".

### A16 Settings `/admin/settings`
Grouped forms with separate Save buttons: **Contact and social** (phone, WhatsApp, email, address, Instagram, TikTok, Facebook – `<TBD>` shown while empty); **Opening hours**; **Booking rules** (default session length, minimum notice hours, how far ahead customers can book); **Delivery areas and fees** (add row: area name, fee RwF, tick "Delivers here"; remove row with confirmation); **Category lists** (portfolio categories).

### A17 Staff accounts `/admin/staff` (Owner only)
Simple list of cards: name, email, role, Active / Switched off. Button "Add a staff member".

### A18 Add a staff member `/admin/staff/new` (Owner only)
Name, email; role fixed to Support staff. "Send invitation" emails a link to choose a password. Confirmation message.

### A19 Staff member `/admin/staff/{id}` (Owner only)
Details; buttons: "Send a password reset link", "Switch off access" (reversible, immediate sign-out), "Remove this staff member" (confirmation; past actions remain in records).

### A20 Policies and agreements `/admin/policies` (Owner only)
List of the six documents (Terms of sale, Refund policy, Privacy policy, Order terms, Session Booking Agreement, Merchandise Order Terms) with version, last changed, "Draft – needs review / Reviewed" status. Edit screen: large text box, "What changed" note, "Publish as new version" (warns: customers will have to accept the new version; earlier acceptances stay on record with the version they accepted). Past versions listed read-only.

### A21 My account `/admin/account`
Shows name, email, role. Change password (current, new, repeat). Sign out button.

---

## Part 3 – Component inventory

- **Site header** – logo (SVG), primary nav (Home, Services, Portfolio, Merchandise, About, Contact), basket icons with count badges (Session basket, Merchandise bag; clicking opens a small panel listing both with separate buttons), skip-to-content link, mobile menu button.
- **Site footer** – logo, tagline, address, hours, WhatsApp, email, social icons (Instagram, TikTok, Facebook with `<TBD>` links hidden until filled), legal links (P15-P18), "Appointment only" note, "Prices in RwF".
- **Button** – primary (pink fill, Noir text), secondary (outline), text link, disabled and busy states.
- **Service card**, **Product card**, **Portfolio tile**, **Package facts list** (included items), **Badge**, **Price tag** ("50,000 RwF"), **Status chip** (admin).
- **Image gallery** with thumbnails, **Lightbox**.
- **Filter chips** and **Sort select**.
- **Calendar picker** and **Time-slot grid** (public and admin variants).
- **Form field set** (label, hint, error, required marker), **Phone field**, **Textarea with counter**, **Select**, **Checkbox**, **Radio chips** (variants).
- **Agreement panel** – scrollable text, version label, required acceptance checkbox, summary bullets.
- **Stepper** (progress), **Order summary**, **Quantity stepper**.
- **Accordion** (FAQ), **Legal document template**.
- **Toast and inline alert**, **Confirm dialog** (admin removals), **Empty state**, **Loading skeleton**, **Pagination / Load more**.
- **Admin shell** – sidebar/top menu, page header with "How do I…?" link, role-aware menu.
- **Help drawer** – renders admin-help.md topic for the current page.
- **Image uploader** (preview, reorder, alt text), **Reorder list**.
- **JSON-LD block** – per-page structured data.
Each component needs a Portfolio scene per state (ES).

## Part 4 – Dynamic content

| Content | Where | Source endpoint |
|---|---|---|
| Services list and detail | P1-P3, P7 | GET /api/services, /api/services/{slug} |
| Portfolio images | P1, P4 | GET /api/portfolio |
| Products | P1, P5, P6 | GET /api/products |
| Site info, hours, delivery areas | footer, P11, P14 | GET /api/site-info |
| Calendar availability | P8 | GET /api/availability |
| Basket price/availability check | P7, P10 | POST /api/quotes/sessions, /api/quotes/merchandise |
| Agreement text | P8, P11, legal pages | GET /api/documents/{slug} |
| Booking request submit (with acceptance) | P8 | POST /api/booking-requests |
| Merchandise order submit (with acceptance) | P11 | POST /api/merchandise-orders |
| Received pages | P9, P12 | GET /api/booking-requests/{reference}, /api/merchandise-orders/{reference} |
| Contact form | P14 | POST /api/enquiries |
| All admin screens | A1-A21 | /api/admin/* (see api-contract.md) |

## Part 5 – Section for every "applies" category

- Catalog / inventory: P2, P3, P5, P6, P4; admin A8-A13.
- Payments / checkout: P7-P12; admin payment recording in A5 and A7 ("Mark as paid").
- Accounts / auth (admin only): A1, A2, A21, A17-A19.
- Admin / back-office: Part 2.
- Search / discovery: filter chips, sort, SEO elements (Part 6).
- Notifications: email templates (Part 7), WhatsApp links in A5, A7, A15, "Messages sent" panels.
- Legal: P15-P18, A20.
- Customer agreements: P8 step 3, P11 section 5, P9/P12 confirmation of accepted version, A5/A7 "Agreement accepted" panel, A20 versions.

## Part 6 – SEO table (public pages)

| Page | Title | Target search intent |
|---|---|---|
| P1 | Aura Shots \| Editorial Photography Sessions in Kigali | photographer Kigali |
| P2 | Photography Packages and Prices in Kigali \| Aura Shots | photography packages Kigali price |
| P3 | {Service} – Photoshoot in Kigali \| Aura Shots | {couple / single / birthday} photoshoot Kigali |
| P4 | Portfolio – Editorial Portraits and Lifestyle Shoots \| Aura Shots | portrait photographer portfolio Kigali |
| P5 | Merchandise – Clothing, Prints and Framed Art \| Aura Shots | Aura Shots merchandise |
| P6 | {Product} \| Aura Shots Merchandise | {product} Kigali |
| P13 | About Aura Shots \| Private Photography in Kigali | Aura Shots photographer Kigali |
| P14 | Contact Aura Shots \| Kigali Photographer | Aura Shots contact |
| P15 | Terms of Sale \| Aura Shots | Aura Shots terms |
| P16 | Refund Policy \| Aura Shots | Aura Shots refund policy |
| P17 | Privacy Policy \| Aura Shots | Aura Shots privacy |
| P18 | Order Terms \| Aura Shots | Aura Shots order terms |
Noindex: P7-P12, P19, all admin. Add canonical URLs, Open Graph images (portfolio photo), `sitemap.xml` generated from published services and products, JSON-LD: ProfessionalService on P1/P14, Service+Offer on P3, Product+Offer on P6, BreadcrumbList on detail pages. Image alt text is mandatory in admin.

## Part 7 – Email templates (backend sends)
1. Booking request received (to customer, includes reference, services, requested slot, "not confirmed yet", agreement version).
2. New booking request (to OWNER_EMAIL).
3. Booking confirmed (final date and time, note) / time changed / declined (reason).
4. Merchandise order received (customer) and new order alert (owner).
5. Order approved (payment instructions, delivery), rejected (reason), delivered.
6. Enquiry alert (owner) and acknowledgement (customer).
7. Staff invitation and password reset.
British English, warm tone, no marketing content or unsubscribe language.

## Part 8 – Icons and badges

Icons (one consistent line-style set, matching the white line-art logo): menu, close, cart/basket (session basket), shopping bag (merchandise bag), calendar, clock, location pin, phone, WhatsApp, email, Instagram, TikTok, Facebook, camera, outfit/hanger, video, picture/image, delivery truck, no-refund/policy, secure/shield (privacy), mobile money, cash, check/success, warning, error, info, chevron left/right/down/up, arrow right, plus, minus, trash/remove, edit/pencil, eye (show), eye-off (hide), drag handle, upload, download, external link, help (question mark), user (admin account), sign out, settings/gear, dashboard, list, bell (waiting), lock, star (featured), filter, sort, expand/collapse, lightbox next/previous. (No search or wishlist icons: neither feature exists.)
Not used: wishlist, account/sign-in for customers, newsletter, search bar.

Badges: New, In stock, Sold out, Featured, Popular (optional, staff-set), From (price prefix), By appointment only, No refunds (informational), Status chips for admin – Waiting for you, Confirmed, Declined, Completed, Approved, Rejected, Paid, Delivered, Published, Hidden, Needs a price, Draft – needs review, Reviewed, Switched off, Answered.
