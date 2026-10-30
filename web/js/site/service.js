/*
 * Service detail (P3). Renders from the server-inserted #initial-data block when present (identical to
 * GET /api/services/{slug}); otherwise calls the API. Unknown or unpublished slugs get a real 404 from
 * the backend; if this file is opened without it we show a friendly message.
 */
(function (AS) {
  "use strict";
  var h = AS.h, $ = AS.$;

  function initial() {
    var el = document.getElementById("initial-data");
    if (!el) { return null; }
    try { return JSON.parse(el.textContent); } catch (e) { return null; }
  }

  function included(s) {
    var c = s.contents || {}, items = [];
    if (c.editedPictures) { items.push(["picture", AS.cards.plural(c.editedPictures, "edited picture", "edited pictures")]); }
    if (c.videos) { items.push(["video", AS.cards.plural(c.videos, "video", "videos")]); }
    if (c.outfits) { items.push(["outfit", AS.cards.plural(c.outfits, "outfit", "outfits")]); }
    if (c.deliveryTime) { items.push(["clock", "Delivered in " + c.deliveryTime]); }
    if (c.locations) { items.push(["location-pin", c.locations]); }
    return items;
  }

  function agreementPreview() {
    AS.api.get("/documents/session-booking-agreement").then(function (doc) {
      var wrap = h("div", { class: "agreement" }, h("div", { class: "agreement__head" }, h("h3", { text: doc.title }), h("span", { class: "agreement__version", text: "Version " + doc.version })), h("div", { class: "agreement__text" }, AS.ui.markdown(doc.body)));
      var m = AS.modal(doc.title, wrap);
      m.dialog.appendChild(h("div", { class: "dialog__actions" }, h("button", { type: "button", class: "btn", text: "Close", onclick: m.close })));
    }, function (err) { AS.toast(err.message, "error"); });
  }

  function render(s) {
    $("#crumb-name").textContent = s.name;
    $("#svc-title").textContent = s.name;
    $("#svc-price").textContent = AS.fmt.rwf(s.priceRwf);
    $("#svc-short").textContent = s.shortDescription || "";
    $("#svc-length").textContent = s.sessionMinutes ? "Session length: " + s.sessionMinutes + " minutes" : "";
    var imgs = (s.images && s.images.length ? s.images : (s.mainImage ? [s.mainImage] : []));
    AS.clear($("#svc-gallery")).appendChild(AS.ui.gallery(imgs, { ratio: "card" }));
    var actions = AS.clear($("#svc-actions"));
    if (s.priceRwf) {
      actions.appendChild(h("div", { class: "btn-pair" }, AS.cards.sessionControl(s, { large: true }), h("a", { class: "btn btn--lg btn--secondary", href: "/contact" }, "Ask a question")));
    } else {
      actions.appendChild(h("p", { class: "alert alert--warning", text: "Currently unavailable" }));
    }
    var more = AS.clear($("#svc-more")), inc = included(s);
    var sec = function (title, id) { return h("section", { class: "section", "aria-labelledby": id }, h("h2", { id: id, text: title })); };
    if (s.description) { var d = sec("About this session", "svc-about"); d.appendChild(h("p", { class: "conditions", text: s.description })); more.appendChild(d); }
    var w = sec("What's included", "svc-included");
    w.appendChild(inc.length ? h("ul", { class: "facts" }, inc.map(function (i) { return h("li", {}, AS.icon(i[0]), h("span", { text: i[1] })); })) : h("p", { class: "muted", text: "Ask us what is included in this package." }));
    if (s.relatedProducts && s.relatedProducts.length) {
      w.appendChild(h("h3", { text: "Goes well with" }));
      w.appendChild(h("ul", {}, s.relatedProducts.map(function (p) { return h("li", {}, h("a", { href: "/shop/" + p.slug, text: p.name })); })));
    }
    more.appendChild(w);
    var c = sec("Conditions", "svc-conditions");
    if (s.conditions) { c.appendChild(h("p", { class: "conditions", text: s.conditions })); }
    c.appendChild(h("div", { class: "notice" }, AS.icon("no-refund"), h("p", {}, h("strong", { text: "No refunds. " }), "Bookings are commitments. Please read the ", h("a", { href: "/legal/refund-policy", text: "refund policy" }), ".")));
    c.appendChild(h("p", {}, h("button", { type: "button", class: "btn btn--text", onclick: agreementPreview }, "Read the Session Booking Agreement")));
    more.appendChild(c);
    if (s.relatedServices && s.relatedServices.length) {
      var r = sec("Other packages", "svc-related");
      r.appendChild(h("div", { class: "grid grid--cards" }, s.relatedServices.slice(0, 3).map(function (x) { return AS.cards.serviceCard(x); })));
      more.appendChild(r);
    }
    // If the backend did not rewrite the head (file opened directly), keep the tab title sensible.
    if (!initial()) {
      AS.setHead({ title: s.name + " – Photoshoot in Kigali | Aura Shots" });
      var can = $('link[rel="canonical"]'); if (can) { can.href = location.origin + location.pathname; }
    }
  }

  function notFound() {
    $("#svc-title").textContent = "We could not find that package";
    AS.clear($("#svc-gallery"));
    AS.clear($("#svc-actions")).appendChild(h("a", { class: "btn", href: "/services", text: "See all packages" }));
  }

  document.addEventListener("DOMContentLoaded", function () {
    var data = initial();
    if (data) { render(data); return; }
    var slug = decodeURIComponent(location.pathname.split("/")[2] || "");
    AS.api.get("/services/" + encodeURIComponent(slug)).then(render, function (err) {
      if (err.status === 404) { notFound(); } else { AS.clear($("#svc-gallery")).appendChild(AS.ui.errorBox(err, function () { location.reload(); })); }
    });
  });
})(window.AS);
