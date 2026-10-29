# Aura Shots Visual Guide

Inputs read: business-context.md, brand-brief.md, design-spec.md (icon and badge list in Part 8 is authoritative), engineering-standards.md (nothing in it constrains visuals). The logo (`business/images/logo/logo.jpg`) and the six sample shots in `business/images/shots/` were viewed. Companion files: `design-tokens.css` (single source of truth), `assets/fonts/fonts.css`, `assets/`.

Locale: British English, prices as "50,000 RwF", WCAG 2.1 AA.

## 1. Audience -> decisions

| Fact (from business-context.md / brand-brief.md) | Visual decision | Reason |
|---|---|---|
| Customers are 20s to late 30s, style-led, "willing to spend on style and quality" | Near-black field, Pearl text, one pink accent, serif display headings, generous space | Restraint reads as luxury; a busy or bubbly look would read as the "local studio" they want to leave behind |
| Differentiator is editorial, cinematic, colour-theory photography | UI stays neutral (Noir, Pearl, one pink); photos carry all other colour | Colourful chrome would fight the colour work in the portfolio |
| Existing identity is black and pink, logo is white line-art on black | Noir `#0A0A0A` background, Aura Pink `#E0457B` unchanged, line-weight icons (1.5px stroke) | Icons must match the thin line of the camera logo |
| Found through Instagram and TikTok, so viewed on phones | Mobile-first sizing: 17px body, 48px tap targets (56px for the main call to action), single-column cards under 640px | Thumb use, glare outdoors; the spec also mandates mobile first |
| New audience: tourists and urban professionals who do not know the brand | Prices, inclusions and the no-refund note use plain Inter at full contrast, with icons plus text, never colour alone | Strangers need clarity and reassurance before trust; English may be a second language for some |
| Appointment-only, no walk-ins, no-refund policy | "By appointment only" and "No refunds" get quiet outlined badges, not alarm colours | Polite, confident, firm; avoids a punitive tone |
| Prices in RwF, large digit counts (100,000 RwF) | Inter tabular figures, currency after the number, 20px semibold on cards | Long numbers must be scannable and comparable |
| Owner likes Etsy for intuitive commerce | Card anatomy and clear pill buttons borrowed from Etsy; none of its density | Familiar patterns for guests, exclusive feel retained |
| Owner and support staff are non-technical; admin is task-per-screen | Admin uses the light theme, 17px text, labelled status chips (dot plus words), big buttons | Long forms and calendars are easier on light; plain status words per design-spec |
| Hosting under 15 USD a month, expected traffic in the thousands | Self-hosted Latin-subset WOFF2 (six files, about 150 KB total), SVG logo, icons and badges, no icon or font CDN | Fast on mobile data in Kigali, no third-party cost or privacy exposure |
| Sample shots are 3:4 portraits shot in real Kigali venues, warm light, one bold outfit colour | Portfolio tile ratio 3:4, card ratio 4:5, imagery rules in section 7 | Match what the owner already shoots, so no re-shooting is needed |
| Logo wordmark is a script | Live text never uses a script face; Cormorant Garamond (high-contrast serif) for headings | Script appears only in the logo, where it stays distinctive |

## 2. What works for this audience (research notes)

Method note: etsy.com and Pixieset sites returned 403 to the fetch tool, so Etsy and the Pixieset studios are known from web-search snippets and Etsy's seller documentation only. Flytographer's Kigali page was fetched.

- **Flytographer (fetched).** White, black-text, very sparse page; its trust signal is a single large number, "50,000+ 5-star reviews", plus named package tiers with duration and photo count (for example 60 minutes, 30 photos). Lesson: tourists want counts and durations stated plainly. We use the same fact-line pattern (edited pictures, videos, outfits, delivery time) but add the dark editorial mood Flytographer lacks. No fake review counts: the business has none to show yet, so trust comes from the real portfolio, stated policy and local address.
- **Etsy (search snippets and seller docs).** Cards are image-led; listing images are 1:1 with about 1.25:1 thumbnails, price directly under the title, and one coloured trust badge (Star Seller) on a solid fill. Lesson: one badge per card, solid fill, short text, no clutter. Seller guidance explicitly warns against stacking badges and fonts. We cap cards at one badge on the image and keep price under the title.
- **Dark editorial photographer sites and templates (search results).** The recurring recipe is a near-black canvas, one restrained accent, serif display type, big imagery with deliberate pacing, and a named-tier pricing section. Lesson: confirms Noir plus a single accent; we choose pink (brand) rather than the common gold so Aura Shots is not interchangeable.
- **Luxury Editorials and Kroma (brief, snippets).** Package pages list looks, locations and number of edits per tier. Our "Package facts list" follows the same structure.

