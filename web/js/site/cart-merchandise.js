/*
 * Merchandise bag (P10). Lines live in localStorage; POST /api/quotes/merchandise re-checks stock flags
 * and prices. The quote returns items in request order, which is how lines are matched up.
 */
(function (AS) {
  "use strict";
  var h = AS.h;

  function paint(root, quote) {
    var lines = AS.store.bag.all();
    AS.clear(root);
    if (!lines.length) { root.appendChild(AS.ui.empty("Your merchandise bag is empty", "Browse clothing, prints and framed art.", "/shop", "Visit the shop")); return; }
    var blocked = false;
    var list = h("ul", { class: "lines" }, lines.map(function (l, i) {
      var q = quote && quote.items[i], bad = q && !q.available, key = AS.store.bag.key(l);
      if (bad) { blocked = true; }
      var n = h("span", { class: "qty__n", "aria-live": "polite", text: String(l.quantity) });
      return h("li", { class: "line" + (bad ? " line--bad" : "") },
        h("div", { class: "line__img" }, AS.img(l.image, { ratio: "square", size: "thumb" })),
        h("div", { class: "line__body" },
          h("p", { class: "line__title", text: l.name }),
          l.optionLabels && l.optionLabels.length ? h("p", { class: "muted small", text: l.optionLabels.join(", ") }) : null,
          bad ? h("p", { class: "line__flag" }, AS.icon("warning", "icon--sm"), "This item is sold out or no longer available. Please remove it.") : null,
          h("div", { class: "line__row" },
            h("span", { class: "qty", role: "group", "aria-label": "Quantity of " + l.name },
              h("button", { type: "button", class: "qty__btn", "aria-label": "Decrease quantity", disabled: l.quantity <= 1, onclick: function () { AS.store.bag.setQty(key, l.quantity - 1); refresh(root); } }, AS.icon("minus")),
              n,
              h("button", { type: "button", class: "qty__btn", "aria-label": "Increase quantity", disabled: l.quantity >= 10, onclick: function () { AS.store.bag.setQty(key, l.quantity + 1); refresh(root); } }, AS.icon("plus"))),
            h("span", { class: "price", text: AS.fmt.rwf((q ? q.unitPriceRwf : l.unitPriceRwf) * l.quantity) }),
            h("button", { type: "button", class: "btn btn--secondary", onclick: function () { AS.store.bag.remove(key); refresh(root); } }, AS.icon("remove", "icon--sm"), "Remove", h("span", { class: "sr-only", text: " " + l.name })))));
    }));
    root.appendChild(list);
    var sub = quote ? quote.subtotalRwf : lines.reduce(function (n, l) { return n + l.unitPriceRwf * l.quantity; }, 0);
    root.appendChild(h("div", { class: "summary" },
      h("p", { class: "row row--between" }, h("span", { text: "Subtotal" }), h("span", { class: "price", text: AS.fmt.rwf(sub) })),
      h("p", { class: "muted small", text: "Delivery fee confirmed on approval. Your session request is separate." })));
    if (!quote) { root.appendChild(AS.alertBox("warning", "We could not re-check prices just now. They will be checked again at checkout.")); }
    if (blocked) { root.appendChild(AS.alertBox("warning", "Remove the unavailable items to continue.")); }
    root.appendChild(h("div", { class: "row" },
      h("a", { class: "btn btn--secondary", href: "/shop", text: "Continue browsing" }),
      blocked ? h("button", { type: "button", class: "btn", disabled: true, text: "Checkout" }) : h("a", { class: "btn btn--lg", href: "/shop/checkout" }, "Checkout", AS.icon("arrow-right", "icon--sm"))));
  }

  function refresh(root) {
    var lines = AS.store.bag.all();
    if (!lines.length) { paint(root, null); return; }
    AS.api.post("/quotes/merchandise", { items: lines.slice(0, 20).map(function (l) { return { productId: l.productId, optionIds: l.optionIds, quantity: l.quantity }; }) })
      .then(function (q) { paint(root, q); }, function () { paint(root, null); });
  }

  document.addEventListener("DOMContentLoaded", function () { var root = AS.$("#bag-root"); paint(root, null); refresh(root); });
})(window.AS);
