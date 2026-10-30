# QA report 3 - Aura Shots (final targeted pass, live backend, 2026-10-29)

Method: `bb build-web`, then the real backend (APP_ENV=dev, `:response-check :log`, throwaway DATA_DIR scratchpad qa-data3, TRUSTED_PROXY_HOPS=1) serving the built dist/web; Playwright (scratchpad) for all browser steps; uberjar run separately. Made-up data only. No code edited; both servers stopped. target/ already existed (not created by this run), so it was left in place.

## 1. Block-dates warning - PASS
Confirmed a session (18 Nov 2026 10:00, "Block Warn Person") as owner, then in the browser Calendar -> click day -> "Block this whole day" -> Block, and again "Block certain hours" 09:30-11:30. Both times the "Blocked" toast appeared and the warning "These confirmed sessions are in the blocked time. They are not cancelled, so please contact the customers:" appeared beside it listing "Block Warn Person - 18 November 2026 10:00" as a link to the session request (link opens it). It stays visible (still there after 2.5 s). Screenshot: qa-screenshots/help/block-dates-warning.png.
Cosmetic: help says "a yellow message"; the box is a pale panel with an amber left bar and warning icon, not literally yellow (help/ux-designer, optional).

## 2. Response schemas in dev (:log) - PASS, zero errors
Drove journeys 1-5 and every admin screen from the real frontend (all 24 admin URLs incl. detail screens, order approve/mark paid/mark delivered, notes, resend, messages answered, 5 settings saves, policy publish via the dialog, service/product/portfolio saves, reorder, staff invitation/switch off/remove, change password, help), plus an API sweep of all remaining routes (public GETs, quotes, reject, reschedule, resend, mark-reviewed, forgot-password, logout). `grep -c "Response does not match its schema"` on the server log = 0; `"level":"error"` lines = 0. `bb test`: 72 tests, 1231 assertions, 0 failures, 0 errors (`:throw` sweep included).

## 3. og:image - PASS (one header deviation)
23 pages fetched (every static page, /, 6 services incl. one without photo, 2 products with and without photo, 4 legal, book, both carts, checkout, 404): every og:image and JSON-LD image URL returns 200. Photo pages (/services/single-session, /shop/qa-hoodie) carry og:image:width, height and alt. `/assets/og-image.png` is 200 `image/png`, 1200x630. Also 200 on the static fingerprinted URLs.
Deviation (minor, backend, `src/aurashots/features/pages` or the static asset handler): `/assets/og-image.png` is served `Cache-Control: public, max-age=31536000, immutable`; api-contract section 6 and Amendment A2.12 say `public, max-age=86400` for this one file (a stable URL cached immutably for a year means a replaced share image will not refresh).

## 4. Meta descriptions - PASS
Shortest description on any page is 64 characters (/services/couple-session); none under 50 on 23 pages. Published privacy version with key point "a": description is now 106 characters (falls back to the default sentence); empty key points also 106.

## 5. Fitness checks F44-F53 and the standards set
- F44 PASS (with a note): `scripts/check/history.clj` OK (no retract or rewrite of :history in 8 files); `test/aurashots/features/history_test.clj` passes (inside bb test). The literal `rg "retract"` in the architecture.md command has one hit, `booking/store.clj:32` (`:db.fn/retractAttribute ... :booking/confirmedDate`), which is not history; architect should tighten the grep text.
- F45 PASS (jar run below: robots exactly `User-agent: *\nDisallow: /`, noindex header, sitemap `<loc>` on PRODUCTION_BASE_URL).
- F46 PASS: `PUBLIC_BASE_URL= APP_ENV=prod|staging bb scripts/web/build.clj` exits 1 ("PUBLIC_BASE_URL must be set when APP_ENV=prod"); server start with APP_ENV=prod and staging without it exits 1 ("PUBLIC_BASE_URL must be set when APP_ENV is prod ..."); build with `https://x.example.test` puts it in 17 files; `rg aura-shots.com dist/web src scripts resources` empty. (architecture.md F46 still says "pending backend": stale text, architect.)
- F47 PASS (item 3 above, except the cache header). F48 not re-run (unchanged). 
- F49 PASS: one `org.datalevin/datalevin-embedded 1.2.10`; flags in :dev, :run, :test and run.clj; restore-drill passes.
- F50 PASS: no `type="module"`/import/export in web; no inline script/style/handler in dist/web; build exits 0.
- F51, F52: covered by bb test (passes). F53 PASS: `OK: 104 routes, all declare a response schema`.
- bb check: 104 routes OK, contract = route table (0 missing, 0 extra), boundaries OK (28 deps, no cycles), responses OK, history OK. bb lint: 0 errors, 0 warnings. F1 kondo with config: 0/0.
- F2, F3, F4, F5, F8, F9, F18, F20, F21, F22, F25, F27, F29: PASS (no output / expected output). F7: APP_ENV=bogus and staging without TOKEN_PEPPER both exit 1 "Invalid configuration".
- F6 note: `rg environ src` hits only the word "environment-specific" in a docstring (`features/pages/routes.clj:125`), a false positive. F10 note: `platform/http_core/response_check.clj:10,43` uses `clojure.pprint/pprint` inside `with-out-str` to format a log field; nothing is printed to stdout, so no real print, but it matches the rg pattern (backend-clojure may want `pr-str` to keep the grep clean).
- F40 (`bb memory-check`, defaults, 30 s, 12 workers): idle 460 MB, peak 832 MB, end 623 MB, 190,497 requests, 0 errors, p95 5 ms, 6000 px upload 201. Result WARN: above the 800 MB design budget, under the 1024 MB limit. Full host run is deployment-engineer's.