How this informed decisions: restraint in colour; photography at full bleed; a single high-contrast price line; badges that are solid, short, one per card; no stock-photo or star-rating clutter.

## 3. Palette

Pink, Noir and Pearl are taken unchanged from brand-brief.md. The brief's pink (`#E0457B`) is the strategist's proposal, not a sampled brand value; it passes AA on Noir (5.00:1) so no adjustment was needed. Shades added by me: surfaces, hover tints, semantic colours and the "ink" variants for light surfaces. The brief's own advice (Noir text on pink fills) is kept. The owner should still confirm the pink against their Instagram pink.

| Role | Token | Hex | Use |
|---|---|---|---|
| Primary (brand black) | `--noir` / `--color-bg` | `#0A0A0A` | Page background, text on pink fills |
| Surface | `--noir-1` / `--color-surface` | `#151115` | Cards, header, footer |
| Surface raised | `--noir-2` / `--color-surface-raised` | `#201A1F` | Inputs, chips, menus, placeholders |
| Text | `--pearl` / `--color-text` | `#F6F1EF` | All body text and headings on dark |
| Text muted | `--mist` / `--color-text-muted` | `#B8ADB2` | Captions, hints, secondary lines |
| Accent (primary action) | `--aura-pink` / `--color-primary` | `#E0457B` | Button fill, selected chip, links and highlights on dark |
| Accent hover | `--aura-pink-hover` | `#EB6B98` | Hover fill and hover link on dark |
| Accent press / tint | `--aura-pink-press` | `#F4C6D7` | Pressed fill, subtle highlights |
| Accent on light | `--aura-pink-ink` | `#B3205A` | Links and pink text on Pearl or white (admin, legal) |
| Border, decorative | `--noir-line` | `#3A3138` | Hairlines between sections (not for field edges) |
| Border, UI | `--noir-edge` | `#8C8187` | Input borders, outline buttons, unchecked checkboxes |
| Success | `--green` / light `--green-ink` | `#4ADE80` / `#14703A` | In stock, saved, confirmed |
| Warning | `--amber` / `--amber-ink` | `#F5B942` / `#8A5A00` | Waiting, needs a price, no-refund notice |
| Error | `--coral` / `--coral-ink` | `#FF7B72` / `#B3261E` | Validation errors, sold out, declined |
| Info | `--sky` / `--sky-ink` | `#7DB8FF` / `#1F5FA8` | Notes, completed, delivered |
| Light theme background / surface | `--pearl`, `--white`, `--pearl-2` | `#F6F1EF`, `#FFFFFF`, `#EFE8E5` | Admin, legal text, agreement panel |
| Light secondary text | `--umber` | `#5E5258` | Muted text on light |

Error coral is deliberately warmer and lighter than the pink so the two are not confused, and errors always add an icon and words. Never use pink for errors, and never use semantic colours as the only signal.

### Contrast ratios (computed by script, WCAG relative luminance)

