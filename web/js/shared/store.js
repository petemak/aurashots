/*
 * Browser-side baskets (design-spec P7, P10). The session basket and the merchandise bag are kept
 * separately in localStorage; the server re-validates everything on quote and submit, so these
 * values are convenience only. Also holds the in-progress booking draft in sessionStorage.
 */
(function (AS) {
  "use strict";
  var SESS = "as.sessions", BAG = "as.bag", DRAFT = "as.bookingDraft", ODRAFT = "as.orderDraft";

  function read(store, key, fallback) {
    try { var v = JSON.parse(store.getItem(key)); return v === null ? fallback : v; } catch (e) { return fallback; }
  }
  function write(store, key, value) {
    try { store.setItem(key, JSON.stringify(value)); } catch (e) { /* storage full or blocked: baskets simply do not persist */ }
    window.dispatchEvent(new CustomEvent("as:baskets"));
  }
  window.addEventListener("storage", function () { window.dispatchEvent(new CustomEvent("as:baskets")); });

  var sessions = {
    all: function () { return read(localStorage, SESS, []); },
    has: function (id) { return sessions.all().some(function (s) { return s.id === id; }); },
    add: function (svc) {
      if (sessions.has(svc.id)) { return; }
      var l = sessions.all();
      l.push({ id: svc.id, slug: svc.slug, name: svc.name, priceRwf: svc.priceRwf, image: svc.mainImage || null });
      write(localStorage, SESS, l);
    },
    remove: function (id) { write(localStorage, SESS, sessions.all().filter(function (s) { return s.id !== id; })); },
    clear: function () { write(localStorage, SESS, []); }
  };

  function lineKey(l) { return l.productId + "|" + l.optionIds.slice().sort().join(","); }
  var bag = {
    all: function () { return read(localStorage, BAG, []); },
    add: function (line) {
      var l = bag.all(), k = lineKey(line);
      var found = l.filter(function (x) { return lineKey(x) === k; })[0];
      if (found) { found.quantity = Math.min(10, found.quantity + line.quantity); } else { l.push(line); }
      write(localStorage, BAG, l);
    },
    setQty: function (k, q) {
      write(localStorage, BAG, bag.all().map(function (x) { if (lineKey(x) === k) { x.quantity = Math.max(1, Math.min(10, q)); } return x; }));
    },
    remove: function (k) { write(localStorage, BAG, bag.all().filter(function (x) { return lineKey(x) !== k; })); },
    clear: function () { write(localStorage, BAG, []); },
    count: function () { return bag.all().reduce(function (n, x) { return n + x.quantity; }, 0); },
    key: lineKey
  };

  function draft(key) {
    return {
      get: function () { return read(sessionStorage, key, {}); },
      set: function (patch) { write(sessionStorage, key, Object.assign({}, read(sessionStorage, key, {}), patch)); },
      clear: function () { try { sessionStorage.removeItem(key); } catch (e) { /* ignore */ } }
    };
  }
  AS.store = { sessions: sessions, bag: bag, bookingDraft: draft(DRAFT), orderDraft: draft(ODRAFT) };
})(window.AS = window.AS || {});