## 6. Uberjar - PASS
`clojure -T:build uber` OK; manifest has `Add-Opens: java.base/java.nio java.base/sun.nio.ch` and `Enable-Native-Access: ALL-UNNAMED`. `java -Xmx512m -jar target/aurashots.jar` with APP_ENV=staging, PUBLIC_BASE_URL, PRODUCTION_BASE_URL, throwaway DATA_DIR starts with no extra flags: /api/health `{"status":"ok"}`; robots `User-agent: *\nDisallow: /`; `X-Robots-Tag: noindex` on HTML (none on /api/health); CSP present; /services/single-session has canonical and og:image on https://staging.example.test, og:image URL serves 200; sitemap on https://prod.example.test; /assets/og-image.png 200 image/png. No ERROR lines in the jar log.
`bb restore-drill` against qa-data3: **PASS (14 checks, 5 s)**, run on a snapshot taken after the journeys (639 datoms; message 23, acceptance 8, booking 5, order 3, enquiry 7, service 7, image 10 all equal to source; 30 upload files; app starts on the restored data; images served). Weekly export also confirmed: `export-20261029.edn` written, log "Weekly export written ... status ok".

## 7. Admin help - PASS
mark-sold-out (topic page and the "How do I...?" drawer from Merchandise): the sentence "does not always bring it back" is gone; the remaining note is "If you marked a whole item as sold out, bring it back with Mark as in stock on its card." block-dates matches the now-visible warning (see item 1; "yellow" wording cosmetic above).

## 8. Regression
- Contract endpoints from the frontend: PASS. All 104 routes exercised through the browser or API with correct paths, methods and shapes (admin writes carry X-CSRF-Token, public creates carry Idempotency-Key).
- Journeys: J1 PASS (booking AS-S-..., agreement v1 recorded, basket emptied), J2 PASS (order AS-M-..., "Awaiting approval", mini-panel link is labelled "View merchandise bag" rather than "View bag"), J3 PASS (dashboard card, list, detail, note, confirm with 11:00 change, Messages sent row, calendar shows 11:00, paid, completed, decline with "Yes, decline"), J4 PASS (price-less publish blocked, add, View on the website, sold out/in stock reflected on P6, remove -> 404, portfolio add shown on P4, block day; a price edit showed the old price for under a minute, within the stated 1-minute cache), J5 PASS (add, "Invitation sent", set password from link, support menu lacks Staff accounts/Policies, switch off, remove).
- Click test at 1280 and 390: 68 PASS, 0 FAIL (nav, hero buttons, Add to basket vs View details vs title link, panels, lightbox, filters, contact form, footer links, 404); no hijacked clicks.
- Tokens: PASS. No hex colours or hard-coded font sizes in any CSS outside design-tokens.css. All 52 icons used exist in assets/icons/ (53 files). Favicon (svg + 32 png) and manifest linked in every page head (only the two `_partials` fragments lack them, which are not pages).
- Screenshots refreshed: 34 files in `/home/pjm/dev/learn/aurashots/qa-screenshots/` ({home,services,service-detail,portfolio,shop,product-detail,cart-sessions,cart-merchandise,book,checkout,about,contact,legal-terms-of-sale,legal-refund-policy,legal-privacy,legal-order-terms,not-found}-{390,1280}.png), no horizontal overflow, only console error is the intentional /nope 404; 21 topic screenshots plus `block-dates-warning.png` in `qa-screenshots/help/`.

## Findings (none blocking)
1. Account screen duplicate id (frontend, `web/js/admin/10-auth.js:93`): the form's `<h2 id="ac-pw">` collides with the "New password" input `id="ac-pw"`, so the label's `for` points at the heading. The New password field has no accessible name for screen readers (getByLabel("New password") fails; the field works with a mouse). Rename the heading id. Accessibility defect on a staff-only screen.
2. `/assets/og-image.png` cache header is immutable/1 year, contract says 86400 (backend).
3. Memory peak 832 MB (WARN over the 800 MB budget, within the 1 GB host limit); confirm on the real host.
4. Doc text out of date (architect): F46 "pending backend", F30 and A2.12 give a 20-character description floor while the build enforces 50; F44 grep text.
5. Help wording "yellow message" (help/ux-designer, cosmetic).

QA sign-off: YES