| Pairing | Ratio | Need | Result |
|---|---|---|---|
| Pearl on Noir | 17.67 | 4.5 | Pass |
| Pearl on Surface `#151115` | 16.70 | 4.5 | Pass |
| Pearl on Surface raised `#201A1F` | 15.25 | 4.5 | Pass |
| Mist on Noir / Surface / Surface raised | 9.11 / 8.60 / 7.86 | 4.5 | Pass |
| Aura Pink text on Noir | 5.00 | 4.5 | Pass |
| Aura Pink text on Surface | 4.73 | 4.5 | Pass |
| Aura Pink text on Surface raised | 4.32 | 4.5 | **Fail: never put pink text on raised surfaces** |
| Pink hover `#EB6B98` on Noir / Surface | 6.68 / 6.31 | 4.5 | Pass |
| Noir text on Pink fill | 5.00 | 4.5 | Pass |
| Noir text on Pink hover fill | 6.68 | 4.5 | Pass |
| Noir text on Pink press/tint fill | 13.11 | 4.5 | Pass |
| Pearl text on Pink fill | 3.53 | 4.5 | **Fail: never use** (large 3:1 only) |
| Pink `#E0457B` text on Pearl | 3.53 | 4.5 | **Fail: use Pink ink on light** |
| Pink ink `#B3205A` on Pearl / White / Pearl-2 | 5.73 / 6.42 / 5.30 | 4.5 | Pass |
| Pearl text on Pink ink fill | 5.73 | 4.5 | Pass (pink buttons on light surfaces may use this) |
| Pink ink hover `#8F1848` on Pearl | 7.84 | 4.5 | Pass |
| Noir on Pearl / White | 17.67 / 19.80 | 4.5 | Pass |
| Umber on Pearl / White / Pearl-2 | 6.64 / 7.44 / 6.14 | 4.5 | Pass |
| Success, warning, error, info text on Noir | 11.36 / 11.22 / 7.85 / 9.59 | 4.5 | Pass |
| Error on Surface | 7.42 | 4.5 | Pass |
| Noir text on success / warning / error / info fills | 11.36 / 11.22 / 7.85 / 9.59 | 4.5 | Pass |
| Green, amber, coral, sky ink on White | 6.16 / 5.93 / 6.54 / 6.44 | 4.5 | Pass |
| Coral ink on Pearl | 5.84 | 4.5 | Pass |
| UI border `#8C8187` on Noir / Surface / Surface raised | 5.28 / 4.99 / 4.56 | 3.0 | Pass |
| UI border light `#7A6E74` on Pearl / White | 4.35 / 4.87 | 3.0 | Pass |
| Pink on Noir as UI component (focus, selected outline) | 5.00 | 3.0 | Pass |
| Focus ring Pearl on Noir | 17.67 | 3.0 | Pass |
| Disabled text `#9A8F95` on `#2A242A` | 4.87 | exempt, still passes | Pass |

Rules that follow: pink text sits only on Noir or Surface; Pink fills carry Noir text; on light surfaces use Pink ink; Pearl text on a Pink fill is banned. Where a pink fill sits on a pink-adjacent area, the focus ring uses a 3px offset so it shows against Noir.

## 4. Typography

| Role | Font | Weights | Licence | Fallback stack |
|---|---|---|---|---|
| Headings, display, price figures on hero | Cormorant Garamond | 500, 600, 600 italic | SIL Open Font License 1.1 (free) | Iowan Old Style, Georgia, Times New Roman, serif |
| Body, forms, prices, admin | Inter | 400, 500, 600 | SIL Open Font License 1.1 (free) | system-ui, -apple-system, Segoe UI, Roboto, Helvetica Neue, Arial, sans-serif |
| Script | None live; logo only | | | |

Self-hosted from `assets/fonts/` (Latin subset WOFF2 from the Fontsource packages of the upstream OFL fonts; licence texts alongside). `font-display: swap` so text shows immediately in the fallback. No Google Fonts request at runtime. Cormorant Garamond is small at low sizes, so it is never used below 26px; use Inter for anything smaller.

| Token | Size | Use |
|---|---|---|
| `--text-xs` | 13px | Badge labels, captions, legal footnotes (never for essential instructions) |
| `--text-sm` | 15px | Hints, admin secondary text |
| `--text-base` | 17px, line height 1.6 | Body. Slightly above 16px because light text on black looks thinner |
| `--text-lg` | 20px | Lead paragraph, price on cards |
| `--text-xl` | 26px | Card titles, h4 (Cormorant 600) |
| `--text-2xl` | 30 to 40px fluid | h3 |
| `--text-3xl` | 36 to 56px fluid | h2 |
| `--text-4xl` | 44 to 80px fluid | h1, hero (line height 1.1, tracking -0.01em) |

Reading text maximum line length 66ch. Prices: Inter 600 with `font-variant-numeric: tabular-nums`, currency after the number ("50,000 RwF"). Eyebrow labels: Inter 600, 13px, uppercase, tracking 0.14em. Tagline "Not just photos…it's an aura" is set in Cormorant 600 italic, never uppercased.

## 5. Spacing, radius, shadow

