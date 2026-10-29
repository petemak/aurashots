/*
 * Session basket (P7). The basket lives in localStorage; POST /api/quotes/sessions re-checks prices
 * and availability. Unavailable packages are flagged and block "Choose date and time" until removed.
 */
(function (AS) {
  "use strict";
  var h = AS.h, REASONS = { unpublished: "No longer available to book.", noPrice: "Temporarily unavailable: no price set.", removed: "No longer offered." };

  function paint(root, quote) {
    var stored = AS.store.sessions.all();
    AS.clear(root);
    if (!stored.length) {
      root.appendChild(AS.ui.empty("Your session basket is empty", "Choose a package to start your request.", "/services", "See packages"));
      return;
    }
    var byId = {};
    if (quote) { quote.items.forEach(function (q) { byId[q.serviceId] = q; }); }
    var blocked = false;
    var list = h("ul", { class: "lines" }, stored.map(function (s) {
      var q = byId[s.id], bad = q && !q.available;
      if (bad) { blocked = true; }
      return h("li", { class: "line" + (bad ? " line--bad" : "") },
        h("div", { class: "line__img" }, AS.img(q && q.mainImage || s.image, { ratio: "square", size: "thumb" })),
        h("div", { class: "line__body" },
          h("p", { class: "line__title", text: (q && q.name) || s.name }),
          bad ? h("p", { class: "line__flag" }, AS.icon("warning", "icon--sm"), (REASONS[q.reason] || "No longer available.") + " Please remove it.") : null,
          h("div", { class: "line__row" },
            h("span", { class: "price", text: AS.fmt.rwf(q ? q.priceRwf : s.priceRwf) }),
            h("button", { type: "button", class: "btn btn--secondary", onclick: function () { AS.store.sessions.remove(s.id); refresh(root); } }, AS.icon("remove", "icon--sm"), "Remove", h("span", { class: "sr-only", text: " " + s.name })))));
    }));
    var total = quote ? quote.totalRwf : stored.reduce(function (n, s) { return n + (s.priceRwf || 0); }, 0);
    root.appendChild(list);
    root.appendChild(h("div", { class: "summary" },
      h("p", { class: "row row--between" }, h("span", { text: "Total" }), h("span", { class: "price", text: AS.fmt.rwf(total) })),
      h("p", { class: "muted small", text: "Payment after confirmation, by cash or mobile money." })));
    if (!quote) { root.appendChild(AS.alertBox("warning", "We could not re-check prices just now. They will be checked again when you send your request.")); }
    if (blocked) { root.appendChild(AS.alertBox("warning", "Remove the unavailable package to continue.")); }
    root.appendChild(h("div", { class: "row" },
      h("a", { class: "btn btn--secondary", href: "/services", text: "Continue browsing" }),
      blocked ? h("button", { type: "button", class: "btn", disabled: true, text: "Choose date and time" }) : h("a", { class: "btn btn--lg", href: "/book" }, "Choose date and time", AS.icon("arrow-right", "icon--sm"))));
  }

  function refresh(root) {
    var ids = AS.store.sessions.all().map(function (s) { return s.id; });
    if (!ids.length) { paint(root, null); return; }
    AS.api.post("/quotes/sessions", { serviceIds: ids.slice(0, 10) }).then(function (q) { paint(root, q); }, function () { paint(root, null); });
  }

  document.addEventListener("DOMContentLoaded", function () {
    var root = AS.$("#basket-root");
    paint(root, null);
    refresh(root);
  });
})(window.AS);
