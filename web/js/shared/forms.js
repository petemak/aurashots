/*
 * Form building blocks: labelled fields with hint and error text linked by aria-describedby,
 * contract-aligned validators and mapping of the API's `fields` errors back onto inputs.
 */
(function (AS) {
  "use strict";
  var h = AS.h;

  // spec: {id, name, label, type, required, hint, value, autocomplete, inputmode, maxlength, rows, options:[{value,label}], counter}
  function field(spec) {
    var id = spec.id, hintId = id + "-hint", errId = id + "-error";
    var control;
    if (spec.options) {
      control = h("select", { class: "select", id: id, name: spec.name || id }, spec.options.map(function (o) { return h("option", { value: o.value, text: o.label }); }));
      if (spec.value !== undefined && spec.value !== null) { control.value = spec.value; }
    } else if (spec.rows) {
      control = h("textarea", { class: "textarea", id: id, name: spec.name || id, rows: spec.rows, maxlength: spec.maxlength });
      control.value = spec.value || "";
    } else {
      control = h("input", { class: "input", id: id, name: spec.name || id, type: spec.type || "text", autocomplete: spec.autocomplete, inputmode: spec.inputmode, maxlength: spec.maxlength, min: spec.min, max: spec.max, step: spec.step });
      control.value = spec.value === undefined || spec.value === null ? "" : spec.value;
    }
    if (spec.required) { control.setAttribute("aria-required", "true"); }
    if (spec.disabled) { control.disabled = true; }
    var described = [];
    if (spec.hint) { described.push(hintId); }
    described.push(errId);
    control.setAttribute("aria-describedby", described.join(" "));
    var err = h("p", { class: "field__error", id: errId }, AS.icon("error", "icon--sm"), h("span", { class: "field__error-text" }));
    var counter = null;
    if (spec.counter && spec.maxlength) {
      counter = h("p", { class: "counter", "aria-hidden": "true" });
      var upd = function () { counter.textContent = control.value.length + " / " + spec.maxlength; };
      control.addEventListener("input", upd); upd();
    }
    var el = h("div", { class: "field" },
      h("label", { for: id }, spec.label, spec.required ? null : h("span", { class: "field__req", text: " (optional)" })),
      spec.hint ? h("p", { class: "field__hint", id: hintId, text: spec.hint }) : null,
      control, counter, err);
    var api = {
      el: el, input: control, name: spec.name || id,
      value: function () { return control.value.trim(); },
      setError: function (msg) {
        if (msg) { el.classList.add("field--error"); control.setAttribute("aria-invalid", "true"); err.querySelector(".field__error-text").textContent = msg; }
        else { el.classList.remove("field--error"); control.removeAttribute("aria-invalid"); err.querySelector(".field__error-text").textContent = ""; }
      }
    };
    control.addEventListener("input", function () { if (el.classList.contains("field--error")) { api.setError(null); } });
    return api;
  }

  var V = {
    required: function (label) { return function (v) { return v ? null : "Enter " + label + "."; }; },
    name: function (v) { return v.length >= 2 && v.length <= 100 ? null : "Enter your full name (2 to 100 characters)."; },
    phone: function (v) { return /^\+?[0-9 ]{7,20}$/.test(v) ? null : "Enter a phone number with country code, for example +250 790 114 749."; },
    email: function (v) { return v.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) ? null : "Enter a valid email address."; },
    max: function (n, label) { return function (v) { return v.length <= n ? null : label + " can be at most " + n + " characters."; }; }
  };

  // Runs [{field, check}] in order; shows each error, focuses the first failing field. Returns true when all pass.
  function validate(rules) {
    var first = null;
    rules.forEach(function (r) {
      var msg = r.check(r.field.value());
      r.field.setError(msg);
      if (msg && !first) { first = r.field; }
    });
    if (first) { first.input.focus(); }
    return !first;
  }

  // Applies an ApiError.fields map ({fieldName: message}) to named field objects. Returns true if any matched.
  function applyServerErrors(err, fieldsByName) {
    var any = false, first = null;
    Object.keys((err && err.fields) || {}).forEach(function (k) {
      var f = fieldsByName[k];
      if (f) { f.setError(err.fields[k]); any = true; if (!first) { first = f; } }
    });
    if (first) { first.input.focus(); }
    return any;
  }

  // Radio group rendered as chips. items: [{value,label,disabled,note}]
  function chipRadios(name, legend, items, selected) {
    var group = h("fieldset", { class: "field" }, h("legend", { text: legend }), h("div", { class: "chips" },
      items.map(function (it, i) {
        var id = name + "-" + i;
        var input = h("input", { type: "radio", id: id, name: name, value: it.value, disabled: it.disabled, checked: selected === it.value });
        return h("label", { class: "chip-radio", for: id }, input, h("span", {}, it.label, it.note ? h("span", { class: "chip-note", text: it.note }) : null));
      })));
    return { el: group, value: function () { var c = group.querySelector("input:checked"); return c ? c.value : null; } };
  }

  function checkbox(id, label, checked) {
    var input = h("input", { type: "checkbox", id: id, checked: !!checked });
    return { el: h("div", { class: "check" }, input, h("label", { for: id }, label)), input: input };
  }

  AS.forms = { field: field, V: V, validate: validate, applyServerErrors: applyServerErrors, chipRadios: chipRadios, checkbox: checkbox };
})(window.AS = window.AS || {});