- Spacing: 4px base. Steps 4, 8, 12, 16, 24, 32, 48, 64, 96, 128 (`--space-1` to `--space-10`). Section rhythm 64px mobile, 96 to 128px desktop: generous space is the luxury signal. Card inner padding 16px mobile, 24px desktop.
- Radius: buttons, chips, badges are pills (999px) to echo the soft curves of the logo; inputs and cards 8px; dialogs, calendar, agreement panel 16px; portfolio tiles 4px so photographs feel framed, not app-like.
- Shadows: on a black page shadows barely show, so depth comes from surface tone (Noir, Surface, Surface raised). `--shadow-md` for menus and dialogs. `--shadow-aura` (a thin pink ring plus soft pink glow) is the single decorative effect, used on hover of cards and the focused hero button only. Nothing else glows.
- Borders: 1px `--noir-line` for decoration; 1px `--noir-edge` on anything the user must find (inputs); 2px for error.
- Max content width 1200px, gutters 16px (mobile) and 32px (960px and up). Layout itself belongs to ux-designer and the frontend.

## 6. Logo and brand assets

Source: `business/images/logo/logo.jpg` (150x150 white line-art DSLR with script "Aura shots" on black). It was redrawn as SVG from this design: camera body, prism hump, two lens arcs, flash arc and strap dot, with the script wordmark overlapping the lens as in the original. The retired slogan "Capturing moments" is left out. The redraw is a hand trace from a 150px source, so letter shapes are close to, not identical to, the original lettering. If the owner has the original vector file, replace `logo*.svg` with it.

| File | Use |
|---|---|
| `assets/logo.svg` | Full lockup, Pearl on transparent, for dark backgrounds (header, hero, footer) |
| `assets/logo-ink.svg` | Full lockup in Noir for light backgrounds (admin sign-in, print, email) |
| `assets/logo-mark.svg`, `-ink` | Camera only (square contexts, avatars) |
| `assets/logo-wordmark.svg`, `-ink` | Script wordmark only |
| `assets/favicon.svg` | Rounded Noir tile with camera, for browser tab |
| `assets/favicon-square.svg` | Full-bleed version, source of 180/192/512 |
| `assets/favicon-16.png`, `-32`, `-180`, `-192`, `-512` | PNG sizes (16 and 32 use a heavier stroke so they stay legible) |
| `assets/site.webmanifest` | Name, colours, icons; `theme_color` and `background_color` `#0A0A0A` |

Rules: clear space around the logo equal to the height of the camera's lens arc; minimum width 120px for the lockup (below that use the mark); never recolour to pink, never add the old slogan, never place on a photo without a dark scrim. Header height with logo: 64px mobile.

Suggested head links (for the frontend): `<link rel="icon" href="/assets/favicon.svg" type="image/svg+xml">`, 32px PNG, `apple-touch-icon` 180px, `manifest`.

## 7. Imagery rules

### What the sample photos show (sampl1 to sampl6)

All six are single-subject, full-length or three-quarter portraits in portrait orientation (about 3:4: 386x518 and 387x516 px; sampl2 is 194x259). They are shot in real Kigali interiors and terraces (a lift, a restaurant, a staircase, a concrete-panel wall, a balcony over the city), under warm, mixed, available light, with a single saturated outfit colour against a neutral or warm setting (lilac against copper, red against patterned tile, orange against grey concrete): that is the colour-theory signature. Poses are confident, hands on hair or wall, strong "main character" body language.

Problems to fix before they go live: all are far too small for the web (the hero needs at least 2400px wide); sampl2 has a white band across the bottom (crop it, since white edges break on Noir); sampl1 and sampl3 have bright white walls that glow against the black page (grade or crop). The six are placeholders for layout; replace with full-resolution originals.

### Photo spec for the owner or photographer

| Rule | Spec |
|---|---|
| Orientation and ratio | Portfolio masters 3:4 portrait, shot with room to crop to 4:5 (cards) and 1:1 (thumbnails). Hero images 16:9 landscape |
| Resolution | At least 2400px on the long edge for portfolio, 3200px for hero; never upscaled |
| Background | Real setting that supports the outfit colour; no white or blown-out walls at the edges; uncluttered; no other people unless the package includes them |
| Lighting | Warm, directional or dramatic; clean highlights, no clipped skin; deep blacks preserved (the site is black) |
| Crop | Subject on a third; head never touching the edge; leave space above for the hero text and a dark area bottom-left for the hero scrim; full-length and three-quarter, with a few close portraits for variety |
| Colour | One dominant outfit colour per frame, consistent warm grade across the set, sRGB, no heavy filters or watermarks |
| Edges | Never a white border or letterbox; if padding is needed use Noir |
| Files | Master JPG at quality 90; the site serves WebP or AVIF derivatives under 200 KB for cards; strip GPS metadata (privacy) |
| People | Written consent from every subject to publish the image |
| Alt text | Describe subject, outfit and setting, for example "Woman in a red strapless dress posing in a restaurant with patterned floor tiles" |
| Overlays | Text on photos only over `--gradient-hero-scrim`; never over a bright area |

