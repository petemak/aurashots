/*
 * Agreement panel (design-spec P8 step 3 and P11 section 5): the full text of a published agreement
 * (GET /api/documents/{slug}), its version, key points, and the required acceptance checkbox. The
 * accepted version is sent back to the server, which records the acceptance and rejects a stale version.
 */
(function (AS) {
  "use strict";
  var h = AS.h;

  function create(slug, opts) {
    opts = opts || {};
    var doc = null, host = h("div", { class: "agreement-host" }, h("p", { class: "muted", text: "Loading the agreement…" }));
    var box = null, errEl = null, listeners = [];

    function notify() { listeners.forEach(function (f) { f(!!(box && box.checked)); }); }

    function paint() {
      AS.clear(host);
      var id = "agree-" + slug;
      box = h("input", { type: "checkbox", id: id, "aria-describedby": id + "-err" });
      box.addEventListener("change", function () { errEl.parentNode.classList.remove("field--error"); notify(); });
      errEl = h("p", { class: "field__error", id: id + "-err" }, AS.icon("error", "icon--sm"), h("span", { text: "Tick the box to accept the agreement before you send your request." }));
      host.appendChild(h("div", { class: "agreement" },
        h("div", { class: "agreement__head" }, h("h3", { text: doc.title }), h("span", { class: "agreement__version", text: "Version " + doc.version + ", published " + AS.fmt.instant(doc.publishedAt).split(",")[0] })),
        doc.summaryPoints && doc.summaryPoints.length ? h("ul", { class: "agreement__points" }, doc.summaryPoints.map(function (p) { return h("li", { text: p }); })) : null,
        h("div", { class: "agreement__text", tabindex: "0", role: "region", "aria-label": doc.title + " full text" }, AS.ui.markdown(doc.body)),
        h("div", { class: "field" }, h("div", { class: "check" }, box, h("label", { for: id, text: "I have read and accept the " + doc.title + " (version " + doc.version + ")" })), errEl)));
      notify();
    }

    var api = {
      el: host,
      load: function () {
        return AS.api.get("/documents/" + slug).then(function (d) { doc = d; paint(); return d; }, function (err) {
          AS.clear(host).appendChild(AS.ui.errorBox(err, function () { api.load().catch(function () { /* shown again */ }); }));
          throw err;
        });
      },
      version: function () { return doc && doc.version; },
      slug: slug,
      accepted: function () { return !!(box && box.checked); },
      showError: function () { if (errEl) { errEl.parentNode.classList.add("field--error"); box.focus(); } },
      onChange: function (f) { listeners.push(f); },
      reload: function () { return api.load(); }
    };
    return api;
  }
  AS.agreement = create;
})(window.AS = window.AS || {});
