/*
 * Reusable UI: image gallery, lightbox, restricted-Markdown renderer (no raw HTML, ever),
 * and small helpers for loading, empty and error states.
 */
(function (AS) {
  "use strict";
  var h = AS.h;

  // ----- restricted Markdown subset: headings, paragraphs, lists, bold, links. Built with DOM nodes only.
  function inline(text) {
    var out = [], re = /\*\*(.+?)\*\*|\[([^\]]+)\]\(([^)\s]+)\)/g, last = 0, m;
    while ((m = re.exec(text))) {
      if (m.index > last) { out.push(text.slice(last, m.index)); }
      if (m[1]) { out.push(h("strong", { text: m[1] })); }
      else if (/^(https?:\/\/|mailto:|\/)/.test(m[3])) { out.push(h("a", { href: m[3], text: m[2] })); }
      else { out.push(m[2]); }
      last = re.lastIndex;
    }
    if (last < text.length) { out.push(text.slice(last)); }
    return out;
  }
  function markdown(src) {
    var root = h("div", { class: "doc" }), lines = String(src || "").split(/\r?\n/), para = [], list = null, ltype = null;
    function flushPara() { if (para.length) { root.appendChild(h("p", {}, inline(para.join(" ")))); para = []; } }
    function flushList() { if (list) { root.appendChild(list); list = null; } }
    lines.forEach(function (raw) {
      var l = raw.trim(), m;
      if (!l) { flushPara(); flushList(); return; }
      if ((m = /^(#{1,4})\s+(.*)$/.exec(l))) { flushPara(); flushList(); root.appendChild(h("h" + Math.min(4, m[1].length + 1), { text: m[2] })); return; } // h1 is the page title, so # becomes h2
      if ((m = /^([-*]|\d+\.)\s+(.*)$/.exec(l))) {
        flushPara();
        var t = /\d/.test(m[1]) ? "ol" : "ul";
        if (!list || ltype !== t) { flushList(); list = h(t); ltype = t; }
        list.appendChild(h("li", {}, inline(m[2])));
        return;
      }
      flushList(); para.push(l);
    });
    flushPara(); flushList();
    return root;
  }

  // ----- gallery: main image plus thumbnails; the thumbnails are real buttons.
  function gallery(images, o) {
    o = o || {};
    var main = h("div", { class: "gallery__main" }), thumbs = h("div", { class: "gallery__thumbs", role: "group", "aria-label": "Choose a picture" });
    var list = images && images.length ? images : [null];
    function show(i) {
      AS.clear(main).appendChild(AS.img(list[i], { size: "large", ratio: o.ratio || "card", sizes: "(min-width: 960px) 50vw, 100vw", eager: i === 0 }));
      AS.$$("button", thumbs).forEach(function (b, j) { if (j === i) { b.setAttribute("aria-current", "true"); } else { b.removeAttribute("aria-current"); } });
    }
    if (list.length > 1) {
      list.forEach(function (im, i) {
        var b = h("button", { type: "button", class: "gallery__thumb", "aria-label": "Show picture " + (i + 1) + " of " + list.length + (im.alt ? ": " + im.alt : ""), onclick: function () { show(i); } },
          h("img", { src: AS.media(im.id, "thumb"), alt: "" }));
        thumbs.appendChild(b);
      });
    }
    show(0);
    return h("div", { class: "gallery" + (o.ratio === "square" ? " gallery--square" : "") }, main, list.length > 1 ? thumbs : null);
  }

  // ----- lightbox: native dialog (focus trap, Esc), previous/next buttons and arrow keys.
  function lightbox(items, start, opener) {
    var i = start, dlg = h("dialog", { class: "lightbox", "aria-label": "Portfolio photo viewer" });
    var stage = h("div", { class: "lightbox__stage" }), cap = h("p", { class: "lightbox__caption" });
    var prev = h("button", { type: "button", class: "btn btn--icon btn--secondary lightbox__nav lightbox__nav--prev", "aria-label": "Previous photo", onclick: function () { go(-1); } }, AS.icon("chevron-left"));
    var next = h("button", { type: "button", class: "btn btn--icon btn--secondary lightbox__nav lightbox__nav--next", "aria-label": "Next photo", onclick: function () { go(1); } }, AS.icon("chevron-right"));
    var close = h("button", { type: "button", class: "btn btn--secondary", onclick: function () { dlg.close(); } }, AS.icon("close", "icon--sm"), "Close");
    dlg.appendChild(h("div", { class: "lightbox__inner" }, h("div", { class: "lightbox__bar" }, close), stage, cap));
    function render() {
      var it = items[i];
      AS.clear(stage).appendChild(AS.img(it.image, { size: "large", ratio: "portrait", sizes: "100vw", alt: it.alt, eager: true }));
      stage.appendChild(prev); stage.appendChild(next);
      cap.textContent = it.caption || "";
      prev.disabled = i === 0; next.disabled = i === items.length - 1;
    }
    function go(d) { var n = i + d; if (n >= 0 && n < items.length) { i = n; render(); } }
    dlg.addEventListener("keydown", function (e) { if (e.key === "ArrowLeft") { go(-1); } else if (e.key === "ArrowRight") { go(1); } });
    dlg.addEventListener("close", function () { dlg.remove(); if (opener) { opener.focus(); } });
    document.body.appendChild(dlg);
    render();
    dlg.showModal();
    close.focus();
  }

  function skeleton(n) { var w = h("div", { class: "grid grid--cards", "aria-hidden": "true" }); for (var k = 0; k < (n || 3); k++) { w.appendChild(h("div", { class: "skeleton" })); } return w; }
  function empty(title, text, linkHref, linkText) {
    return h("div", { class: "empty" }, h("h2", { class: "card__title", text: title }), h("p", { class: "muted", text: text }), linkHref ? h("a", { class: "btn", href: linkHref, text: linkText }) : null);
  }
  function errorBox(err, retry) {
    return h("div", { class: "alert alert--error", role: "alert" }, AS.icon("error"), h("div", { class: "alert__body" },
      h("p", { text: (err && err.message) || "Something went wrong." }), retry ? h("button", { type: "button", class: "btn btn--secondary", onclick: retry, text: "Try again" }) : null));
  }
  function priceTag(n, prefix) { return h("span", { class: "price" }, prefix ? prefix + " " : "", AS.fmt.rwf(n)); }

  // Sets the main heading target for focus after in-page step changes.
  function focusHeading(el) { if (el) { el.setAttribute("tabindex", "-1"); el.focus(); } }

  AS.ui = { markdown: markdown, gallery: gallery, lightbox: lightbox, skeleton: skeleton, empty: empty, errorBox: errorBox, priceTag: priceTag, focusHeading: focusHeading };
})(window.AS = window.AS || {});
