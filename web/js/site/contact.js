/* Contact (P14): fills details from GET /api/site-info and posts the enquiry form (POST /api/enquiries). */
(function (AS) {
  "use strict";
  var h = AS.h, F = AS.forms, $ = AS.$;

  function fill(info) {
    if (!info) { return; }
    $("#c-address").textContent = info.address;
    var p = $("#c-phone"); p.textContent = info.phone; p.href = "tel:+" + info.phone.replace(/[^0-9]/g, "");
    $("#c-whatsapp").href = "https://wa.me/" + (info.whatsapp || "").replace(/[^0-9]/g, "");
    var em = $("#c-email"); AS.clear(em);
    if (info.email && info.email.indexOf("@") > 0) { em.appendChild(h("a", { href: "mailto:" + info.email, text: info.email })); } else { em.textContent = info.email || "OWNER_EMAIL"; }
    var body = AS.clear($("#c-hours"));
    AS.fmt.hours(info.openingHours).forEach(function (r) { body.appendChild(h("tr", {}, h("th", { scope: "row", text: r.days }), h("td", { text: r.range }))); });
    var soc = AS.clear($("#c-social")), s = info.social || {};
    [["instagram", "Instagram"], ["tiktok", "TikTok"], ["facebook", "Facebook"]].forEach(function (x) {
      if (s[x[0]]) { soc.appendChild(h("li", {}, AS.icon(x[0]), h("a", { href: s[x[0]], rel: "noopener", target: "_blank", text: x[1] + " (opens in a new tab)" }))); }
      else { soc.appendChild(h("li", {}, AS.icon(x[0]), h("span", { class: "muted", text: x[1] + ": <TBD>" }))); }
    });
  }

  function form() {
    var root = $("#enquiry-root");
    var f = {
      name: F.field({ id: "e-name", name: "name", label: "Your name", required: true, autocomplete: "name", maxlength: 100 }),
      email: F.field({ id: "e-email", name: "email", label: "Email", required: true, type: "email", autocomplete: "email", maxlength: 254 }),
      phone: F.field({ id: "e-phone", name: "phone", label: "Phone", type: "tel", autocomplete: "tel", inputmode: "tel", maxlength: 20 }),
      message: F.field({ id: "e-message", name: "message", label: "Message", required: true, rows: 6, maxlength: 2000, counter: true })
    };
    var hp = h("div", { class: "hp", "aria-hidden": "true" }, h("label", { for: "e-website", text: "Leave this field empty" }), h("input", { type: "text", id: "e-website", tabindex: "-1", autocomplete: "off" }));
    var status = h("div", { "aria-live": "polite" }), submitting = false, key = null, last = null;
    var btn = h("button", { type: "submit", class: "btn btn--lg" }, "Send message");
    var el = h("form", { novalidate: true }, f.name.el, f.email.el, f.phone.el, f.message.el, hp, status, btn, h("p", { class: "muted small", text: "We reply by email or WhatsApp." }));
    el.addEventListener("submit", function (e) {
      e.preventDefault();
      if (submitting) { return; }
      var phone = f.phone.value();
      if (!F.validate([{ field: f.name, check: F.V.name }, { field: f.email, check: F.V.email },
        { field: f.phone, check: function (v) { return !v || /^\+?[0-9 ]{7,20}$/.test(v) ? null : "Enter a phone number with country code, or leave it empty."; } },
        { field: f.message, check: function (v) { return v.length >= 10 && v.length <= 2000 ? null : "Write a message of at least 10 characters."; } }])) { return; }
      var body = { name: f.name.value(), email: f.email.value(), phone: phone || null, message: f.message.value(), website: $("#e-website").value || "" };
      var json = JSON.stringify(body);
      if (json !== last) { last = json; key = AS.api.uuid(); } // same payload on retry keeps the key, so no duplicate enquiry
      submitting = true; btn.disabled = true; btn.setAttribute("aria-busy", "true"); AS.clear(status);
      AS.api.post("/enquiries", body, { idempotencyKey: key }).then(function () {
        AS.clear(root).appendChild(AS.alertBox("success", h("div", {}, h("p", {}, h("strong", { text: "Thank you, your message is on its way." })), h("p", { text: "We will reply by email or WhatsApp." }))));
        $("#enquiry-root .alert").setAttribute("tabindex", "-1"); $("#enquiry-root .alert").focus();
      }, function (err) {
        submitting = false; btn.disabled = false; btn.setAttribute("aria-busy", "false");
        if (err.code === "validationFailed" && err.fields && F.applyServerErrors(err, f)) { return; }
        status.appendChild(AS.alertBox("error", err.code === "rateLimited" ? "You have sent several messages in a short time. Please wait a while, or message us on WhatsApp." : err.message));
      });
    });
    root.appendChild(el);
  }

  document.addEventListener("DOMContentLoaded", function () { AS.siteInfo().then(fill); form(); });
})(window.AS);
