/*
 * Cards and small repeated pieces of the public site: service card, product card, portfolio tile,
 * the add-to-session-basket control, and a loader that shows a skeleton, errors with retry, and empties.
 */
(function (AS) {
  "use strict";
  var h = AS.h;

  function plural(n, one, many) { return n + " " + (n === 1 ? one : many); }

  function factsLine(c) {
    c = c || {};
    var parts = [];
    if (c.editedPictures) { parts.push(plural(c.editedPictures, "edited picture", "edited pictures")); }
    if (c.videos) { parts.push(plural(c.videos, "video", "videos")); }
    if (c.deliveryTime) { parts.push("Delivered in " + c.deliveryTime); }
    return parts.join(" · ");
  }

  // Button that adds a service to the session basket, then becomes a link to the basket.
  function sessionControl(svc, opts) {
    opts = opts || {};
    var box = h("div", { class: "session-control" });
    function paint() {
      AS.clear(box);
      if (AS.store.sessions.has(svc.id)) {
        box.appendChild(h("a", { class: "btn" + (opts.large ? " btn--lg" : ""), href: "/cart/sessions" }, AS.icon("success", "icon--sm"), "In your basket", h("span", { class: "sr-only", text: " – view basket" })));
      } else {
        box.appendChild(h("button", { type: "button", class: "btn" + (opts.large ? " btn--lg" : ""), onclick: function () {
          if (AS.store.sessions.all().length >= 10) { AS.toast("A session request can include up to 10 packages.", "warning"); return; }
          AS.store.sessions.add(svc); AS.toast(svc.name + " added to your session basket."); 
        } }, AS.icon("session-basket", "icon--sm"), "Add to basket"));
      }
    }
    window.addEventListener("as:baskets", paint);
    paint();
    return box;
  }

  function serviceCard(svc, o) {
    o = o || {};
    var facts = factsLine(svc.contents);
    return h("article", { class: "card" },
      h("div", { class: "card__media" }, AS.img(svc.mainImage, { ratio: "card", alt: svc.mainImage && svc.mainImage.alt })),
      h("div", { class: "card__body" },
        h("h3", { class: "card__title" }, h("a", { href: "/services/" + svc.slug, text: svc.name })),
        svc.shortDescription ? h("p", { class: "card__text", text: svc.shortDescription }) : null,
        facts ? h("p", { class: "card__facts", text: facts }) : null,
        h("p", { class: "price" }, o.from ? [AS.badge("from", "From"), " "] : null, AS.fmt.rwf(svc.priceRwf)),
        h("div", { class: "card__actions" },
          h("a", { class: "btn btn--secondary", href: "/services/" + svc.slug }, "View details", h("span", { class: "sr-only", text: " of " + svc.name })),
          o.noAdd ? null : sessionControl(svc))));
  }

  function productBadge(p) {
    if (p.availability === "soldOut") { return AS.badge("sold-out", "Sold out"); }
    if (p.isNew) { return AS.badge("new", "New"); }
    return AS.badge("in-stock", "In stock");
  }
  function productCard(p) {
    return h("article", { class: "card" + (p.availability === "soldOut" ? " card--muted" : "") },
      h("div", { class: "card__media card__media--square" }, h("span", { class: "card__badge" }, productBadge(p)), AS.img(p.mainImage, { ratio: "square", sizes: "(min-width: 960px) 25vw, (min-width: 640px) 50vw, 100vw" })),
      h("div", { class: "card__body" },
        h("h3", { class: "card__title" }, h("a", { href: "/shop/" + p.slug, text: p.name })),
        h("p", { class: "price", text: AS.fmt.rwf(p.priceRwf) }),
        h("div", { class: "card__actions" }, h("a", { class: "btn btn--secondary", href: "/shop/" + p.slug }, "View", h("span", { class: "sr-only", text: " " + p.name })))));
  }

  // item: portfolio API item {id, image, caption, description}
  function tile(item, onOpen) {
    return h("button", { type: "button", class: "tile", "aria-label": "View larger: " + (item.caption || item.description || "photo"), onclick: function (e) { onOpen(e.currentTarget); } },
      h("span", { class: "tile__img" }, AS.img(item.image, { ratio: "portrait", alt: item.description || (item.image && item.image.alt) || "", sizes: "(min-width: 960px) 33vw, 50vw" }), h("span", { class: "tile__zoom" }, AS.icon("external-link", "icon--sm"))),
      item.caption ? h("span", { class: "tile__cap", text: item.caption }) : null);
  }

  // Renders into `box`: skeleton while loading, error box with retry, otherwise render(result).
  function loadInto(box, fetcher, render) {
    AS.clear(box).appendChild(AS.ui.skeleton(3));
    return fetcher().then(function (res) { AS.clear(box); render(res); }, function (err) {
      AS.clear(box).appendChild(AS.ui.errorBox(err, function () { loadInto(box, fetcher, render); }));
    });
  }

  function lightboxItems(list) {
    return list.map(function (it) { return { image: it.image, alt: it.description || (it.image && it.image.alt) || "", caption: it.caption }; });
  }

  AS.cards = { serviceCard: serviceCard, productCard: productCard, tile: tile, sessionControl: sessionControl, factsLine: factsLine, loadInto: loadInto, lightboxItems: lightboxItems, plural: plural };
})(window.AS = window.AS || {});
