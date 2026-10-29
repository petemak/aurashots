/* Admin A14 calendar, A15 messages, A16 settings, A17-A19 staff, A20 policies and agreements. */
(function (AS) {
  "use strict";
  var h = AS.h, F = AS.forms, A = AS.admin, fmt = AS.fmt;
  function int(v) { return v === "" || v === null || v === undefined ? null : parseInt(v, 10); }
  function reloadHere() { A.go(location.pathname + location.search, true); }

  // ================= A14 calendar
  A.route("/admin/calendar", { id: "A14", title: "Calendar" }, function (ctx) {
    var view = AS.todayKigali().slice(0, 7) + "-01", selected = null, data = { blocks: [], sessions: [], pending: [] };
    var wrap = h("div", { class: "cal" }), calBox = h("div"), side = h("div", { class: "panel", "aria-live": "polite" });
    wrap.appendChild(calBox); wrap.appendChild(side);
    ctx.main.appendChild(h("p", { class: "muted", text: "Customers cannot pick blocked dates or times." }));
    ctx.main.appendChild(wrap);
    ctx.main.appendChild(h("div", { class: "row" }, h("button", { type: "button", class: "btn btn--secondary", text: "Block several days", onclick: function () { blockForm("range"); } })));

    function bounds() { var d = fmt.parseYmd(view), last = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)); return { y: d.getUTCFullYear(), m: d.getUTCMonth(), offset: (d.getUTCDay() + 6) % 7, count: last.getUTCDate(), last: fmt.ymd(last) }; }
    function load() {
      var b = bounds();
      return AS.api.get("/admin/calendar", { from: view, to: b.last }).then(function (res) { if (!ctx.current()) { return; } data = res; paintCal(); paintSide(); });
    }
    function dayBlocks(date) { return (data.blocks || []).filter(function (x) { return x.date === date; }); }
    function paintCal() {
      var b = bounds(), grid = h("div", { class: "calendar__grid", role: "group", "aria-label": fmt.monthName(b.y, b.m) });
      ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].forEach(function (d) { grid.appendChild(h("span", { class: "calendar__dow", "aria-hidden": "true", text: d })); });
      for (var e = 0; e < b.offset; e++) { grid.appendChild(h("span", { class: "calendar__day calendar__day--empty", "aria-hidden": "true" })); }
      for (var n = 1; n <= b.count; n++) {
        var date = view.slice(0, 8) + fmt.pad(n), bl = dayBlocks(date), ses = (data.sessions || []).filter(function (x) { return x.date === date; }), pen = (data.pending || []).filter(function (x) { return x.date === date; });
        var full = bl.some(function (x) { return !x.startTime; }), part = !full && bl.length > 0;
        var label = fmt.dateLong(date) + (full ? ", blocked" : part ? ", partly blocked" : "") + (ses.length ? ", " + ses.length + " confirmed" : "") + (pen.length ? ", " + pen.length + " waiting" : "");
        var btn = h("button", { type: "button", class: "calendar__day", "data-date": date, "data-blocked": full ? "full" : part ? "part" : null, "aria-pressed": selected === date ? "true" : "false", "aria-label": label },
          h("span", { text: String(n) }), ses.slice(0, 2).map(function (s) { return h("span", { class: "small" }, h("span", { class: "dot" }), s.startTime); }), pen.length ? h("span", { class: "small" }, h("span", { class: "dot dot--pending" }), pen.length + " waiting") : null);
        btn.addEventListener("click", (function (dt) { return function () { selected = dt; paintCal(); paintSide(); }; })(date));
        grid.appendChild(btn);
      }
      AS.clear(calBox).appendChild(h("div", { class: "calendar" },
        h("div", { class: "calendar__head" },
          h("button", { type: "button", class: "btn btn--icon btn--secondary", "aria-label": "Previous month", onclick: function () { shift(-1); } }, AS.icon("chevron-left")),
          h("h2", { class: "calendar__title", "aria-live": "polite", text: fmt.monthName(b.y, b.m) }),
          h("button", { type: "button", class: "btn btn--icon btn--secondary", "aria-label": "Next month", onclick: function () { shift(1); } }, AS.icon("chevron-right"))),
        grid,
        h("ul", { class: "legend" }, h("li", {}, h("span", { class: "legend__swatch legend__swatch--blocked" }), "Blocked or partly blocked"), h("li", {}, h("span", { class: "dot" }), "Confirmed session"), h("li", {}, h("span", { class: "dot dot--pending" }), "Waiting request"))));
    }
    function shift(n) { var d = fmt.parseYmd(view); d.setUTCMonth(d.getUTCMonth() + n); view = fmt.ymd(d); selected = null; load(); }
    function paintSide() {
      AS.clear(side);
      if (!selected) { side.appendChild(h("p", { class: "muted", text: "Choose a day to see what is on and to block it." })); return; }
      var bl = dayBlocks(selected), ses = (data.sessions || []).filter(function (x) { return x.date === selected; }), pen = (data.pending || []).filter(function (x) { return x.date === selected; });
      side.appendChild(h("h3", { text: fmt.dateLong(selected) }));
      if (!bl.length && !ses.length && !pen.length) { side.appendChild(h("p", { class: "muted", text: "Nothing on this day." })); }
      ses.forEach(function (s) { side.appendChild(h("p", {}, h("span", { class: "dot" }), s.startTime + " to " + s.endTime + " · ", h("a", { href: "/admin/session-requests/" + s.id, text: s.customerName }))); });
      pen.forEach(function (s) { side.appendChild(h("p", {}, h("span", { class: "dot dot--pending" }), s.startTime + " (waiting) · ", h("a", { href: "/admin/session-requests/" + s.id, text: s.customerName }))); });
      bl.forEach(function (x) {
        var un = h("button", { type: "button", class: "btn btn--secondary btn--sm", text: "Unblock" });
        un.addEventListener("click", function () {
          AS.confirm({ title: "Unblock this time?", body: "Customers will be able to pick it again.", confirmLabel: "Yes, unblock" }).then(function (ok) { if (ok) { A.busy(un, function () { return AS.api.del("/admin/calendar/blocks/" + x.id); }).then(function () { AS.toast("Unblocked"); load(); }, A.fail); } });
        });
        side.appendChild(h("p", {}, h("strong", { text: x.startTime ? "Blocked " + x.startTime + " to " + x.endTime : "Blocked all day" }), x.reason ? " · " + x.reason : "", " ", un));
      });
      side.appendChild(h("div", { class: "decision" }, h("button", { type: "button", class: "btn", text: "Block this whole day", onclick: function () { blockForm("day"); } }), h("button", { type: "button", class: "btn btn--secondary", text: "Block certain hours", onclick: function () { blockForm("hours"); } })));
    }
    function blockForm(kind) {
      var from = F.field({ id: "bk-from", name: "fromDate", label: kind === "range" ? "From date" : "Date", required: true, type: "date", value: selected || AS.todayKigali() });
      var to = F.field({ id: "bk-to", name: "toDate", label: "To date", required: true, type: "date", value: selected || AS.todayKigali() });
      var st = F.field({ id: "bk-st", name: "startTime", label: "From", required: true, type: "time", value: "09:00", step: 1800 }), en = F.field({ id: "bk-en", name: "endTime", label: "To", required: true, type: "time", value: "12:00", step: 1800 });
      var reason = F.field({ id: "bk-reason", name: "reason", label: "Reason", maxlength: 200 });
      var fields = kind === "range" ? [from, to, reason] : kind === "hours" ? [from, st, en, reason] : [from, reason];
      A.dialogForm({
        title: kind === "range" ? "Block several days" : kind === "hours" ? "Block certain hours" : "Block this whole day", submitLabel: "Block", fields: fields, formFields: { fromDate: from, toDate: to, startTime: st, endTime: en, reason: reason },
        validate: function () {
          var rules = [{ field: from, check: F.V.required("a date") }];
          if (kind === "range") { rules.push({ field: to, check: function (v) { return v && v >= from.value() ? null : "The last day must be on or after the first day."; } }); }
          if (kind === "hours") { rules.push({ field: en, check: function (v) { return v && v > st.value() ? null : "The end time must be after the start time."; } }); }
          return F.validate(rules);
        },
        submit: function () { return AS.api.post("/admin/calendar/blocks", { fromDate: from.value(), toDate: kind === "range" ? to.value() : from.value(), startTime: kind === "hours" ? st.value() : null, endTime: kind === "hours" ? en.value() : null, reason: reason.value() || null }); },
        done: function (res) {
          AS.toast("Blocked");
          if (res && res.affectedSessions && res.affectedSessions.length) {
            side.prepend(AS.alertBox("warning", h("div", {}, h("p", { text: "These confirmed sessions are in the blocked time. They are not cancelled, so please contact the customers:" }),
              h("ul", {}, res.affectedSessions.map(function (s) { return h("li", {}, h("a", { href: "/admin/session-requests/" + s.id, text: (s.customerName || s.reference) + " · " + fmt.date(s.date) + " " + (s.startTime || "") })); })))));
          }
          load();
        }
      });
    }
    return load();
  });

  // ================= A15 messages
  A.route("/admin/messages", { id: "A15", title: "Messages" }, function (ctx) {
    return A.listScreen(ctx, {
      path: "/admin/enquiries", def: "new", empty: "No messages here",
      chips: [{ value: "new", label: "Waiting" }, { value: "answered", label: "Answered" }, { value: null, label: "All" }],
      item: function (m) {
        var done = h("button", { type: "button", class: "btn btn--sm", text: "Mark as answered" });
        done.addEventListener("click", function () { A.busy(done, function () { return AS.api.post("/admin/enquiries/" + m.id + "/answered"); }).then(function () { AS.toast("Saved"); reloadHere(); }, A.fail); });
        return h("li", { class: "list-item" }, h("details", {},
          h("summary", {}, h("strong", { text: m.name }), " · " + fmt.instant(m.receivedAt) + " · " + (m.message.length > 70 ? m.message.slice(0, 70) + "…" : m.message)),
          h("div", { class: "stack" }, A.chip(m.status === "answered" ? "answered" : "new"), h("p", { class: "conditions", text: m.message }),
            A.dl([["Email", m.email], ["Phone", m.phone]]),
            h("div", { class: "decision" }, h("a", { class: "btn btn--secondary btn--sm", href: "mailto:" + m.email }, AS.icon("email", "icon--sm"), "Reply by email"),
              m.whatsappLink ? h("a", { class: "btn btn--secondary btn--sm", href: m.whatsappLink, rel: "noopener", target: "_blank" }, AS.icon("whatsapp", "icon--sm"), "Message on WhatsApp") : null,
              m.status === "answered" ? null : done))));
      }
    });
  });

  // ================= A16 settings
  function settingsBody(s) {
    var so = s.social || {}, b = s.booking || {};
    return { phone: s.phone, whatsapp: s.whatsapp, email: s.email, address: s.address, social: { instagram: so.instagram || null, tiktok: so.tiktok || null, facebook: so.facebook || null },
      openingHours: s.openingHours || [], booking: { minNoticeHours: b.minNoticeHours, windowDays: b.windowDays, defaultSessionMinutes: b.defaultSessionMinutes === undefined ? null : b.defaultSessionMinutes },
      deliveryAreas: (s.deliveryAreas || []).map(function (a) { var x = { name: a.name, feeRwf: a.feeRwf === undefined ? null : a.feeRwf, active: a.active !== false }; if (a.id) { x.id = a.id; } return x; }),
      portfolioCategories: (s.portfolioCategories || []).map(function (c) { var x = { name: c.name }; if (c.id) { x.id = c.id; } return x; }) };
  }
  A.route("/admin/settings", { id: "A16", title: "Settings" }, function (ctx) {
    return AS.api.get("/admin/settings").then(function (s0) {
      if (!ctx.current()) { return; }
      var cur = s0;
      function save(btn, patch, okMsg) {
        var body = settingsBody(cur); patch(body);
        return A.busy(btn, function () { return AS.api.put("/admin/settings", body); }).then(function (res) { cur = res && res.openingHours ? res : Object.assign({}, cur, body); AS.toast(okMsg || "Saved"); AS.clear(ctx.main); build(); }, function (err) { throw err; });
      }
      function group(title, id, kids) { return h("section", { class: "panel", "aria-labelledby": id }, h("h2", { id: id, text: title }), kids); }
      function build() {
        var so = cur.social || {}, bk = cur.booking || {};
        // contact and social
        var c = {
          phone: F.field({ id: "st-phone", name: "phone", label: "Phone", required: true, type: "tel", value: cur.phone, maxlength: 20 }),
          whatsapp: F.field({ id: "st-wa", name: "whatsapp", label: "WhatsApp number", required: true, type: "tel", value: cur.whatsapp, maxlength: 20 }),
          email: F.field({ id: "st-email", name: "email", label: "Email", required: true, value: cur.email, maxlength: 254 }),
          address: F.field({ id: "st-addr", name: "address", label: "Address", required: true, value: cur.address, maxlength: 300 }),
          instagram: F.field({ id: "st-ig", name: "social.instagram", label: "Instagram link", type: "url", value: so.instagram, hint: so.instagram ? null : "<TBD> until you add it. Empty links are hidden from visitors." }),
          tiktok: F.field({ id: "st-tt", name: "social.tiktok", label: "TikTok link", type: "url", value: so.tiktok, hint: so.tiktok ? null : "<TBD> until you add it. Empty links are hidden from visitors." }),
          facebook: F.field({ id: "st-fb", name: "social.facebook", label: "Facebook link", type: "url", value: so.facebook, hint: so.facebook ? null : "<TBD> until you add it. Empty links are hidden from visitors." })
        };
        var b1 = h("button", { type: "submit", class: "btn" }, "Save");
        var f1 = h("form", { novalidate: true }, c.phone.el, c.whatsapp.el, c.email.el, c.address.el, c.instagram.el, c.tiktok.el, c.facebook.el, b1);
        f1.addEventListener("submit", function (e) {
          e.preventDefault();
          if (!F.validate([{ field: c.phone, check: F.V.phone }, { field: c.whatsapp, check: F.V.phone }, { field: c.address, check: F.V.required("an address") }, { field: c.email, check: F.V.required("an email address") }])) { return; }
          save(b1, function (b) { b.phone = c.phone.value(); b.whatsapp = c.whatsapp.value(); b.email = c.email.value(); b.address = c.address.value(); b.social = { instagram: c.instagram.value() || null, tiktok: c.tiktok.value() || null, facebook: c.facebook.value() || null }; }).catch(function (err) { A.formError(err, { phone: c.phone, whatsapp: c.whatsapp, email: c.email, address: c.address, "social.instagram": c.instagram, "social.tiktok": c.tiktok, "social.facebook": c.facebook }); });
        });
        ctx.main.appendChild(group("Contact and social", "g-contact", f1));

        // opening hours
        var hours = (cur.openingHours || []).map(function (r) { return { days: r.days.slice(), open: r.open, close: r.close }; });
        var hBox = h("div"), DAYS = [["mon", "Mon"], ["tue", "Tue"], ["wed", "Wed"], ["thu", "Thu"], ["fri", "Fri"], ["sat", "Sat"], ["sun", "Sun"]];
        function paintHours() {
          AS.clear(hBox);
          hours.forEach(function (r, i) {
            var open = F.field({ id: "oh-o" + i, name: "open", label: "Opens", required: true, type: "time", value: r.open }), close = F.field({ id: "oh-c" + i, name: "close", label: "Closes", required: true, type: "time", value: r.close });
            open.input.addEventListener("input", function () { r.open = open.input.value; }); close.input.addEventListener("input", function () { r.close = close.input.value; });
            var days = h("fieldset", { class: "field" }, h("legend", { text: "Days" }), h("div", { class: "chips" }, DAYS.map(function (d) {
              var cb = h("input", { type: "checkbox", id: "oh-" + i + d[0], checked: r.days.indexOf(d[0]) >= 0 });
              cb.addEventListener("change", function () { r.days = DAYS.map(function (x) { return x[0]; }).filter(function (k) { return k === d[0] ? cb.checked : r.days.indexOf(k) >= 0; }); });
              return h("label", { class: "chip-radio", for: cb.id }, cb, h("span", { text: d[1] }));
            })));
            hBox.appendChild(h("div", { class: "hours-row" }, days, h("div", { class: "form-grid form-grid--2" }, open.el, close.el), h("button", { type: "button", class: "btn btn--secondary btn--sm", onclick: function () { hours.splice(i, 1); paintHours(); } }, "Take out this row")));
          });
        }
        paintHours();
        var b2 = h("button", { type: "button", class: "btn" }, "Save");
        b2.addEventListener("click", function () {
          if (hours.some(function (r) { return !r.days.length || !r.open || !r.close || r.close <= r.open; })) { AS.toast("Each row needs at least one day and a closing time after the opening time.", "error"); return; }
          save(b2, function (b) { b.openingHours = hours; }).catch(A.fail);
        });
        ctx.main.appendChild(group("Opening hours", "g-hours", [h("p", { class: "muted", text: "These hours also decide which times customers can book." }), hBox, h("div", { class: "form-actions" }, h("button", { type: "button", class: "btn btn--secondary", onclick: function () { hours.push({ days: [], open: "09:00", close: "17:00" }); paintHours(); } }, AS.icon("plus", "icon--sm"), "Add hours"), b2)]));

        // booking rules
        var r = {
          defaultSessionMinutes: F.field({ id: "br-len", name: "booking.defaultSessionMinutes", label: "Standard session length (minutes)", type: "number", min: 15, step: 5, inputmode: "numeric", value: bk.defaultSessionMinutes, hint: "Used when a package has no length. Empty means 60 minutes." }),
          minNoticeHours: F.field({ id: "br-notice", name: "booking.minNoticeHours", label: "Minimum notice (hours)", required: true, type: "number", min: 0, step: 1, inputmode: "numeric", value: bk.minNoticeHours }),
          windowDays: F.field({ id: "br-window", name: "booking.windowDays", label: "How far ahead customers can book (days)", required: true, type: "number", min: 1, step: 1, inputmode: "numeric", value: bk.windowDays })
        };
        var b3 = h("button", { type: "submit", class: "btn" }, "Save"), f3 = h("form", { novalidate: true }, r.defaultSessionMinutes.el, r.minNoticeHours.el, r.windowDays.el, b3);
        f3.addEventListener("submit", function (e) {
          e.preventDefault();
          var num = function (v) { return /^\d+$/.test(v) ? null : "Enter a whole number."; };
          if (!F.validate([{ field: r.minNoticeHours, check: num }, { field: r.windowDays, check: num }, { field: r.defaultSessionMinutes, check: function (v) { return v === "" ? null : num(v); } }])) { return; }
          save(b3, function (b) { b.booking = { minNoticeHours: int(r.minNoticeHours.value()), windowDays: int(r.windowDays.value()), defaultSessionMinutes: int(r.defaultSessionMinutes.value()) }; }).catch(function (err) { A.formError(err, { "booking.minNoticeHours": r.minNoticeHours, "booking.windowDays": r.windowDays, "booking.defaultSessionMinutes": r.defaultSessionMinutes }); });
        });
        ctx.main.appendChild(group("Booking rules", "g-rules", f3));

        // delivery areas
        var areas = (cur.deliveryAreas || []).map(function (a) { return { id: a.id, name: a.name, feeRwf: a.feeRwf === undefined ? null : a.feeRwf, active: a.active !== false }; }), aBox = h("div");
        function paintAreas() {
          AS.clear(aBox);
          if (!areas.length) { aBox.appendChild(h("p", { class: "muted", text: "No delivery areas yet. Customers will type their district instead." })); }
          areas.forEach(function (a, i) {
            var nm = F.field({ id: "da-n" + i, name: "name", label: "Area name", required: true, value: a.name, maxlength: 100 }), fee = F.field({ id: "da-f" + i, name: "fee", label: "Fee (RwF)", type: "number", min: 0, step: 1, inputmode: "numeric", value: a.feeRwf, hint: "Leave empty for 'to be confirmed'." });
            nm.input.addEventListener("input", function () { a.name = nm.input.value; }); fee.input.addEventListener("input", function () { a.feeRwf = int(fee.input.value); });
            a.nameField = nm; a.feeField = fee;
            var on = F.checkbox("da-a" + i, "Delivers here", a.active); on.input.addEventListener("change", function () { a.active = on.input.checked; });
            aBox.appendChild(h("div", { class: "hours-row" }, h("div", { class: "form-grid form-grid--2" }, nm.el, fee.el), on.el,
              h("button", { type: "button", class: "btn btn--secondary btn--sm", onclick: function () { AS.confirm({ title: "Are you sure? This cannot be undone.", body: "Remove " + (a.name || "this area") + "?", confirmLabel: "Yes, remove", danger: true }).then(function (ok) { if (ok) { areas.splice(i, 1); paintAreas(); } }); } }, AS.icon("remove", "icon--sm"), "Remove")));
          });
        }
        paintAreas();
        var b4 = h("button", { type: "button", class: "btn" }, "Save");
        b4.addEventListener("click", function () {
          var bad = false; areas.forEach(function (a) { if (!a.name.trim()) { a.nameField.setError("Enter an area name."); bad = true; } if (a.feeField.input.value !== "" && !/^\d+$/.test(a.feeField.input.value)) { a.feeField.setError("Enter a whole number."); bad = true; } });
          if (bad) { return; }
          save(b4, function (b) { b.deliveryAreas = areas.map(function (a) { var x = { name: a.name.trim(), feeRwf: a.feeRwf, active: a.active }; if (a.id) { x.id = a.id; } return x; }); }).catch(A.fail);
        });
        ctx.main.appendChild(group("Delivery areas and fees", "g-areas", [aBox, h("div", { class: "form-actions" }, h("button", { type: "button", class: "btn btn--secondary", onclick: function () { areas.push({ name: "", feeRwf: null, active: true }); paintAreas(); } }, AS.icon("plus", "icon--sm"), "Add an area"), b4)]));

        // portfolio categories
        var cats = (cur.portfolioCategories || []).map(function (x) { return { id: x.id, name: x.name }; }), cBox = h("div");
        function paintCats() {
          AS.clear(cBox);
          cats.forEach(function (x, i) {
            var nm = F.field({ id: "pc-" + i, name: "name", label: "Category name", required: true, value: x.name, maxlength: 50 }); nm.input.addEventListener("input", function () { x.name = nm.input.value; }); x.field = nm;
            cBox.appendChild(h("div", { class: "row" }, nm.el, h("button", { type: "button", class: "btn btn--secondary btn--sm", onclick: function () { AS.confirm({ title: "Are you sure? This cannot be undone.", body: "Remove the category " + (x.name || "") + "?", confirmLabel: "Yes, remove", danger: true }).then(function (ok) { if (ok) { cats.splice(i, 1); paintCats(); } }); } }, AS.icon("remove", "icon--sm"), "Remove")));
          });
        }
        paintCats();
        var b5 = h("button", { type: "button", class: "btn" }, "Save");
        b5.addEventListener("click", function () {
          var bad = false; cats.forEach(function (x) { if (!x.name.trim()) { x.field.setError("Enter a category name."); bad = true; } });
          if (bad) { return; }
          save(b5, function (b) { b.portfolioCategories = cats.map(function (x) { var o = { name: x.name.trim() }; if (x.id) { o.id = x.id; } return o; }); }).catch(A.fail);
        });
        ctx.main.appendChild(group("Category lists", "g-cats", [h("p", { class: "muted", text: "Portfolio categories shown as filters on the Portfolio page." }), cBox, h("div", { class: "form-actions" }, h("button", { type: "button", class: "btn btn--secondary", onclick: function () { cats.push({ name: "" }); paintCats(); } }, AS.icon("plus", "icon--sm"), "Add a category"), b5)]));
      }
      build();
    });
  });

  // ================= A17-A19 staff (owner only)
  A.route("/admin/staff", { id: "A17", title: "Staff accounts", roles: ["owner"] }, function (ctx) {
    ctx.main.appendChild(h("div", { class: "row" }, h("a", { class: "btn", href: "/admin/staff/new" }, AS.icon("plus", "icon--sm"), "Add a staff member")));
    return AS.api.get("/admin/staff").then(function (res) {
      if (!ctx.current()) { return; }
      ctx.main.appendChild(h("ul", { class: "list" }, (res.items || []).map(function (u) {
        return h("li", { class: "list-item" }, h("p", { class: "list-item__title" }, h("a", { href: "/admin/staff/" + u.id, text: u.name })), h("p", { class: "list-item__meta", text: u.email + " · " + (u.role === "owner" ? "Owner" : "Support staff") }),
          h("div", {}, u.active ? (u.invitationPending ? h("span", { class: "pill-note", text: "Invitation sent" }) : h("span", { class: "pill-note", text: "Active" })) : A.chip("off")));
      })));
    });
  });
  A.route("/admin/staff/new", { id: "A18", title: "Add a staff member", roles: ["owner"] }, function (ctx) {
    var name = F.field({ id: "sf-name", name: "name", label: "Name", required: true, maxlength: 100 }), email = F.field({ id: "sf-email", name: "email", label: "Email", required: true, type: "email", maxlength: 254 });
    var btn = h("button", { type: "submit", class: "btn btn--lg" }, "Send invitation");
    var form = h("form", { novalidate: true, class: "panel" }, name.el, email.el, h("p", { class: "muted", text: "They will join as support staff. We email them a link to choose a password." }), h("div", { class: "form-actions" }, btn, h("a", { class: "btn btn--secondary", href: "/admin/staff", text: "Cancel" })));
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      if (!F.validate([{ field: name, check: F.V.name }, { field: email, check: F.V.email }])) { return; }
      A.busy(btn, function () { return AS.api.post("/admin/staff", { name: name.value(), email: email.value() }); }).then(function () { AS.toast("Invitation sent to " + email.value() + "."); A.go("/admin/staff"); },
        function (err) { if (err.code === "conflict") { email.setError("That email already has an account."); email.input.focus(); } else { A.formError(err, { name: name, email: email }); } });
    });
    ctx.main.appendChild(h("p", {}, h("a", { href: "/admin/staff", text: "Back to staff accounts" }))); ctx.main.appendChild(form);
  });
  A.route("/admin/staff/:id", { id: "A19", title: "Staff member", roles: ["owner"] }, function (ctx) {
    function load() {
      return AS.api.get("/admin/staff/" + encodeURIComponent(ctx.params.id)).then(function (u) {
        if (!ctx.current()) { return; }
        AS.clear(ctx.main);
        ctx.main.appendChild(h("p", {}, h("a", { href: "/admin/staff", text: "Back to staff accounts" })));
        ctx.main.appendChild(h("section", { class: "panel" }, h("h2", { text: u.name }), A.dl([["Email", u.email], ["Role", u.role === "owner" ? "Owner" : "Support staff"], ["Access", u.active ? "On" : "Switched off"], ["Last signed in", u.lastSignInAt ? AS.fmt.instant(u.lastSignInAt) : "Not yet"]])));
        var acts = h("div", { class: "decision" }), me = A.user && A.user.id === u.id;
        var reset = h("button", { type: "button", class: "btn btn--secondary", text: "Send a password reset link" });
        reset.addEventListener("click", function () { A.busy(reset, function () { return AS.api.post("/admin/staff/" + u.id + "/reset-password-link"); }).then(function () { AS.toast("A reset link is on its way."); }, A.fail); });
        acts.appendChild(reset);
        if (u.role !== "owner" && !me) {
          var sw = h("button", { type: "button", class: "btn btn--secondary", text: u.active ? "Switch off access" : "Switch on access" });
          sw.addEventListener("click", function () { A.busy(sw, function () { return AS.api.patch("/admin/staff/" + u.id, { active: !u.active }); }).then(function () { AS.toast("Saved"); load(); }, A.fail); });
          var rm = h("button", { type: "button", class: "btn btn--danger", text: "Remove this staff member" });
          rm.addEventListener("click", function () { AS.confirm({ title: "Are you sure? This cannot be undone.", body: "Their past actions stay in the records under their name.", confirmLabel: "Yes, remove", danger: true }).then(function (ok) { if (ok) { A.busy(rm, function () { return AS.api.del("/admin/staff/" + u.id); }).then(function () { AS.toast("Removed"); A.go("/admin/staff"); }, A.fail); } }); });
          acts.appendChild(sw); acts.appendChild(rm);
        }
        ctx.main.appendChild(A.section("What you can do", "sf-do")); ctx.main.lastChild.appendChild(acts);
      });
    }
    return load();
  });

  // ================= A20 policies and agreements (owner only). The edit screen is /admin/policies?edit={slug}
  A.route("/admin/policies", { id: "A20", title: "Policies and agreements", roles: ["owner"] }, function (ctx) {
    var edit = ctx.query.get("edit");
    if (edit) { return editDoc(ctx, edit); }
    return AS.api.get("/admin/documents").then(function (res) {
      if (!ctx.current()) { return; }
      ctx.main.appendChild(h("p", { class: "muted", text: "Customers must accept the newest version when they book or order." }));
      ctx.main.appendChild(h("ul", { class: "list" }, (res.items || []).map(function (d) {
        return h("li", { class: "list-item" }, h("p", { class: "list-item__title" }, h("a", { href: "/admin/policies?edit=" + encodeURIComponent(d.slug), text: d.title })), h("p", { class: "list-item__meta", text: "Version " + d.currentVersion + " · Last changed " + AS.fmt.instant(d.publishedAt) }), h("div", {}, A.chip(d.reviewStatus === "reviewed" ? "reviewed" : "draft")));
      })));
    });
  });
  function editDoc(ctx, slug) {
    return AS.api.get("/admin/documents/" + encodeURIComponent(slug)).then(function (d) {
      if (!ctx.current()) { return; }
      A.setPage({ title: d.title || "Edit document", page: "A20" });
      var body = F.field({ id: "dc-body", name: "body", label: "Text", required: true, rows: 22, value: d.body }), pts = F.field({ id: "dc-pts", name: "summaryPoints", label: "Key points (one per line)", rows: 4, value: (d.summaryPoints || []).join("\n"), hint: "Shown above the agreement text at checkout." });
      var note = F.field({ id: "dc-note", name: "changeNote", label: "What changed", required: true, maxlength: 300 });
      var pub = h("button", { type: "submit", class: "btn btn--lg" }, "Publish as new version");
      var form = h("form", { novalidate: true, class: "panel" }, body.el, pts.el, note.el, h("div", { class: "form-actions" }, pub, h("a", { class: "btn btn--secondary", href: "/admin/policies", text: "Cancel" })));
      form.addEventListener("submit", function (e) {
        e.preventDefault();
        if (!F.validate([{ field: body, check: F.V.required("the text") }, { field: note, check: F.V.required("a short note about what changed") }])) { return; }
        AS.confirm({ title: "Publish as a new version?", body: "Customers will have to accept the new version. Earlier acceptances stay on record with the version they accepted.", confirmLabel: "Yes, publish" }).then(function (ok) {
          if (!ok) { return; }
          A.busy(pub, function () { return AS.api.post("/admin/documents/" + encodeURIComponent(slug) + "/versions", { body: body.input.value, summaryPoints: pts.input.value.split("\n").map(function (x) { return x.trim(); }).filter(Boolean), changeNote: note.value() }); }).then(function () { AS.toast("Published"); reloadHere(); }, function (err) { A.formError(err, { body: body, summaryPoints: pts, changeNote: note }); });
        });
      });
      ctx.main.appendChild(h("p", {}, h("a", { href: "/admin/policies", text: "Back to policies and agreements" })));
      ctx.main.appendChild(h("section", { class: "panel" }, h("p", {}, A.chip(d.reviewStatus === "reviewed" ? "reviewed" : "draft")), (function () {
        if (d.reviewStatus === "reviewed") { return h("p", { class: "muted", text: "This document has been reviewed." }); }
        var mr = h("button", { type: "button", class: "btn btn--secondary", text: "Mark as reviewed" });
        mr.addEventListener("click", function () { A.busy(mr, function () { return AS.api.post("/admin/documents/" + encodeURIComponent(slug) + "/mark-reviewed"); }).then(function () { AS.toast("Saved"); reloadHere(); }, A.fail); });
        return mr;
      })()));
      ctx.main.appendChild(form);
      var vs = A.section("Past versions", "dc-vers");
      vs.appendChild(h("ul", { class: "list" }, (d.versions || []).map(function (v) {
        var view = h("button", { type: "button", class: "btn btn--secondary btn--sm", text: "View" });
        view.addEventListener("click", function () { AS.api.get("/documents/" + encodeURIComponent(slug), { version: v.version }).then(function (doc) { var m = AS.modal("Version " + v.version, h("div", { class: "agreement" }, h("div", { class: "agreement__text" }, AS.ui.markdown(doc.body)))); m.dialog.appendChild(h("div", { class: "dialog__actions" }, h("button", { type: "button", class: "btn", text: "Close", onclick: m.close }))); }, A.fail); });
        return h("li", { class: "list-item" }, h("p", { class: "list-item__title", text: "Version " + v.version }), h("p", { class: "list-item__meta", text: AS.fmt.instant(v.publishedAt) + " · " + (v.publishedByName || "") + (v.changeNote ? " · " + v.changeNote : "") }), h("div", { class: "list-item__actions" }, view));
      })));
      ctx.main.appendChild(vs);
    });
  }
})(window.AS);
