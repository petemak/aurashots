/*
 * Request a session (P8): three steps on one page, entries preserved across Back/Forward.
 * 1 Date and time (GET /api/availability)  2 Your details  3 Review and accept the agreement,
 * then POST /api/booking-requests with an Idempotency-Key that is reused on retry of the same payload.
 */
(function (AS) {
  "use strict";
  var h = AS.h, F = AS.forms;
  var root, services, info, step = 1, cal = null, agreement = null, submitting = false;
  var draft = AS.store.bookingDraft.get();
  var S = { date: draft.date || null, time: draft.time || null, d: draft.details || {} };
  var STEPS = ["Date and time", "Your details", "Review and accept"];
  var fields = {};

  function save() { AS.store.bookingDraft.set({ date: S.date, time: S.time, details: S.d }); }
  function serviceIds() { return services.map(function (s) { return s.id; }); }
  function slotText() { return AS.fmt.dateLong(S.date) + " at " + S.time; }

  function stepper() {
    return h("ol", { class: "stepper", "aria-label": "Progress" }, STEPS.map(function (t, i) {
      var n = i + 1;
      return h("li", { "aria-current": n === step ? "step" : null, "data-done": n < step ? "true" : "false" },
        h("span", { class: "stepper__dot" }, n < step ? AS.icon("success", "icon--sm") : String(n)), h("span", { text: t }));
    }));
  }

  function go(n, push) {
    step = n;
    if (push !== false) { history.pushState({ step: n }, "", location.pathname); }
    render();
    var hd = AS.$("#step-title"); if (hd) { AS.ui.focusHeading(hd); }
    window.scrollTo(0, 0);
  }

  // ---------- step 1
  function step1() {
    var notice = draft.notice; draft.notice = null;
    var slotsBox = h("div", { "aria-live": "polite" }), summary = h("p", { class: "chosen-slot", "aria-live": "polite" });
    var next = h("button", { type: "button", class: "btn btn--lg", disabled: !S.time, onclick: function () { go(2); } }, "Next", AS.icon("arrow-right", "icon--sm"));
    var msg = h("div");
    function paintSummary() {
      AS.clear(summary);
      if (S.date && S.time) { summary.appendChild(document.createTextNode(slotText())); summary.appendChild(h("span", { class: "muted small", text: " This is your preferred time. We will confirm it or suggest another." })); }
      else { summary.appendChild(h("span", { class: "muted", text: S.date ? "Choose a start time." : "Choose a day first." })); }
      next.disabled = !(S.date && S.time);
    }
    function loadSlots(date) {
      AS.clear(slotsBox).appendChild(h("p", { class: "muted", text: "Loading times…" }));
      AS.api.get("/availability", { from: date, to: date, date: date, serviceIds: serviceIds() }).then(function (res) {
        var day = (res.days || [])[0], slots = (day && day.slots) || [];
        AS.clear(slotsBox);
        if (!slots.length) { slotsBox.appendChild(h("p", { class: "muted", text: "No start times are available on this day. Please choose another day." })); return; }
        slotsBox.appendChild(h("div", { class: "slots", role: "group", "aria-label": "Start times on " + AS.fmt.dateLong(date) }, slots.map(function (sl) {
          var ok = sl.available, b = h("button", { type: "button", class: "slot", "aria-pressed": ok ? String(S.time === sl.startTime && S.date === date) : null, "aria-disabled": ok ? null : "true" }, sl.startTime, ok ? null : h("span", { class: "slot__note", text: "Unavailable" }));
          b.addEventListener("click", function () {
            if (!ok) { return; }
            S.time = sl.startTime; save();
            AS.$$(".slot", slotsBox).forEach(function (x) { if (x.getAttribute("aria-disabled") !== "true") { x.setAttribute("aria-pressed", "false"); } });
            b.setAttribute("aria-pressed", "true"); paintSummary();
          });
          return b;
        })));
      }, function (err) { AS.clear(slotsBox).appendChild(AS.ui.errorBox(err, function () { loadSlots(date); })); });
    }
    var maxDate = null;
    if (info && info.booking && info.booking.windowDays) { var t = AS.fmt.parseYmd(AS.todayKigali()); t.setUTCDate(t.getUTCDate() + info.booking.windowDays); maxDate = AS.fmt.ymd(t); }
    cal = AS.calendar({
      selected: S.date, maxDate: maxDate,
      getDays: function (from, to) { return AS.api.get("/availability", { from: from, to: to, serviceIds: serviceIds() }).then(function (r) { return r.days || []; }); },
      onSelect: function (date) { if (S.date !== date) { S.time = null; } S.date = date; save(); paintSummary(); loadSlots(date); },
      onError: function (err, retry) { AS.clear(msg).appendChild(AS.ui.errorBox(err, function () { AS.clear(msg); retry(); })); }
    });
    paintSummary();
    if (S.date) { loadSlots(S.date); }
    return h("section", { "aria-labelledby": "step-title" },
      h("h2", { id: "step-title", text: "Step 1: Choose a date and time" }),
      notice ? AS.alertBox("warning", notice) : null,
      msg,
      h("div", { class: "book-grid" }, cal.el, h("div", {}, h("h3", { text: "Start time" }), slotsBox, summary)),
      h("div", { class: "step-actions" }, h("a", { class: "btn btn--secondary", href: "/cart/sessions" }, "Back to basket"), next));
  }

  // ---------- step 2
  function step2() {
    var d = S.d;
    fields = {
      name: F.field({ id: "f-name", name: "name", label: "Full name", required: true, value: d.name, autocomplete: "name", maxlength: 100 }),
      phone: F.field({ id: "f-phone", name: "phone", label: "Phone with country code", required: true, type: "tel", value: d.phone, autocomplete: "tel", inputmode: "tel", hint: "For example +250 790 114 749. We use it for WhatsApp.", maxlength: 20 }),
      email: F.field({ id: "f-email", name: "email", label: "Email", required: true, type: "email", value: d.email, autocomplete: "email", maxlength: 254 }),
      address: F.field({ id: "f-address", name: "address", label: "Address or area for the shoot", value: d.address, maxlength: 300, hint: "A neighbourhood is fine if you do not know the place yet." }),
      notes: F.field({ id: "f-notes", name: "notes", label: "Notes and wishes", rows: 4, value: d.notes, maxlength: 1000, counter: true })
    };
    var pref = F.chipRadios("contactPreference", "Preferred contact method", [{ value: "email", label: "Email" }, { value: "whatsapp", label: "WhatsApp" }], d.contactPreference || "email");
    var hp = h("div", { class: "hp", "aria-hidden": "true" }, h("label", { for: "f-website", text: "Leave this field empty" }), h("input", { type: "text", id: "f-website", name: "website", tabindex: "-1", autocomplete: "off" }));
    var form = h("form", { novalidate: true, "aria-labelledby": "step-title" },
      h("h2", { id: "step-title", text: "Step 2: Your details" }),
      h("div", { class: "form-grid form-grid--2" }, fields.name.el, fields.phone.el), fields.email.el, fields.address.el, pref.el, fields.notes.el, hp,
      h("div", { class: "step-actions" }, h("button", { type: "button", class: "btn btn--secondary", onclick: function () { collect(pref); go(1); } }, "Back"), h("button", { type: "submit", class: "btn btn--lg" }, "Next", AS.icon("arrow-right", "icon--sm"))));
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      if (F.validate([
        { field: fields.name, check: F.V.name }, { field: fields.phone, check: F.V.phone }, { field: fields.email, check: F.V.email },
        { field: fields.address, check: F.V.max(300, "Address") }, { field: fields.notes, check: F.V.max(1000, "Notes") }])) { collect(pref); go(3); }
    });
    return form;
  }
  function collect(pref) {
    S.d = { name: fields.name.value(), phone: fields.phone.value(), email: fields.email.value(), address: fields.address.value(), notes: fields.notes.value(), contactPreference: pref.value() };
    save();
  }

  // ---------- step 3
  function step3() {
    var wrap = h("section", { "aria-labelledby": "step-title" }, h("h2", { id: "step-title", text: "Step 3: Review and accept" }));
    var total = services.reduce(function (n, s) { return n + (s.priceRwf || 0); }, 0);
    var status = h("div", { "aria-live": "polite" });
    var list = h("ul", { class: "summary-list" }, services.map(function (s) { return h("li", {}, h("span", { text: s.name }), h("span", { class: "price", text: AS.fmt.rwf(s.priceRwf) })); }), h("li", { class: "total" }, h("span", { text: "Total" }), h("span", { class: "price", text: AS.fmt.rwf(total) })));
    wrap.appendChild(h("div", { class: "summary" }, h("h3", { text: "Your session" }), list, h("p", {}, h("span", { class: "chosen-slot", text: slotText() }), " ", h("button", { type: "button", class: "btn btn--text", onclick: function () { go(1); } }, "Edit", h("span", { class: "sr-only", text: " date and time" })))));
    var d = S.d;
    wrap.appendChild(h("div", { class: "summary" }, h("h3", { text: "Your details" }),
      h("dl", { class: "dl" }, h("dt", { text: "Name" }), h("dd", { text: d.name }), h("dt", { text: "Phone" }), h("dd", { text: d.phone }), h("dt", { text: "Email" }), h("dd", { text: d.email }),
        d.address ? [h("dt", { text: "Address or area" }), h("dd", { text: d.address })] : null,
        h("dt", { text: "Contact by" }), h("dd", { text: d.contactPreference === "whatsapp" ? "WhatsApp" : "Email" }),
        d.notes ? [h("dt", { text: "Notes" }), h("dd", { text: d.notes })] : null),
      h("button", { type: "button", class: "btn btn--text", onclick: function () { go(2); } }, "Edit", h("span", { class: "sr-only", text: " your details" }))));
    agreement = AS.agreement("session-booking-agreement");
    var submit = h("button", { type: "button", class: "btn btn--lg", disabled: true }, "Send booking request");
    agreement.onChange(function (ok) { submit.disabled = !ok || submitting; });
    wrap.appendChild(agreement.el);
    wrap.appendChild(status);
    wrap.appendChild(h("div", { class: "step-actions" }, h("button", { type: "button", class: "btn btn--secondary", onclick: function () { go(2); } }, "Back"), h("div", {}, submit, h("p", { class: "muted small", text: "No payment now." }))));
    agreement.load().catch(function () { /* error box already shown */ });
    submit.addEventListener("click", function () { send(submit, status); });
    return wrap;
  }

  function payload() {
    var d = S.d, customer = { name: d.name, phone: d.phone, email: d.email };
    if (d.address) { customer.address = d.address; }
    return { customer: customer, contactPreference: d.contactPreference, serviceIds: serviceIds(), preferredSlot: { date: S.date, startTime: S.time },
      notes: d.notes || null, agreement: { slug: agreement.slug, version: agreement.version(), accepted: true }, website: (AS.$("#f-website") || {}).value || "" };
  }

  function send(btn, status) {
    if (submitting) { return; }
    if (!agreement.accepted()) { agreement.showError(); return; }
    var body = payload(), json = JSON.stringify(body), k = AS.store.bookingDraft.get().key;
    // One key per distinct payload: a retry of the same request reuses it, so the server creates only one booking.
    if (!k || k.hash !== json) { k = { hash: json, value: AS.api.uuid() }; AS.store.bookingDraft.set({ key: k }); }
    submitting = true; btn.disabled = true; btn.setAttribute("aria-busy", "true"); AS.clear(status);
    AS.api.post("/booking-requests", body, { idempotencyKey: k.value }).then(function (res) {
      AS.store.sessions.clear(); AS.store.bookingDraft.clear();
      location.assign("/book/received/" + encodeURIComponent(res.reference) + "?token=" + encodeURIComponent(res.accessToken));
    }, function (err) {
      submitting = false; btn.setAttribute("aria-busy", "false"); btn.disabled = !agreement.accepted();
      if (err.code === "slotUnavailable") {
        S.time = null; AS.store.bookingDraft.set({ date: S.date, time: null, notice: "That time was just taken. Please choose another time. Your details are saved." });
        draft = AS.store.bookingDraft.get(); AS.store.bookingDraft.set({ notice: null }); go(1);
      } else if (err.code === "agreementVersionStale") {
        status.appendChild(AS.alertBox("warning", "The agreement has been updated. Please read the new version and accept it again."));
        agreement.reload();
      } else if (err.code === "itemUnavailable") {
        status.appendChild(AS.alertBox("error", h("div", {}, h("p", { text: err.message }), h("a", { href: "/cart/sessions", text: "Review your session basket" }))));
      } else if (err.code === "validationFailed" && err.fields) {
        var map = {}; Object.keys(err.fields).forEach(function (key) { map[key.split(".").pop()] = err.fields[key]; });
        go(2, false); Object.keys(fields).forEach(function (n) { if (map[n]) { fields[n].setError(map[n]); } });
        var first = Object.keys(fields).filter(function (n) { return map[n]; })[0];
        if (first) { fields[first].input.focus(); } else { AS.clear(status).appendChild(AS.alertBox("error", err.message)); }
      } else if (err.code === "rateLimited") {
        status.appendChild(AS.alertBox("error", "You have sent several requests in a short time. Please wait a while and try again, or message us on WhatsApp."));
      } else {
        status.appendChild(AS.alertBox("error", err.message + " You can press the button again; your request will only be sent once."));
      }
    });
  }

  function render() {
    AS.clear(root).appendChild(stepper());
    root.appendChild(step === 1 ? step1() : step === 2 ? step2() : step3());
  }

  document.addEventListener("DOMContentLoaded", function () {
    root = AS.$("#book-root");
    services = AS.store.sessions.all();
    if (!services.length) { location.replace("/services"); return; }
    if (!S.d.name && !S.date) { history.replaceState({ step: 1 }, "", location.pathname); }
    AS.siteInfo().then(function (i) {
      info = i;
      // Entering the page again with a full draft returns to step 1 (the slot may have gone since).
      history.replaceState({ step: 1 }, "", location.pathname);
      render();
    });
    window.addEventListener("popstate", function (e) {
      var s = (e.state && e.state.step) || 1;
      if (s === 3 && !(S.d.name && S.date && S.time)) { s = 1; }
      step = s; render();
    });
  });
})(window.AS);
