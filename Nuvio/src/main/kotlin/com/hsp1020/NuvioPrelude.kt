package com.hsp1020

// Shared web, DOM, crypto and CommonJS polyfills for QuickJS.
private const val PART_WEB = """
// Part 2: byte helpers, TextEncoder/TextDecoder, Buffer, URLSearchParams, URL, axios.
// ES5 syntax only on purpose.

// ---------------------------------------------------------------- byte helpers
var __B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

function __toBytes(x) {
  if (x === null || x === undefined) return [];
  var u = null;
  if (typeof ArrayBuffer !== 'undefined' && x instanceof ArrayBuffer) u = new Uint8Array(x);
  else if (x.buffer && typeof ArrayBuffer !== 'undefined' && x.buffer instanceof ArrayBuffer) u = new Uint8Array(x.buffer, x.byteOffset || 0, x.byteLength);
  var src = u || x;
  var out = new Array(src.length);
  for (var i = 0; i < src.length; i++) out[i] = src[i] & 255;
  return out;
}
function __bytesToHex(b) {
  var h = '0123456789abcdef', s = [];
  for (var i = 0; i < b.length; i++) s.push(h.charAt((b[i] >> 4) & 15) + h.charAt(b[i] & 15));
  return s.join('');
}
function __hexToBytes(s) {
  s = String(s).replace(/[^0-9a-fA-F]/g, '');
  var out = [];
  for (var i = 0; i + 1 < s.length; i += 2) out.push(parseInt(s.substr(i, 2), 16));
  return out;
}
function __bytesToB64(b, urlSafe, noPad) {
  var out = [], i = 0, n = b.length;
  for (; i + 2 < n; i += 3) {
    var v = (b[i] << 16) | (b[i + 1] << 8) | b[i + 2];
    out.push(__B64.charAt((v >> 18) & 63), __B64.charAt((v >> 12) & 63), __B64.charAt((v >> 6) & 63), __B64.charAt(v & 63));
  }
  if (n - i === 1) {
    var v1 = b[i] << 16;
    out.push(__B64.charAt((v1 >> 18) & 63), __B64.charAt((v1 >> 12) & 63), '==');
  } else if (n - i === 2) {
    var v2 = (b[i] << 16) | (b[i + 1] << 8);
    out.push(__B64.charAt((v2 >> 18) & 63), __B64.charAt((v2 >> 12) & 63), __B64.charAt((v2 >> 6) & 63), '=');
  }
  var s = out.join('');
  if (urlSafe) s = s.replace(/\+/g, '-').replace(/\//g, '_');
  if (noPad) s = s.replace(/=+${'$'}/, '');
  return s;
}
function __b64ToBytes(s) {
  s = String(s).replace(/-/g, '+').replace(/_/g, '/').replace(/[^A-Za-z0-9+\/]/g, '');
  var out = [], acc = 0, bits = 0;
  for (var i = 0; i < s.length; i++) {
    acc = (acc << 6) | __B64.indexOf(s.charAt(i)); bits += 6;
    if (bits >= 8) { bits -= 8; out.push((acc >> bits) & 255); acc &= (1 << bits) - 1; }
  }
  return out;
}
function __utf8Bytes(str) {
  var out = [];
  for (var i = 0; i < str.length; i++) {
    var c = str.charCodeAt(i);
    if (c >= 0xD800 && c <= 0xDBFF && i + 1 < str.length) {
      var d = str.charCodeAt(i + 1);
      if (d >= 0xDC00 && d <= 0xDFFF) { c = 0x10000 + ((c - 0xD800) << 10) + (d - 0xDC00); i++; }
    }
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xC0 | (c >> 6), 0x80 | (c & 63));
    else if (c < 0x10000) out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    else out.push(0xF0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  return out;
}
function __utf8Decode(b) {
  var out = [], i = 0, n = b.length;
  while (i < n) {
    var c = b[i++], cp;
    if (c < 0x80) cp = c;
    else if (c >= 0xC0 && c < 0xE0 && i < n) cp = ((c & 31) << 6) | (b[i++] & 63);
    else if (c >= 0xE0 && c < 0xF0 && i + 1 < n) { cp = ((c & 15) << 12) | ((b[i] & 63) << 6) | (b[i + 1] & 63); i += 2; }
    else if (c >= 0xF0 && i + 2 < n) { cp = ((c & 7) << 18) | ((b[i] & 63) << 12) | ((b[i + 1] & 63) << 6) | (b[i + 2] & 63); i += 3; }
    else cp = 0xFFFD;
    if (cp > 0xFFFF) { cp -= 0x10000; out.push(String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 1023))); }
    else out.push(String.fromCharCode(cp));
  }
  return out.join('');
}
function __latin1Bytes(s) { var o = []; for (var i = 0; i < s.length; i++) o.push(s.charCodeAt(i) & 255); return o; }
function __latin1Decode(b) { var o = []; for (var i = 0; i < b.length; i++) o.push(String.fromCharCode(b[i] & 255)); return o.join(''); }
function __newU8(bytes) { var u = new Uint8Array(bytes.length); for (var i = 0; i < bytes.length; i++) u[i] = bytes[i]; return u; }

// ---------------------------------------------------------------- TextEncoder / TextDecoder
if (typeof TextEncoder === 'undefined') {
  var TextEncoder = function () { this.encoding = 'utf-8'; };
  TextEncoder.prototype.encode = function (s) { return __newU8(__utf8Bytes(s === undefined ? '' : String(s))); };
  globalThis.TextEncoder = TextEncoder;
}
if (typeof TextDecoder === 'undefined') {
  var TextDecoder = function (label) { this.encoding = (label || 'utf-8').toLowerCase(); };
  TextDecoder.prototype.decode = function (buf) {
    var b = __toBytes(buf);
    return this.encoding === 'latin1' || this.encoding === 'iso-8859-1' ? __latin1Decode(b) : __utf8Decode(b);
  };
  globalThis.TextDecoder = TextDecoder;
}

// ---------------------------------------------------------------- Buffer (minimal)
if (typeof Buffer === 'undefined') {
  var Buffer = function () { throw new Error('Use Buffer.from()'); };
  var __makeBuffer = function (bytes) {
    var u = __newU8(bytes);
    u.__isBuffer = true;
    u.toString = function (enc, start, end) {
      var b = __toBytes(this);
      if (start !== undefined || end !== undefined) b = b.slice(start || 0, end === undefined ? b.length : end);
      enc = (enc || 'utf8').toLowerCase();
      if (enc === 'base64') return __bytesToB64(b);
      if (enc === 'base64url') return __bytesToB64(b, true, true);
      if (enc === 'hex') return __bytesToHex(b);
      if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') return __latin1Decode(b);
      return __utf8Decode(b);
    };
    return u;
  };
  Buffer.from = function (v, enc) {
    var bytes;
    if (typeof v === 'string') {
      enc = (enc || 'utf8').toLowerCase();
      if (enc === 'base64' || enc === 'base64url') bytes = __b64ToBytes(v);
      else if (enc === 'hex') bytes = __hexToBytes(v);
      else if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') bytes = __latin1Bytes(v);
      else bytes = __utf8Bytes(v);
    } else bytes = __toBytes(v);
    return __makeBuffer(bytes);
  };
  Buffer.alloc = function (n, fill) { var b = []; for (var i = 0; i < n; i++) b.push(fill ? fill & 255 : 0); return __makeBuffer(b); };
  Buffer.concat = function (list) { var b = []; for (var i = 0; i < list.length; i++) b = b.concat(__toBytes(list[i])); return __makeBuffer(b); };
  Buffer.isBuffer = function (x) { return !!(x && x.__isBuffer); };
  Buffer.byteLength = function (s) { return __utf8Bytes(String(s)).length; };
  globalThis.Buffer = Buffer;
}

// ---------------------------------------------------------------- URLSearchParams / URL
(function () {
  function enc(s) {
    return encodeURIComponent(String(s)).replace(/%20/g, '+').replace(/[!'()~]/g, function (c) {
      return '%' + c.charCodeAt(0).toString(16).toUpperCase();
    });
  }
  function dec(s) {
    s = String(s).replace(/\+/g, ' ');
    try { return decodeURIComponent(s); } catch (e) { return s; }
  }

  function URLSearchParams(init) {
    this._l = [];
    this._u = null;
    if (init === undefined || init === null) return;
    if (typeof init === 'string') this._parse(init);
    else if (init instanceof URLSearchParams) { for (var i = 0; i < init._l.length; i++) this._l.push([init._l[i][0], init._l[i][1]]); }
    else if (Array.isArray(init)) { for (var j = 0; j < init.length; j++) this._l.push([String(init[j][0]), String(init[j][1])]); }
    else if (typeof init === 'object') { for (var k in init) if (Object.prototype.hasOwnProperty.call(init, k)) this._l.push([k, String(init[k])]); }
  }
  URLSearchParams.prototype._parse = function (str) {
    str = String(str).replace(/^\?/, '');
    this._l = [];
    if (!str) return;
    var parts = str.split('&');
    for (var i = 0; i < parts.length; i++) {
      if (!parts[i]) continue;
      var eq = parts[i].indexOf('=');
      if (eq < 0) this._l.push([dec(parts[i]), '']);
      else this._l.push([dec(parts[i].slice(0, eq)), dec(parts[i].slice(eq + 1))]);
    }
  };
  URLSearchParams.prototype._touch = function () { if (this._u) this._u._p.search = this.toString() ? '?' + this.toString() : ''; };
  URLSearchParams.prototype.append = function (k, v) { this._l.push([String(k), String(v)]); this._touch(); };
  URLSearchParams.prototype['delete'] = function (k) {
    k = String(k);
    this._l = this._l.filter(function (p) { return p[0] !== k; });
    this._touch();
  };
  URLSearchParams.prototype.get = function (k) {
    k = String(k);
    for (var i = 0; i < this._l.length; i++) if (this._l[i][0] === k) return this._l[i][1];
    return null;
  };
  URLSearchParams.prototype.getAll = function (k) {
    k = String(k);
    var o = [];
    for (var i = 0; i < this._l.length; i++) if (this._l[i][0] === k) o.push(this._l[i][1]);
    return o;
  };
  URLSearchParams.prototype.has = function (k) { return this.get(k) !== null; };
  URLSearchParams.prototype.set = function (k, v) {
    k = String(k); v = String(v);
    var found = false, out = [];
    for (var i = 0; i < this._l.length; i++) {
      if (this._l[i][0] === k) { if (!found) { out.push([k, v]); found = true; } }
      else out.push(this._l[i]);
    }
    if (!found) out.push([k, v]);
    this._l = out;
    this._touch();
  };
  URLSearchParams.prototype.sort = function () {
    this._l.sort(function (a, b) { return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0); });
    this._touch();
  };
  URLSearchParams.prototype.forEach = function (cb, thisArg) {
    for (var i = 0; i < this._l.length; i++) cb.call(thisArg, this._l[i][1], this._l[i][0], this);
  };
  function iter(arr) {
    var i = 0, it = { next: function () { return i < arr.length ? { value: arr[i++], done: false } : { value: undefined, done: true }; } };
    if (typeof Symbol !== 'undefined' && Symbol.iterator) it[Symbol.iterator] = function () { return it; };
    return it;
  }
  URLSearchParams.prototype.keys = function () { return iter(this._l.map(function (p) { return p[0]; })); };
  URLSearchParams.prototype.values = function () { return iter(this._l.map(function (p) { return p[1]; })); };
  URLSearchParams.prototype.entries = function () { return iter(this._l.map(function (p) { return [p[0], p[1]]; })); };
  if (typeof Symbol !== 'undefined' && Symbol.iterator) {
    URLSearchParams.prototype[Symbol.iterator] = URLSearchParams.prototype.entries;
  }
  URLSearchParams.prototype.toString = function () {
    return this._l.map(function (p) { return enc(p[0]) + '=' + enc(p[1]); }).join('&');
  };

  var DEFAULT_PORT = { 'http:': '80', 'https:': '443', 'ws:': '80', 'wss:': '443', 'ftp:': '21' };
  var SPECIAL = { 'http:': 1, 'https:': 1, 'ws:': 1, 'wss:': 1, 'ftp:': 1, 'file:': 1 };

  function normPath(path) {
    var segs = path.split('/'), out = [];
    for (var i = 0; i < segs.length; i++) {
      var s = segs[i];
      if (s === '..') { if (out.length > 1) out.pop(); if (i === segs.length - 1) out.push(''); }
      else if (s === '.') { if (i === segs.length - 1) out.push(''); }
      else out.push(s);
    }
    var r = out.join('/');
    return r.charAt(0) === '/' ? r : '/' + r;
  }

  function parseAbs(s) {
    var m = /^([a-zA-Z][a-zA-Z0-9+.\-]*:)(\/\/([^\/?#]*))?([^?#]*)(\?[^#]*)?(#.*)?${'$'}/.exec(s);
    if (!m) return null;
    var p = { protocol: m[1].toLowerCase(), username: '', password: '', hostname: '', port: '', pathname: m[4] || '', search: m[5] || '', hash: m[6] || '', hasAuth: m[2] !== undefined };
    if (p.search === '?') p.search = '';
    if (p.hash === '#') p.hash = '';
    if (m[2] !== undefined) {
      var auth = m[3] || '';
      var at = auth.lastIndexOf('@');
      if (at >= 0) {
        var cred = auth.slice(0, at); auth = auth.slice(at + 1);
        var ci = cred.indexOf(':');
        p.username = ci >= 0 ? cred.slice(0, ci) : cred;
        p.password = ci >= 0 ? cred.slice(ci + 1) : '';
      }
      var hm = /^(\[[^\]]*\]|[^:]*)(?::(\d*))?${'$'}/.exec(auth);
      p.hostname = (hm ? hm[1] : auth).toLowerCase();
      p.port = hm && hm[2] ? hm[2] : '';
      if (DEFAULT_PORT[p.protocol] === p.port) p.port = '';
    }
    if (SPECIAL[p.protocol] && p.hasAuth) {
      p.pathname = p.pathname ? normPath(p.pathname.charAt(0) === '/' ? p.pathname : '/' + p.pathname) : '/';
    }
    return p;
  }

  function resolve(s, base) {
    var abs = parseAbs(s);
    if (abs) return abs;
    if (!base) return null;
    var b = typeof base === 'string' ? parseAbs(base) : base._p;
    if (!b) return null;
    var out = { protocol: b.protocol, username: b.username, password: b.password, hostname: b.hostname, port: b.port, pathname: b.pathname, search: b.search, hash: '', hasAuth: b.hasAuth };
    if (s.indexOf('//') === 0) {
      var again = parseAbs(b.protocol + s);
      return again;
    }
    var m = /^([^?#]*)(\?[^#]*)?(#.*)?${'$'}/.exec(s);
    var path = m[1], q = m[2], h = m[3];
    if (path === '') {
      if (q !== undefined) out.search = q === '?' ? '' : q;
      out.hash = h && h !== '#' ? h : '';
      return out;
    }
    out.search = q && q !== '?' ? q : '';
    out.hash = h && h !== '#' ? h : '';
    if (path.charAt(0) === '/') out.pathname = normPath(path);
    else {
      var dir = b.pathname.slice(0, b.pathname.lastIndexOf('/') + 1) || '/';
      out.pathname = normPath(dir + path);
    }
    return out;
  }

  function URL(href, base) {
    var p = resolve(String(href).trim(), base);
    if (!p) { var e = new TypeError('Invalid URL: ' + href); e.code = 'ERR_INVALID_URL'; throw e; }
    this._p = p;
    this._sp = new URLSearchParams(p.search);
    this._sp._u = this;
  }
  function def(name, get, set) {
    Object.defineProperty(URL.prototype, name, { get: get, set: set || function () { }, enumerable: true, configurable: true });
  }
  def('protocol', function () { return this._p.protocol; }, function (v) { v = String(v); this._p.protocol = v.slice(-1) === ':' ? v : v + ':'; });
  def('username', function () { return this._p.username; }, function (v) { this._p.username = String(v); });
  def('password', function () { return this._p.password; }, function (v) { this._p.password = String(v); });
  def('hostname', function () { return this._p.hostname; }, function (v) { this._p.hostname = String(v).toLowerCase(); });
  def('port', function () { return this._p.port; }, function (v) { v = String(v); this._p.port = DEFAULT_PORT[this._p.protocol] === v ? '' : v; });
  def('host', function () { return this._p.hostname + (this._p.port ? ':' + this._p.port : ''); }, function (v) {
    var m = /^([^:]*)(?::(\d*))?${'$'}/.exec(String(v));
    if (m) { this._p.hostname = m[1].toLowerCase(); this._p.port = m[2] || ''; }
  });
  def('pathname', function () { return this._p.pathname; }, function (v) { v = String(v); this._p.pathname = v.charAt(0) === '/' ? v : '/' + v; });
  def('search', function () { return this._p.search; }, function (v) {
    v = String(v);
    this._p.search = v === '' || v === '?' ? '' : (v.charAt(0) === '?' ? v : '?' + v);
    this._sp._parse(this._p.search);
  });
  def('hash', function () { return this._p.hash; }, function (v) {
    v = String(v);
    this._p.hash = v === '' || v === '#' ? '' : (v.charAt(0) === '#' ? v : '#' + v);
  });
  def('origin', function () {
    return SPECIAL[this._p.protocol] && this._p.protocol !== 'file:' ? this._p.protocol + '//' + this.host : 'null';
  });
  def('searchParams', function () { return this._sp; });
  def('href', function () {
    var p = this._p, cred = p.username ? p.username + (p.password ? ':' + p.password : '') + '@' : '';
    return p.protocol + (p.hasAuth ? '//' + cred + this.host : '') + p.pathname + p.search + p.hash;
  }, function (v) {
    var p = parseAbs(String(v));
    if (!p) throw new TypeError('Invalid URL: ' + v);
    this._p = p; this._sp._parse(p.search);
  });
  URL.prototype.toString = function () { return this.href; };
  URL.prototype.toJSON = function () { return this.href; };

  globalThis.URLSearchParams = URLSearchParams;
  globalThis.URL = URL;
})();

// ---------------------------------------------------------------- axios (subset)
var __axios = (function () {
  function merge(a, b) {
    var o = {}, k;
    for (k in a) if (Object.prototype.hasOwnProperty.call(a, k)) o[k] = a[k];
    for (k in b) if (Object.prototype.hasOwnProperty.call(b, k)) o[k] = b[k];
    if (a && a.headers || b && b.headers) o.headers = merge(a && a.headers, b && b.headers);
    return o;
  }
  function request(defaults, cfg) {
    cfg = merge(defaults || {}, cfg || {});
    var url = String(cfg.url);
    if (cfg.baseURL && !/^[a-zA-Z][a-zA-Z0-9+.\-]*:\/\//.test(url)) url = cfg.baseURL.replace(/\/+${'$'}/, '') + '/' + url.replace(/^\/+/, '');
    if (cfg.params) {
      var sp = new URLSearchParams(cfg.params instanceof URLSearchParams ? cfg.params : undefined);
      if (!(cfg.params instanceof URLSearchParams)) for (var pk in cfg.params) if (Object.prototype.hasOwnProperty.call(cfg.params, pk) && cfg.params[pk] !== undefined && cfg.params[pk] !== null) sp.append(pk, cfg.params[pk]);
      var q = sp.toString();
      if (q) url += (url.indexOf('?') >= 0 ? '&' : '?') + q;
    }
    var headers = {};
    var hs = cfg.headers || {};
    for (var hk in hs) if (Object.prototype.hasOwnProperty.call(hs, hk) && typeof hs[hk] !== 'object') headers[hk] = hs[hk];
    function hasHeader(n) { for (var k in headers) if (k.toLowerCase() === n) return true; return false; }
    var body = cfg.data;
    if (body !== undefined && body !== null && typeof body !== 'string') {
      if (body instanceof URLSearchParams) { body = body.toString(); if (!hasHeader('content-type')) headers['Content-Type'] = 'application/x-www-form-urlencoded'; }
      else if (typeof body === 'object') { body = JSON.stringify(body); if (!hasHeader('content-type')) headers['Content-Type'] = 'application/json'; }
      else body = String(body);
    }
    var method = String(cfg.method || 'get').toUpperCase();
    var p = fetch(url, { method: method, headers: headers, body: body, redirect: cfg.maxRedirects === 0 ? 'manual' : 'follow' });
    if (cfg.timeout > 0) {
      p = Promise.race([p, new Promise(function (_, rej) {
        setTimeout(function () { var e = new Error('timeout of ' + cfg.timeout + 'ms exceeded'); e.code = 'ECONNABORTED'; e.isAxiosError = true; rej(e); }, cfg.timeout);
      })]);
    }
    return p.then(function (res) {
      var rt = cfg.responseType || 'json';
      var hdrs = {};
      res.headers.forEach(function (v, k) { hdrs[k] = v; });
      var out = { status: res.status, statusText: res.statusText, headers: hdrs, config: cfg, request: { responseURL: res.url }, data: null };
      var pr = rt === 'arraybuffer' ? res.arrayBuffer() : res.text().then(function (t) {
        if (rt === 'text') return t;
        try { return JSON.parse(t); } catch (e) { return t; }
      });
      return pr.then(function (d) {
        out.data = d;
        var ok = typeof cfg.validateStatus === 'function' ? cfg.validateStatus(res.status) : (res.status >= 200 && res.status < 300);
        if (!ok) {
          var err = new Error('Request failed with status code ' + res.status);
          err.response = out; err.config = cfg; err.isAxiosError = true; err.code = 'ERR_BAD_REQUEST';
          throw err;
        }
        return out;
      });
    });
  }
  function create(defaults) {
    defaults = defaults || {};
    var inst = function (cfg) { return request(defaults, typeof cfg === 'string' ? { url: cfg } : cfg); };
    inst.defaults = defaults;
    inst.interceptors = { request: { use: function () { } }, response: { use: function () { } } };
    inst.request = function (cfg) { return request(defaults, cfg); };
    ['get', 'delete', 'head', 'options'].forEach(function (m) {
      inst[m] = function (url, cfg) { return request(defaults, merge(cfg || {}, { url: url, method: m })); };
    });
    ['post', 'put', 'patch'].forEach(function (m) {
      inst[m] = function (url, data, cfg) { return request(defaults, merge(cfg || {}, { url: url, method: m, data: data })); };
    });
    inst.create = function (d) { return create(merge(defaults, d || {})); };
    inst.isAxiosError = function (e) { return !!(e && e.isAxiosError); };
    inst.all = function (p) { return Promise.all(p); };
    inst.spread = function (cb) { return function (arr) { return cb.apply(null, arr); }; };
    return inst;
  }
  var a = create({});
  a['default'] = a;
  return a;
})();

"""

