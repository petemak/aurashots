/*
 * Admin shell (design-spec Part 2, architecture D10): one HTML file, a small History-API router,
 * session check (GET /api/admin/auth/me), role-aware menu, the "How do I...?" help link and drawer
 * (topics built from admin-help.md at build time), and helpers shared by every admin screen.
 * The server enforces roles; hiding menu items here is convenience only.
 */
(function (AS) {
  "use strict";
  var h = AS.h, $ = AS.$;
  var A = AS.admin = { user: null, routes: [], ctx: null };
  var navToken = 0;

  var NAV = [
    { href: "/admin", label: "Dashboard", icon: "dashboard", exact: true },
    { href: "/admin/session-requests", label: "Session requests", icon: "camera" },
    { href: "/admin/orders", label: "Merchandise orders", icon: "merchandise-bag" },
    { href: "/admin/services", label: "Services", icon: "list" },
    { href: "/admin/merchandise", label: "Merchandise", icon: "outfit" },
    { href: "/admin/portfolio", label: "Portfolio", icon: "picture" },
    { href: "/admin/calendar", label: "Calendar", icon: "calendar" },
    { href: "/admin/messages", label: "Messages", icon: "email" },
    { href: "/admin/settings", label: "Settings", icon: "settings" },
    { href: "/admin/staff", label: "Staff accounts", icon: "user", roles: ["owner"] },
    { href: "/admin/policies", label: "Policies and agreements", icon: "lock", roles: ["owner"] },
    { href: "/admin/account", label: "My account", icon: "user" },
    { href: "/admin/help", label: "Help", icon: "help" }
  ];

  // ---------- routing
  A.route = function (pattern, opts, handler) {
    var names = [], re = new RegExp("^" + pattern.replace(/:([a-z]+)/g, function (m, n) { names.push(n); return "([^/]+)"; }) + "/?$");
    A.routes.push({ re: re, names: names, opts: opts, handler: handler });
  };
  A.go = function (path, replace) {
    if (replace) { history.replaceState(null, "", path); } else { history.pushState(null, "", path); }
    dispatch();
  };
  function dispatch() {
    var my = ++navToken, url = new URL(location.href), path = url.pathname.replace(/\/$/, "") || "/admin";
    var found = null, params = {};
    A.routes.some(function (r) {
      var m = r.re.exec(path);
      if (!m) { return false; }
      r.names.forEach(function (n, i) { params[n] = decodeURIComponent(m[i + 1]); });
      found = r; return true;
    });
    closeDrawer(); closeMenu();
    var content = AS.clear($("#admin-content"));
    if (!found) { A.setPage({ title: "Page not found" }); content.appendChild(AS.alertBox("warning", h("div", {}, h("p", { text: "We could not find that admin page." }), h("a", { href: "/admin", text: "Go to the dashboard" })))); return; }
    if (!found.opts.public && !A.user) { A.go("/admin/sign-in?next=" + encodeURIComponent(path + url.search), true); return; }
    if (found.opts.guestOnly && A.user) { A.go("/admin", true); return; }
    document.body.classList.toggle("is-guest", !A.user);
    paintChrome();
    if (found.opts.roles && A.user && found.opts.roles.indexOf(A.user.role) < 0) {
      A.setPage({ title: found.opts.title || "Not available" });
      content.appendChild(AS.alertBox("warning", h("div", {}, h("p", { text: "This page is only for the owner." }), h("a", { href: "/admin", text: "Back to the dashboard" }))));
      return;
    }
    var ctx = { params: params, query: url.searchParams, main: content, id: found.opts.id, token: my, current: function () { return my === navToken; } };
    A.ctx = ctx;
    A.setPage({ title: found.opts.title || "Admin", page: found.opts.id });
    var p;
    try { p = found.handler(ctx); } catch (e) { p = Promise.reject(e); }
    Promise.resolve(p).then(function () { if (my === navToken) { AS.ui.focusHeading($("#admin-title")); } }, function (err) {
      if (my !== navToken) { return; }
      AS.clear(content).appendChild(err && err.status === 404 ? AS.alertBox("warning", "We could not find that record. It may have been removed.") : AS.ui.errorBox(err, function () { dispatch(); }));
    });
  }

  A.setPage = function (o) {
    $("#admin-title").textContent = o.title;
    document.title = o.title + " | Aura Shots admin";
    var link = $("#help-link"), topic = o.page ? topicForPage(o.page) : null;
    link.hidden = !topic;
    if (topic) { link.href = "/admin/help/" + topic.id; link.setAttribute("data-help", topic.id); }
  };

  // ---------- chrome (menu, header)
  function paintChrome() {
    var nav = AS.clear($("#admin-nav")), path = location.pathname.replace(/\/$/, "") || "/admin";
    if (A.user) {
      nav.appendChild(h("ul", {}, NAV.filter(function (n) { return !n.roles || n.roles.indexOf(A.user.role) >= 0; }).map(function (n) {
        var cur = n.exact ? path === n.href : (path === n.href || path.indexOf(n.href + "/") === 0);
        return h("li", {}, h("a", { href: n.href, "aria-current": cur ? "page" : null }, AS.icon(n.icon), n.label));
      })));
    }
    var u = AS.clear($("#admin-user"));
    if (A.user) { u.appendChild(h("a", { href: "/admin/account", text: A.user.name + " (" + (A.user.role === "owner" ? "Owner" : "Support") + ")" })); }
  }
  function closeMenu() { var n = $("#admin-nav"); n.classList.remove("is-open"); $("#admin-menu-toggle").setAttribute("aria-expanded", "false"); }

  // ---------- help (topics prebuilt from admin-help.md)
  function role() { return A.user ? A.user.role : "support"; }
  function visibleTopics() { return (AS.helpTopics || []).filter(function (t) { return t.roles.indexOf(role()) >= 0; }); }
  function topicForPage(pid) {
    var all = AS.helpTopics || [], r = role();
    return all.filter(function (t) { return t.pages.indexOf(pid) >= 0 && t.roles.indexOf(r) >= 0; })[0] || null;
  }
  function topicBody(t, inDrawer) {
    var body = h("div", { class: "help-body doc" });
    body.innerHTML = t.html; // built from admin-help.md at build time by scripts/web/build.clj; no runtime Markdown parsing
    if (inDrawer) { AS.$$("a[data-help]", body).forEach(function (a) { a.addEventListener("click", function (e) { e.preventDefault(); openDrawer(a.getAttribute("data-help")); }); }); }
    return body;
  }
  var drawerOpener = null;
  function openDrawer(id) {
    var d = $("#help-drawer"), t = (AS.helpTopics || []).filter(function (x) { return x.id === id; })[0];
    AS.clear(d);
    if (!drawerOpener) { drawerOpener = document.activeElement; }
    var close = h("button", { type: "button", class: "btn btn--secondary btn--sm", onclick: closeDrawer }, AS.icon("close", "icon--sm"), "Close help");
    if (!t || t.roles.indexOf(role()) < 0) {
      d.appendChild(h("div", { class: "row row--between" }, h("h2", { id: "help-drawer-title", text: "Help" }), close));
      d.appendChild(AS.alertBox("info", "That help topic is not available for your account."));
    } else {
      d.appendChild(h("div", { class: "row row--between" }, h("h2", { id: "help-drawer-title", text: t.title }), close));
      d.appendChild(h("p", { class: "muted", text: t.summary }));
      d.appendChild(topicBody(t, true));
      d.appendChild(h("p", {}, h("a", { href: "/admin/help/" + t.id, text: "Open this help as a page" }), " · ", h("a", { href: "/admin/help", text: "All help topics" })));
    }
    d.hidden = false; close.focus();
  }
  function closeDrawer() {
    var d = $("#help-drawer");
    if (d && !d.hidden) { d.hidden = true; if (drawerOpener && document.contains(drawerOpener)) { drawerOpener.focus(); } drawerOpener = null; }
  }
  A.helpTopic = function (ctx) {
    var t = (AS.helpTopics || []).filter(function (x) { return x.id === ctx.params.topic; })[0];
    A.setPage({ title: t && t.roles.indexOf(role()) >= 0 ? t.title : "Help" });
    if (!t) { ctx.main.appendChild(AS.alertBox("warning", h("div", {}, h("p", { text: "We could not find that help topic." }), h("a", { href: "/admin/help", text: "See all help topics" })))); return; }
    if (t.roles.indexOf(role()) < 0) { ctx.main.appendChild(AS.alertBox("info", h("div", {}, h("p", { text: "This help topic is for the owner only." }), h("a", { href: "/admin/help", text: "See the help topics for you" })))); return; }
    ctx.main.appendChild(h("div", { class: "panel" }, h("p", { class: "lead", text: t.summary }), topicBody(t, false)));
    ctx.main.appendChild(h("p", {}, h("a", { href: "/admin/help", text: "All help topics" })));
  };
  A.helpIndex = function (ctx) {
    var groups = {}, order = [];
    visibleTopics().forEach(function (t) { if (!groups[t.group]) { groups[t.group] = []; order.push(t.group); } groups[t.group].push(t); });
    ctx.main.appendChild(h("div", { class: "help-groups" }, order.map(function (g) {
      return h("section", { class: "panel", "aria-labelledby": "hg-" + g.replace(/\W/g, "") }, h("h2", { id: "hg-" + g.replace(/\W/g, ""), text: g }),
        h("ul", { class: "list" }, groups[g].map(function (t) { return h("li", { class: "list-item" }, h("p", { class: "list-item__title" }, h("a", { href: "/admin/help/" + t.id, text: t.title })), h("p", { class: "list-item__meta", text: t.summary })); })));
    })));
  };

  // ---------- shared helpers for screens
  var CHIPS = {
    pending: ["status-waiting-for-you", "Waiting for you"], confirmed: ["status-confirmed", "Confirmed"], declined: ["status-declined", "Declined"], completed: ["status-completed", "Completed"],
    approved: ["status-approved", "Approved"], rejected: ["status-rejected", "Rejected"], paid: ["status-paid", "Paid"], delivered: ["status-delivered", "Delivered"],
    published: ["status-published", "Published"], hidden: ["status-hidden", "Hidden"], needsPrice: ["status-needs-a-price", "Needs a price"], draft: ["status-draft-needs-review", "Draft – needs review"],
    reviewed: ["status-reviewed", "Reviewed"], off: ["status-switched-off", "Switched off"], answered: ["status-answered", "Answered"], "new": ["status-waiting-for-you", "Waiting for you"]
  };
  A.chip = function (kind) { var c = CHIPS[kind]; return c ? AS.badge(c[0], c[1]) : h("span", { class: "pill-note", text: kind }); };

  A.fail = function (err) { AS.toast((err && err.message) || "Something went wrong.", "error"); };
  // Runs fn() (returning a promise) while the button shows its busy state; failures become a toast.
  A.busy = function (btn, fn) {
    btn.disabled = true; btn.setAttribute("aria-busy", "true");
    return Promise.resolve().then(fn).then(function (r) { btn.disabled = false; btn.setAttribute("aria-busy", "false"); return r; }, function (err) { btn.disabled = false; btn.setAttribute("aria-busy", "false"); throw err; });
  };
  A.section = function (title, id) { return h("section", { class: "panel", "aria-labelledby": id }, h("h2", { id: id, text: title })); };
  A.dl = function (pairs) { return h("dl", { class: "dl" }, pairs.filter(function (p) { return p[1] !== null && p[1] !== undefined && p[1] !== ""; }).map(function (p) { return [h("dt", { text: p[0] }), h("dd", {}, p[1])]; })); };
  A.pager = function (ctx, res, base) {
    var page = res.page, last = Math.max(1, Math.ceil(res.total / res.pageSize));
    function href(p) { var q = new URLSearchParams(ctx.query); q.set("page", p); return location.pathname + "?" + q.toString(); }
    if (last <= 1) { return null; }
    return h("nav", { class: "pager", "aria-label": "Pages" }, page > 1 ? h("a", { class: "btn btn--secondary", href: href(page - 1), text: "Previous" }) : h("span"), h("span", { class: "muted", text: "Page " + page + " of " + last }), page < last ? h("a", { class: "btn btn--secondary", href: href(page + 1), text: "Next" }) : h("span"));
  };
  // Filter chips as links that keep the rest of the query. chips: [{value (null = all), label}]
  A.chipLinks = function (ctx, key, chips, current) {
    return h("div", { class: "chips", role: "group", "aria-label": "Filter" }, chips.map(function (c) {
      var q = new URLSearchParams(ctx.query); q.delete("page"); if (c.value) { q.set(key, c.value); } else { q.set(key, "all"); }
      return h("a", { class: "chip", href: location.pathname + "?" + q.toString(), "aria-current": (c.value || null) === current ? "true" : null, text: c.label });
    }));
  };
  // Generic paged list screen: o = {path, key, chips, def, params, empty, item(it)->node}
  A.listScreen = function (ctx, o) {
    var status = ctx.query.get(o.key || "status") || o.def || "all", page = +(ctx.query.get("page") || 1);
    var cur = status === "all" ? null : status;
    ctx.main.appendChild(A.chipLinks(ctx, o.key || "status", o.chips, cur));
    var box = h("div", { "aria-live": "polite" }, h("p", { class: "muted", text: "Loading…" }));
    ctx.main.appendChild(box);
    var params = Object.assign({ page: page, pageSize: 20 }, o.params || {}); params[o.key || "status"] = cur;
    return AS.api.get(o.path, params).then(function (res) {
      if (!ctx.current()) { return; }
      AS.clear(box);
      if (!res.items.length) { box.appendChild(AS.ui.empty(o.empty || "Nothing here", "", null)); return; }
      box.appendChild(h("ul", { class: "list" }, res.items.map(o.item)));
      var pg = A.pager(ctx, res); if (pg) { box.appendChild(pg); }
    });
  };
  // Form-level server error display: shows field errors on matching fields, otherwise a toast.
  A.formError = function (err, fields) {
    var map = {};
    Object.keys(fields).forEach(function (k) { map[k] = fields[k]; });
    var any = false, first = null;
    Object.keys((err && err.fields) || {}).forEach(function (k) {
      var f = map[k] || map[k.split(".").pop()];
      if (f && f.setError) { f.setError(err.fields[k]); any = true; if (!first) { first = f; } }
    });
    if (first && first.input) { first.input.focus(); }
    if (!any) { A.fail(err); }
  };
  A.ymdNow = function () { return AS.todayKigali(); };
  A.siteInfo = AS.siteInfo;

  // ---------- boot
  document.addEventListener("click", function (e) {
    var a = e.target.closest && e.target.closest("a[href]");
    if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || a.target === "_blank") { return; }
    var url = new URL(a.href, location.href);
    if (url.origin !== location.origin || (url.pathname !== "/admin" && url.pathname.indexOf("/admin/") !== 0)) { return; }
    e.preventDefault();
    if (a.id === "help-link" && a.getAttribute("data-help")) { openDrawer(a.getAttribute("data-help")); return; }
    A.go(url.pathname + url.search);
  });
  window.addEventListener("popstate", dispatch);
  window.addEventListener("as:unauthenticated", function () { if (A.user) { A.user = null; AS.api.setCsrf(null); A.go("/admin/sign-in?next=" + encodeURIComponent(location.pathname), true); } });
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") { closeDrawer(); } });

  A.setSession = function (res) { A.user = res.user; AS.api.setCsrf(res.csrfToken); };
  A.clearSession = function () { A.user = null; AS.api.setCsrf(null); };

  document.addEventListener("DOMContentLoaded", function () {
    $("#admin-menu-toggle").addEventListener("click", function () {
      var n = $("#admin-nav"), open = n.classList.toggle("is-open");
      this.setAttribute("aria-expanded", open ? "true" : "false");
    });
    AS.api.get("/admin/auth/me").then(A.setSession, function () { A.clearSession(); }).then(dispatch);
  });
})(window.AS = window.AS || {});
