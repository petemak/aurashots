/*
 * Month calendar picker used on the booking page (design-spec P8 step 1). Days come from
 * GET /api/availability (one request per displayed month). Keyboard: arrows move by day/week,
 * Home/End by row, PageUp/PageDown by month, Enter/Space selects. Unavailable days are focusable
 * but aria-disabled and explained in their accessible name.
 */
(function (AS) {
  "use strict";
  var h = AS.h, fmt = AS.fmt;
  var STATUS_TEXT = { available: "available", blocked: "not available, blocked", full: "fully booked", past: "in the past", tooSoon: "too soon to book", closed: "closed" };

  function addDays(s, n) { var d = fmt.parseYmd(s); d.setUTCDate(d.getUTCDate() + n); return fmt.ymd(d); }
  function ym(s) { return s.slice(0, 7); }

  // o: {getDays(from,to) -> Promise<[{date,status}]>, maxDate, selected, onSelect(date), onError(err, retry)}
  function calendar(o) {
    var today = AS.todayKigali();
    var view = (o.selected || today).slice(0, 7) + "-01";
    var focus = o.selected || today;
    var selected = o.selected || null;
    var days = {}, loading = true, token = 0;
    var root = h("div", { class: "calendar" });

    function monthBounds(first) {
      var d = fmt.parseYmd(first);
      var last = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0));
      return { first: first, last: fmt.ymd(last), count: last.getUTCDate(), offset: (d.getUTCDay() + 6) % 7, y: d.getUTCFullYear(), m: d.getUTCMonth() };
    }

    function load() {
      var b = monthBounds(view), my = ++token;
      loading = true; render();
      o.getDays(b.first, b.last).then(function (list) {
        if (my !== token) { return; }
        days = {}; list.forEach(function (d) { days[d.date] = d; });
        loading = false; render();
      }, function (err) { if (my === token) { loading = false; render(); if (o.onError) { o.onError(err, load); } } });
    }

    function shiftMonth(n) {
      var d = fmt.parseYmd(view); d.setUTCMonth(d.getUTCMonth() + n); view = fmt.ymd(d);
      if (ym(focus) !== ym(view)) { focus = view; }
      load();
    }

    function render(keepFocus) {
      var b = monthBounds(view), hadFocus = keepFocus || root.contains(document.activeElement) && document.activeElement.classList.contains("calendar__day");
      var prevDisabled = ym(view) <= ym(today);
      var nextDisabled = o.maxDate && addDays(b.last, 1) > o.maxDate;
      var grid = h("div", { class: "calendar__grid", role: "group", "aria-label": fmt.monthName(b.y, b.m), "aria-busy": loading ? "true" : "false" });
      ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].forEach(function (d) { grid.appendChild(h("span", { class: "calendar__dow", "aria-hidden": "true", text: d })); });
      for (var e = 0; e < b.offset; e++) { grid.appendChild(h("span", { class: "calendar__day calendar__day--empty", "aria-hidden": "true" })); }
      for (var n = 1; n <= b.count; n++) {
        var date = b.first.slice(0, 8) + fmt.pad(n), d = days[date];
        var status = loading || !d ? (date < today ? "past" : "unknown") : d.status;
        var ok = status === "available";
        var btn = h("button", {
          type: "button", class: "calendar__day" + (date === today ? " calendar__day--today" : ""), "data-date": date, "data-status": status,
          "aria-disabled": ok ? null : "true", "aria-pressed": ok ? (date === selected ? "true" : "false") : null,
          "aria-label": fmt.dateLong(date) + ", " + (loading ? "loading" : (STATUS_TEXT[status] || "not available")) + (date === today ? ", today" : ""),
          tabindex: date === focus ? "0" : "-1", text: String(n)
        });
        btn.addEventListener("click", (function (dt, okk) { return function () { focus = dt; if (okk) { selected = dt; o.onSelect(dt); render(true); } }; })(date, ok));
        grid.appendChild(btn);
      }
      grid.addEventListener("keydown", function (ev) {
        var step = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }[ev.key], t = null;
        if (step !== undefined) { t = addDays(focus, step); }
        else if (ev.key === "Home") { t = addDays(focus, -((fmt.parseYmd(focus).getUTCDay() + 6) % 7)); }
        else if (ev.key === "End") { t = addDays(focus, 6 - ((fmt.parseYmd(focus).getUTCDay() + 6) % 7)); }
        else if (ev.key === "PageUp" || ev.key === "PageDown") { var dd = fmt.parseYmd(focus); dd.setUTCMonth(dd.getUTCMonth() + (ev.key === "PageUp" ? -1 : 1)); t = fmt.ymd(dd); }
        if (!t) { return; }
        ev.preventDefault();
        if (t < today && ym(t) < ym(today)) { return; }
        if (o.maxDate && t > addDays(o.maxDate, 30)) { return; }
        focus = t;
        if (ym(t) !== ym(view)) { view = t.slice(0, 7) + "-01"; load(); setTimeout(function () { var x = root.querySelector('[data-date="' + focus + '"]'); if (x) { x.focus(); } }, 0); }
        else { render(true); }
      });
      AS.clear(root).appendChild(h("div", { class: "calendar__head" },
        h("button", { type: "button", class: "btn btn--icon btn--secondary", "aria-label": "Previous month", disabled: prevDisabled, onclick: function () { shiftMonth(-1); } }, AS.icon("chevron-left")),
        h("h3", { class: "calendar__title", "aria-live": "polite", text: fmt.monthName(b.y, b.m) }),
        h("button", { type: "button", class: "btn btn--icon btn--secondary", "aria-label": "Next month", disabled: nextDisabled, onclick: function () { shiftMonth(1); } }, AS.icon("chevron-right"))));
      root.appendChild(grid);
      root.appendChild(h("ul", { class: "legend" },
        h("li", {}, h("span", { class: "legend__swatch legend__swatch--selected" }), "Selected"),
        h("li", {}, h("span", { class: "legend__swatch legend__swatch--blocked" }), "Blocked or closed"),
        h("li", {}, h("span", { class: "legend__swatch" }), "Greyed days are past, too soon, fully booked or blocked")));
      if (hadFocus) { var f = root.querySelector('[data-date="' + focus + '"]'); if (f) { f.focus(); } }
    }

    load();
    return { el: root, reload: load, select: function (d) { selected = d; render(); } };
  }
  AS.calendar = calendar;
})(window.AS = window.AS || {});