Merchandise shots (clothing, prints, framed art): square 1:1 or 4:5, soft even light, plain Pearl or mid-grey backdrop, product centred with 10% margin, one front view plus a detail view and (for clothing) a worn view; framed art shown on a plain wall with the frame fully visible. Not stock photography and no random-photo services: every image comes from the owner.

### Images the owner must supply

1. Hero: 2 landscape 16:9 images, 3200px wide, subject on the right third, dark or darkenable lower left.
2. Service cover images, 4:5, 2000px minimum, one for each package: Single Session, Couple Session, Besties Package, Special Package, Weekend Sessions. At present no couple, friends or birthday sample exists: the samples are all solo portraits.
3. Portfolio: at least 6 images per category (Portraits, Couples and Friends, Birthdays, Lifestyle), 3:4, full resolution. Today only solo portraits are available, so three of four categories are empty. Add a short caption and alt text for each.
4. Merchandise: for each item (T-shirt, hoodie, print, framed art) 3 to 4 images per the merchandise spec above.
5. About page: one photo of the owner or behind the scenes, 4:5 (optional but builds trust for strangers).
6. Social sharing image: 1200x630 (derived from a portfolio shot, with the logo).
7. Higher-resolution (or vector) original of the logo, if it exists.
Until then use `assets/placeholders/` (neutral labelled "Photo to be supplied" tiles at 3:4, 4:5, 16:9, 1:1). Placeholders are clearly placeholders; no stand-in photos.

## 8. Icons

