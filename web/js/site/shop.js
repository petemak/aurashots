/* Merchandise list (P5): category chips, sort, product grid with Load more, delivery note. */
(function (AS) {
  "use strict";
  var h = AS.h, $ = AS.$;
  var CATS = [{ id: null, name: "All" }, { id: "clothing", name: "Clothing" }, { id: "prints", name: "Prints" }, { id: "framed-art", name: "Framed art" }];
  var state = { category: null, sort: "newest", page: 1, items: [], total: 0 }, PAGE = 24;

  function chips() {
    var box = AS.clear($("#shop-filters"));
    CATS.forEach(function (c) {
      box.appendChild(h("button", { type: "button", class: "chip", "aria-pressed": state.category === c.id ? "true" : "false", text: c.name, onclick: function () { state.category = c.id; reset(); chips(); } }));
    });
  }
  function reset() { state.page = 1; state.items = []; load(); }

  function paint() {
    var box = AS.clear($("#product-list"));
    if (!state.items.length) { box.appendChild(AS.ui.empty("Nothing here yet", "Try another category or come back soon.", "/shop", "See everything")); return; }
    box.appendChild(h("div", { class: "grid grid--cards grid--4" }, state.items.map(AS.cards.productCard)));
    if (state.items.length < state.total) { box.appendChild(h("p", { class: "center" }, h("button", { type: "button", class: "btn btn--secondary", text: "Load more", onclick: function () { state.page++; load(true); } }))); }
  }

  function load(append) {
    var box = $("#product-list");
    if (!append) { AS.clear(box).appendChild(AS.ui.skeleton(3)); }
    AS.api.get("/products", { category: state.category, sort: state.sort, page: state.page, pageSize: PAGE }).then(function (res) {
      state.items = state.items.concat(res.items || []); state.total = res.total || state.items.length; paint();
    }, function (err) { AS.clear(box).appendChild(AS.ui.errorBox(err, function () { load(append); })); });
  }

  document.addEventListener("DOMContentLoaded", function () {
    chips();
    $("#shop-sort").addEventListener("change", function (e) { state.sort = e.target.value; reset(); });
    load();
    AS.siteInfo().then(function (info) {
      var el = $("#delivery-note"), areas = info && info.deliveryAreas;
      if (el && areas && areas.length) {
        el.textContent = "Delivery areas and fees: " + areas.map(function (a) { return a.name + " (" + (a.feeRwf === null || a.feeRwf === undefined ? "<TBD>" : AS.fmt.rwf(a.feeRwf)) + ")"; }).join(", ");
      }
    });
  });
})(window.AS);