private const val PART_CHEERIO = """
// Part 3: cheerio-compatible API on top of the native Jsoup bridge (__domCall).
// The DOM lives on the Kotlin side; JS only keeps numeric node ids.

var __cheerio = (function () {
  function dom(op, ids, a, b) {
    var r = __domCall(op, JSON.stringify(ids), a === undefined ? null : a, b === undefined ? null : b);
    return r === null || r === undefined ? null : JSON.parse(r);
  }

  var nodeCache = {};
  function makeNode(id) {
    var n = nodeCache[id];
    if (n) return n;
    n = { __id: id, type: 'tag' };
    Object.defineProperty(n, 'name', { get: function () { return dom('tagName', [id]); }, enumerable: true });
    Object.defineProperty(n, 'attribs', { get: function () { return dom('attribs', [id]) || {}; }, enumerable: true });
    Object.defineProperty(n, 'children', { get: function () { return (dom('rel', [id], 'children', null) || []).map(makeNode); }, enumerable: false });
    Object.defineProperty(n, 'parent', {
      get: function () { var p = dom('rel', [id], 'parent', null) || []; return p.length ? makeNode(p[0]) : null; },
      enumerable: false
    });
    nodeCache[id] = n;
    return n;
  }

  function idsOf(sel) {
    var out = [];
    for (var i = 0; i < sel.length; i++) if (sel[i] && sel[i].__id !== undefined) out.push(sel[i].__id);
    return out;
  }
  function nodes(ids) { var o = []; for (var i = 0; i < ids.length; i++) o.push(makeNode(ids[i])); return o; }

  function Sel(items, doc) {
    this.length = 0;
    this._doc = doc;
    for (var i = 0; i < items.length; i++) this[this.length++] = items[i];
  }
  function mk(sel, items) { return new Sel(items, sel._doc); }

  var P = Sel.prototype;
  P.constructor = Sel;
  P.cheerio = '[cheerio object]';

  P.get = function (i) {
    if (i === undefined) { var a = []; for (var k = 0; k < this.length; k++) a.push(this[k]); return a; }
    return i < 0 ? this[this.length + i] : this[i];
  };
  P.toArray = function () { return this.get(); };
  P.first = function () { return mk(this, this.length ? [this[0]] : []); };
  P.last = function () { return mk(this, this.length ? [this[this.length - 1]] : []); };
  P.eq = function (i) {
    i = Number(i) || 0;
    if (i < 0) i += this.length;
    return mk(this, i >= 0 && i < this.length ? [this[i]] : []);
  };
  P.slice = function (a, b) { return mk(this, this.get().slice(a, b)); };
  P.each = function (fn) {
    for (var i = 0; i < this.length; i++) { if (fn.call(this[i], i, this[i]) === false) break; }
    return this;
  };
  P.map = function (fn) {
    var out = [];
    for (var i = 0; i < this.length; i++) {
      var v = fn.call(this[i], i, this[i]);
      if (v === null || v === undefined) continue;
      if (Array.isArray(v)) out = out.concat(v); else out.push(v);
    }
    return mk(this, out);
  };
  P.find = function (s) { return mk(this, nodes(dom('find', idsOf(this), String(s)) || [])); };
  P.filter = function (x) {
    var out = [], i;
    if (typeof x === 'function') {
      for (i = 0; i < this.length; i++) if (x.call(this[i], i, this[i])) out.push(this[i]);
      return mk(this, out);
    }
    if (typeof x === 'string') return mk(this, nodes(dom('filter', idsOf(this), x) || []));
    var keep = {}; var other = x instanceof Sel ? x.get() : [x];
    for (i = 0; i < other.length; i++) if (other[i] && other[i].__id !== undefined) keep[other[i].__id] = true;
    for (i = 0; i < this.length; i++) if (keep[this[i].__id]) out.push(this[i]);
    return mk(this, out);
  };
  P.not = function (x) {
    var out = [], i;
    if (typeof x === 'function') {
      for (i = 0; i < this.length; i++) if (!x.call(this[i], i, this[i])) out.push(this[i]);
      return mk(this, out);
    }
    if (typeof x === 'string') return mk(this, nodes(dom('not', idsOf(this), x) || []));
    var drop = {}; var other = x instanceof Sel ? x.get() : [x];
    for (i = 0; i < other.length; i++) if (other[i] && other[i].__id !== undefined) drop[other[i].__id] = true;
    for (i = 0; i < this.length; i++) if (!drop[this[i].__id]) out.push(this[i]);
    return mk(this, out);
  };
  P.is = function (x) {
    if (typeof x === 'function') { for (var i = 0; i < this.length; i++) if (x.call(this[i], i, this[i])) return true; return false; }
    if (typeof x === 'string') return dom('is', idsOf(this), x) === true;
    return this.filter(x).length > 0;
  };
  P.has = function (s) { return mk(this, nodes(dom('has', idsOf(this), String(s)) || [])); };

  function rel(name) {
    return function (s) { return mk(this, nodes(dom('rel', idsOf(this), name, s === undefined ? null : String(s)) || [])); };
  }
  P.children = rel('children');
  P.parent = rel('parent');
  P.parents = rel('parents');
  P.closest = rel('closest');
  P.next = rel('next');
  P.nextAll = rel('nextAll');
  P.prev = rel('prev');
  P.prevAll = rel('prevAll');
  P.siblings = rel('siblings');

  P.add = function (x, ctx) {
    var other;
    if (typeof x === 'string') other = (ctx ? new Sel(nodes(dom('find', idsOf(ctx instanceof Sel ? ctx : [ctx]), x) || []), this._doc) : new Sel(nodes(dom('find', [this._doc], x) || []), this._doc)).get();
    else if (x instanceof Sel) other = x.get();
    else if (Array.isArray(x)) other = x;
    else other = [x];
    var all = this.get().concat(other);
    return mk(this, nodes(dom('order', idsOf(all)) || []));
  };

  P.attr = function (name, value) {
    if (typeof name === 'object' && name !== null) {
      for (var k in name) if (Object.prototype.hasOwnProperty.call(name, k)) dom('setAttr', idsOf(this), k, String(name[k]));
      return this;
    }
    if (value === undefined) { var r = dom('attr', idsOf(this), String(name)); return r === null ? undefined : r; }
    dom('setAttr', idsOf(this), String(name), value === null ? null : String(value));
    return this;
  };
  P.removeAttr = function (name) { dom('removeAttr', idsOf(this), String(name)); return this; };
  P.hasClass = function (c) { return dom('hasClass', idsOf(this), String(c)) === true; };
  P.prop = function (name) {
    var r = dom('prop', idsOf(this), String(name));
    return r === null ? undefined : r;
  };
  P.data = function (key) {
    var r = dom('attr', idsOf(this), 'data-' + String(key).replace(/([A-Z])/g, '-${'$'}1').toLowerCase());
    if (r === null) return undefined;
    if (r === 'true') return true;
    if (r === 'false') return false;
    if (r === 'null') return null;
    if (r !== '' && String(+r) === r) return +r;
    if (/^[\[{]/.test(r)) { try { return JSON.parse(r); } catch (e) { } }
    return r;
  };
  P.val = function () { var r = dom('val', idsOf(this)); return r === null ? undefined : r; };
  P.text = function () { return this.length ? dom('text', idsOf(this)) : ''; };
  P.html = function () { return this.length ? dom('html', idsOf(this)) : null; };
  P.outerHtml = function () { return this.length ? dom('outer', idsOf(this)) : ''; };
  P.toString = function () { return this.length ? dom('outer', idsOf(this)) : ''; };
  P.remove = function () { dom('remove', idsOf(this)); return this; };
  P.index = function () {
    if (!this.length) return -1;
    var s = dom('rel', [this[0].__id], 'prevAll', null) || [];
    return s.length;
  };

  function load(html) {
    var docId = dom('load', [], html === undefined || html === null ? '' : String(html));
    var rootNode = makeNode(docId);
    function ${'$'}(sel, ctx) {
      if (sel === undefined || sel === null || sel === '') return new Sel([], docId);
      if (typeof sel === 'string') {
        if (/^\s*</.test(sel)) return new Sel(nodes(dom('fragment', [], sel) || []), docId);
        var scope = [docId];
        if (ctx !== undefined && ctx !== null) scope = idsOf(ctx instanceof Sel ? ctx : (ctx.__id !== undefined ? [ctx] : ${'$'}(ctx)));
        return new Sel(nodes(dom('find', scope, sel) || []), docId);
      }
      if (sel instanceof Sel) return sel;
      if (Array.isArray(sel)) return new Sel(sel, docId);
      if (sel.__id !== undefined) return new Sel([sel], docId);
      return new Sel([], docId);
    }
    ${'$'}.root = function () { return new Sel([rootNode], docId); };
    ${'$'}.html = function (x) {
      if (x === undefined) return dom('outer', [docId]);
      var s = x instanceof Sel ? x : ${'$'}(x);
      return s.length ? dom('outer', idsOf(s)) : '';
    };
    ${'$'}.xml = ${'$'}.html;
    ${'$'}.text = function (x) { var s = x === undefined ? ${'$'}.root() : (x instanceof Sel ? x : ${'$'}(x)); return s.text(); };
    ${'$'}.load = load;
    ${'$'}.fn = Sel.prototype;
    return ${'$'};
  }

  var mod = { load: load, version: '0.20.2' };
  mod['default'] = mod;
  return mod;
})();

"""

