/* Services list (P2). */
(function (AS) {
  "use strict";
  document.addEventListener("DOMContentLoaded", function () {
    var box = AS.$("#service-list");
    AS.cards.loadInto(box, function () { return AS.api.get("/services"); }, function (res) {
      var items = res.items || [];
      if (!items.length) { box.appendChild(AS.ui.empty("No packages are available right now", "Please get in touch and we will help you plan your session.", "/contact", "Contact us")); return; }
      box.appendChild(AS.h("div", { class: "grid grid--cards" }, items.map(function (s) { return AS.cards.serviceCard(s); })));
    });
  });
})(window.AS);
