/* Admin A8/A9 services, A10/A11 merchandise, A12/A13 portfolio, and the shared image uploader. */
(function (AS) {
  "use strict";
  var h = AS.h, F = AS.forms, A = AS.admin;
  var notice = null; // one-off message shown on the next screen (for example after creating a record)

  function int(v) { return v === "" || v === null || v === undefined ? null : parseInt(v, 10); }
  function numField(id, name, label, val, extra) { return F.field(Object.assign({ id: id, name: name, label: label, type: "number", min: 0, step: 1, inputmode: "numeric", value: val === null || val === undefined ? "" : val }, extra || {})); }
  function intCheck(v) { return v === "" || /^\d+$/.test(v) ? null : "Enter a whole number."; }
  function imageId(it) { return it.imageId || it.id; }

  // ---------- image uploader: POST /api/admin/images, per-image alt text (required), reorder with buttons
  function uploader(initial, o) {
    o = o || {};
    var items = (initial || []).map(function (i) { return { imageId: imageId(i), alt: i.alt || "" }; });
    var listBox = h("div"), status = h("div", { "aria-live": "polite" });
    var input = h("input", { type: "file", id: "up-" + (o.id || "pics"), accept: "image/jpeg,image/png,image/webp", multiple: !o.single, class: "sr-only" });
    var label = h("label", { class: "btn btn--secondary", for: input.id }, AS.icon("upload", "icon--sm"), o.single ? "Choose a photo" : "Add pictures");
    function paint() {
      AS.clear(listBox);
      if (!items.length) { listBox.appendChild(h("p", { class: "muted", text: "No pictures yet." })); return; }
      listBox.appendChild(h("ul", { class: "upload-list" }, items.map(function (it, i) {
        var alt = F.field({ id: "alt-" + (o.id || "p") + "-" + i, name: "alt", label: "Picture description", required: true, value: it.alt, maxlength: o.altMax || 200, hint: "Say what the picture shows, for people who cannot see it." });
        alt.input.addEventListener("input", function () { it.alt = alt.input.value; });
        it.field = alt;
        return h("li", {}, h("div", { class: "thumb" }, h("img", { src: AS.media(it.imageId, "thumb"), alt: "" })), alt.el,
          h("div", { class: "row" },
            o.single ? null : h("button", { type: "button", class: "btn btn--secondary btn--sm", disabled: i === 0, onclick: function () { move(i, -1); } }, "Move up", h("span", { class: "sr-only", text: " picture " + (i + 1) })),
            o.single ? null : h("button", { type: "button", class: "btn btn--secondary btn--sm", disabled: i === items.length - 1, onclick: function () { move(i, 1); } }, "Move down", h("span", { class: "sr-only", text: " picture " + (i + 1) })),
            h("button", { type: "button", class: "btn btn--secondary btn--sm", onclick: function () { items.splice(i, 1); paint(); } }, AS.icon("remove", "icon--sm"), "Take out", h("span", { class: "sr-only", text: " picture " + (i + 1) }))));
      })));
      if (!o.single && items.length > 1) { listBox.appendChild(h("p", { class: "muted small", text: "The first picture is the main one." })); }
    }
    function move(i, d) { var t = items[i]; items[i] = items[i + d]; items[i + d] = t; paint(); }
    input.addEventListener("change", function () {
      var files = Array.prototype.slice.call(input.files); input.value = "";
      AS.clear(status);
      files.reduce(function (p, file) {
        return p.then(function () {
          if (file.size > 10 * 1024 * 1024) { status.appendChild(AS.alertBox("error", file.name + " is larger than 10 MB.")); return null; }
          label.setAttribute("aria-busy", "true");
          return AS.api.upload("/admin/images", file).then(function (img) {
            if (o.single) { items = []; }
            items.push({ imageId: img.id, alt: img.alt || "" }); paint();
          }, function (err) { status.appendChild(AS.alertBox("error", file.name + ": " + err.message)); });
        });
      }, Promise.resolve()).then(function () { label.removeAttribute("aria-busy"); });
    });
    paint();
    return {
      el: h("div", { class: "field" }, h("p", { class: "label", text: o.single ? "Photo" : "Pictures" }), h("p", { class: "field__hint", text: "JPG, PNG or WebP, up to 10 MB each." }), listBox, status, h("div", {}, input, label)),
      value: function () { return items.map(function (i) { return { imageId: i.imageId, alt: i.alt.trim() }; }); },
      items: function () { return items; },
      validate: function () {
        var ok = true, first = null;
        items.forEach(function (it) { var bad = !it.alt.trim() || it.alt.length > (o.altMax || 200); if (it.field) { it.field.setError(bad ? "Describe the picture in a few words." : null); } if (bad) { ok = false; if (!first) { first = it.field; } } });
        if (first) { first.input.focus(); }
        return ok;
      }
    };
  }
  A.uploader = uploader;

  function reorderIds(list, i, d) { var ids = list.map(function (x) { return x.id; }), t = ids[i]; ids[i] = ids[i + d]; ids[i + d] = t; return ids; }
  function removeDialog(what, extra) {
    return AS.confirm({ title: "Are you sure? This cannot be undone.", body: extra || ("This " + what + " will be removed."), confirmLabel: "Yes, remove", danger: true });
  }

  // ================= services
  function serviceBody(r) {
    var c = r.contents || {};
    return { name: r.name, shortDescription: r.shortDescription || "", description: r.description || "", priceRwf: r.priceRwf === undefined ? null : r.priceRwf, sessionMinutes: r.sessionMinutes === undefined ? null : r.sessionMinutes,
      contents: { editedPictures: c.editedPictures === undefined ? null : c.editedPictures, videos: c.videos === undefined ? null : c.videos, outfits: c.outfits === undefined ? null : c.outfits, deliveryTime: c.deliveryTime || null, locations: c.locations || null },
      conditions: r.conditions || "", relatedProductIds: r.relatedProductIds || (r.relatedProducts || []).map(function (p) { return p.id; }),
      images: (r.images || []).map(function (i) { return { imageId: imageId(i), alt: i.alt || "" }; }), featured: !!r.featured, status: r.status === "hidden" ? "hidden" : "published", sortOrder: r.sortOrder || 0 };
  }

  A.route("/admin/services", { id: "A8", title: "Services" }, function (ctx) {
    var top = h("div", { class: "row" }, h("a", { class: "btn", href: "/admin/services/new" }, AS.icon("plus", "icon--sm"), "Add a service"));
    ctx.main.appendChild(top);
    ctx.main.appendChild(h("p", { class: "muted", text: "Hidden services are not shown on the website. A service needs a price before it can be published." }));
    var box = h("div"); ctx.main.appendChild(box);
    function load() {
      return AS.api.get("/admin/services").then(function (res) {
        if (!ctx.current()) { return; }
        AS.clear(box);
        var items = res.items || [];
        if (!items.length) { box.appendChild(AS.ui.empty("No services yet", "Add your first package.", "/admin/services/new", "Add a service")); return; }
        box.appendChild(h("ul", { class: "cards-grid list" }, items.map(function (s, i) {
          var kind = s.needsPrice ? "needsPrice" : s.status;
          var toggle = h("button", { type: "button", class: "btn btn--secondary btn--sm", disabled: s.needsPrice && s.status !== "published", text: s.status === "published" ? "Hide from shop" : "Show on shop" });
          toggle.addEventListener("click", function () {
            A.busy(toggle, function () { return AS.api.get("/admin/services/" + s.id).then(function (r) { var b = serviceBody(r); b.status = s.status === "published" ? "hidden" : "published"; return AS.api.put("/admin/services/" + s.id, b); }); }).then(function () { AS.toast("Saved"); load(); }, A.fail);
          });
          var mv = function (d, txt) { return h("button", { type: "button", class: "btn btn--secondary btn--sm", disabled: i + d < 0 || i + d >= items.length, text: txt, onclick: function (e) { A.busy(e.currentTarget, function () { return AS.api.put("/admin/service-order", { ids: reorderIds(items, i, d) }); }).then(load, A.fail); } }); };
          return h("li", { class: "list-item list-item--thumb" }, h("div", { class: "thumb" }, AS.img(s.mainImage, { ratio: "square", size: "thumb" })),
            h("p", { class: "list-item__title" }, h("a", { href: "/admin/services/" + s.id, text: s.name })),
            h("p", { class: "list-item__meta", text: s.priceRwf ? AS.fmt.rwf(s.priceRwf) : "No price yet" }),
            h("div", {}, A.chip(kind)),
            h("div", { class: "list-item__actions" }, h("a", { class: "btn btn--secondary btn--sm", href: "/admin/services/" + s.id }, "Edit", h("span", { class: "sr-only", text: " " + s.name })), toggle, mv(-1, "Move up"), mv(1, "Move down")));
        })));
      });
    }
    return load();
  });

  function serviceForm(ctx, rec) {
    var isNew = !rec, r = rec || { status: "hidden", featured: false, contents: {} }, c = r.contents || {};
    var f = {
      name: F.field({ id: "sv-name", name: "name", label: "Name", required: true, value: r.name, maxlength: 100 }),
      shortDescription: F.field({ id: "sv-short", name: "shortDescription", label: "Short description", value: r.shortDescription, maxlength: 200, hint: "Shown on cards." }),
      description: F.field({ id: "sv-desc", name: "description", label: "Detailed description", rows: 6, value: r.description, maxlength: 5000 }),
      priceRwf: numField("sv-price", "priceRwf", "Price (RwF)", r.priceRwf, { hint: "Customers cannot order a service without a price." }),
      sessionMinutes: numField("sv-len", "sessionMinutes", "Session length (minutes)", r.sessionMinutes, { hint: "Leave empty to use the standard length from Settings." }),
      "contents.editedPictures": numField("sv-pics", "contents.editedPictures", "Number of edited pictures", c.editedPictures),
      "contents.videos": numField("sv-vid", "contents.videos", "Number of videos", c.videos),
      "contents.outfits": numField("sv-out", "contents.outfits", "Outfits", c.outfits),
      "contents.deliveryTime": F.field({ id: "sv-del", name: "contents.deliveryTime", label: "Delivery time", value: c.deliveryTime, maxlength: 100, hint: "For example 5 days." }),
      "contents.locations": F.field({ id: "sv-loc", name: "contents.locations", label: "Locations", value: c.locations, maxlength: 200 }),
      conditions: F.field({ id: "sv-cond", name: "conditions", label: "Conditions", rows: 5, value: r.conditions, maxlength: 3000 })
    };
    var pics = uploader(r.images, { id: "svc" });
    var featured = F.checkbox("sv-feat", "Show on the home page", r.featured);
    var status = F.chipRadios("sv-status", "Status", [{ value: "published", label: "Published" }, { value: "hidden", label: "Hidden" }], r.status === "published" ? "published" : "hidden");
    var related = h("div", { class: "field" }, h("p", { class: "label", text: "Related merchandise" }), h("p", { class: "muted", text: "Loading…" }));
    var selected = (r.relatedProductIds || (r.relatedProducts || []).map(function (p) { return p.id; }));
    AS.api.get("/admin/products", { pageSize: 100 }).then(function (res) {
      var items = res.items || res || [];
      AS.clear(related).appendChild(h("p", { class: "label", text: "Related merchandise (optional)" }));
      if (!items.length) { related.appendChild(h("p", { class: "muted", text: "No merchandise yet." })); return; }
      items.forEach(function (p) { var cb = F.checkbox("rel-" + p.id, p.name, selected.indexOf(p.id) >= 0); cb.input.setAttribute("data-id", p.id); related.appendChild(cb.el); });
    }, function () { AS.clear(related).appendChild(h("p", { class: "muted", text: "The merchandise list could not be loaded." })); });
    var saveBtn = h("button", { type: "submit", class: "btn btn--lg" }, "Save");
    var msg = h("div", { "aria-live": "polite" });
    var form = h("form", { novalidate: true, class: "panel" },
      h("h2", { text: "About the service" }), f.name.el, f.shortDescription.el, f.description.el, f.priceRwf.el, f.sessionMinutes.el,
      h("h2", { text: "What's included" }), h("div", { class: "form-grid form-grid--2" }, f["contents.editedPictures"].el, f["contents.videos"].el, f["contents.outfits"].el), f["contents.deliveryTime"].el, f["contents.locations"].el,
      h("h2", { text: "Conditions" }), f.conditions.el, related, h("h2", { text: "Pictures" }), pics.el,
      h("h2", { text: "Visibility" }), featured.el, status.el, msg,
      h("div", { class: "form-actions" }, saveBtn, h("a", { class: "btn btn--secondary", href: "/admin/services", text: "Cancel" })));
    form.addEventListener("submit", function (e) {
      e.preventDefault(); AS.clear(msg);
      var rules = [{ field: f.name, check: function (v) { return v.length >= 1 && v.length <= 100 ? null : "Enter a name (up to 100 characters)."; } }, { field: f.priceRwf, check: intCheck }, { field: f.sessionMinutes, check: intCheck },
        { field: f["contents.editedPictures"], check: intCheck }, { field: f["contents.videos"], check: intCheck }, { field: f["contents.outfits"], check: intCheck }];
      if (status.value() === "published") { rules.push({ field: f.priceRwf, check: function (v) { return v && +v > 0 ? null : "Please enter a price before publishing."; } }); }
      if (!F.validate(rules) | !pics.validate()) { return; }
      var body = { name: f.name.value(), shortDescription: f.shortDescription.value(), description: f.description.value(), priceRwf: int(f.priceRwf.value()), sessionMinutes: int(f.sessionMinutes.value()),
        contents: { editedPictures: int(f["contents.editedPictures"].value()), videos: int(f["contents.videos"].value()), outfits: int(f["contents.outfits"].value()), deliveryTime: f["contents.deliveryTime"].value() || null, locations: f["contents.locations"].value() || null },
        conditions: f.conditions.value(), relatedProductIds: AS.$$("input[data-id]:checked", related).map(function (x) { return x.getAttribute("data-id"); }),
        images: pics.value(), featured: featured.input.checked, status: status.value(), sortOrder: r.sortOrder || 0 };
      A.busy(saveBtn, function () { return isNew ? AS.api.post("/admin/services", body) : AS.api.put("/admin/services/" + r.id, body); }).then(function (res) {
        var slug = (res && res.slug) || r.slug;
        notice = AS.alertBox("success", h("p", {}, h("strong", { text: "Saved. " }), h("a", { href: slug ? "/services/" + slug : "/services", target: "_blank", rel: "noopener", text: "View on the website (opens in a new tab)" })));
        if (isNew && res && res.id) { A.go("/admin/services/" + res.id, true); }
        else { A.go("/admin/services/" + r.id, true); }
      }, function (err) { A.formError(err, f); if (err.fields && err.fields.priceRwf === undefined && err.code === "validationFailed" && !err.fields.name) { msg.appendChild(AS.alertBox("error", err.message)); } });
    });
    ctx.main.appendChild(h("p", {}, h("a", { href: "/admin/services", text: "Back to services" })));
    if (notice) { ctx.main.appendChild(notice); notice = null; }
    ctx.main.appendChild(form);
    if (!isNew) {
      var rm = h("button", { type: "button", class: "btn btn--danger", text: "Remove this service" });
      rm.addEventListener("click", function () {
        removeDialog("service", "It will disappear from the website for good. Old booking requests still show it. If you only want a pause, choose Hidden instead.").then(function (ok) {
          if (ok) { A.busy(rm, function () { return AS.api.del("/admin/services/" + r.id); }).then(function () { AS.toast("Removed"); A.go("/admin/services"); }, A.fail); }
        });
      });
      ctx.main.appendChild(h("section", { class: "panel" }, h("h2", { text: "Remove" }), h("p", { class: "muted", text: "Removing hides the service from the website for good. Old requests stay readable." }), rm));
    }
  }
  A.route("/admin/services/new", { id: "A9", title: "Add a service" }, function (ctx) { serviceForm(ctx, null); });
  A.route("/admin/services/:id", { id: "A9", title: "Edit a service" }, function (ctx) {
    return AS.api.get("/admin/services/" + encodeURIComponent(ctx.params.id)).then(function (r) { if (ctx.current()) { r.id = r.id || ctx.params.id; serviceForm(ctx, r); } });
  });

  // ================= merchandise
  function availabilityOf(p) {
    if (p.availability) { return p.availability; }
    if (p.optionGroups && p.optionGroups.length) { return p.optionGroups.every(function (g) { return g.options.every(function (o) { return !o.inStock; }); }) ? "soldOut" : "inStock"; }
    return "inStock";
  }
  A.route("/admin/merchandise", { id: "A10", title: "Merchandise" }, function (ctx) {
    ctx.main.appendChild(h("div", { class: "row" }, h("a", { class: "btn", href: "/admin/merchandise/new" }, AS.icon("plus", "icon--sm"), "Add an item")));
    var box = h("div"); ctx.main.appendChild(box);
    function load() {
      return AS.api.get("/admin/products", { pageSize: 100 }).then(function (res) {
        if (!ctx.current()) { return; }
        var items = res.items || res || [];
        AS.clear(box);
        if (!items.length) { box.appendChild(AS.ui.empty("No merchandise yet", "Add your first item.", "/admin/merchandise/new", "Add an item")); return; }
        box.appendChild(h("ul", { class: "cards-grid list" }, items.map(function (p) {
          var sold = availabilityOf(p) === "soldOut", img = p.mainImage || (p.images && p.images[0] ? { id: imageId(p.images[0]), alt: p.images[0].alt } : null);
          var tog = h("button", { type: "button", class: "btn btn--secondary btn--sm", text: sold ? "Mark as in stock" : "Mark as sold out" });
          tog.addEventListener("click", function () { A.busy(tog, function () { return AS.api.patch("/admin/products/" + p.id + "/availability", { inStock: sold }); }).then(function () { AS.toast("Saved"); load(); }, A.fail); });
          return h("li", { class: "list-item list-item--thumb" }, h("div", { class: "thumb" }, AS.img(img, { ratio: "square", size: "thumb" })),
            h("p", { class: "list-item__title" }, h("a", { href: "/admin/merchandise/" + p.id, text: p.name })),
            h("p", { class: "list-item__meta", text: AS.fmt.rwf(p.priceRwf) + (p.status === "hidden" ? " · Hidden" : "") }),
            h("div", {}, AS.badge(sold ? "sold-out" : "in-stock", sold ? "Sold out" : "In stock")),
            h("div", { class: "list-item__actions" }, h("a", { class: "btn btn--secondary btn--sm", href: "/admin/merchandise/" + p.id }, "Edit", h("span", { class: "sr-only", text: " " + p.name })), tog));
        })));
      });
    }
    return load();
  });

  function merchForm(ctx, rec) {
    var isNew = !rec, r = rec || { status: "hidden", optionGroups: [], category: "clothing" };
    var f = {
      name: F.field({ id: "mp-name", name: "name", label: "Name", required: true, value: r.name, maxlength: 100 }),
      category: F.field({ id: "mp-cat", name: "category", label: "Category", required: true, value: r.category || "clothing", options: [{ value: "clothing", label: "Clothing" }, { value: "prints", label: "Prints" }, { value: "framed-art", label: "Framed art" }] }),
      description: F.field({ id: "mp-desc", name: "description", label: "Description", rows: 5, value: r.description, maxlength: 5000 }),
      details: F.field({ id: "mp-det", name: "details", label: "Details", rows: 4, value: r.details, maxlength: 3000, hint: "Material, size, care." }),
      priceRwf: numField("mp-price", "priceRwf", "Price (RwF)", r.priceRwf, { required: true, min: 1 })
    };
    var groups = (r.optionGroups || []).map(function (g) { return { name: g.name, options: g.options.map(function (o) { return { id: o.id, label: o.label, inStock: !!o.inStock }; }) }; });
    var gBox = h("div");
    function paintGroups() {
      AS.clear(gBox);
      groups.forEach(function (g, gi) {
        var name = F.field({ id: "og-" + gi, name: "gname", label: "Option group (for example Size or Colour)", required: true, value: g.name, maxlength: 50 });
        name.input.addEventListener("input", function () { g.name = name.input.value; });
        g.nameField = name;
        gBox.appendChild(h("fieldset", { class: "panel" }, h("legend", { class: "sr-only", text: "Option group " + (gi + 1) }), name.el,
          g.options.map(function (o, oi) {
            var lab = F.field({ id: "oo-" + gi + "-" + oi, name: "olabel", label: "Option", required: true, value: o.label, maxlength: 50 });
            lab.input.addEventListener("input", function () { o.label = lab.input.value; }); o.field = lab;
            var stock = F.checkbox("os-" + gi + "-" + oi, "In stock", o.inStock); stock.input.addEventListener("change", function () { o.inStock = stock.input.checked; });
            return h("div", { class: "row" }, lab.el, stock.el, h("button", { type: "button", class: "btn btn--secondary btn--sm", onclick: function () { g.options.splice(oi, 1); paintGroups(); } }, AS.icon("remove", "icon--sm"), "Take out option"));
          }),
          h("div", { class: "row" }, h("button", { type: "button", class: "btn btn--secondary btn--sm", onclick: function () { g.options.push({ label: "", inStock: true }); paintGroups(); } }, AS.icon("plus", "icon--sm"), "Add an option"),
            h("button", { type: "button", class: "btn btn--secondary btn--sm", onclick: function () { groups.splice(gi, 1); paintGroups(); } }, "Take out this group"))));
      });
    }
    paintGroups();
    var pics = uploader(r.images, { id: "mer" });
    var isNewBadge = F.checkbox("mp-new", "Show the New label", r.isNew), featured = F.checkbox("mp-feat", "Show on the home page", r.featured);
    var status = F.chipRadios("mp-status", "Status", [{ value: "published", label: "Published" }, { value: "hidden", label: "Hidden" }], r.status === "published" ? "published" : "hidden");
    var saveBtn = h("button", { type: "submit", class: "btn btn--lg" }, "Save"), msg = h("div", { "aria-live": "polite" });
    var form = h("form", { novalidate: true, class: "panel" }, f.name.el, f.category.el, f.description.el, f.details.el, f.priceRwf.el,
      h("h2", { text: "Options" }), h("p", { class: "muted", text: "For example Size with M and L, or Colour with Black. Tick In stock for the ones you have. We do not count stock." }), gBox,
      h("button", { type: "button", class: "btn btn--secondary", onclick: function () { groups.push({ name: "", options: [{ label: "", inStock: true }] }); paintGroups(); } }, AS.icon("plus", "icon--sm"), "Add an option group"),
      h("h2", { text: "Pictures" }), pics.el, h("h2", { text: "Visibility" }), isNewBadge.el, featured.el, status.el, msg,
      h("div", { class: "form-actions" }, saveBtn, h("a", { class: "btn btn--secondary", href: "/admin/merchandise", text: "Cancel" })));
    form.addEventListener("submit", function (e) {
      e.preventDefault(); AS.clear(msg);
      var ok = F.validate([{ field: f.name, check: F.V.required("a name") }, { field: f.priceRwf, check: function (v) { return /^\d+$/.test(v) && +v >= 1 ? null : "Enter a price of at least 1 RwF."; } }]);
      groups.forEach(function (g) { if (g.nameField && !g.name.trim()) { g.nameField.setError("Enter a group name."); ok = false; } g.options.forEach(function (o) { if (o.field && !o.label.trim()) { o.field.setError("Enter an option name."); ok = false; } }); });
      if (!(pics.validate() && ok)) { return; }
      var body = { name: f.name.value(), category: f.category.input.value, description: f.description.value(), details: f.details.value(), priceRwf: int(f.priceRwf.value()),
        optionGroups: groups.map(function (g) { return { name: g.name.trim(), options: g.options.map(function (o) { var x = { label: o.label.trim(), inStock: o.inStock }; if (o.id) { x.id = o.id; } return x; }) }; }),
        images: pics.value(), isNew: isNewBadge.input.checked, featured: featured.input.checked, status: status.value() };
      A.busy(saveBtn, function () { return isNew ? AS.api.post("/admin/products", body) : AS.api.put("/admin/products/" + r.id, body); }).then(function (res) {
        var slug = (res && res.slug) || r.slug;
        notice = AS.alertBox("success", h("p", {}, h("strong", { text: "Saved. " }), h("a", { href: slug ? "/shop/" + slug : "/shop", target: "_blank", rel: "noopener", text: "View on the website (opens in a new tab)" })));
        A.go("/admin/merchandise/" + ((res && res.id) || r.id), true);
      }, function (err) { A.formError(err, f); });
    });
    ctx.main.appendChild(h("p", {}, h("a", { href: "/admin/merchandise", text: "Back to merchandise" })));
    if (notice) { ctx.main.appendChild(notice); notice = null; }
    ctx.main.appendChild(form);
    if (!isNew) {
      var rm = h("button", { type: "button", class: "btn btn--danger", text: "Remove this item" });
      rm.addEventListener("click", function () { removeDialog("item", "It will be taken off the shop. Old orders still show it. To hide it for a while, choose Hidden instead.").then(function (ok) { if (ok) { A.busy(rm, function () { return AS.api.del("/admin/products/" + r.id); }).then(function () { AS.toast("Removed"); A.go("/admin/merchandise"); }, A.fail); } }); });
      ctx.main.appendChild(h("section", { class: "panel" }, h("h2", { text: "Remove" }), rm));
    }
  }
  A.route("/admin/merchandise/new", { id: "A11", title: "Add an item" }, function (ctx) { merchForm(ctx, null); });
  A.route("/admin/merchandise/:id", { id: "A11", title: "Edit an item" }, function (ctx) {
    return AS.api.get("/admin/products/" + encodeURIComponent(ctx.params.id)).then(function (r) { if (ctx.current()) { r.id = r.id || ctx.params.id; merchForm(ctx, r); } });
  });

  // ================= portfolio
  A.route("/admin/portfolio", { id: "A12", title: "Portfolio" }, function (ctx) {
    ctx.main.appendChild(h("div", { class: "row" }, h("a", { class: "btn", href: "/admin/portfolio/new" }, AS.icon("plus", "icon--sm"), "Add a photo")));
    var box = h("div"); ctx.main.appendChild(box);
    function load() {
      return Promise.all([AS.api.get("/admin/portfolio", { pageSize: 100 }), AS.siteInfo()]).then(function (all) {
        if (!ctx.current()) { return; }
        var items = all[0].items || [], cats = {}; ((all[1] && all[1].portfolioCategories) || []).forEach(function (c) { cats[c.id] = c.name; });
        AS.clear(box);
        if (!items.length) { box.appendChild(AS.ui.empty("No photos yet", "Add your first photo.", "/admin/portfolio/new", "Add a photo")); return; }
        if (all[0].total > items.length) { box.appendChild(AS.alertBox("info", "Showing the first " + items.length + " photos.")); }
        box.appendChild(h("ul", { class: "cards-grid list" }, items.map(function (p, i) {
          var feat = F.checkbox("pf-" + p.id, "Show on the home page", p.featured);
          feat.input.addEventListener("change", function () {
            AS.api.put("/admin/portfolio/" + p.id, { imageId: p.image.id, caption: p.caption || "", description: p.description || "", categoryId: p.categoryId, featured: feat.input.checked }).then(function () { AS.toast("Saved"); p.featured = feat.input.checked; }, function (err) { feat.input.checked = !feat.input.checked; A.fail(err); });
          });
          var mv = function (d, txt) { return h("button", { type: "button", class: "btn btn--secondary btn--sm", disabled: i + d < 0 || i + d >= items.length, text: txt, onclick: function (e) { A.busy(e.currentTarget, function () { return AS.api.put("/admin/portfolio-order", { ids: reorderIds(items, i, d) }); }).then(load, A.fail); } }); };
          return h("li", { class: "list-item list-item--thumb" }, h("div", { class: "thumb" }, AS.img(p.image, { ratio: "portrait", size: "thumb", alt: "" })),
            h("p", { class: "list-item__title" }, h("a", { href: "/admin/portfolio/" + p.id, text: p.caption || "Untitled photo" })),
            h("p", { class: "list-item__meta", text: (cats[p.categoryId] || "No category") + " · Position " + (i + 1) }), feat.el,
            h("div", { class: "list-item__actions" }, h("a", { class: "btn btn--secondary btn--sm", href: "/admin/portfolio/" + p.id, text: "Edit" }), mv(-1, "Move up"), mv(1, "Move down")));
        })));
      });
    }
    return load();
  });

  function photoForm(ctx, rec, cats) {
    var isNew = !rec, r = rec || {};
    var pics = uploader(r.image ? [{ imageId: r.image.id, alt: r.description || r.image.alt || "" }] : [], { id: "ph", single: true, altMax: 300 });
    // The photo's alt text is its description; the uploader shows it, but the API field is `description`.
    var f = {
      caption: F.field({ id: "ph-cap", name: "caption", label: "Caption", value: r.caption, maxlength: 200 }),
      categoryId: F.field({ id: "ph-cat", name: "categoryId", label: "Category", required: true, value: r.categoryId || (cats[0] && cats[0].id), options: cats.map(function (c) { return { value: c.id, label: c.name }; }) })
    };
    var featured = F.checkbox("ph-feat", "Show on the home page", r.featured), btn = h("button", { type: "submit", class: "btn btn--lg" }, "Save");
    var form = h("form", { novalidate: true, class: "panel" }, pics.el, f.caption.el, f.categoryId.el, featured.el, h("div", { class: "form-actions" }, btn, h("a", { class: "btn btn--secondary", href: "/admin/portfolio", text: "Cancel" })));
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var items = pics.items();
      if (!items.length) { AS.toast("Choose a photo first.", "error"); return; }
      if (!pics.validate()) { return; }
      var body = { imageId: items[0].imageId, caption: f.caption.value(), description: items[0].alt.trim(), categoryId: f.categoryId.input.value, featured: featured.input.checked };
      A.busy(btn, function () { return isNew ? AS.api.post("/admin/portfolio", body) : AS.api.put("/admin/portfolio/" + r.id, body); }).then(function () { AS.toast("Saved"); A.go("/admin/portfolio"); }, function (err) { A.formError(err, Object.assign({ description: { setError: function (m) { AS.toast(m, "error"); } } }, f)); });
    });
    ctx.main.appendChild(h("p", {}, h("a", { href: "/admin/portfolio", text: "Back to the portfolio" })));
    ctx.main.appendChild(form);
    if (!isNew) {
      var rm = h("button", { type: "button", class: "btn btn--danger", text: "Remove this photo" });
      rm.addEventListener("click", function () { removeDialog("photo").then(function (ok) { if (ok) { A.busy(rm, function () { return AS.api.del("/admin/portfolio/" + r.id); }).then(function () { AS.toast("Removed"); A.go("/admin/portfolio"); }, A.fail); } }); });
      ctx.main.appendChild(h("section", { class: "panel" }, h("h2", { text: "Remove" }), rm));
    }
  }
  A.route("/admin/portfolio/new", { id: "A13", title: "Add a photo" }, function (ctx) { return AS.siteInfo().then(function (i) { photoForm(ctx, null, (i && i.portfolioCategories) || []); }); });
  A.route("/admin/portfolio/:id", { id: "A13", title: "Edit a photo" }, function (ctx) {
    // The admin API has no single-item GET, so the item is found in the list (contract 3.8).
    return Promise.all([AS.api.get("/admin/portfolio", { pageSize: 100 }), AS.siteInfo()]).then(function (all) {
      var item = (all[0].items || []).filter(function (x) { return x.id === ctx.params.id; })[0];
      if (!item) { throw { status: 404 }; }
      if (ctx.current()) { photoForm(ctx, item, (all[1] && all[1].portfolioCategories) || []); }
    });
  });
})(window.AS);
