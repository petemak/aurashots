/*
 * Public site chrome: marks the current nav item, mobile menu, header basket counts and the
 * two-section basket panel (design-spec Part 3), and fills the footer from GET /api/site-info.
 */
(function (AS) {
  "use strict";
  var h = AS.h, $ = AS.$;
  var infoPromise = null;

  AS.siteInfo = function () {
    if (!infoPromise) { infoPromise = AS.api.get("/site-info").catch(function () { return null; }); }
    return infoPromise;
  };

  function setCount(id, n, label) {
    var el = $("#" + id);
    if (!el) { return; }
    el.textContent = n; el.hidden = n === 0;
    var btn = el.closest("button");
    if (btn) { btn.setAttribute("aria-label", label + (n ? ", " + n + (n === 1 ? " item" : " items") : ", empty")); }
  }

  var panel, openFrom = null;
  function closePanel(returnFocus) {
    if (!panel || panel.hidden) { return; }
    panel.hidden = true;
    ["toggle-sessions", "toggle-bag"].forEach(function (id) { var b = $("#" + id); if (b) { b.setAttribute("aria-expanded", "false"); } });
    if (returnFocus && openFrom) { openFrom.focus(); }
  }

  function renderPanel(message) {
    var s = AS.store.sessions.all(), b = AS.store.bag.all();
    AS.clear(panel);
    panel.appendChild(h("h2", { text: "Your baskets" }));
    if (message) { panel.appendChild(AS.alertBox("success", message)); }
    panel.appendChild(h("section", { "aria-label": "Session basket" },
      h("p", { class: "label", text: "Session basket (" + s.length + ")" }),
      s.length ? h("ul", { class: "small" }, s.slice(0, 4).map(function (x) { return h("li", { text: x.name }); })) : h("p", { class: "muted small", text: "No sessions yet." }),
      h("a", { class: "btn btn--secondary btn--block", href: "/cart/sessions", text: "View session basket" })));
    panel.appendChild(h("section", { "aria-label": "Merchandise bag" },
      h("p", { class: "label", text: "Merchandise bag (" + AS.store.bag.count() + ")" }),
      b.length ? h("ul", { class: "small" }, b.slice(0, 4).map(function (x) { return h("li", { text: x.quantity + " x " + x.name }); })) : h("p", { class: "muted small", text: "Nothing in your bag." }),
      h("a", { class: "btn btn--secondary btn--block", href: "/cart/merchandise", text: "View merchandise bag" })));
    panel.appendChild(h("p", { class: "muted small", text: "Sessions and merchandise are requested separately." }));
    if (message) {
      panel.appendChild(h("button", { type: "button", class: "btn btn--text", text: "Keep browsing", onclick: function () { closePanel(true); } }));
    }
  }

  function openPanel(which, message) {
    openFrom = $(which === "bag" ? "#toggle-bag" : "#toggle-sessions");
    renderPanel(message);
    panel.hidden = false;
    ["toggle-sessions", "toggle-bag"].forEach(function (id) { var bt = $("#" + id); if (bt) { bt.setAttribute("aria-expanded", "true"); } });
    var first = panel.querySelector("a, button");
    if (first && message) { first.focus(); }
  }
  AS.openBasketPanel = openPanel;

  function updateCounts() {
    setCount("count-sessions", AS.store.sessions.all().length, "Session basket");
    setCount("count-bag", AS.store.bag.count(), "Merchandise bag");
    if (panel && !panel.hidden) { renderPanel(); }
  }

  function initHeader() {
    var path = location.pathname.replace(/\/$/, "") || "/";
    AS.$$("#site-nav a").forEach(function (a) {
      var p = a.getAttribute("href");
      if (p === path || (p !== "/" && path.indexOf(p + "/") === 0)) { a.setAttribute("aria-current", "page"); }
    });
    var menu = $("#menu-toggle"), nav = $("#site-nav");
    if (menu && nav) {
      menu.addEventListener("click", function () {
        var open = nav.classList.toggle("is-open");
        menu.setAttribute("aria-expanded", open ? "true" : "false");
      });
    }
    panel = $("#basket-panel");
    ["sessions", "bag"].forEach(function (w) {
      var btn = $("#toggle-" + w);
      if (btn) {
        btn.addEventListener("click", function () { if (!panel.hidden && openFrom === btn) { closePanel(true); } else { openPanel(w); } });
      }
    });
    document.addEventListener("keydown", function (e) { if (e.key === "Escape") { closePanel(true); } });
    document.addEventListener("click", function (e) { if (panel && !panel.hidden && !e.target.closest(".site-header")) { closePanel(false); } });
    window.addEventListener("as:baskets", updateCounts);
    updateCounts();
  }

  function digits(s) { return (s || "").replace(/[^0-9]/g, ""); }

  function fillFooter(info) {
    if (!info) { return; }
    var set = function (id, text) { var el = $("#" + id); if (el && text) { el.textContent = text; } };
    set("footer-address", info.address);
    if (info.phone) { var p = $("#footer-phone"); if (p) { p.textContent = info.phone; p.href = "tel:+" + digits(info.phone); } }
    if (info.whatsapp) { var w = $("#footer-whatsapp"); if (w) { w.textContent = "WhatsApp " + info.phone; w.href = "https://wa.me/" + digits(info.whatsapp); } }
    var em = $("#footer-email");
    if (em && info.email) {
      if (info.email.indexOf("@") > 0) { AS.clear(em); em.appendChild(h("a", { href: "mailto:" + info.email, text: info.email })); } else { em.textContent = info.email; }
    }
    var hours = $("#footer-hours");
    if (hours && info.openingHours) {
      AS.clear(hours);
      AS.fmt.hours(info.openingHours).forEach(function (r) { hours.appendChild(h("li", {}, h("span", { text: r.days }), h("span", { text: r.range }))); });
    }
    var soc = $("#footer-social");
    if (soc && info.social) {
      AS.clear(soc);
      [["instagram", "Instagram"], ["tiktok", "TikTok"], ["facebook", "Facebook"]].forEach(function (s) {
        if (info.social[s[0]]) {
          soc.appendChild(h("li", {}, h("a", { class: "icon-btn", href: info.social[s[0]], rel: "noopener", "aria-label": s[1] + " (opens in a new tab)", target: "_blank" }, AS.icon(s[0]))));
        }
      });
    }
  }

  document.addEventListener("DOMContentLoaded", function () {
    if (!$(".site-header")) { return; }
    initHeader();
    AS.siteInfo().then(fillFooter);
  });
})(window.AS = window.AS || {});
