/* Admin A1 sign in, A2 forgotten/reset password, A3 dashboard, A21 my account. */
(function (AS) {
  "use strict";
  var h = AS.h, F = AS.forms, A = AS.admin;

  function passwordFields(prefix) {
    return {
      pw: F.field({ id: prefix + "-pw", name: "newPassword", label: "New password", required: true, type: "password", autocomplete: "new-password", hint: "At least 12 characters." }),
      pw2: F.field({ id: prefix + "-pw2", name: "repeat", label: "Repeat the new password", required: true, type: "password", autocomplete: "new-password" })
    };
  }
  function checkPasswords(f) {
    return F.validate([
      { field: f.pw, check: function (v) { return v.length >= 12 ? null : "Use at least 12 characters."; } },
      { field: f.pw2, check: function (v) { return v === f.pw.value() ? null : "The two passwords do not match."; } }]);
  }

  A.route("/admin/sign-in", { id: "A1", title: "Sign in", public: true, guestOnly: true }, function (ctx) {
    var email = F.field({ id: "si-email", name: "email", label: "Email", required: true, type: "email", autocomplete: "username" });
    var pw = F.field({ id: "si-pw", name: "password", label: "Password", required: true, type: "password", autocomplete: "current-password" });
    var status = h("div", { "aria-live": "polite" }), btn = h("button", { type: "submit", class: "btn btn--lg btn--block" }, "Sign in");
    var form = h("form", { novalidate: true, class: "panel signin" }, h("img", { src: "/assets/logo-ink.svg", alt: "" , width: "545", height: "320"}), email.el, pw.el, status, btn,
      h("p", { class: "center" }, h("a", { href: "/admin/forgot-password", text: "Forgotten your password?" })));
    form.addEventListener("submit", function (e) {
      e.preventDefault(); AS.clear(status);
      if (!F.validate([{ field: email, check: F.V.email }, { field: pw, check: F.V.required("your password") }])) { return; }
      A.busy(btn, function () { return AS.api.post("/admin/auth/login", { email: email.value(), password: pw.input.value }); }).then(function (res) {
        A.setSession(res);
        var next = ctx.query.get("next");
        A.go(next && next.indexOf("/admin") === 0 && next.indexOf("//") < 0 ? next : "/admin", true);
      }, function (err) {
        status.appendChild(AS.alertBox("error", err.status === 429 ? "Too many attempts. Please wait a few minutes before trying again." : err.status === 401 ? "That email or password is not right." : err.message));
      });
    });
    ctx.main.appendChild(form);
  });

  A.route("/admin/forgot-password", { id: "A2", title: "Forgotten your password?", public: true }, function (ctx) {
    var email = F.field({ id: "fp-email", name: "email", label: "Email", required: true, type: "email", autocomplete: "email" });
    var btn = h("button", { type: "submit", class: "btn btn--lg btn--block" }, "Send me a reset link");
    var form = h("form", { novalidate: true, class: "panel signin" }, h("p", { text: "Type your email and we will send you a link to choose a new password." }), email.el, btn, h("p", { class: "center" }, h("a", { href: "/admin/sign-in", text: "Back to sign in" })));
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      if (!F.validate([{ field: email, check: F.V.email }])) { return; }
      A.busy(btn, function () { return AS.api.post("/admin/auth/forgot-password", { email: email.value() }); }).then(function () {
        AS.clear(form).appendChild(AS.alertBox("success", "If that email is known, a reset link is on its way. It works once and lasts 60 minutes."));
        form.appendChild(h("p", { class: "center" }, h("a", { href: "/admin/sign-in", text: "Back to sign in" })));
      }, function (err) { A.fail(err); });
    });
    ctx.main.appendChild(form);
  });

  A.route("/admin/reset-password", { id: "A2", title: "Choose a new password", public: true }, function (ctx) {
    var token = ctx.query.get("token"), f = passwordFields("rp"), status = h("div", { "aria-live": "polite" });
    var btn = h("button", { type: "submit", class: "btn btn--lg btn--block" }, "Save password");
    var form = h("form", { novalidate: true, class: "panel signin" }, f.pw.el, f.pw2.el, status, btn);
    if (!token) { ctx.main.appendChild(AS.alertBox("warning", h("div", {}, h("p", { text: "This link is missing its code." }), h("a", { href: "/admin/forgot-password", text: "Ask for a new link" })))); return; }
    form.addEventListener("submit", function (e) {
      e.preventDefault(); AS.clear(status);
      if (!checkPasswords(f)) { return; }
      A.busy(btn, function () { return AS.api.post("/admin/auth/reset-password", { token: token, newPassword: f.pw.input.value }); }).then(function () {
        AS.toast("Your password is saved. Please sign in."); A.go("/admin/sign-in", true);
      }, function (err) { status.appendChild(AS.alertBox("error", err.status === 400 ? h("div", {}, h("p", { text: "That link has expired or was already used." }), h("a", { href: "/admin/forgot-password", text: "Ask for a new link" })) : err.message)); });
    });
    ctx.main.appendChild(form);
  });

  A.route("/admin", { id: "A3", title: "Dashboard" }, function (ctx) {
    return AS.api.get("/admin/dashboard").then(function (d) {
      if (!ctx.current()) { return; }
      var stat = function (n, label, href) { return h("a", { class: "stat", href: href }, h("span", { class: "stat__n", text: String(n) }), h("span", { class: "stat__label", text: label })); };
      ctx.main.appendChild(h("div", { class: "stat-grid" },
        stat(d.pendingSessionRequests, "Session requests waiting for you", "/admin/session-requests"),
        stat(d.pendingOrders, "Merchandise orders waiting for you", "/admin/orders"),
        stat(d.unansweredEnquiries, "Messages to answer", "/admin/messages")));
      var up = A.section("Coming up: confirmed sessions", "d-up");
      if (!d.upcomingSessions.length) { up.appendChild(h("p", { class: "muted", text: "No confirmed sessions coming up." })); }
      else { up.appendChild(h("ul", { class: "list" }, d.upcomingSessions.slice(0, 5).map(function (s) {
        return h("li", { class: "list-item" }, h("p", { class: "list-item__title" }, h("a", { href: "/admin/session-requests/" + s.id, text: s.customerName })),
          h("p", { class: "list-item__meta", text: AS.fmt.dateLong(s.date) + " at " + s.startTime + " · " + s.services.join(", ") + " · " + s.reference }));
      }))); }
      ctx.main.appendChild(up);
      ctx.main.appendChild(h("section", { class: "panel", "aria-labelledby": "d-short" }, h("h2", { id: "d-short", text: "Shortcuts" }),
        h("div", { class: "row" }, h("a", { class: "btn", href: "/admin/services/new", text: "Add a service" }), h("a", { class: "btn", href: "/admin/portfolio/new", text: "Add a photo" }), h("a", { class: "btn", href: "/admin/calendar", text: "Block a date" }))));
    });
  });

  A.route("/admin/account", { id: "A21", title: "My account" }, function (ctx) {
    var u = A.user;
    ctx.main.appendChild(h("section", { class: "panel", "aria-labelledby": "ac-me" }, h("h2", { id: "ac-me", text: "Your details" }), A.dl([["Name", u.name], ["Email", u.email], ["Role", u.role === "owner" ? "Owner" : "Support staff"]])));
    var cur = F.field({ id: "ac-cur", name: "currentPassword", label: "Current password", required: true, type: "password", autocomplete: "current-password" }), f = passwordFields("ac");
    var btn = h("button", { type: "submit", class: "btn" }, "Save password");
    var form = h("form", { novalidate: true, class: "panel", "aria-labelledby": "ac-pw" }, h("h2", { id: "ac-pw", text: "Change password" }), cur.el, f.pw.el, f.pw2.el, btn);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      if (!F.validate([{ field: cur, check: F.V.required("your current password") }]) || !checkPasswords(f)) { return; }
      A.busy(btn, function () { return AS.api.post("/admin/auth/change-password", { currentPassword: cur.input.value, newPassword: f.pw.input.value }); }).then(function () {
        AS.toast("Saved"); cur.input.value = ""; f.pw.input.value = ""; f.pw2.input.value = "";
      }, function (err) { if (err.status === 400) { cur.setError("That is not your current password."); cur.input.focus(); } else { A.fail(err); } });
    });
    ctx.main.appendChild(form);
    var out = h("button", { type: "button", class: "btn btn--secondary" }, AS.icon("sign-out", "icon--sm"), "Sign out");
    out.addEventListener("click", function () {
      A.busy(out, function () { return AS.api.post("/admin/auth/logout"); }).then(function () { A.clearSession(); A.go("/admin/sign-in", true); }, A.fail);
    });
    ctx.main.appendChild(h("section", { class: "panel" }, h("h2", { text: "Sign out" }), out));
  });

  A.route("/admin/help", { id: "HELP", title: "Help", public: true }, function (ctx) { A.helpIndex(ctx); });
  A.route("/admin/help/:topic", { id: "HELP", title: "Help", public: true }, function (ctx) { A.helpTopic(ctx); });
})(window.AS);
