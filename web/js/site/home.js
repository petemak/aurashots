/* Home page (P1): featured services, portfolio strip and hero image, merchandise teaser, visit block. */
(function (AS) {
  "use strict";
  var h = AS.h, $ = AS.$;

  function services() {
    var box = $("#featured-services");
    AS.cards.loadInto(box, function () { return AS.api.get("/services"); }, function (res) {
      var all = res.items || [], feat = all.filter(function (s) { return s.featured; });
      var list = (feat.length ? feat : all).slice(0, 3);
      if (!list.length) { box.appendChild(AS.ui.empty("Packages are coming soon", "Message us and we will help you plan your session.", "/contact", "Contact us")); return; }
      box.appendChild(h("div", { class: "grid grid--cards" }, list.map(function (s) { return AS.cards.serviceCard(s, { from: true }); })));
    });
  }

  function portfolio() {
    var strip = $("#portfolio-strip");
    AS.api.get("/portfolio", { featured: true, pageSize: 8 }).then(function (res) {
      var items = res.items || [];
      if (!items.length) { strip.parentNode.parentNode.hidden = true; return; }
      var hero = $("#hero-img"), first = items[0].image;
      if (first && first.id) {
        hero.src = AS.media(first.id, "large");
        hero.alt = items[0].description || first.alt || "";
        hero.addEventListener("error", function () { hero.src = "/assets/placeholders/placeholder-landscape-16x9.svg"; }, { once: true });
      }
      var lb = AS.cards.lightboxItems(items);
      items.forEach(function (it, i) { strip.appendChild(AS.cards.tile(it, function (opener) { AS.ui.lightbox(lb, i, opener); })); });
    }, function () { strip.parentNode.parentNode.hidden = true; });
  }

  function products() {
    var box = $("#featured-products");
    AS.api.get("/products", { featured: true, pageSize: 3 }).then(function (res) {
      var items = res.items || [];
      if (!items.length) { box.appendChild(h("p", { class: "muted" }, "New pieces are on their way. ", h("a", { href: "/shop", text: "Visit the shop" }), ".")); return; }
      box.appendChild(h("div", { class: "grid grid--cards" }, items.map(AS.cards.productCard)));
    }, function (err) { box.appendChild(AS.ui.errorBox(err, function () { AS.clear(box); products(); })); });
  }

  function visit() {
    AS.siteInfo().then(function (info) {
      if (!info) { return; }
      $("#visit-address").textContent = info.address;
      var wa = $("#visit-whatsapp"); wa.href = "https://wa.me/" + (info.whatsapp || "").replace(/[^0-9]/g, "");
      var body = $("#visit-hours"); AS.clear(body);
      AS.fmt.hours(info.openingHours).forEach(function (r) { body.appendChild(h("tr", {}, h("th", { scope: "row", text: r.days }), h("td", { text: r.range }))); });
    });
  }

  document.addEventListener("DOMContentLoaded", function () { services(); portfolio(); products(); visit(); });
})(window.AS);
