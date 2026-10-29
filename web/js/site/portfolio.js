/* Portfolio (P4): category chips, masonry grid with Load more, and the lightbox. */
(function (AS) {
  "use strict";
  var h = AS.h, $ = AS.$;
  var state = { category: null, page: 1, items: [], total: 0 }, PAGE = 24;

  function chips(cats) {
    var box = $("#portfolio-filters");
    function paint() {
      AS.clear(box);
      [{ id: null, name: "All" }].concat(cats).forEach(function (c) {
        box.appendChild(h("button", { type: "button", class: "chip", "aria-pressed": state.category === c.id ? "true" : "false", text: c.name, onclick: function () { state.category = c.id; state.page = 1; state.items = []; paint(); load(); } }));
      });
    }
    paint();
  }

  function paintGrid() {
    var wrap = $("#portfolio-grid-wrap");
    AS.clear(wrap);
    if (!state.items.length) {
      wrap.appendChild(AS.ui.empty("Nothing in this category yet", "New work is added regularly.", "/portfolio", "See all photos"));
      return;
    }
    var lb = AS.cards.lightboxItems(state.items);
    wrap.appendChild(h("div", { class: "masonry" }, state.items.map(function (it, i) { return AS.cards.tile(it, function (opener) { AS.ui.lightbox(lb, i, opener); }); })));
    if (state.items.length < state.total) {
      wrap.appendChild(h("p", { class: "center" }, h("button", { type: "button", class: "btn btn--secondary", text: "Load more", onclick: function () { state.page++; load(true); } })));
    }
    wrap.appendChild(h("p", { class: "sr-only", role: "status", text: "Showing " + state.items.length + " of " + state.total + " photos" }));
  }

  function load(append) {
    var wrap = $("#portfolio-grid-wrap");
    if (!append) { AS.clear(wrap).appendChild(AS.ui.skeleton(3)); }
    AS.api.get("/portfolio", { category: state.category, page: state.page, pageSize: PAGE }).then(function (res) {
      state.items = state.items.concat(res.items || []); state.total = res.total || state.items.length; paintGrid();
    }, function (err) { AS.clear(wrap).appendChild(AS.ui.errorBox(err, function () { load(append); })); });
  }

  document.addEventListener("DOMContentLoaded", function () {
    AS.siteInfo().then(function (info) { chips((info && info.portfolioCategories) || []); load(); });
  });
})(window.AS);
