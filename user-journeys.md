# User Journeys – Aura Shots

Page IDs refer to design-spec.md. Reference patterns: Etsy (clear cards, price, add-to-basket, guest flow), Flytographer and Luxury Editorials (what is included, price, how booking works up front). Unlike a marketplace, the final step here is a request, not a payment, and the page copy says so at every stage.

## Journey 1 – A visitor books a photography session (guest)

Who: a tourist or urban professional who found the site through Google.

1. They land on **P1 Home** from a search for "photographer Kigali". They see the hero, tagline and two buttons. They click "Book a session".
2. **P2 Services** shows the package cards with prices in RwF. They compare Single Session and Couple Session and click "View details" on one.
3. **P3 Service detail** shows the gallery, price, what's included, conditions and the no-refund notice. They click "Add to session basket". The button changes to "In your basket – View basket" and the header basket icon shows 1. (Optional: they go back and add a second package.)
4. They click the basket icon, or "View basket". **P7 Session basket** lists their choice and total. A line reads "Payment after confirmation, by cash or mobile money". They click "Choose date and time".
5. **P8 step 1**: a calendar opens. Blocked and past days are greyed and cannot be selected. They pick Saturday 14 November 2026, and start-time buttons appear; unavailable times are disabled. They pick 10:30 and see "Saturday, 14 November 2026 at 10:30. This is your preferred time. We will confirm it or suggest another." They click "Next".
6. **P8 step 2**: they enter name, phone with country code, email, optional address or area, contact preference (WhatsApp), and notes. Errors appear next to the field in plain words. They click "Next".
7. **P8 step 3 (review and accept)**: they see their services, total, date and time, details, and the Session Booking Agreement with its three key points (a request is not confirmed until we confirm; bookings are commitments; no refunds). The button "Send booking request" is disabled. They read, tick "I have read and accept the Session Booking Agreement (version 3)", and the button enables. They click it.
8. The server checks the slot, the prices and the agreement version, saves the request as pending, and records the acceptance (name, email, time, version). If the slot has just been taken, they return to step 1 with a message and keep their details. If the agreement changed, the new text appears and they must accept again.
9. They land on **P9 Request received** with reference AS-S-XXXXXXXX, status "Pending", what happens next, and the same details by email within minutes. The session basket is now empty.
10. Later, staff confirm (or change the time) and the customer receives an email or WhatsApp message with the final date and time. They pay cash or mobile money after confirmation (offline).

Where it can go wrong, and the intended recovery: empty basket on P8 redirects to P2; a service removed since adding is flagged in P7 and must be removed; double-clicking submit or a network retry reuses the idempotency key so only one request exists.

## Journey 2 – A customer orders merchandise (guest)

1. From the header or the P1 teaser, the customer opens **P5 Merchandise**, filters to "Framed art" and opens a product (**P6**).
2. They choose the size option; sold-out options are disabled and labelled "Sold out". They click "Add to merchandise bag". The header shows a bag count and a mini-panel confirming, with "View bag" and "Keep browsing".
3. **P10 Merchandise bag**: they review lines, change quantity, see "Delivery fee confirmed on approval". They click "Checkout". (If they also have a session in the session basket, it stays separate; the bag page says "Your session request is separate.")
4. **P11 Merchandise checkout**: they enter name, phone, email, choose a delivery area (or enter their district if none are configured), the address and notes. The summary shows the subtotal and either the fee or "To be confirmed".
5. They read the Merchandise Order Terms, tick the acceptance box with version number, and click "Place order request". The server validates stock flags, creates the pending order, and records the acceptance.
6. **P12 Order received** shows the reference AS-M-XXXXXXXX, "Awaiting approval". An email follows.
7. Staff approve (setting the delivery fee if needed) or reject with a reason. The customer is notified, pays cash or mobile money, and the order is delivered; staff mark paid and delivered.

## Journey 3 – Staff process a session request (admin)

Who: owner or support staff member.

1. They open `/admin` and see **A1 Sign in**. They enter email and password and click "Sign in". A wrong password gives a plain message; five failures cause a short wait.
2. **A3 Dashboard** shows "Session requests waiting for you (2)". They click the card.
3. **A4 Session requests** lists waiting requests with name, services, requested date and time. They click the newest.
4. **A5 Session request** shows the customer, what they asked for, the Agreement accepted panel (name, date, version), and the Decision buttons. A banner shows whether the slot clashes with another confirmed session or block.
5. They message the customer on WhatsApp via the prefilled link to discuss wishes, location and price (as the business does today). Optionally they add an internal note.
6. They click "Confirm this date and time". A small form shows the requested date and time; the staff member changes the time to 11:00 and writes a note. They click "Confirm". If it clashes, a warning explains and they must choose another time.
7. The screen shows status "Confirmed" and the text "The customer will be emailed." A "Messages sent" row shows the email as sent (or failed with "Send again").
8. The calendar (**A14**) now shows the session at 11:00 and customers can no longer pick that time on P8.
9. After the shoot and payment, they open the request again, click "Mark as paid" (cash or mobile money, amount), then "Mark as completed". To refuse a request instead, they click "Decline request", write a reason, and confirm in the dialog.

Staff cannot create a booking for a customer; if a customer messages on Instagram, staff point them to the website.

## Journey 4 – Staff update the catalogue and the storefront reflects it (admin)

1. Signed in, they open **A8 Services**. They click "Add a service".
2. **A9**: they fill in the name, short and detailed descriptions, upload pictures, and enter the contents (edited pictures, videos, outfits, delivery time, locations) and conditions. They set Status "Published" without a price; Save shows "Please enter a price before publishing". They enter 50,000 and Save. A green "Saved" message offers "View on the website".
3. They click "View on the website" and see the card on **P2** and the page **P3** with the new price and contents (cached for at most a minute).
4. To change a price (for example a seed price), they open the service in A9, change **Price (RwF)**, Save. The storefront shows the new price; existing requests keep the price they were made at.
5. To mark a hoodie as sold out, they open **A10 Merchandise** and tap "Mark as sold out" on its card. On **P6** the button reads "Sold out" and the card shows the badge.
6. To remove a service, they open it in A9, click "Remove this service" at the bottom, and confirm "Are you sure? This cannot be undone". It disappears from P2/P3, and old requests still display.
7. To add a photo to the portfolio, they use **A12 / A13** (upload, caption, alt text, category) and see it in the **P4** grid.
8. To block a date (for example a public holiday), they use **A14**, click the day, "Block this whole day", add a reason, and the day is greyed out on **P8**.

## Journey 5 – The owner adds a staff member (admin, owner only)

1. Owner opens **A17 Staff accounts** and clicks "Add a staff member" (**A18**).
2. They enter name and email and click "Send invitation". The list shows the person as "Invitation sent".
3. The new staff member opens the emailed link, sets a password on the reset screen (**A2**), and signs in. Their menu lacks Staff accounts and Policies.
4. If the person leaves, the owner opens **A19**, clicks "Switch off access" (immediate sign-out) or "Remove this staff member" and confirms.

## Gaps these journeys expose and how the spec closes them

- Cart-to-checkout hand-off: baskets are client-side; server re-quotes (api 2.8, 2.9) and on submit.
- Slot contention: pending requests do not block; staff confirmation does; confirm warns about clashes.
- Customers get no tracking page: mitigated by the reference code, emails and the P9/P12 pages reachable via token.
- Two separate carts: header panel lists both with separate buttons so the customer never wonders where an item went.
