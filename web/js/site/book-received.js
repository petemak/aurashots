/*
 * Request received (P9). Reads the reference from the path and the access token from the query string
 * (kept in the URL so a refresh works): GET /api/booking-requests/{reference}?token=...
 */
(function (AS) {
  "use strict";
  var h = AS.h;
  var STATUS = { pending: "Pending: we will confirm shortly", confirmed: "Confirmed", declined: "Declined", completed: "Completed" };

  function render(root, r, info) {
    var slot = r.status === "confirmed" || r.status === "completed" ? r.confirmedSlot || r.preferredSlot : null;
    AS.clear(root).appendChild(h("h1", { text: "Request received" }));
    root.appendChild(h("p", { class: "lead", text: "Thank you. We have your session request." }));
    root.appendChild(h("p", {}, "Your reference", h("br"), h("span", { class: "ref", text: r.reference })));
    root.appendChild(AS.alertBox(r.status === "declined" ? "warning" : "success", h("p", {}, h("strong", { text: "Status: " }), STATUS[r.status] || r.status)));
    if (slot) { root.appendChild(h("p", { class: "chosen-slot", text: "Your session: " + AS.fmt.dateLong(slot.date) + " at " + slot.startTime })); }
    if (r.customerNote) { root.appendChild(h("p", { class: "muted", text: "Note from us: " + r.customerNote })); }
    root.appendChild(h("div", { class: "summary" },
      h("h2", { class: "card__title", text: "What you asked for" }),
      h("ul", { class: "summary-list" }, (r.services || []).map(function (s) { return h("li", {}, h("span", { text: s.name }), h("span", { class: "price", text: AS.fmt.rwf(s.priceRwf) })); }), h("li", { class: "total" }, h("span", { text: "Total" }), h("span", { class: "price", text: AS.fmt.rwf(r.totalRwf) }))),
      h("p", { text: "Requested time: " + AS.fmt.dateLong(r.preferredSlot.date) + " at " + r.preferredSlot.startTime }),
      r.agreementAcceptance ? h("p", { class: "muted small", text: "You accepted the Session Booking Agreement, version " + r.agreementAcceptance.version + ", on " + AS.fmt.instant(r.agreementAcceptance.acceptedAt) + "." }) : null));
    root.appendChild(h("h2", { class: "card__title", text: "What happens next" }));
    root.appendChild(h("p", { text: "We will reply by " + (r.contactPreference === "whatsapp" ? "WhatsApp" : "email") + " to confirm your time. You pay by cash or mobile money after confirmation. Nothing has been charged." }));
    var wa = info && info.whatsapp ? "https://wa.me/" + info.whatsapp.replace(/[^0-9]/g, "") : null;
    root.appendChild(h("div", { class: "row", role: "group", "aria-label": "Contact and navigation" }, wa ? h("a", { class: "btn btn--secondary", href: wa }, AS.icon("whatsapp", "icon--sm"), "WhatsApp us") : null,
      info && info.email && info.email.indexOf("@") > 0 ? h("a", { class: "btn btn--secondary", href: "mailto:" + info.email }, AS.icon("email", "icon--sm"), "Email us") : null,
      h("a", { class: "btn", href: "/", text: "Back to home" })));
  }

  document.addEventListener("DOMContentLoaded", function () {
    var root = AS.$("#received-root"), ref = decodeURIComponent(location.pathname.split("/")[3] || ""), token = new URLSearchParams(location.search).get("token");
    if (!token) { AS.clear(root).appendChild(h("h1", { text: "Request received" })); root.appendChild(AS.alertBox("info", "Open this page from the link we sent you to see your request details. Your reference is " + ref + ".")); return; }
    Promise.all([AS.api.get("/booking-requests/" + encodeURIComponent(ref), { token: token }), AS.siteInfo()]).then(function (res) { render(root, res[0], res[1]); }, function (err) {
      AS.clear(root).appendChild(h("h1", { text: "Request received" }));
      root.appendChild(err.status === 404 ? AS.alertBox("warning", "We could not find that request. Please check the link in your confirmation email, or contact us with your reference.") : AS.ui.errorBox(err, function () { location.reload(); }));
    });
  });
})(window.AS);
