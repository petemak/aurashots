/*
 * Merchandise checkout (P11): details, delivery, order summary (POST /api/quotes/merchandise),
 * payment note, then the Merchandise Order Terms and POST /api/merchandise-orders.
 */
(function (AS) {
  "use strict";
  var h = AS.h, F = AS.forms;
  var KEY = "as.orderDraft";
  var lines, info, f = {}, agreement, submitting = false, areaSel, quoteBox, areaText, otherWrap;

  function items() { return lines.map(function (l) { return { productId: l.productId, optionIds: l.optionIds, quantity: l.quantity }; }); }

  function quote() {
    var areaId = areaSel && areaSel.value && areaSel.value !== "__other" ? areaSel.value : null;
    AS.api.post("/quotes/merchandise", { items: items(), deliveryAreaId: areaId || undefined }).then(function (q) {
      AS.clear(quoteBox);
      var bad = q.items.some(function (i) { return !i.available; });
      quoteBox.appendChild(h("ul", { class: "summary-list" },
        q.items.map(function (i) { return h("li", {}, h("span", { text: i.quantity + " x " + i.name + (i.optionLabels && i.optionLabels.length ? " (" + i.optionLabels.join(", ") + ")" : "") + (i.available ? "" : " (unavailable)") }), h("span", { class: "price", text: AS.fmt.rwf(i.lineTotalRwf) })); }),
        h("li", {}, h("span", { text: "Subtotal" }), h("span", { class: "price", text: AS.fmt.rwf(q.subtotalRwf) })),
        h("li", {}, h("span", { text: "Delivery" }), h("span", { class: "price", text: q.deliveryFeeRwf === null || q.deliveryFeeRwf === undefined ? "To be confirmed" : AS.fmt.rwf(q.deliveryFeeRwf) })),
        h("li", { class: "total" }, h("span", { text: "Total" }), h("span", { class: "price", text: q.totalRwf === null || q.totalRwf === undefined ? "To be confirmed" : AS.fmt.rwf(q.totalRwf) }))));
      if (bad) { quoteBox.appendChild(AS.alertBox("warning", h("p", {}, "Some items are no longer available. ", h("a", { href: "/cart/merchandise", text: "Review your bag" }), "."))); }
    }, function () {
      AS.clear(quoteBox).appendChild(h("ul", { class: "summary-list" }, lines.map(function (l) { return h("li", {}, h("span", { text: l.quantity + " x " + l.name }), h("span", { class: "price", text: AS.fmt.rwf(l.unitPriceRwf * l.quantity) })); })));
    });
  }

  function build(root) {
    var d = AS.store.orderDraft.get();
    f = {
      name: F.field({ id: "c-name", name: "name", label: "Full name", required: true, value: d.name, autocomplete: "name", maxlength: 100 }),
      phone: F.field({ id: "c-phone", name: "phone", label: "Phone with country code", required: true, type: "tel", value: d.phone, autocomplete: "tel", inputmode: "tel", maxlength: 20 }),
      email: F.field({ id: "c-email", name: "email", label: "Email", required: true, type: "email", value: d.email, autocomplete: "email", maxlength: 254 }),
      address: F.field({ id: "c-address", name: "address", label: "Street address or description", required: true, value: d.address, maxlength: 300, autocomplete: "street-address" }),
      landmark: F.field({ id: "c-landmark", name: "landmark", label: "Landmark", value: d.landmark, maxlength: 100 }),
      notes: F.field({ id: "c-notes", name: "notes", label: "Delivery notes", rows: 3, value: d.notes, maxlength: 500, counter: true })
    };
    var areas = (info && info.deliveryAreas) || [];
    f.areaText = F.field({ id: "c-area-text", name: "areaText", label: areas.length ? "Your district or area" : "District", required: true, value: d.areaText, maxlength: 100 });
    var areaField = null;
    if (areas.length) {
      areaSel = h("select", { class: "select", id: "c-area", "aria-describedby": "c-area-hint" },
        h("option", { value: "", text: "Choose your area" }),
        areas.map(function (a) { return h("option", { value: a.id, text: a.name + " (" + (a.feeRwf === null || a.feeRwf === undefined ? "fee to be confirmed" : AS.fmt.rwf(a.feeRwf)) + ")" }); }),
        h("option", { value: "__other", text: "Another area (type it below)" }));
      areaSel.value = d.areaId || "";
      areaField = h("div", { class: "field" }, h("label", { for: "c-area", text: "Delivery area" }), h("p", { class: "field__hint", id: "c-area-hint", text: "The delivery fee depends on the area." }), areaSel,
        h("p", { class: "field__error", id: "c-area-err" }, AS.icon("error", "icon--sm"), h("span", { text: "Choose a delivery area." })));
      otherWrap = h("div", { hidden: true }, f.areaText.el);
      areaSel.addEventListener("change", function () { otherWrap.hidden = areaSel.value !== "__other"; areaField.classList.remove("field--error"); quote(); });
      otherWrap.hidden = areaSel.value !== "__other";
    }
    var hp = h("div", { class: "hp", "aria-hidden": "true" }, h("label", { for: "c-website", text: "Leave this field empty" }), h("input", { type: "text", id: "c-website", tabindex: "-1", autocomplete: "off" }));
    quoteBox = h("div", { "aria-live": "polite" }, h("p", { class: "muted", text: "Checking prices…" }));
    agreement = AS.agreement("merchandise-order-terms");
    var status = h("div", { "aria-live": "polite" });
    var submit = h("button", { type: "submit", class: "btn btn--lg", disabled: true }, "Place order request");
    agreement.onChange(function (ok) { submit.disabled = !ok || submitting; });
    var form = h("form", { novalidate: true, class: "layout-2" },
      h("div", {},
        h("section", { class: "form-section", "aria-labelledby": "s-details" }, h("h2", { id: "s-details", text: "Your details" }), h("div", { class: "form-grid form-grid--2" }, f.name.el, f.phone.el), f.email.el),
        h("section", { class: "form-section", "aria-labelledby": "s-delivery" }, h("h2", { id: "s-delivery", text: "Delivery" }), areaField, areas.length ? otherWrap : f.areaText.el, f.address.el, f.landmark.el, f.notes.el, hp),
        h("section", { class: "form-section", "aria-labelledby": "s-terms" }, h("h2", { id: "s-terms", text: "Review and accept" }), h("p", { text: "Pay by cash or mobile money after we approve your order." }), agreement.el, status,
          h("div", { class: "row" }, submit, h("a", { class: "btn btn--secondary", href: "/cart/merchandise", text: "Back to bag" })), h("p", { class: "muted small", text: "No payment now." }))),
      h("aside", {}, h("div", { class: "summary" }, h("h2", { text: "Order summary" }), quoteBox)));
    root.appendChild(form);
    quote();
    agreement.load().catch(function () { /* error box shown */ });
    form.addEventListener("submit", function (e) { e.preventDefault(); send(submit, status, areas.length > 0); });
  }

  function send(btn, status, hasAreas) {
    if (submitting) { return; }
    var rules = [{ field: f.name, check: F.V.name }, { field: f.phone, check: F.V.phone }, { field: f.email, check: F.V.email },
      { field: f.address, check: function (v) { return v.length >= 5 && v.length <= 300 ? null : "Enter your delivery address (5 to 300 characters)."; } }];
    var usingText = !hasAreas || areaSel.value === "__other";
    if (usingText) { rules.push({ field: f.areaText, check: F.V.required("your district or area") }); }
    else if (!areaSel.value) { areaSel.parentNode.classList.add("field--error"); areaSel.focus(); F.validate(rules); areaSel.focus(); return; }
    if (!F.validate(rules)) { return; }
    if (!agreement.accepted()) { agreement.showError(); return; }
    AS.store.orderDraft.set({ name: f.name.value(), phone: f.phone.value(), email: f.email.value(), address: f.address.value(), landmark: f.landmark.value(), notes: f.notes.value(), areaId: areaSel ? areaSel.value : null, areaText: f.areaText.value() });
    var body = {
      customer: { name: f.name.value(), phone: f.phone.value(), email: f.email.value() },
      delivery: { areaId: usingText ? null : areaSel.value, areaText: usingText ? f.areaText.value() : null, address: f.address.value(), landmark: f.landmark.value() || null, notes: f.notes.value() || null },
      items: items(), agreement: { slug: agreement.slug, version: agreement.version(), accepted: true }, website: AS.$("#c-website").value || ""
    };
    var json = JSON.stringify(body), k = AS.store.orderDraft.get().key;
    if (!k || k.hash !== json) { k = { hash: json, value: AS.api.uuid() }; AS.store.orderDraft.set({ key: k }); }
    submitting = true; btn.disabled = true; btn.setAttribute("aria-busy", "true"); AS.clear(status);
    AS.api.post("/merchandise-orders", body, { idempotencyKey: k.value }).then(function (res) {
      AS.store.bag.clear(); AS.store.orderDraft.clear();
      location.assign("/shop/received/" + encodeURIComponent(res.reference) + "?token=" + encodeURIComponent(res.accessToken));
    }, function (err) {
      submitting = false; btn.setAttribute("aria-busy", "false"); btn.disabled = !agreement.accepted();
      if (err.code === "itemUnavailable") { status.appendChild(AS.alertBox("error", h("div", {}, h("p", { text: err.message }), h("a", { href: "/cart/merchandise", text: "Review your merchandise bag" })))); }
      else if (err.code === "agreementVersionStale") { status.appendChild(AS.alertBox("warning", "The order terms have been updated. Please read the new version and accept it again.")); agreement.reload(); }
      else if (err.code === "validationFailed" && err.fields) {
        var shown = false; Object.keys(err.fields).forEach(function (key) { var n = key.split(".").pop(), fld = f[n]; if (fld) { fld.setError(err.fields[key]); if (!shown) { fld.input.focus(); shown = true; } } });
        if (!shown) { status.appendChild(AS.alertBox("error", err.message)); }
      } else if (err.code === "rateLimited") { status.appendChild(AS.alertBox("error", "You have sent several requests in a short time. Please wait a while and try again.")); }
      else { status.appendChild(AS.alertBox("error", err.message + " You can press the button again; your order will only be sent once.")); }
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    var root = AS.$("#checkout-root");
    lines = AS.store.bag.all();
    if (!lines.length) { root.appendChild(AS.ui.empty("Your merchandise bag is empty", "Add something before checking out.", "/shop", "Visit the shop")); return; }
    AS.siteInfo().then(function (i) { info = i; build(root); });
  });
})(window.AS);
