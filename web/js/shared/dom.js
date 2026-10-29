/*
 * DOM helpers shared by the public site and the admin: element builder, formatting (RwF, dates in
 * British style), icons, images with placeholders, toasts and the confirm dialog.
 */
(function (AS) {
  "use strict";

  function h(tag, attrs) {
    var el = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      var v = attrs[k];
      if (v === null || v === undefined || v === false) { return; }
      if (k === "class") { el.className = v; }
      else if (k === "text") { el.textContent = v; }
      else if (k.slice(0, 2) === "on" && typeof v === "function") { el.addEventListener(k.slice(2), v); }
      else if (k === "value") { el.value = v; }
      else if (v === true) { el.setAttribute(k, ""); }
      else { el.setAttribute(k, v); }
    });
    append(el, Array.prototype.slice.call(arguments, 2));
    return el;
  }
  function append(el, kids) {
    kids.forEach(function (c) {
      if (c === null || c === undefined || c === false) { return; }
      if (Array.isArray(c)) { append(el, c); }
      else { el.appendChild(typeof c === "object" ? c : document.createTextNode(String(c))); }
    });
    return el;
  }
  function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } return el; }
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  var DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
  var MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
  function parseYmd(s) { var p = s.split("-"); return new Date(Date.UTC(+p[0], +p[1] - 1, +p[2])); }
  function ymd(d) { return d.getUTCFullYear() + "-" + ("0" + (d.getUTCMonth() + 1)).slice(-2) + "-" + ("0" + d.getUTCDate()).slice(-2); }
  var fmt = {
    rwf: function (n) { return n === null || n === undefined ? "To be confirmed" : Number(n).toLocaleString("en-GB") + " RwF"; },
    date: function (s) { var d = parseYmd(s); return d.getUTCDate() + " " + MONTHS[d.getUTCMonth()] + " " + d.getUTCFullYear(); },
    dateLong: function (s) { var d = parseYmd(s); return DAYS[d.getUTCDay()] + ", " + fmt.date(s); },
    monthName: function (y, m) { return MONTHS[m] + " " + y; },
    dayName: function (s) { return DAYS[parseYmd(s).getUTCDay()]; },
    instant: function (iso) {
      var d = new Date(iso);
      if (isNaN(d)) { return iso; }
      return d.toLocaleString("en-GB", { timeZone: "Africa/Kigali", day: "numeric", month: "long", year: "numeric", hour: "2-digit", minute: "2-digit", hour12: false });
    },
    time12: function (t) { var p = t.split(":"), hh = +p[0]; return ((hh % 12) || 12) + ":" + p[1] + " " + (hh < 12 ? "AM" : "PM"); },
    daysShort: { mon: "Mon", tue: "Tue", wed: "Wed", thu: "Thu", fri: "Fri", sat: "Sat", sun: "Sun" },
    hours: function (oh) {
      var names = { mon: "Mon", tue: "Tue", wed: "Wed", thu: "Thu", fri: "Fri", sat: "Sat", sun: "Sun" };
      return (oh || []).map(function (r) {
        var d = r.days.length > 2 ? names[r.days[0]] + " to " + names[r.days[r.days.length - 1]] : r.days.map(function (x) { return names[x]; }).join(", ");
        return { days: d, range: fmt.time12(r.open) + " to " + fmt.time12(r.close) };
      });
    },
    pad: function (n) { return ("0" + n).slice(-2); }
  };
  fmt.parseYmd = parseYmd; fmt.ymd = ymd;
  function todayKigali() {
    var p = new Intl.DateTimeFormat("en-CA", { timeZone: "Africa/Kigali", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
    return p; // en-CA gives YYYY-MM-DD
  }

  function icon(name, extra) {
    return h("span", { class: "icon icon--" + name + (extra ? " " + extra : ""), "aria-hidden": "true" });
  }

  var PH = { card: "/assets/placeholders/placeholder-card-4x5.svg", portrait: "/assets/placeholders/placeholder-portrait-3x4.svg", square: "/assets/placeholders/placeholder-square-1x1.svg", hero: "/assets/placeholders/placeholder-landscape-16x9.svg" };
  function media(id, size) { return "/media/" + encodeURIComponent(id) + "/" + size + ".jpg"; }
  // Image element from a contract Image {id, url, thumbUrl, width, height, alt}. Uses the documented
  // /media/{id}/{size}.jpg sizes for srcset; falls back to a neutral placeholder.
  function img(image, o) {
    o = o || {};
    var ph = PH[o.ratio || "card"];
    var el = h("img", { alt: o.alt !== undefined ? o.alt : (image && image.alt) || "", loading: o.eager ? "eager" : "lazy", decoding: "async" });
    if (!image || !image.id) { el.src = ph; if (!o.alt && !(image && image.alt)) { el.alt = ""; } return el; }
    el.src = media(image.id, o.size || "card");
    el.srcset = media(image.id, "thumb") + " 400w, " + media(image.id, "card") + " 800w, " + media(image.id, "large") + " 1600w";
    el.sizes = o.sizes || "(min-width: 960px) 33vw, (min-width: 640px) 50vw, 100vw";
    if (image.width && image.height) { el.width = image.width; el.height = image.height; }
    el.addEventListener("error", function once() { el.removeEventListener("error", once); el.removeAttribute("srcset"); el.src = ph; });
    return el;
  }
  function badge(file, alt) {
    var p = "/assets/badges/" + file + ".svg"; // the build supplies AS.assetMap (logical path -> fingerprinted path)
    return h("img", { class: "badge", src: (AS.assetMap && AS.assetMap[p]) || p, alt: alt });
  }

  function toast(message, kind) {
    var host = document.getElementById("toasts");
    if (!host) { return; }
    var n = h("div", { class: "alert alert--" + (kind || "success") }, icon(kind === "error" ? "error" : kind === "warning" ? "warning" : "success"), h("div", { class: "alert__body" }, h("p", { text: message })),
      h("button", { type: "button", class: "btn btn--text", "aria-label": "Dismiss message", onclick: function () { n.remove(); } }, icon("close", "icon--sm")));
    host.appendChild(n);
    setTimeout(function () { if (n.parentNode) { n.remove(); } }, kind === "error" ? 10000 : 6000);
  }

  // Native <dialog> gives focus trapping, Esc to close and an inert page behind it.
  function confirm(o) {
    return new Promise(function (resolve) {
      var dlg = h("dialog", { class: "dialog", "aria-labelledby": "dlg-title" });
      var ok = h("button", { type: "button", class: "btn " + (o.danger ? "btn--danger" : ""), text: o.confirmLabel || "Yes" });
      var cancel = h("button", { type: "button", class: "btn btn--secondary", text: o.cancelLabel || "Cancel" });
      dlg.appendChild(h("h2", { id: "dlg-title", text: o.title || "Are you sure?" }));
      if (o.body) { dlg.appendChild(typeof o.body === "string" ? h("p", { text: o.body }) : o.body); }
      dlg.appendChild(h("div", { class: "dialog__actions" }, cancel, ok));
      var result = false;
      ok.addEventListener("click", function () { result = true; dlg.close(); });
      cancel.addEventListener("click", function () { dlg.close(); });
      dlg.addEventListener("close", function () { dlg.remove(); resolve(result); });
      document.body.appendChild(dlg);
      dlg.showModal();
      cancel.focus(); // first focus on the safe choice
    });
  }

  // Shows a modal with arbitrary content (used for forms in the admin). Returns {dialog, close}.
  function modal(title, content) {
    var dlg = h("dialog", { class: "dialog", "aria-labelledby": "mdl-title" }, h("h2", { id: "mdl-title", text: title }), content);
    dlg.addEventListener("close", function () { dlg.remove(); });
    document.body.appendChild(dlg);
    dlg.showModal();
    return { dialog: dlg, close: function () { dlg.close(); } };
  }

  function alertBox(kind, content) {
    var body = h("div", { class: "alert__body" }, typeof content === "string" ? h("p", { text: content }) : content);
    return h("div", { class: "alert alert--" + kind, role: kind === "error" ? "alert" : null }, icon(kind === "error" ? "error" : kind === "warning" ? "warning" : kind === "info" ? "info" : "success"), body);
  }

  function setHead(o) {
    if (o.title) {
      document.title = o.title;
      var og = $('meta[property="og:title"]'); if (og) { og.setAttribute("content", o.title); }
    }
    if (o.description) {
      var d = $('meta[name="description"]'); if (d) { d.setAttribute("content", o.description); }
      var ogd = $('meta[property="og:description"]'); if (ogd) { ogd.setAttribute("content", o.description); }
    }
  }

  AS.h = h; AS.clear = clear; AS.$ = $; AS.$$ = $$; AS.fmt = fmt; AS.todayKigali = todayKigali; AS.icon = icon;
  AS.img = img; AS.media = media; AS.badge = badge; AS.toast = toast; AS.confirm = confirm; AS.modal = modal;
  AS.alertBox = alertBox; AS.setHead = setHead;
})(window.AS = window.AS || {});
