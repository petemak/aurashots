/*
 * API client: the ONLY module that calls fetch (architecture.md section 2.5). Same-origin /api only.
 * Adds the CSRF header to state-changing calls once a token is set, optionally an Idempotency-Key,
 * and maps every failure to an ApiError that carries the contract's error shape {code, message, fields}.
 */
(function (AS) {
  "use strict";
  var csrfToken = null;

  function ApiError(status, error) {
    this.name = "ApiError";
    this.status = status;
    this.code = (error && error.code) || (status === 0 ? "network" : "serverError");
    this.message = (error && error.message) || "Something went wrong. Please try again.";
    this.fields = (error && error.fields) || null;
  }
  ApiError.prototype = Object.create(Error.prototype);

  function query(params) {
    var parts = [];
    Object.keys(params || {}).forEach(function (k) {
      var v = params[k];
      if (v === null || v === undefined || v === "") { return; }
      parts.push(encodeURIComponent(k) + "=" + encodeURIComponent(Array.isArray(v) ? v.join(",") : v));
    });
    return parts.length ? "?" + parts.join("&") : "";
  }

  function request(method, path, opts) {
    opts = opts || {};
    var headers = { "Accept": "application/json" };
    var init = { method: method, headers: headers, credentials: "same-origin" };
    if (opts.body instanceof FormData) {
      init.body = opts.body;
    } else if (opts.body !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(opts.body);
    }
    if (method !== "GET" && csrfToken) { headers["X-CSRF-Token"] = csrfToken; }
    if (opts.idempotencyKey) { headers["Idempotency-Key"] = opts.idempotencyKey; }
    return fetch("/api" + path + query(opts.query), init).then(function (res) {
      if (res.status === 204) { return null; }
      return res.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
        if (!res.ok) {
          var err = new ApiError(res.status, data && data.error);
          err.retryAfter = res.headers.get("Retry-After");
          if (res.status === 401 && path.indexOf("/admin/") === 0 && path.indexOf("/admin/auth/login") !== 0) {
            window.dispatchEvent(new CustomEvent("as:unauthenticated"));
          }
          throw err;
        }
        if (opts.wantHeaders) { return { data: data, replay: res.headers.get("Idempotent-Replay") === "true" }; }
        return data;
      });
    }, function () {
      throw new ApiError(0, { code: "network", message: "We could not reach the server. Check your connection and try again." });
    });
  }

  function uuid() {
    if (window.crypto && crypto.randomUUID) { return crypto.randomUUID(); }
    var b = new Uint8Array(16);
    crypto.getRandomValues(b);
    b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
    var h = Array.prototype.map.call(b, function (x) { return ("0" + x.toString(16)).slice(-2); }).join("");
    return [h.slice(0, 8), h.slice(8, 12), h.slice(12, 16), h.slice(16, 20), h.slice(20)].join("-");
  }

  AS.ApiError = ApiError;
  AS.api = {
    get: function (path, params) { return request("GET", path, { query: params }); },
    post: function (path, body, opts) { return request("POST", path, Object.assign({ body: body === undefined ? {} : body }, opts)); },
    put: function (path, body) { return request("PUT", path, { body: body }); },
    patch: function (path, body) { return request("PATCH", path, { body: body }); },
    del: function (path) { return request("DELETE", path); },
    upload: function (path, file) { var fd = new FormData(); fd.append("file", file); return request("POST", path, { body: fd }); },
    setCsrf: function (t) { csrfToken = t; },
    uuid: uuid
  };
})(window.AS = window.AS || {});