private const val PART_CRYPTO = """
// Part 4: crypto-js compatible API (the subset used by Nuvio scrapers).
// WordArray, encoders and CipherParams are plain JS; hashing / HMAC / block ciphers go to the JVM
// through __cryptoHash, __cryptoHmac, __cryptoCipher and __cryptoRandom (all work on hex strings).

var __CryptoJS = (function () {
  function WordArray(words, sigBytes) {
    if (words instanceof WordArray) { sigBytes = sigBytes === undefined ? words.sigBytes : sigBytes; words = words.words.slice(0); }
    else if (words && typeof words === 'object' && words.buffer !== undefined && !Array.isArray(words)) {
      var bs = __toBytes(words), w = [];
      for (var i = 0; i < bs.length; i++) w[i >>> 2] |= bs[i] << (24 - (i % 4) * 8);
      words = w; sigBytes = bs.length;
    }
    this.words = words || [];
    this.sigBytes = sigBytes === undefined ? this.words.length * 4 : sigBytes;
  }
  WordArray.create = function (words, sigBytes) { return new WordArray(words, sigBytes); };
  WordArray.random = function (n) { return bytesToWA(__hexToBytes(__cryptoRandom(n))); };
  WordArray.prototype = {
    constructor: WordArray,
    toString: function (encoder) { return (encoder || Hex).stringify(this); },
    clone: function () { return new WordArray(this.words.slice(0), this.sigBytes); },
    clamp: function () {
      this.words[this.sigBytes >>> 2] &= 0xffffffff << (32 - (this.sigBytes % 4) * 8);
      this.words.length = Math.ceil(this.sigBytes / 4);
      return this;
    },
    concat: function (other) {
      this.clamp();
      var tw = this.words, ow = other.words, ts = this.sigBytes, os = other.sigBytes;
      if (ts % 4) {
        for (var i = 0; i < os; i++) {
          var b = (ow[i >>> 2] >>> (24 - (i % 4) * 8)) & 255;
          tw[(ts + i) >>> 2] |= b << (24 - ((ts + i) % 4) * 8);
        }
      } else {
        for (var j = 0; j < os; j += 4) tw[(ts + j) >>> 2] = ow[j >>> 2];
      }
      this.sigBytes += os;
      return this;
    }
  };

  function bytesToWA(bytes) {
    var w = [];
    for (var i = 0; i < bytes.length; i++) w[i >>> 2] |= (bytes[i] & 255) << (24 - (i % 4) * 8);
    return new WordArray(w, bytes.length);
  }
  function waToBytes(wa) {
    var out = [], n = wa.sigBytes;
    for (var i = 0; i < n; i++) out.push((wa.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 255);
    return out;
  }

  var Hex = {
    stringify: function (wa) { return __bytesToHex(waToBytes(wa)); },
    parse: function (s) { return bytesToWA(__hexToBytes(s)); }
  };
  var Latin1 = {
    stringify: function (wa) { return __latin1Decode(waToBytes(wa)); },
    parse: function (s) { return bytesToWA(__latin1Bytes(String(s))); }
  };
  var Utf8 = {
    stringify: function (wa) { return __utf8Decode(waToBytes(wa)); },
    parse: function (s) { return bytesToWA(__utf8Bytes(String(s))); }
  };
  var Base64 = {
    stringify: function (wa) { return __bytesToB64(waToBytes(wa)); },
    parse: function (s) { return bytesToWA(__b64ToBytes(s)); }
  };
  var Base64url = {
    stringify: function (wa, pad) { return __bytesToB64(waToBytes(wa), true, pad === false); },
    parse: function (s) { return bytesToWA(__b64ToBytes(s)); }
  };

  function toWA(x) { return typeof x === 'string' ? Utf8.parse(x) : x; }

  // ---- hashes / HMAC
  var HASHES = { MD5: 'MD5', SHA1: 'SHA-1', SHA224: 'SHA-224', SHA256: 'SHA-256', SHA384: 'SHA-384', SHA512: 'SHA-512' };
  var MACS = { MD5: 'HmacMD5', SHA1: 'HmacSHA1', SHA224: 'HmacSHA224', SHA256: 'HmacSHA256', SHA384: 'HmacSHA384', SHA512: 'HmacSHA512' };
  var api = {};
  Object.keys(HASHES).forEach(function (n) {
    api[n] = function (msg) { return bytesToWA(__hexToBytes(__cryptoHash(HASHES[n], __bytesToHex(waToBytes(toWA(msg)))))); };
    api['Hmac' + n] = function (msg, key) {
      return bytesToWA(__hexToBytes(__cryptoHmac(MACS[n], __bytesToHex(waToBytes(toWA(key))), __bytesToHex(waToBytes(toWA(msg))))));
    };
  });
  function md5Bytes(bytes) { return __hexToBytes(__cryptoHash('MD5', __bytesToHex(bytes))); }

  // ---- cipher params + OpenSSL format
  function CipherParams(o) { for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) this[k] = o[k]; }
  CipherParams.create = function (o) { return new CipherParams(o); };
  CipherParams.prototype.toString = function (fmt) { return (fmt || OpenSSL).stringify(this); };
  var OpenSSL = {
    stringify: function (cp) {
      var wa = cp.salt ? WordArray.create([0x53616c74, 0x65645f5f]).concat(cp.salt).concat(cp.ciphertext) : cp.ciphertext;
      return Base64.stringify(wa);
    },
    parse: function (str) {
      var wa = Base64.parse(str), salt;
      if (wa.words[0] === 0x53616c74 && wa.words[1] === 0x65645f5f) {
        salt = WordArray.create(wa.words.slice(2, 4));
        wa.words.splice(0, 4);
        wa.sigBytes -= 16;
      }
      return new CipherParams({ ciphertext: wa, salt: salt });
    }
  };
  var HexFormat = {
    stringify: function (cp) { return cp.ciphertext.toString(Hex); },
    parse: function (s) { return new CipherParams({ ciphertext: Hex.parse(s) }); }
  };

  // ---- block ciphers
  function evpKdf(password, keyBytesLen, ivBytesLen, saltWA) {
    var pass = waToBytes(toWA(password)), salt = saltWA ? waToBytes(saltWA) : [];
    var d = [], prev = [], need = keyBytesLen + ivBytesLen;
    while (d.length < need) {
      prev = md5Bytes(prev.concat(pass, salt));
      d = d.concat(prev);
    }
    return { key: d.slice(0, keyBytesLen), iv: d.slice(keyBytesLen, need) };
  }

  function process(algo, ivLen, keyLen, encrypt, message, key, cfg) {
    cfg = cfg || {};
    var mode = (cfg.mode && cfg.mode.name) || 'CBC';
    var pad = (cfg.padding && cfg.padding.name) || 'Pkcs7';
    var fmt = cfg.format || OpenSSL;
    var cp = null, keyBytes, ivBytes, salt;

    if (!encrypt) {
      cp = typeof message === 'string' ? fmt.parse(message) : (message && message.ciphertext ? message : new CipherParams({ ciphertext: message }));
    }
    if (typeof key === 'string') {
      salt = encrypt ? (cfg.salt ? toWA(cfg.salt) : WordArray.random(8)) : cp.salt;
      var kd = evpKdf(key, keyLen, ivLen, salt);
      keyBytes = kd.key;
      ivBytes = kd.iv;
    } else {
      keyBytes = waToBytes(key);
      ivBytes = cfg.iv ? waToBytes(toWA(cfg.iv)) : [];
    }
    if (mode !== 'ECB' && ivLen > 0 && ivBytes.length === 0) { for (var z = 0; z < ivLen; z++) ivBytes.push(0); }

    var data = waToBytes(encrypt ? toWA(message) : cp.ciphertext);
    var outHex = __cryptoCipher(algo, encrypt ? 'encrypt' : 'decrypt', mode, pad, __bytesToHex(keyBytes), __bytesToHex(ivBytes), __bytesToHex(data));
    var out = outHex === null ? new WordArray([], 0) : bytesToWA(__hexToBytes(outHex));
    if (!encrypt) return out;
    return new CipherParams({
      ciphertext: out, key: bytesToWA(keyBytes), iv: bytesToWA(ivBytes), salt: salt,
      algorithm: algo, mode: cfg.mode, padding: cfg.padding, blockSize: ivLen / 4, formatter: fmt
    });
  }

  function cipher(algo, ivLen, keyLen) {
    return {
      encrypt: function (message, key, cfg) { return process(algo, ivLen, keyLen, true, message, key, cfg); },
      decrypt: function (ciphertext, key, cfg) { return process(algo, ivLen, keyLen, false, ciphertext, key, cfg); }
    };
  }

  api.AES = cipher('AES', 16, 32);
  api.DES = cipher('DES', 8, 8);
  api.TripleDES = cipher('DESede', 8, 24);

  api.lib = { WordArray: WordArray, CipherParams: CipherParams };
  api.enc = { Hex: Hex, Latin1: Latin1, Utf8: Utf8, Base64: Base64, Base64url: Base64url };
  api.format = { OpenSSL: OpenSSL, Hex: HexFormat };
  api.mode = { CBC: { name: 'CBC' }, ECB: { name: 'ECB' }, CTR: { name: 'CTR' }, CTRGladman: { name: 'CTR' }, CFB: { name: 'CFB' }, OFB: { name: 'OFB' } };
  api.pad = { Pkcs7: { name: 'Pkcs7' }, NoPadding: { name: 'NoPadding' }, ZeroPadding: { name: 'ZeroPadding' } };
  api['default'] = api;
  return api;
})();

"""

private const val PART_MODULES = """
// Part 5: CommonJS require() for the modules the Nuvio app provides to scrapers.

function require(name) {
  name = String(name);
  if (name.indexOf('node:') === 0) name = name.slice(5);
  switch (name) {
    case 'cheerio-without-node-native':
    case 'react-native-cheerio':
    case 'cheerio':
      return __cheerio;
    case 'crypto-js':
      return __CryptoJS;
    case 'axios':
      return __axios;
    case 'url':
      return { URL: globalThis.URL, URLSearchParams: globalThis.URLSearchParams };
    case 'buffer':
      return { Buffer: globalThis.Buffer };
  }
  var e = new Error("Cannot find module '" + name + "'");
  e.code = 'MODULE_NOT_FOUND';
  throw e;
}

"""

internal val NUVIO_QUICKJS_HOST_PRELUDE: String =
    listOf(PART_WEB, PART_CHEERIO, PART_CRYPTO, PART_MODULES).joinToString("\n")