**Family: Tabler Icons, outline set** (https://tabler.io/icons), MIT licence (licence file copied to `assets/icons/LICENSE-Tabler-Icons-MIT.txt`). Chosen because it is actively maintained (npm package `@tabler/icons` v3.50.0, last published 27 October 2026), permissively licensed, includes the brand glyphs the spec needs (WhatsApp, Instagram, TikTok, Facebook, which Lucide no longer ships), and is a single consistent line style that matches the line-art logo. One family only; nothing mixed, nothing hand-drawn. Only the 52 icons listed in design-spec Part 8 were copied.

Style rules: 24px grid, stroke 1.5px (reduced from the set's default 2px so it matches the thin logo line; the only edit), round caps and joins, `currentColor`, no fills. Sizes 20, 24, 32px. An icon beside text is decorative (`aria-hidden`); a standalone icon button needs an `aria-label`. Colour: Pearl by default, Pink only for active states on dark, Noir on pink fills.

Files in `assets/icons/` (source Tabler name in brackets where different): menu, close (x), session-basket (shopping-cart), merchandise-bag (shopping-bag), calendar, clock, location-pin (map-pin), phone, whatsapp, email (mail), instagram, tiktok, facebook, camera, outfit (hanger), video, picture (photo), delivery (truck-delivery), no-refund (receipt-refund), secure (shield-lock), mobile-money (device-mobile-dollar), cash, success (check), warning (alert-triangle), error (alert-circle), info (info-circle), chevron-left, chevron-right, chevron-down, chevron-up, arrow-right, plus, minus, remove (trash), edit (pencil), eye, eye-off, drag-handle (grip-vertical), upload, download, external-link, help, user, sign-out (logout), settings, dashboard (layout-dashboard), list, bell, lock, star, filter, sort (arrows-sort).

Mappings for spec entries that reuse a glyph: expand/collapse = chevron-down / chevron-up; lightbox next/previous = chevron-right / chevron-left. Not used: wishlist, customer sign-in, newsletter, search.

Note on "no-refund": Tabler has no dedicated "no refund" glyph, so the receipt-refund icon is paired with the words "No refunds" and is never used alone.

## 9. Badges

Simple geometric SVG pills, 28px high, text converted to outlines from Inter 600 so they render without the web font. Pill radius, 12px horizontal padding. Retail badges are uppercase 12px with 0.08em tracking; status chips are sentence case 13px with a leading 8px coloured dot (state is carried by the words, the dot is reinforcement). Every badge has an opaque fill or outline, so they are legible over photos. One badge per card image, top-left, 8px inset.

| Badge | File | Style |
|---|---|---|
| New | `badges/new.svg` | Pink fill, Noir text (5.00:1) |
| In stock | `badges/in-stock.svg` | Surface raised fill, green dot, Pearl text |
| Sold out | `badges/sold-out.svg` | Coral fill, Noir text (7.85:1) |
| Featured | `badges/featured.svg` | Pearl fill, Noir text |
| Popular | `badges/popular.svg` | Pink tint fill, Noir text |
| From (price prefix) | `badges/from.svg` | 2px Noir-edge outline, Pearl text |
| By appointment only | `badges/by-appointment-only.svg` | 2px Pearl outline, Pearl text |
| No refunds | `badges/no-refunds.svg` | 2px amber outline, amber text (11.22:1 on Noir) |
| Admin status chips | `badges/status-*.svg` (15 files: waiting-for-you, confirmed, declined, completed, approved, rejected, paid, delivered, published, hidden, needs-a-price, draft-needs-review, reviewed, switched-off, answered) | Surface raised fill, Pearl text, dot: amber = waiting, needs a price, draft; green = confirmed, approved, paid, published, reviewed; coral = declined, rejected; sky = completed, delivered, answered; grey = hidden, switched off |

Badge SVGs are made for dark surfaces. "Free delivery" and "Secure checkout" were suggested as examples in my brief but are not in design-spec Part 8 (delivery fees are `<TBD>`, and payment is cash or mobile money after approval, so a "secure checkout" claim would be misleading), so they are not produced. The frontend may also render badges as HTML using the same tokens; the look is identical.

## 10. Components and states

All states use tokens. Focus is always a visible 3px `--color-focus-ring` outline with 3px offset (Pearl on dark, Pink ink on light), never removed. Hover effects are also reachable by keyboard focus. Motion 200ms, off under reduced-motion.

| Component | Default | Hover | Focus | Disabled | Error |
|---|---|---|---|---|---|
| Primary button | Pink fill, Noir text 600, pill, 48px high (56px hero) | Fill `#EB6B98` | Focus ring offset 3px | Fill `#2A242A`, text `#9A8F95`, `cursor:not-allowed`, plus a visible reason in text when it matters ("Sold out") | n/a; error shown beside the control |
| Secondary button | Transparent, 1px Pearl outline, Pearl text | Fill Pearl 10%, outline Pink | Focus ring | Outline `--noir-line`, text `#9A8F95` | n/a |
| Text link | Pink on dark (Pink ink on light), underlined | Hover pink lighter, underline thicker | Ring | Not applicable | n/a |
| Input, textarea, select | Surface raised fill, 1px `--noir-edge`, 8px radius, 48px high, label above (never placeholder-only) | Border Pearl | 2px Pink border plus ring | Fill `#2A242A`, text grey, no pointer | 2px coral border, coral error icon and message under field linked by `aria-describedby`, text "Enter a valid email address" |
| Checkbox, radio | 24px box, 1.5px `--noir-edge`; checked = Pink fill with Noir tick | Border Pearl | Ring | Greyed, label muted | Coral border with message |
| Radio chip (variants) | Pill, 1px `--noir-edge`, 48px high | Border Pearl | Ring | Struck-through text, muted, label "Sold out" | n/a |
| Filter chip | Pill, outline | Outline Pink | Ring | n/a | n/a (selected = Pink fill, Noir text) |
| Service card, product card | Surface fill, 8px radius, image at 4:5 on top, title Cormorant 26px, price Inter 600 20px, fact line 15px muted | `--shadow-aura`, image zooms 1.03 | Ring around card link | Muted, badge "Sold out" | n/a |
| Portfolio tile | 3:4 image, 4px radius, caption below | Image brightens, small zoom icon; no text hidden | Ring | n/a | Broken image: placeholder tile with alt text |
| Badge, status chip | See section 9 | Not interactive | n/a | n/a | n/a |
| Calendar day | 48px square, Pearl number | Outline Pink | Ring | Greyed, `aria-disabled`, legend explains; blocked = coral diagonal stripe | n/a |
| Time slot button | Secondary button style | As secondary | Ring | "Unavailable", muted, struck-through | n/a |
| Quantity stepper | Two 48px circle buttons with minus and plus icons, count between | Outline Pink | Ring | Minus disabled at 1 | n/a |
| Accordion | Row with chevron-down, hairline between rows, 48px min | Row tint `--noir-2` | Ring | n/a | n/a |
| Toast and inline alert | Surface raised, 4px left bar in semantic colour plus icon and text | n/a | Ring on close | n/a | Error: coral bar, error icon |
| Confirm dialog | `--noir-2`, 16px radius, `--shadow-lg`, overlay `rgb(10 10 10 / 0.72)`; destructive confirm is a coral-fill button with Noir text | As button | Focus trapped, first focus on the safe "Cancel" | n/a | n/a |
| Stepper (booking steps) | Numbered circles, current = Pink fill, done = check icon, upcoming = outline | n/a | Ring | n/a | Step with error shows error icon |
| Agreement panel | Light theme island (Pearl field, Noir text) inside the dark page; scrollable, 16px radius, version label top right | n/a | Ring on scroll area | Submit disabled until the box is ticked | Missing tick: coral message |
| Admin shell, status chips, forms | Light theme, 17px text, buttons 56px, chips with words | As above | As above | As above | As above |
| Skeleton loader | `--noir-2` blocks with slow Pearl 6% shimmer (static under reduced motion) | n/a | n/a | n/a | n/a |

Hero: full-bleed photo, `--gradient-hero-scrim` at the bottom, logo, tagline in Cormorant italic, "Book a session" (primary) and "See the portfolio" (secondary).

## 11. Flags

1. **Pink is a proposal.** Kept exactly (`#E0457B`) because it passes AA; owner to confirm it matches their Instagram pink.
2. **Pearl on Pink fails** (3.53:1). All pink buttons use Noir text, as the brief says. A white-on-pink look is not possible at body size without darkening the pink, which would change the identity.
3. **Error colour versus brand pink.** Coral `#FF7B72` is close in warmth to pink. Mitigated by always adding an icon and text; if testing shows confusion, change error to a clearer red only after checking contrast.
4. **Logo redraw is approximate.** Traced by hand from 150px, wordmark letter shapes differ in detail. Provide the original vector if it exists.
5. **Sample photos are too small and a few have white edges.** Supply full-resolution originals (section 7). The portfolio categories Couples and Friends, Birthdays currently have no sample at all.
6. **Brief versus audience.** None conflict. Tourists and professionals are served by clarity (large type, plain facts) rather than by changing the brand.
7. **Seed prices** (Besties 12,000, Special 10,000, Weekend 15,000 RwF) look wrong beside Single 50,000 and Couple 100,000, as brand-brief.md already notes. Design works with any number, but the "From" badge and price styling should not be launched with unconfirmed values.
8. **Fonts are Latin subset.** If Kinyarwanda or French content with unusual characters is added, add the latin-ext files.

## 12. Do and do not

1. **Do** put Noir text on pink fills and use pink text only on Noir or the first surface; **do not** put Pearl text on pink or pink text on light backgrounds (use Pink ink).
2. **Do** let the photography carry colour: one pink accent, one badge per card; **do not** add extra accent colours, gradients on buttons, or stack badges.
3. **Do** state price as "50,000 RwF" in Inter 600 with tabular figures and show what is included beside it; **do not** hide prices, use USD, or set prices in the serif.
4. **Do** keep every control 48px or more with a visible 3px focus ring and a text label; **do not** rely on colour alone, remove outlines, or use placeholders as labels.
5. **Do** use the real logo files, Tabler icons at 1.5px stroke and the specified photo ratios (3:4, 4:5, 16:9); **do not** use the retired slogan, set live text in script, mix icon sets, crop with white borders, or use stock or random photos.

## 13. Social sharing image

`assets/og-image.png` (1200x630, about 49 KB; source `assets/og-image.svg`) is the preview for `og:image` and `twitter:image` (`summary_large_image`). Noir background, the real `logo.svg` lockup unchanged, the tagline "Not just photos…it's an aura" in Cormorant Garamond 600 (Pearl), one Aura Pink rule, and "KIGALI, RWANDA" in Inter 500 (Mist). No photos. All content sits inside the central safe area of 1080x566 (60px left/right, 32px top/bottom margins) because platforms crop. Text is converted to outlines in the SVG, so rendering needs no installed fonts. Regenerate when the tagline, logo, location or palette tokens change, by running `scripts/design/og_image.py` (instructions at the top of that file; it writes both the SVG and the 1200x630 PNG). Do not edit the outlined text in the SVG by hand. Keep the file under 300 KB.
