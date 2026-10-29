/*
 * Order request received (P12): GET /api/merchandise-orders/{reference}?token=...
 */
(function (AS) {
  "use strict";
  var h = AS.h;
  var STATUS = { pending: "Awaiting approval", approved: "Approved", rejected: "Rejected", paid: "Paid", delivered: "Delivered" };

  function render(root, o, info) {
    AS.clear(root).appendChild(h("h1", { text: "Order request received" }));
    root.appendChild(h("p", { class: "lead", text: "Thank you. We have your order request." }));
    root.appendChild(h("p", {}, "Your reference", h("br"), h("span", { class: "ref", text: o.reference })));
    root.appendChild(AS.alertBox(o.status === "rejected" ? "warning" : "success", h("p", {}, h("strong", { text: "Status: " }), STATUS[o.status] || o.status)));
    if (o.customerNote) { root.appendChild(h("p", { class: "muted", text: "Note from us: " + o.customerNote })); }
    var d = o.delivery || {}, area = d.areaText || ((info && info.deliveryAreas || []).filter(function (a) { return a.id === d.areaId; })[0] || {}).name;
    root.appendChild(h("div", { class: "summary" },
      h("h2", { class: "card__title", text: "Your items" }),
      h("ul", { class: "summary-list" }, (o.items || []).map(function (i) {
        return h("li", {}, h("span", { text: i.quantity + " x " + i.name + (i.optionLabels && i.optionLabels.length ? " (" + i.optionLabels.join(", ") + ")" : "") }), h("span", { class: "price", text: AS.fmt.rwf(i.lineTotalRwf) }));
      }),
        h("li", {}, h("span", { text: "Subtotal" }), h("span", { class: "price", text: AS.fmt.rwf(o.subtotalRwf) })),
        h("li", {}, h("span", { text: "Delivery" }), h("span", { class: "price", text: o.deliveryFeeRwf === null || o.deliveryFeeRwf === undefined ? "To be confirmed" : AS.fmt.rwf(o.deliveryFeeRwf) })),
        h("li", { class: "total" }, h("span", { text: "Total" }), h("span", { class: "price", text: o.totalRwf === null || o.totalRwf === undefined ? "To be confirmed" : AS.fmt.rwf(o.totalRwf) }))),
      h("p", {}, h("strong", { text: "Delivery to: " }), [d.address, area, d.landmark].filter(Boolean).join(", ")),
      o.agreementAcceptance ? h("p", { class: "muted small", text: "You accepted the Merchandise Order Terms, version " + o.agreementAcceptance.version + ", on " + AS.fmt.instant(o.agreementAcceptance.acceptedAt) + "." }) : null));
    root.appendChild(h("h2", { class: "card__title", text: "What happens next" }));
    root.appendChild(h("p", { text: "We will review your order and contact you. After approval you pay by cash or mobile money, then we deliver. Nothing has been charged." }));
    var wa = info && info.whatsapp ? "https://wa.me/" + info.whatsapp.replace(/[^0-9]/g, "") : null;
    root.appendChild(h("div", { class: "row" }, wa ? h("a", { class: "btn btn--secondary", href: wa }, AS.icon("whatsapp", "icon--sm"), "WhatsApp us") : null, h("a", { class: "btn", href: "/", text: "Back to home" })));
  }

  document.addEventListener("DOMContentLoaded", function () {
    var root = AS.$("#received-root"), ref = decodeURIComponent(location.pathname.split("/")[3] || ""), token = new URLSearchParams(location.search).get("token");
    if (!token) { AS.clear(root).appendChild(h("h1", { text: "Order request received" })); root.appendChild(AS.alertBox("info", "Open this page from the link we sent you to see your order details. Your reference is " + ref + ".")); return; }
    Promise.all([AS.api.get("/merchandise-orders/" + encodeURIComponent(ref), { token: token }), AS.siteInfo()]).then(function (res) { render(root, res[0], res[1]); }, function (err) {
      AS.clear(root).appendChild(h("h1", { text: "Order request received" }));
      root.appendChild(err.status === 404 ? AS.alertBox("warning", "We could not find that order. Please check the link in your confirmation email, or contact us with your reference.") : AS.ui.errorBox(err, function () { location.reload(); }));
    });
  });
})(window.AS);
