/*
 * Product detail (P6). Renders from #initial-data (same as GET /api/products/{slug}) or the API.
 * Option groups are radio chips, one per group; sold-out options are disabled and labelled.
 */
(function (AS) {
  "use strict";
  var h = AS.h, $ = AS.$;

  function initial() {
    var el = document.getElementById("initial-data");
    if (!el) { return null; }
    try { return JSON.parse(el.textContent); } catch (e) { return null; }
  }

  function buyForm(p) {
    var soldOut = p.availability === "soldOut";
    var form = h("form", { novalidate: true, class: "stack" });
    var groups = (p.optionGroups || []).map(function (g, gi) {
      var radios = AS.forms.chipRadios("opt-" + gi, g.name, g.options.map(function (o) { return { value: o.id, label: o.label, disabled: !o.inStock, note: o.inStock ? null : " (Sold out)" }; }),
        g.options.length === 1 && g.options[0].inStock ? g.options[0].id : null);
      var err = h("p", { class: "field__error", role: "alert" }, AS.icon("error", "icon--sm"), h("span"));
      radios.el.appendChild(err);
      radios.setError = function (m) { radios.el.classList.toggle("field--error", !!m); err.querySelector("span").textContent = m || ""; };
      radios.group = g; return radios;
    });
    groups.forEach(function (g) { form.appendChild(g.el); });
    var qty = 1, n = h("span", { class: "qty__n", "aria-live": "polite", text: "1" });
    var minus = h("button", { type: "button", class: "qty__btn", "aria-label": "Decrease quantity", disabled: true }, AS.icon("minus"));
    var plus = h("button", { type: "button", class: "qty__btn", "aria-label": "Increase quantity" }, AS.icon("plus"));
    function setQty(v) { qty = Math.max(1, Math.min(10, v)); n.textContent = qty; minus.disabled = qty === 1; plus.disabled = qty === 10; }
    minus.addEventListener("click", function () { setQty(qty - 1); }); plus.addEventListener("click", function () { setQty(qty + 1); });
    form.appendChild(h("div", { class: "field" }, h("p", { class: "label", id: "qty-label", text: "Quantity" }), h("div", { class: "qty", role: "group", "aria-labelledby": "qty-label" }, minus, n, plus)));
    var add = h("button", { type: "submit", class: "btn btn--lg btn--block", disabled: soldOut }, soldOut ? "Sold out" : [AS.icon("merchandise-bag", "icon--sm"), "Add to merchandise bag"]);
    form.appendChild(add);
    form.appendChild(h("p", { class: "muted small", text: "Your order is a request until we approve it." }));
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      if (soldOut) { return; }
      var ids = [], labels = [], bad = null;
      groups.forEach(function (g) {
        var v = g.value();
        if (!v) { g.setError("Choose a " + g.group.name.toLowerCase() + "."); if (!bad) { bad = g; } return; }
        g.setError(null);
        ids.push(v);
        g.group.options.forEach(function (o) { if (o.id === v) { labels.push(o.label); } });
      });
      if (bad) { bad.el.querySelector("input").focus(); return; }
      AS.store.bag.add({ productId: p.id, slug: p.slug, name: p.name, optionIds: ids, optionLabels: labels, unitPriceRwf: p.priceRwf, quantity: qty, image: p.mainImage || null });
      AS.openBasketPanel("bag", p.name + " was added to your merchandise bag.");
    });
    return form;
  }

  function render(p) {
    $("#crumb-name").textContent = p.name;
    $("#prd-title").textContent = p.name;
    $("#prd-price").textContent = AS.fmt.rwf(p.priceRwf);
    var imgs = p.images && p.images.length ? p.images : (p.mainImage ? [p.mainImage] : []);
    AS.clear($("#prd-gallery")).appendChild(AS.ui.gallery(imgs, { ratio: "square" }));
    AS.clear($("#prd-stock")).appendChild(h("span", { class: "stock" }, p.availability === "soldOut" ? AS.badge("sold-out", "Sold out") : AS.badge("in-stock", "In stock"), p.isNew ? AS.badge("new", "New") : null));
    AS.clear($("#prd-form")).appendChild(buyForm(p));
    var more = AS.clear($("#prd-more"));
    var sec = function (title, id, kids) { return h("section", { class: "section", "aria-labelledby": id }, h("h2", { id: id, text: title }), kids); };
    if (p.description) { more.appendChild(sec("Description", "prd-desc", h("p", { class: "conditions", text: p.description }))); }
    if (p.details) { more.appendChild(sec("Details", "prd-details", h("p", { class: "conditions", text: p.details }))); }
    more.appendChild(sec("Delivery and payment", "prd-delivery", h("p", { text: "We deliver within Rwanda. Your order is a request until we approve it. After approval you pay by cash or mobile money. The delivery fee is confirmed when we approve." })));
    if (p.relatedServiceIds && p.relatedServiceIds.length) {
      AS.api.get("/services").then(function (res) {
        var rel = (res.items || []).filter(function (s) { return p.relatedServiceIds.indexOf(s.id) >= 0; });
        if (rel.length) { more.appendChild(sec("Goes well with", "prd-related", h("div", { class: "grid grid--cards" }, rel.map(function (s) { return AS.cards.serviceCard(s); })))); }
      }, function () { /* related items are optional */ });
    }
    if (!initial()) {
      AS.setHead({ title: p.name + " | Aura Shots Merchandise" });
      var can = $('link[rel="canonical"]'); if (can) { can.href = location.origin + location.pathname; }
    }
  }

  document.addEventListener("DOMContentLoaded", function () {
    var data = initial();
    if (data) { render(data); return; }
    var slug = decodeURIComponent(location.pathname.split("/")[2] || "");
    AS.api.get("/products/" + encodeURIComponent(slug)).then(render, function (err) {
      $("#prd-title").textContent = err.status === 404 ? "We could not find that item" : "Something went wrong";
      AS.clear($("#prd-gallery")); AS.clear($("#prd-form")).appendChild(h("a", { class: "btn", href: "/shop", text: "Back to the shop" }));
    });
  });
})(window.AS);
