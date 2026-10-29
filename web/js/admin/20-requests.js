/* Admin A4/A5 session requests and A6/A7 merchandise orders (list, detail, decisions, notes, messages). */
(function (AS) {
  "use strict";
  var h = AS.h, F = AS.forms, A = AS.admin;

  // Small modal form helper used by decisions throughout the admin.
  A.dialogForm = function (o) {
    var status = h("div", { "aria-live": "polite" });
    var submit = h("button", { type: "submit", class: "btn" + (o.danger ? " btn--danger" : "") }, o.submitLabel);
    var cancel = h("button", { type: "button", class: "btn btn--secondary", text: "Cancel" });
    var form = h("form", { novalidate: true }, o.intro ? h("p", { text: o.intro }) : null, o.fields.map(function (f) { return f.el || f; }), status, h("div", { class: "dialog__actions" }, cancel, submit));
    var m = AS.modal(o.title, form);
    cancel.addEventListener("click", m.close);
    function run(extra) {
      AS.clear(status);
      A.busy(submit, function () { return o.submit(extra); }).then(function (res) { m.close(); if (o.done) { o.done(res); } }, function (err) {
        if (o.onError && o.onError(err, status, run)) { return; }
        if (err.fields && o.formFields && AS.forms.applyServerErrors(err, o.formFields)) { return; }
        status.appendChild(AS.alertBox("error", err.message));
      });
    }
    form.addEventListener("submit", function (e) { e.preventDefault(); if (o.validate && !o.validate()) { return; } run(); });
    return m;
  };

  function int(v) { return v === "" ? null : parseInt(v, 10); }
  var MSG_STATUS = { sent: "Sent", queued: "Not sent yet", failed: "Could not be sent" };
  var TPL = function (t) { return String(t || "").replace(/[-_]/g, " ").replace(/^./, function (c) { return c.toUpperCase(); }); };

  function payRadios(prefix) { return F.chipRadios(prefix, "How did they pay?", [{ value: "cash", label: "Cash" }, { value: "mobileMoney", label: "Mobile money" }], "cash"); }

  function messagesPanel(kind, rec, reload) {
    var sec = A.section("Messages sent", "m-" + kind);
    if (!rec.messages || !rec.messages.length) { sec.appendChild(h("p", { class: "muted", text: "No emails yet. If email is not set up, contact the customer by WhatsApp or phone." })); return sec; }
    sec.appendChild(h("ul", { class: "list" }, rec.messages.map(function (m) {
      var btn = m.status === "sent" ? null : h("button", { type: "button", class: "btn btn--secondary btn--sm", text: "Send again" });
      if (btn) { btn.addEventListener("click", function () { A.busy(btn, function () { return AS.api.post("/admin/" + kind + "/" + rec.id + "/messages/" + m.id + "/resend"); }).then(function () { AS.toast("The email will be sent again shortly."); reload(); }, A.fail); }); }
      return h("li", { class: "list-item" }, h("p", { class: "list-item__title", text: TPL(m.template) }),
        h("p", { class: "list-item__meta", text: "To " + m.to + " · " + (MSG_STATUS[m.status] || m.status) + (m.sentAt ? " · " + AS.fmt.instant(m.sentAt) : "") + (m.error ? " · " + m.error : "") }), btn ? h("div", { class: "list-item__actions" }, btn) : null);
    })));
    return sec;
  }
  function notesPanel(kind, rec, reload) {
    var sec = A.section("Internal notes", "n-" + kind);
    sec.appendChild(h("p", { class: "muted small", text: "Only staff can see these notes." }));
    if (rec.internalNotes && rec.internalNotes.length) {
      sec.appendChild(h("ul", { class: "timeline" }, rec.internalNotes.map(function (n) { return h("li", {}, h("small", { text: n.authorName + " · " + AS.fmt.instant(n.createdAt) }), h("p", { text: n.text })); })));
    }
    var t = F.field({ id: "note-" + kind, name: "text", label: "Add a note", rows: 3, required: true, maxlength: 1000 });
    var btn = h("button", { type: "submit", class: "btn btn--secondary" }, "Add note");
    var form = h("form", { novalidate: true }, t.el, btn);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      if (!F.validate([{ field: t, check: function (v) { return v.length >= 1 && v.length <= 1000 ? null : "Write a note of up to 1000 characters."; } }])) { return; }
      A.busy(btn, function () { return AS.api.post("/admin/" + kind + "/" + rec.id + "/notes", { text: t.value() }); }).then(function () { AS.toast("Saved"); reload(); }, A.fail);
    });
    sec.appendChild(form);
    return sec;
  }
  function historyPanel(rec) {
    var sec = A.section("History", "hist");
    sec.appendChild(h("ul", { class: "timeline" }, (rec.history || []).map(function (e) { return h("li", {}, h("small", { text: AS.fmt.instant(e.at) + " · " + e.byName }), h("p", { text: e.action + (e.detail ? ": " + e.detail : "") })); })));
    return sec;
  }
  function agreementPanel(rec) {
    var a = rec.agreementAcceptance, sec = A.section("Agreement accepted", "agr");
    if (!a) { sec.appendChild(h("p", { class: "muted", text: "No record found." })); return sec; }
    var view = h("button", { type: "button", class: "btn btn--text", text: "View the exact text they accepted" });
    view.addEventListener("click", function () {
      AS.api.get("/documents/" + a.slug, { version: a.version }).then(function (doc) {
        var m = AS.modal(doc.title + " (version " + doc.version + ")", h("div", { class: "agreement" }, h("div", { class: "agreement__text" }, AS.ui.markdown(doc.body))));
        m.dialog.appendChild(h("div", { class: "dialog__actions" }, h("button", { type: "button", class: "btn", text: "Close", onclick: m.close })));
      }, A.fail);
    });
    sec.appendChild(A.dl([["Accepted by", a.customerName + (a.customerEmail ? " (" + a.customerEmail + ")" : "")], ["When", AS.fmt.instant(a.acceptedAt)], ["Version", "Version " + a.version], ["Fingerprint", a.bodyHash ? h("code", { text: a.bodyHash }) : null]]));
    sec.appendChild(view);
    sec.appendChild(h("p", { class: "muted small", text: "This record is read-only." }));
    return sec;
  }
  function waButton(rec) { return rec.whatsappLink ? h("a", { class: "btn btn--secondary btn--sm", href: rec.whatsappLink, rel: "noopener", target: "_blank" }, AS.icon("whatsapp", "icon--sm"), "Message on WhatsApp") : null; }
  function payLine(p) { return p && p.status === "paid" ? "Paid" + (p.method ? " by " + (p.method === "cash" ? "cash" : "mobile money") : "") + (p.amountRwf !== null && p.amountRwf !== undefined ? ", " + AS.fmt.rwf(p.amountRwf) : "") + (p.paidAt ? " on " + AS.fmt.instant(p.paidAt) : "") : "Not paid yet"; }

  // ---------------- A4 list
  A.route("/admin/session-requests", { id: "A4", title: "Session requests" }, function (ctx) {
    return A.listScreen(ctx, {
      path: "/admin/booking-requests", def: "pending", empty: "No requests here",
      chips: [{ value: "pending", label: "Waiting for you" }, { value: "confirmed", label: "Confirmed" }, { value: "declined", label: "Declined" }, { value: "completed", label: "Completed" }, { value: null, label: "All" }],
      item: function (it) {
        return h("li", { class: "list-item" }, h("p", { class: "list-item__title" }, h("a", { href: "/admin/session-requests/" + it.id, text: it.customerName + " · " + it.reference })), A.chip(it.status),
          h("p", { class: "list-item__meta", text: (it.serviceNames || []).join(", ") }),
          h("p", { class: "list-item__meta", text: "Asked for " + AS.fmt.date(it.preferredSlot.date) + " at " + it.preferredSlot.startTime + (it.confirmedSlot ? " · Confirmed for " + AS.fmt.date(it.confirmedSlot.date) + " at " + it.confirmedSlot.startTime : "") + " · Received " + AS.fmt.instant(it.createdAt) }));
      }
    });
  });

  // ---------------- A5 detail
  A.route("/admin/session-requests/:id", { id: "A5", title: "Session request" }, function (ctx) {
    var box = ctx.main;
    function load(notice) {
      return AS.api.get("/admin/booking-requests/" + encodeURIComponent(ctx.params.id)).then(function (r) { if (ctx.current()) { paint(r, notice); } });
    }
    function paint(r, notice) {
      AS.clear(box);
      box.appendChild(h("p", {}, h("a", { href: "/admin/session-requests", text: "Back to session requests" })));
      if (notice) { box.appendChild(notice); }
      var reload = function (n) { return load(n); };
      var c = r.customer || {};
      box.appendChild(h("section", { class: "panel", "aria-labelledby": "s-sum" }, h("h2", { id: "s-sum", text: r.reference }), A.chip(r.status), A.dl([["Received", AS.fmt.instant(r.createdAt)]])));
      box.appendChild(h("section", { class: "panel", "aria-labelledby": "s-cust" }, h("h2", { id: "s-cust", text: "Customer" }),
        A.dl([["Name", c.name], ["Phone", c.phone], ["Email", c.email ? h("a", { href: "mailto:" + c.email, text: c.email }) : null], ["Address or area", c.address], ["Prefers", r.contactPreference === "whatsapp" ? "WhatsApp" : "Email"]]), waButton(r)));
      var ask = h("section", { class: "panel", "aria-labelledby": "s-ask" }, h("h2", { id: "s-ask", text: "What they asked for" }),
        h("ul", { class: "summary-list" }, (r.services || []).map(function (s) { return h("li", {}, h("span", { text: s.name }), h("span", { class: "price", text: AS.fmt.rwf(s.priceRwf) })); }), h("li", { class: "total" }, h("span", { text: "Total" }), h("span", { class: "price", text: AS.fmt.rwf(r.totalRwf) }))),
        A.dl([["Asked for", AS.fmt.dateLong(r.preferredSlot.date) + " at " + r.preferredSlot.startTime], ["Confirmed for", r.confirmedSlot ? AS.fmt.dateLong(r.confirmedSlot.date) + " at " + r.confirmedSlot.startTime : null], ["Session length", r.durationMinutes ? r.durationMinutes + " minutes" : null], ["Their notes", r.notes], ["Payment", payLine(r.payment)]]));
      if (r.slotConflict && r.status === "pending") { ask.insertBefore(AS.alertBox("warning", r.slotConflict === "blocked" ? "Warning: this time is blocked on your calendar." : "Warning: another confirmed session already uses this time."), ask.children[1]); }
      box.appendChild(ask);
      box.appendChild(agreementPanel(r));
      var dec = h("div", { class: "decision" });
      if (r.status === "pending") {
        dec.appendChild(h("button", { type: "button", class: "btn", text: "Confirm this date and time", onclick: function () { slotForm(r, "confirm", reload); } }));
        dec.appendChild(h("button", { type: "button", class: "btn btn--secondary", text: "Decline request", onclick: function () { declineForm(r, reload); } }));
      }
      if (r.status === "confirmed") {
        dec.appendChild(h("button", { type: "button", class: "btn", text: "Change date or time", onclick: function () { slotForm(r, "reschedule", reload); } }));
        dec.appendChild(h("button", { type: "button", class: "btn btn--secondary", text: "Mark as completed", onclick: function (e) { var b = e.currentTarget; A.busy(b, function () { return AS.api.post("/admin/booking-requests/" + r.id + "/complete"); }).then(function () { AS.toast("Saved"); reload(); }, A.fail); } }));
      }
      if ((r.status === "confirmed" || r.status === "completed") && r.payment && r.payment.status !== "paid") {
        dec.appendChild(h("button", { type: "button", class: "btn btn--secondary", text: "Mark as paid", onclick: function () { paidForm("booking-requests", r, reload); } }));
      }
      var dsec = A.section("Decision", "s-dec");
      dsec.appendChild(dec.children.length ? dec : h("p", { class: "muted", text: r.status === "declined" ? "This request was declined. It cannot be reopened." : "Nothing more to do here." }));
      box.appendChild(dsec);
      box.appendChild(messagesPanel("booking-requests", r, reload));
      box.appendChild(notesPanel("booking-requests", r, reload));
      box.appendChild(historyPanel(r));
    }

    function slotForm(r, verb, reload) {
      var base = r.confirmedSlot || r.preferredSlot;
      var date = F.field({ id: "cf-date", name: "date", label: "Date", required: true, type: "date", value: base.date, min: AS.todayKigali() });
      var time = F.field({ id: "cf-time", name: "startTime", label: "Start time", required: true, type: "time", value: base.startTime, step: 1800 });
      var note = F.field({ id: "cf-note", name: "noteToCustomer", label: "Note to the customer", rows: 3, maxlength: 1000, hint: "For example the meeting place." });
      A.dialogForm({
        title: verb === "confirm" ? "Confirm this date and time" : "Change date or time", submitLabel: "Confirm", fields: [date, time, note], formFields: { date: date, startTime: time, noteToCustomer: note },
        intro: verb === "confirm" ? "The date and time they asked for are filled in. Change them if you need to." : null,
        validate: function () { return F.validate([{ field: date, check: F.V.required("a date") }, { field: time, check: F.V.required("a start time") }]); },
        submit: function (force) { return AS.api.post("/admin/booking-requests/" + r.id + "/" + verb, { date: date.value(), startTime: time.value(), noteToCustomer: note.value() || null, force: force === true }); },
        onError: function (err, status, run) {
          if (err.code !== "slotUnavailable") { return false; }
          status.appendChild(AS.alertBox("warning", h("div", {}, h("p", { text: "That time clashes with a block or another confirmed session. Choose a different time." }),
            h("button", { type: "button", class: "btn btn--secondary btn--sm", text: "Confirm anyway (double up)", onclick: function () { run(true); } }))));
          return true;
        },
        done: function () {
          var wa = r.whatsappLink ? h("a", { href: r.whatsappLink, rel: "noopener", target: "_blank", text: "Message them on WhatsApp" }) : null;
          reload(AS.alertBox("success", h("p", {}, "Saved. The customer will be emailed. ", wa ? ["You can also ", wa, "."] : null)));
        }
      });
    }
    function declineForm(r, reload) {
      var reason = F.field({ id: "dc-reason", name: "reason", label: "Reason", rows: 4, required: true, maxlength: 1000, hint: "The customer will read this, so please be polite." });
      A.dialogForm({
        title: "Decline request", submitLabel: "Decline request", danger: true, fields: [reason], formFields: { reason: reason },
        validate: function () { return F.validate([{ field: reason, check: F.V.required("a reason") }]); },
        submit: function () { return AS.confirm({ title: "Are you sure?", body: "The request will be declined and the customer emailed. This cannot be undone.", confirmLabel: "Yes, decline", danger: true }).then(function (ok) { if (!ok) { throw { message: "Not declined." }; } return AS.api.post("/admin/booking-requests/" + r.id + "/decline", { reason: reason.value() }); }); },
        done: function () { AS.toast("Declined. The customer will be emailed."); reload(); }
      });
    }
    return load();
  });

  function paidForm(kind, r, reload) {
    var pay = payRadios("pm"), amt = F.field({ id: "pm-amt", name: "amountRwf", label: "Amount (RwF)", required: true, type: "number", value: r.totalRwf === null ? "" : r.totalRwf, min: 0, step: 1, inputmode: "numeric" });
    A.dialogForm({
      title: "Mark as paid", submitLabel: "Save", fields: [pay, amt], formFields: { amountRwf: amt },
      validate: function () { return F.validate([{ field: amt, check: function (v) { return /^\d+$/.test(v) ? null : "Enter the amount as a whole number."; } }]); },
      submit: function () { return AS.api.post("/admin/" + kind + "/" + r.id + "/mark-paid", { method: pay.value(), amountRwf: int(amt.value()) }); },
      done: function () { AS.toast("Saved"); reload(); }
    });
  }

  // ---------------- A6 list
  A.route("/admin/orders", { id: "A6", title: "Merchandise orders" }, function (ctx) {
    return A.listScreen(ctx, {
      path: "/admin/merchandise-orders", def: "pending", empty: "No orders here",
      chips: [{ value: "pending", label: "Waiting for you" }, { value: "approved", label: "Approved" }, { value: "paid", label: "Paid" }, { value: "delivered", label: "Delivered" }, { value: "rejected", label: "Rejected" }, { value: null, label: "All" }],
      item: function (it) {
        return h("li", { class: "list-item" }, h("p", { class: "list-item__title" }, h("a", { href: "/admin/orders/" + it.id, text: it.customerName + " · " + it.reference })), A.chip(it.status),
          h("p", { class: "list-item__meta", text: AS.cards.plural(it.itemCount, "item", "items") + " · " + (it.totalRwf === null || it.totalRwf === undefined ? "Total to be confirmed" : AS.fmt.rwf(it.totalRwf)) + " · Received " + AS.fmt.instant(it.createdAt) }));
      }
    });
  });

  // ---------------- A7 detail
  A.route("/admin/orders/:id", { id: "A7", title: "Merchandise order" }, function (ctx) {
    var box = ctx.main, info = null;
    function load(notice) {
      return Promise.all([AS.api.get("/admin/merchandise-orders/" + encodeURIComponent(ctx.params.id)), AS.siteInfo()]).then(function (res) { info = res[1]; if (ctx.current()) { paint(res[0], notice); } });
    }
    function paint(o, notice) {
      AS.clear(box);
      box.appendChild(h("p", {}, h("a", { href: "/admin/orders", text: "Back to merchandise orders" })));
      if (notice) { box.appendChild(notice); }
      var reload = function (n) { return load(n); }, c = o.customer || {}, d = o.delivery || {};
      var area = d.areaText || ((info && info.deliveryAreas || []).filter(function (a) { return a.id === d.areaId; })[0] || {}).name;
      box.appendChild(h("section", { class: "panel", "aria-labelledby": "o-sum" }, h("h2", { id: "o-sum", text: o.reference }), A.chip(o.status), A.dl([["Received", AS.fmt.instant(o.createdAt)]])));
      box.appendChild(h("section", { class: "panel", "aria-labelledby": "o-cust" }, h("h2", { id: "o-cust", text: "Customer" }),
        A.dl([["Name", c.name], ["Phone", c.phone], ["Email", c.email ? h("a", { href: "mailto:" + c.email, text: c.email }) : null]]), waButton(o)));
      box.appendChild(h("section", { class: "panel", "aria-labelledby": "o-del" }, h("h2", { id: "o-del", text: "Delivery" }), A.dl([["Area", area], ["Address", d.address], ["Landmark", d.landmark], ["Notes", d.notes]])));
      box.appendChild(h("section", { class: "panel", "aria-labelledby": "o-items" }, h("h2", { id: "o-items", text: "Items" }),
        h("ul", { class: "summary-list" }, (o.items || []).map(function (i) { return h("li", {}, h("span", { text: i.quantity + " x " + i.name + (i.optionLabels && i.optionLabels.length ? " (" + i.optionLabels.join(", ") + ")" : "") + " at " + AS.fmt.rwf(i.unitPriceRwf) }), h("span", { class: "price", text: AS.fmt.rwf(i.lineTotalRwf) })); }),
          h("li", {}, h("span", { text: "Subtotal" }), h("span", { class: "price", text: AS.fmt.rwf(o.subtotalRwf) })),
          h("li", {}, h("span", { text: "Delivery fee" }), h("span", { class: "price", text: o.deliveryFeeRwf === null || o.deliveryFeeRwf === undefined ? "To be confirmed" : AS.fmt.rwf(o.deliveryFeeRwf) })),
          h("li", { class: "total" }, h("span", { text: "Total" }), h("span", { class: "price", text: o.totalRwf === null || o.totalRwf === undefined ? "To be confirmed" : AS.fmt.rwf(o.totalRwf) }))),
        A.dl([["Payment", payLine(o.payment)]])));
      box.appendChild(agreementPanel(o));
      var dec = h("div", { class: "decision" });
      if (o.status === "pending") {
        dec.appendChild(h("button", { type: "button", class: "btn", text: "Approve order", onclick: function () { approveForm(o, reload); } }));
        dec.appendChild(h("button", { type: "button", class: "btn btn--secondary", text: "Reject order", onclick: function () { rejectForm(o, reload); } }));
      }
      if (o.status === "approved") {
        dec.appendChild(h("button", { type: "button", class: "btn", text: "Mark as paid", onclick: function () { paidForm("merchandise-orders", { id: o.id, totalRwf: o.totalRwf }, reload); } }));
        dec.appendChild(h("button", { type: "button", class: "btn btn--secondary", text: "Mark as delivered (paid on delivery)", onclick: function () { deliveredForm(o, reload); } }));
      }
      if (o.status === "paid") {
        dec.appendChild(h("button", { type: "button", class: "btn", text: "Mark as delivered", onclick: function (e) { A.busy(e.currentTarget, function () { return AS.api.post("/admin/merchandise-orders/" + o.id + "/mark-delivered", {}); }).then(function () { AS.toast("Saved"); reload(); }, A.fail); } }));
      }
      var dsec = A.section("Decision", "o-dec");
      dsec.appendChild(dec.children.length ? dec : h("p", { class: "muted", text: "This order is finished." }));
      box.appendChild(dsec);
      box.appendChild(messagesPanel("merchandise-orders", o, reload));
      box.appendChild(notesPanel("merchandise-orders", o, reload));
      box.appendChild(historyPanel(o));
    }
    function approveForm(o, reload) {
      var fee = F.field({ id: "ap-fee", name: "deliveryFeeRwf", label: "Delivery fee (RwF)", type: "number", min: 0, step: 1, inputmode: "numeric", value: o.deliveryFeeRwf === null || o.deliveryFeeRwf === undefined ? "" : o.deliveryFeeRwf, required: o.deliveryFeeRwf === null || o.deliveryFeeRwf === undefined, hint: o.deliveryFeeRwf === null || o.deliveryFeeRwf === undefined ? "Type the fee because the area fee is not set." : "Taken from the delivery area." });
      var note = F.field({ id: "ap-note", name: "noteToCustomer", label: "Note to the customer", rows: 3, maxlength: 1000 });
      A.dialogForm({
        title: "Approve order", submitLabel: "Approve", fields: [fee, note], formFields: { deliveryFeeRwf: fee, noteToCustomer: note },
        validate: function () { return F.validate([{ field: fee, check: function (v) { var stored = o.deliveryFeeRwf !== null && o.deliveryFeeRwf !== undefined; if (v === "") { return stored ? null : "Enter the delivery fee as a whole number."; } return /^\d+$/.test(v) ? null : "Enter the delivery fee as a whole number."; } }]); },
        submit: function () { return AS.api.post("/admin/merchandise-orders/" + o.id + "/approve", { deliveryFeeRwf: int(fee.value()), noteToCustomer: note.value() || null }); },
        done: function () { AS.toast("Approved. The customer will be emailed."); reload(); }
      });
    }
    function rejectForm(o, reload) {
      var reason = F.field({ id: "rj-reason", name: "reason", label: "Reason", rows: 4, required: true, maxlength: 1000, hint: "The customer will read this." });
      A.dialogForm({
        title: "Reject order", submitLabel: "Reject order", danger: true, fields: [reason], formFields: { reason: reason },
        validate: function () { return F.validate([{ field: reason, check: F.V.required("a reason") }]); },
        submit: function () { return AS.confirm({ title: "Are you sure?", body: "The order will be rejected and the customer emailed. This cannot be undone.", confirmLabel: "Yes, reject", danger: true }).then(function (ok) { if (!ok) { throw { message: "Not rejected." }; } return AS.api.post("/admin/merchandise-orders/" + o.id + "/reject", { reason: reason.value() }); }); },
        done: function () { AS.toast("Rejected. The customer will be emailed."); reload(); }
      });
    }
    function deliveredForm(o, reload) {
      var pay = payRadios("dm"), amt = F.field({ id: "dm-amt", name: "amountRwf", label: "Amount received (RwF)", required: true, type: "number", min: 0, step: 1, inputmode: "numeric", value: o.totalRwf === null || o.totalRwf === undefined ? "" : o.totalRwf });
      A.dialogForm({
        title: "Mark as delivered", intro: "Use this when the customer pays on delivery. Record the payment now.", submitLabel: "Save", fields: [pay, amt], formFields: { amountRwf: amt },
        validate: function () { return F.validate([{ field: amt, check: function (v) { return /^\d+$/.test(v) ? null : "Enter the amount as a whole number."; } }]); },
        submit: function () { return AS.api.post("/admin/merchandise-orders/" + o.id + "/mark-delivered", { method: pay.value(), amountRwf: int(amt.value()) }); },
        done: function () { AS.toast("Saved"); reload(); }
      });
    }
    return load();
  });
})(window.AS);
