/* Legal document template (P15 to P18): GET /api/documents/{slug}, rendered as restricted Markdown. */
(function (AS) {
  "use strict";
  var h = AS.h, $ = AS.$;
  var DOCS = [["terms-of-sale", "Terms of sale"], ["refund-policy", "Refund policy"], ["privacy", "Privacy policy"], ["order-terms", "Order terms"]];

  document.addEventListener("DOMContentLoaded", function () {
    var slug = decodeURIComponent(location.pathname.split("/")[2] || ""), root = $("#legal-root");
    var links = $("#legal-links");
    DOCS.forEach(function (d) { if (d[0] !== slug) { links.appendChild(h("li", {}, h("a", { class: "chip", href: "/legal/" + d[0], text: d[1] }))); } });
    AS.api.get("/documents/" + encodeURIComponent(slug)).then(function (doc) {
      $("#legal-title").textContent = doc.title;
      $("#legal-crumb").textContent = doc.title;
      $("#legal-meta").textContent = "Version " + doc.version + ". Last updated " + AS.fmt.instant(doc.publishedAt).split(",")[0] + ".";
      AS.clear(root).appendChild(AS.ui.markdown(doc.body));
      if (document.title.indexOf(doc.title) !== 0) {
        // Head is normally rewritten by the backend; this keeps the tab and canonical right when it is not.
        AS.setHead({ title: doc.title + " | Aura Shots" });
        var can = $('link[rel="canonical"]'); if (can) { can.href = location.origin + location.pathname; }
      }
    }, function (err) {
      $("#legal-title").textContent = err.status === 404 ? "We could not find that document" : "Something went wrong";
      AS.clear(root).appendChild(err.status === 404 ? h("p", { text: "Please choose one of the documents below." }) : AS.ui.errorBox(err, function () { location.reload(); }));
    });
  });
})(window.AS);
