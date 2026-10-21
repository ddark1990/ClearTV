/*
 * ClearTV content script. Injected at document start into every frame (WebViewCompat.addDocumentStartJavaScript,
 * or evaluateJavascript from onPageStarted where that WebView feature is missing).
 * __CTV_BRIDGE__ and __CTV_TOKEN__ are replaced by the app with the per-launch bridge name and message token.
 * Does: uBO-compatible scriptlets, element hiding (specific + generic, lowly generic on demand), media state
 * reporting (keep screen on) and remote-control media commands.
 */
(function () {
  'use strict';
  if (window.__ctvInjected) return;
  try { Object.defineProperty(window, '__ctvInjected', { value: true }); } catch (e) { window.__ctvInjected = true; }
  var B = window['__CTV_BRIDGE__'];
  if (!B) return;
  var TOKEN = '__CTV_TOKEN__';
  var href = String(location.href);
  if (href.indexOf('http') !== 0) {
    try { if (window.parent !== window && document.referrer) href = document.referrer; } catch (e) {}
  }
  var frameId = Math.random().toString(36).slice(2);

  /* ------------------------------------------------- desktop layout width
   * In desktop mode (our desktop UA) the page should lay out like on a 1280 px wide desktop window and be scaled
   * down to the TV, not at the WebView's device-width (960 CSS px on a 1080p Fire TV, a tablet breakpoint where
   * hero images are blown up to full width). The page's viewport meta is pinned to width=1280; the WebView
   * (useWideViewPort + loadWithOverviewMode) then fits that width to the screen. */
  if (window === window.top && /\(Windows NT /.test(navigator.userAgent)) (function () {
    var W = 'width=1280';
    function fix(m) {
      if (m.nodeName === 'META' && String(m.getAttribute('name')).toLowerCase() === 'viewport'
          && m.getAttribute('content') !== W) m.setAttribute('content', W);
    }
    function added(recs) {
      for (var i = 0; i < recs.length; i++) {
        var r = recs[i];
        if (r.type === 'attributes') { fix(r.target); continue; }
        for (var j = 0; j < r.addedNodes.length; j++) fix(r.addedNodes[j]);
      }
    }
    try {
      var own = document.createElement('meta');
      own.setAttribute('name', 'viewport');
      own.setAttribute('content', W);
      (document.head || document.documentElement || document).appendChild(own);
      var ms = document.getElementsByTagName('meta');
      for (var i = 0; i < ms.length; i++) fix(ms[i]);
      var obs = new MutationObserver(added);
      obs.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
      // after parsing only the head can still gain a viewport tag; stop watching the whole document
      document.addEventListener('DOMContentLoaded', function () {
        obs.disconnect();
        if (document.head) obs.observe(document.head, { childList: true, subtree: true, attributes: true,
          attributeFilter: ['content', 'name'] });
      }, { once: true });
    } catch (e) {}
  })();

  /* ---------------------------------------------------------------- helpers */
  var reEsc = /[.*+?^${}()|[\]\\]/g;
  function toRegex(p, flagsDefault) {
    if (p === undefined || p === null || p === '') return /^/;
    var m = /^\/(.+)\/([gimsu]*)$/.exec(p);
    if (m) { try { return new RegExp(m[1], m[2] || flagsDefault || ''); } catch (e) { return /^\b$/; } }
    return new RegExp(String(p).replace(reEsc, '\\$&'), flagsDefault || '');
  }
  function noop() {}
  var INVALID = {};
  function parseValue(v) {
    switch (v) {
      case 'undefined': return undefined;
      case 'false': return false;
      case 'true': return true;
      case 'null': return null;
      case "''": case '': case 'emptyStr': return '';
      case 'noopFunc': case 'noopCallbackFunc': return function () {};
      case 'trueFunc': return function () { return true; };
      case 'falseFunc': return function () { return false; };
      case 'throwFunc': return function () { throw new Error(''); };
      case '[]': case 'emptyArr': return [];
      case '{}': case 'emptyObj': return {};
      case 'yes': return 'yes';
      case 'no': return 'no';
      case 'noopPromiseResolve': return function () { return Promise.resolve(); };
      case 'noopPromiseReject': return function () { return Promise.reject(); };
    }
    if (/^-?\d+$/.test(v)) { var n = parseInt(v, 10); if (Math.abs(n) <= 0x7FFF) return n; return INVALID; }
    if (/^'.*'$/.test(v)) return v.slice(1, -1);
    return INVALID;
  }
  var magic = String.fromCharCode(Date.now() % 26 + 97) + Math.floor(Math.random() * 982451653 + 982451653).toString(36);
  var errorTrapped = false;
  function trapErrors() {
    if (errorTrapped) return;
    errorTrapped = true;
    var prev = window.onerror;
    window.onerror = function (msg) {
      if (typeof msg === 'string' && msg.indexOf(magic) !== -1) return true;
      if (prev instanceof Function) return prev.apply(this, arguments);
    };
  }
  function wrap(owner, name, handler) {
    var orig = owner[name];
    if (typeof orig !== 'function') return;
    var w;
    if (typeof Proxy === 'function') {
      w = new Proxy(orig, { apply: function (t, self, args) { return handler(t, self, args); } });
    } else {
      w = function () { return handler(orig, this, Array.prototype.slice.call(arguments)); };
    }
    try { owner[name] = w; } catch (e) {}
  }
  function onReady(fn) {
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn, { once: true });
  }
  function matchProps(props, details) {
    // "url:/re/ method:POST" or a bare url pattern
    if (!props) return true;
    var parts = props.split(/\s+/);
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i];
      if (!p) continue;
      var c = p.indexOf(':');
      var k = 'url', v = p;
      if (c > 0 && /^[a-zA-Z]+$/.test(p.slice(0, c)) && p.slice(0, c) !== 'http' && p.slice(0, c) !== 'https') {
        k = p.slice(0, c); v = p.slice(c + 1);
      }
      var val = details[k];
      if (val === undefined) return false;
      if (!toRegex(v).test(String(val))) return false;
    }
    return true;
  }

  /* -------------------------------------------------------------- json prune */
  function pathParts(path) { return path.split('.'); }
  function pruneOne(obj, parts, i) {
    if (obj === null || typeof obj !== 'object') return false;
    var key = parts[i];
    var last = i === parts.length - 1;
    if (key === '[]' || key === '[-]' || key === '{-}') {
      var removed = false;
      if (Array.isArray(obj)) {
        for (var j = obj.length - 1; j >= 0; j--) {
          if (last) { if (key !== '[]') { obj.splice(j, 1); removed = true; } continue; }
          if (key === '[-]') { if (hasPath(obj[j], parts, i + 1)) { obj.splice(j, 1); removed = true; } }
          else if (pruneOne(obj[j], parts, i + 1)) removed = true;
        }
      } else {
        for (var k in obj) {
          if (!Object.prototype.hasOwnProperty.call(obj, k)) continue;
          if (key === '{-}' && hasPath(obj[k], parts, i + 1)) { delete obj[k]; removed = true; }
          else if (pruneOne(obj[k], parts, i + 1)) removed = true;
        }
      }
      return removed;
    }
    if (!Object.prototype.hasOwnProperty.call(obj, key)) return false;
    if (last) { delete obj[key]; return true; }
    return pruneOne(obj[key], parts, i + 1);
  }
  function hasPath(obj, parts, i) {
    if (i >= parts.length) return true;
    if (obj === null || typeof obj !== 'object') return false;
    var key = parts[i];
    if (key === '[]' || key === '[-]' || key === '{-}') {
      for (var k in obj) { if (Object.prototype.hasOwnProperty.call(obj, k) && hasPath(obj[k], parts, i + 1)) return true; }
      return false;
    }
    if (!Object.prototype.hasOwnProperty.call(obj, key)) return false;
    return hasPath(obj[key], parts, i + 1);
  }
  function makePruner(rawPrune, rawNeedle) {
    var prune = (rawPrune || '').split(/ +/).filter(Boolean).map(pathParts);
    var needles = (rawNeedle || '').split(/ +/).filter(Boolean).map(pathParts);
    return function (obj) {
      if (obj === null || typeof obj !== 'object' || prune.length === 0) return obj;
      for (var i = 0; i < needles.length; i++) if (!hasPath(obj, needles[i], 0)) return obj;
      for (var j = 0; j < prune.length; j++) pruneOne(obj, prune[j], 0);
      return obj;
    };
  }
  function namedArgs(args, start) {
    var o = {};
    for (var i = start; i + 1 < args.length; i += 2) o[args[i]] = args[i + 1];
    return o;
  }

  /* --------------------------------------------------------------- scriptlets */
  var S = {};
  S['set-constant'] = function (chain, raw) {
    if (!chain) return;
    var value = parseValue(raw);
    if (value === INVALID) return;
    function trapProp(owner, prop, configurable, h) {
      if (h.init(owner[prop]) === false) return;
      var od = Object.getOwnPropertyDescriptor(owner, prop);
      if (od && od.configurable === false) return;
      try {
        Object.defineProperty(owner, prop, {
          configurable: configurable,
          get: function () { return h.getter(); },
          set: function (a) { h.setter(a); }
        });
      } catch (e) {}
    }
    function trapChain(owner, ch) {
      var pos = ch.indexOf('.');
      if (pos === -1) {
        trapProp(owner, ch, false, {
          init: function () { return true; },
          getter: function () { return value; },
          setter: function () {}
        });
        return;
      }
      var prop = ch.slice(0, pos), rest = ch.slice(pos + 1);
      var v = owner[prop];
      if (v instanceof Object || (typeof v === 'object' && v !== null)) { trapChain(v, rest); return; }
      trapProp(owner, prop, true, {
        v: undefined,
        init: function (x) { this.v = x; return true; },
        getter: function () { return this.v; },
        setter: function (a) { this.v = a; if (a instanceof Object) trapChain(a, rest); }
      });
    }
    trapChain(window, chain);
  };
  function abortOnRead(chain) {
    var abort = function () { throw new ReferenceError(magic); };
    function makeProxy(owner, ch) {
      var pos = ch.indexOf('.');
      if (pos === -1) {
        var d = Object.getOwnPropertyDescriptor(owner, ch);
        if (!d || d.get !== abort) { try { Object.defineProperty(owner, ch, { get: abort, set: function () {} }); } catch (e) {} }
        return;
      }
      var prop = ch.slice(0, pos), rest = ch.slice(pos + 1);
      var v = owner[prop];
      if (v) { makeProxy(v, rest); return; }
      var desc = Object.getOwnPropertyDescriptor(owner, prop);
      if (desc && desc.set !== undefined) return;
      try {
        Object.defineProperty(owner, prop, {
          get: function () { return v; },
          set: function (a) { v = a; if (a instanceof Object) makeProxy(a, rest); }
        });
      } catch (e) {}
    }
    makeProxy(window, chain);
    trapErrors();
  }
  S['abort-on-property-read'] = function (chain) { if (chain) abortOnRead(chain); };
  S['abort-on-property-write'] = function (prop) {
    if (!prop) return;
    var owner = window, ch = prop;
    var pos = ch.lastIndexOf('.');
    if (pos !== -1) {
      var path = ch.slice(0, pos).split('.');
      for (var i = 0; i < path.length; i++) { owner = owner[path[i]]; if (!(owner instanceof Object)) return; }
      ch = ch.slice(pos + 1);
    }
    try { delete owner[ch]; } catch (e) {}
    try {
      Object.defineProperty(owner, ch, { set: function () { throw new ReferenceError(magic); } });
    } catch (e) {}
    trapErrors();
  };
  S['abort-current-script'] = function (target, needle) {
    if (!target) return;
    var re = toRegex(needle);
    var chain = target.split('.');
    var owner = window, prop;
    for (;;) {
      prop = chain.shift();
      if (chain.length === 0) break;
      if (!(prop in owner)) { owner = null; break; }
      owner = owner[prop];
      if (!(owner instanceof Object)) return;
    }
    if (!owner) return;
    var desc = Object.getOwnPropertyDescriptor(owner, prop);
    var value = owner[prop];
    var getter = desc && desc.get, setter = desc && desc.set;
    var thisScript = document.currentScript;
    function validate() {
      var e = document.currentScript;
      if (!(e instanceof HTMLScriptElement) || e === thisScript) return;
      var text = e.src ? '' : (e.textContent || '');
      if (needle && e.src && !re.test(e.src) && !re.test(text)) return;
      if (needle && !e.src && !re.test(text)) return;
      throw new ReferenceError(magic);
    }
    try {
      Object.defineProperty(owner, prop, {
        get: function () { validate(); return getter ? getter.call(owner) : value; },
        set: function (a) { validate(); if (setter) setter.call(owner, a); else value = a; }
      });
    } catch (e) { return; }
    trapErrors();
  };
  function timerDefuser(name) {
    return function (needle, delay) {
      needle = needle || '';
      delay = delay || '';
      var nNot = needle.charAt(0) === '!'; if (nNot) needle = needle.slice(1);
      var dNot = delay.charAt(0) === '!'; if (dNot) delay = delay.slice(1);
      var d = delay !== '' ? parseInt(delay, 10) : NaN;
      if (needle === '' && delay === '') return;
      var re = toRegex(needle);
      wrap(window, name, function (t, self, args) {
        var a = String(args[0]);
        var b = args[1];
        var defuse = true;
        if (needle !== '') defuse = re.test(a) !== nNot;
        if (defuse && !isNaN(d)) defuse = (b === d) !== dNot;
        if (defuse) args[0] = noop;
        return t.apply(self, args);
      });
    };
  }
  S['no-setTimeout-if'] = timerDefuser('setTimeout');
  S['no-setInterval-if'] = timerDefuser('setInterval');
  function timerBooster(name) {
    return function (needle, delay, boost) {
      var re = toRegex(needle === '*' ? '' : needle);
      var d = delay === '*' ? -1 : (delay ? parseInt(delay, 10) : 1000);
      var f = boost !== undefined && boost !== '' ? parseFloat(boost) : 0.05;
      if (isNaN(f) || !isFinite(f)) f = 0.05;
      f = Math.min(Math.max(f, 0.001), 50);
      wrap(window, name, function (t, self, args) {
        if ((d === -1 || args[1] === d) && re.test(String(args[0]))) args[1] = (args[1] || 0) * f;
        return t.apply(self, args);
      });
    };
  }
  S['adjust-setTimeout'] = timerBooster('setTimeout');
  S['adjust-setInterval'] = timerBooster('setInterval');
  S['addEventListener-defuser'] = function (type, pattern) {
    var reT = toRegex(type), reP = toRegex(pattern);
    if (typeof EventTarget === 'undefined') return;
    wrap(EventTarget.prototype, 'addEventListener', function (t, self, args) {
      var h = '';
      try { h = String(args[1]); } catch (e) {}
      if (reT.test(String(args[0])) && reP.test(h)) return;
      return t.apply(self, args);
    });
  };
  function domStay(run, behavior) {
    behavior = behavior || '';
    var timer = 0;
    var go = function () { timer = 0; try { run(); } catch (e) {} };
    if (behavior.indexOf('asap') !== -1) go();
    onReady(function () {
      go();
      if (behavior.indexOf('stay') !== -1 || behavior.indexOf('complete') === -1) {
        new MutationObserver(function () { if (!timer) timer = setTimeout(go, 50); })
          .observe(document.documentElement, { childList: true, subtree: true, attributes: true });
      }
    });
    if (behavior.indexOf('complete') !== -1) window.addEventListener('load', go, { once: true });
  }
  S['remove-attr'] = function (attrs, selector, behavior) {
    if (!attrs) return;
    var list = attrs.split(/\s*\|\s*/);
    var sel = selector || list.map(function (a) { return '[' + a + ']'; }).join(',');
    domStay(function () {
      var els = document.querySelectorAll(sel);
      for (var i = 0; i < els.length; i++) for (var j = 0; j < list.length; j++) els[i].removeAttribute(list[j]);
    }, behavior);
  };
  S['remove-class'] = function (classes, selector, behavior) {
    if (!classes) return;
    var list = classes.split(/\s*\|\s*/);
    var sel = selector || list.map(function (c) { return '.' + c; }).join(',');
    domStay(function () {
      var els = document.querySelectorAll(sel);
      for (var i = 0; i < els.length; i++) for (var j = 0; j < list.length; j++) els[i].classList.remove(list[j]);
    }, behavior);
  };
  S['set-attr'] = function (selector, attr, value) {
    if (!selector || !attr) return;
    value = value || '';
    if (!/^(?:true|false|-?\d+|\[.+\])?$/.test(value)) return;
    domStay(function () {
      var els = document.querySelectorAll(selector);
      for (var i = 0; i < els.length; i++) {
        var v = value;
        if (/^\[.+\]$/.test(value)) v = els[i].getAttribute(value.slice(1, -1)) || '';
        els[i].setAttribute(attr, v);
      }
    }, 'stay');
  };
  S['no-window-open-if'] = function (pattern) {
    pattern = pattern || '';
    var not = pattern.charAt(0) === '!';
    if (not) pattern = pattern.slice(1);
    var re = toRegex(pattern);
    wrap(window, 'open', function (t, self, args) {
      var url = String(args[0] || '');
      if (re.test(url) !== not) return null;
      return t.apply(self, args);
    });
  };
  function fakeBody(kind) {
    if (kind === 'emptyObj') return '{}';
    if (kind === 'emptyArr') return '[]';
    if (kind === 'true') return 'true';
    return '';
  }
  S['prevent-fetch'] = function (props, body) {
    if (typeof fetch !== 'function') return;
    if (props === undefined) return;
    wrap(window, 'fetch', function (t, self, args) {
      var d = { url: '', method: 'GET' };
      try {
        var r = args[0];
        if (r instanceof Request) { d.url = r.url; d.method = r.method; }
        else d.url = String(r);
        if (args[1] && args[1].method) d.method = String(args[1].method).toUpperCase();
      } catch (e) {}
      if (props === '' || props === '*' || matchProps(props, d)) {
        return Promise.resolve(new Response(fakeBody(body), { status: 200, statusText: 'OK' }));
      }
      return t.apply(self, args);
    });
  };
  S['prevent-xhr'] = function (props) {
    if (props === undefined || typeof XMLHttpRequest === 'undefined') return;
    var P = XMLHttpRequest.prototype;
    var open = P.open, send = P.send;
    P.open = function (method, url) {
      this.__ctv = { method: String(method).toUpperCase(), url: String(url) };
      return open.apply(this, arguments);
    };
    P.send = function () {
      var d = this.__ctv;
      if (!d || !(props === '' || props === '*' || matchProps(props, d))) return send.apply(this, arguments);
      var xhr = this;
      var def = function (k, v) { try { Object.defineProperty(xhr, k, { value: v, configurable: true }); } catch (e) {} };
      def('readyState', 4); def('status', 200); def('statusText', 'OK');
      def('responseText', ''); def('response', xhr.responseType === 'json' ? null : ''); def('responseURL', d.url);
      setTimeout(function () {
        ['readystatechange', 'load', 'loadend'].forEach(function (ev) {
          try { xhr.dispatchEvent(new Event(ev)); } catch (e) {}
        });
      }, 1);
    };
  };
  S['json-prune'] = function (rawPrune, rawNeedle) {
    var pr = makePruner(rawPrune, rawNeedle);
    var parse = JSON.parse;
    JSON.parse = function () { return pr(parse.apply(this, arguments)); };
    if (typeof Response !== 'undefined') {
      wrap(Response.prototype, 'json', function (t, self, args) { return t.apply(self, args).then(pr); });
    }
  };
  S['json-prune-fetch-response'] = function (rawPrune, rawNeedle) {
    if (typeof fetch !== 'function') return;
    var named = namedArgs(arguments, 2);
    var props = named.propsToMatch || '';
    var pr = makePruner(rawPrune, rawNeedle);
    wrap(window, 'fetch', function (t, self, args) {
      var d = { url: '', method: 'GET' };
      try {
        var r = args[0];
        if (r instanceof Request) { d.url = r.url; d.method = r.method; } else d.url = String(r);
        if (args[1] && args[1].method) d.method = String(args[1].method).toUpperCase();
      } catch (e) {}
      var p = t.apply(self, args);
      if (props && !matchProps(props, d)) return p;
      return p.then(function (resp) {
        return resp.clone().text().then(function (text) {
          var obj;
          try { obj = JSON.parse(text); } catch (e) { return resp; }
          var out = JSON.stringify(pr(obj));
          var r2 = new Response(out, { status: resp.status, statusText: resp.statusText, headers: resp.headers });
          try { Object.defineProperty(r2, 'url', { value: resp.url }); } catch (e) {}
          try { Object.defineProperty(r2, 'type', { value: resp.type }); } catch (e) {}
          return r2;
        }, function () { return resp; });
      });
    });
  };
  S['json-prune-xhr-response'] = function (rawPrune, rawNeedle) {
    if (typeof XMLHttpRequest === 'undefined') return;
    var named = namedArgs(arguments, 2);
    var props = named.propsToMatch || '';
    var pr = makePruner(rawPrune, rawNeedle);
    var P = XMLHttpRequest.prototype;
    var open = P.open;
    P.open = function (method, url) {
      this.__ctvj = { method: String(method).toUpperCase(), url: String(url) };
      return open.apply(this, arguments);
    };
    var dText = Object.getOwnPropertyDescriptor(P, 'responseText');
    var dResp = Object.getOwnPropertyDescriptor(P, 'response');
    if (!dText || !dResp || !dText.get || !dResp.get) return;
    function pruned(xhr) {
      if (xhr.__ctvjOut !== undefined) return xhr.__ctvjOut;
      var raw = dText.get.call(xhr);
      var out = raw;
      try { out = JSON.stringify(pr(JSON.parse(raw))); } catch (e) {}
      if (xhr.readyState === 4) xhr.__ctvjOut = out;
      return out;
    }
    function applies(xhr) {
      var d = xhr.__ctvj;
      return d && xhr.readyState === 4 && (!props || matchProps(props, d));
    }
    Object.defineProperty(P, 'responseText', {
      configurable: true, enumerable: true,
      get: function () { return applies(this) ? pruned(this) : dText.get.call(this); }
    });
    Object.defineProperty(P, 'response', {
      configurable: true, enumerable: true,
      get: function () {
        var rt = this.responseType;
        if (!applies(this) || (rt !== '' && rt !== 'text' && rt !== 'json')) return dResp.get.call(this);
        if (rt === 'json') { var o = dResp.get.call(this); return pr(o); }
        return pruned(this);
      }
    });
  };
  function storageSetter(store) {
    return function (key, value) {
      if (!key) return;
      var s;
      try { s = window[store]; } catch (e) { return; }
      if (!s) return;
      var map = { 'undefined': 'undefined', 'false': 'false', 'true': 'true', 'null': 'null', 'emptyArr': '[]',
        'emptyObj': '{}', "''": '', '': '', 'yes': 'yes', 'no': 'no', 'on': 'on', 'off': 'off', 'accept': 'accept',
        'reject': 'reject', 'accepted': 'accepted', 'rejected': 'rejected' };
      try {
        if (value === '$remove$') { s.removeItem(key); return; }
        var v;
        if (Object.prototype.hasOwnProperty.call(map, value)) v = map[value];
        else if (/^\d+$/.test(value) && parseInt(value, 10) <= 0x7FFF) v = value;
        else return;
        s.setItem(key, v);
      } catch (e) {}
    };
  }
  S['set-local-storage-item'] = storageSetter('localStorage');
  S['set-session-storage-item'] = storageSetter('sessionStorage');

  // --- cookie notices (uBO "Cookie Notices" list): answer the banner instead of only hiding it
  function trustedValue(v) {
    if (v === '$now$') return String(Date.now());
    if (v === '$currentDate$') return new Date().toUTCString();
    if (v === '$currentISODate$') return new Date().toISOString();
    return v === undefined ? '' : v;
  }
  S['trusted-set-cookie'] = function (name, value, offset, path) {
    if (!name) return;
    var c = encodeURIComponent(name) + '=' + encodeURIComponent(trustedValue(value));
    var sec = parseInt(offset, 10);
    if (sec > 0) c += '; expires=' + new Date(Date.now() + sec * 1000).toUTCString();
    if (path !== 'none') c += '; path=/';
    try { document.cookie = c; } catch (e) {}
  };
  function trustedStorage(store) {
    return function (key, value) {
      if (!key) return;
      try {
        var s = window[store];
        if (value === '$remove$') s.removeItem(key); else s.setItem(key, trustedValue(value));
      } catch (e) {}
    };
  }
  S['trusted-set-local-storage-item'] = trustedStorage('localStorage');
  S['trusted-set-session-storage-item'] = trustedStorage('sessionStorage');
  S['trusted-click-element'] = function (selectors, extraMatch, delay) {
    if (!selectors) return;
    if (extraMatch) {
      // only "cookie:name" / "localStorage:key" conditions are supported; anything else is ignored
      var ok = extraMatch.split(/\s*,\s*/).every(function (m) {
        var i = m.indexOf(':');
        if (i < 0) return true;
        var kind = m.slice(0, i), what = m.slice(i + 1);
        if (kind === 'cookie') return document.cookie.split(/;\s*/).some(function (c) { return toRegex(what).test(c.split('=')[0]); });
        if (kind === 'localStorage') { try { return localStorage.getItem(what) !== null; } catch (e) { return false; } }
        return true;
      });
      if (!ok) return;
    }
    var list = selectors.split(/\s*,\s+/).filter(function (x) { return x && x.indexOf('>>>') < 0; });
    var wait = Math.min(Math.max(parseInt(delay, 10) || 1, 1), 10000);
    var deadline = Date.now() + 10000;
    var obs = null, timer = 0;
    function step() {
      timer = 0;
      while (list.length) {
        var el = null;
        try { el = document.querySelector(list[0]); } catch (e) { list.shift(); continue; }
        if (!el) break;
        list.shift();
        try { el.click(); } catch (e) {}
        if (list.length) { timer = setTimeout(step, wait); return; }
      }
      if (!list.length || Date.now() > deadline) { if (obs) obs.disconnect(); obs = null; return; }
      if (!obs) {
        obs = new MutationObserver(function () { if (!timer) timer = setTimeout(step, 100); });
        obs.observe(document.documentElement, { childList: true, subtree: true });
        setTimeout(function () { if (obs) { obs.disconnect(); obs = null; } }, 10000);
      }
    }
    onReady(function () { setTimeout(step, wait); });
  };
  S['set-cookie'] = function (name, value, path) {
    if (!name) return;
    var ok = /^(?:true|false|yes|no|y|n|on|off|ok|accept|reject|accepted|rejected|allow|deny|necessary|hide|\d{1,5}|)$/i;
    value = value || '';
    if (!ok.test(value)) return;
    var p = path === 'none' ? '' : '; path=/';
    try { document.cookie = encodeURIComponent(name) + '=' + encodeURIComponent(value) + p; } catch (e) {}
  };
  S['remove-node-text'] = function (nodeName, condition) {
    if (!nodeName) return;
    var reN = toRegex(nodeName.toLowerCase() === nodeName ? '/^' + nodeName.replace(reEsc, '\\$&') + '$/' : nodeName, 'i');
    var reC = toRegex(condition);
    function check(node) {
      if (!reN.test(node.nodeName)) return;
      var t = node.textContent || '';
      if (t && reC.test(t)) {
        if (node.nodeType === 3) node.textContent = '';
        else if (node.parentNode) { node.textContent = ''; node.parentNode.removeChild(node); }
      }
    }
    function scan(root) {
      var w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
      var list = [];
      while (w.nextNode()) list.push(w.currentNode);
      list.forEach(check);
    }
    var mo = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) check(added[j]);
      }
    });
    var start = function () {
      if (!document.documentElement) return;
      mo.observe(document.documentElement, { childList: true, subtree: true });
      scan(document.documentElement);
    };
    if (document.documentElement) start(); else document.addEventListener('readystatechange', start, { once: true });
  };
  S['noeval-if'] = function (needle) {
    var re = toRegex(needle);
    wrap(window, 'eval', function (t, self, args) {
      if (re.test(String(args[0]))) return;
      return t.apply(self, args);
    });
  };
  S['window.name-defuser'] = function () {
    if (window === window.top) window.name = '';
  };
  S['disable-newtab-links'] = function () {
    document.addEventListener('click', function (ev) {
      var e = ev.target;
      while (e && e.localName !== 'a') e = e.parentElement;
      if (e && e.getAttribute('target') === '_blank') e.setAttribute('target', '_self');
    }, true);
  };

  /* ---------------------------------------------------------------- cosmetics */
  var sheet = null, styleEl = null, useAdopted = false;
  function ensureSheet() {
    if (sheet) {
      if (useAdopted && document.adoptedStyleSheets.indexOf(sheet) === -1) {
        try { document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]); } catch (e) {}
      } else if (styleEl && !styleEl.isConnected && document.documentElement) {
        var rules = [];
        try { for (var i = 0; i < sheet.cssRules.length; i++) rules.push(sheet.cssRules[i].cssText); } catch (e) {}
        document.documentElement.appendChild(styleEl);
        sheet = styleEl.sheet;
        for (var k = 0; k < rules.length; k++) { try { sheet.insertRule(rules[k], sheet.cssRules.length); } catch (e) {} }
      }
      return sheet;
    }
    try {
      if ('adoptedStyleSheets' in document && typeof CSSStyleSheet === 'function') {
        var s = new CSSStyleSheet();
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([s]);
        sheet = s; useAdopted = true;
        return sheet;
      }
    } catch (e) {}
    if (!document.documentElement) return null;
    styleEl = document.createElement('style');
    styleEl.setAttribute('data-ctv', '');
    document.documentElement.appendChild(styleEl);
    sheet = styleEl.sheet;
    return sheet;
  }
  var HIDE = '{display:none!important}';
  function insertRule(s, rule) {
    try { s.insertRule(rule, s.cssRules.length); return true; } catch (e) { return false; }
  }
  function addSelectors(sels) {
    if (!sels || !sels.length) return;
    var s = ensureSheet();
    if (!s) { onReady(function () { addSelectors(sels); }); return; }
    for (var i = 0; i < sels.length; i += 40) {
      var chunk = sels.slice(i, i + 40);
      if (!insertRule(s, chunk.join(',') + HIDE)) {
        for (var j = 0; j < chunk.length; j++) insertRule(s, chunk[j] + HIDE);
      }
    }
  }
  function addStyles(styles) {
    if (!styles || !styles.length) return;
    var s = ensureSheet();
    if (!s) { onReady(function () { addStyles(styles); }); return; }
    for (var i = 0; i < styles.length; i++) insertRule(s, styles[i][0] + '{' + styles[i][1] + '}');
  }
  var seenTokens = Object.create(null);
  var pendingNodes = [];
  var genericTimer = 0;
  function collect(el, out) {
    if (!el || el.nodeType !== 1) return;
    var id = el.id;
    if (id && typeof id === 'string' && !seenTokens['#' + id]) { seenTokens['#' + id] = 1; out.push('#' + id); }
    var cl = el.classList;
    if (cl) for (var i = 0; i < cl.length; i++) {
      var t = '.' + cl[i];
      if (!seenTokens[t]) { seenTokens[t] = 1; out.push(t); }
    }
  }
  function flushGeneric() {
    genericTimer = 0;
    var out = [];
    var nodes = pendingNodes;
    pendingNodes = [];
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.nodeType !== 1) continue;
      collect(n, out);
      if (n.firstElementChild) {
        var all = n.querySelectorAll('[id],[class]');
        for (var j = 0; j < all.length; j++) collect(all[j], out);
      }
    }
    if (out.length === 0) return;
    var res;
    try { res = JSON.parse(B.generic(href, JSON.stringify(out))); } catch (e) { return; }
    addSelectors(res);
  }
  function startGeneric() {
    pendingNodes.push(document.documentElement);
    flushGeneric();
    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'attributes') pendingNodes.push(m.target);
        else for (var j = 0; j < m.addedNodes.length; j++) pendingNodes.push(m.addedNodes[j]);
      }
      if (pendingNodes.length && !genericTimer) genericTimer = setTimeout(flushGeneric, 100);
      if (useAdopted || styleEl) ensureSheet();
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['id', 'class'] });
  }

  /* -------------------------------------------------------------------- media */
  function mediaPlaying() {
    var m = document.querySelectorAll('video,audio');
    for (var i = 0; i < m.length; i++) if (!m[i].paused && !m[i].ended && m[i].readyState > 2) return true;
    return false;
  }
  var lastState = null;
  function reportMedia() {
    var p = mediaPlaying();
    if (p === lastState) return;
    lastState = p;
    try { B.media(frameId, p); } catch (e) {}
  }
  ['playing', 'play', 'pause', 'ended', 'emptied', 'waiting'].forEach(function (ev) {
    document.addEventListener(ev, function () { setTimeout(reportMedia, 0); }, true);
  });
  window.addEventListener('pagehide', function () { try { B.media(frameId, false); } catch (e) {} });
  function mainVideo() {
    var vids = document.querySelectorAll('video');
    var best = null, area = 0;
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      var a = r.width * r.height;
      if (a > area) { area = a; best = vids[i]; }
    }
    return best;
  }
  var pausedByApp = [];
  function mediaCommand(cmd) {
    var v = mainVideo();
    if (cmd === 'pauseAll') {
      // remember what was playing, so returning to the service from home resumes it
      var all = document.querySelectorAll('video,audio');
      for (var i = 0; i < all.length; i++) {
        try { if (!all[i].paused) { if (pausedByApp.indexOf(all[i]) < 0) pausedByApp.push(all[i]); all[i].pause(); } } catch (e) {}
      }
    } else if (cmd === 'resumeAll') {
      for (var j = 0; j < pausedByApp.length; j++) {
        try { if (pausedByApp[j].isConnected) { var p = pausedByApp[j].play(); if (p && p.catch) p.catch(noop); } } catch (e) {}
      }
      pausedByApp = [];
    } else if (v) {
      if (cmd === 'toggle') { if (v.paused) { var pr = v.play(); if (pr && pr.catch) pr.catch(noop); } else v.pause(); }
      else if (cmd === 'back') v.currentTime = Math.max(0, v.currentTime - 10);
      else if (cmd === 'fwd') { if (isFinite(v.duration)) v.currentTime = Math.min(v.duration, v.currentTime + 10); }
    }
    var frames = document.querySelectorAll('iframe');
    for (var k = 0; k < frames.length; k++) {
      try { frames[k].contentWindow.postMessage({ __ctv: TOKEN, cmd: cmd }, '*'); } catch (e) {}
    }
  }
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (d && d.__ctv === TOKEN && typeof d.cmd === 'string') mediaCommand(d.cmd);
  });
  try {
    Object.defineProperty(window, '__ctvMedia_' + TOKEN, { value: mediaCommand });
    Object.defineProperty(window, '__ctvHasMedia_' + TOKEN, {
      value: function () { return !!document.querySelector('video') || document.querySelectorAll('iframe').length > 0; }
    });
  } catch (e) {}

  /* ------------------------------------------------------- focus navigation
   * Default remote mode. The app forwards arrow keys as nav('up'|'down'|'left'|'right') and OK as nav('ok').
   * Arrows move a highlight ring to the nearest link, button or field in that direction (only elements near the
   * window are measured, so a press costs one layout read); with nothing in that direction the page or the
   * carousel under the ring scrolls. OK answers "tap:x,y" with the target's centre in CSS pixels, and the app
   * sends a real touch there, so pages see an ordinary trusted tap. Top frame only. */
  if (window === window.top) (function () {
    var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,video,[role=button],[role=link],' +
      '[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=switch],[tabindex]:not([tabindex="-1"]),' +
      '[contenteditable=""],[contenteditable=true],[onclick]';
    var cur = null, ring = null, raf = 0;
    function ensureRing() {
      if (ring && ring.isConnected) return ring;
      ring = document.createElement('div');
      ring.setAttribute('aria-hidden', 'true');
      ring.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;pointer-events:none;z-index:2147483647;' +
        'box-sizing:border-box;border:4px solid #4FC3F7;border-radius:8px;display:none;' +
        'box-shadow:0 0 0 3px rgba(0,0,0,.85),inset 0 0 0 2px rgba(0,0,0,.6),0 0 18px 4px rgba(79,195,247,.55);';
      (document.body || document.documentElement).appendChild(ring);
      return ring;
    }
    function place() {
      raf = 0;
      if (!cur || !cur.isConnected) { if (ring) ring.style.display = 'none'; return; }
      var r = cur.getBoundingClientRect(), g = ensureRing(), pad = 4;
      if (r.width === 0 && r.height === 0) { g.style.display = 'none'; return; }
      g.style.transform = 'translate(' + (r.left - pad) + 'px,' + (r.top - pad) + 'px)';
      g.style.width = (r.width + 2 * pad) + 'px';
      g.style.height = (r.height + 2 * pad) + 'px';
      g.style.display = 'block';
    }
    function schedule() { if (!raf && cur) raf = requestAnimationFrame(place); }
    window.addEventListener('scroll', schedule, { capture: true, passive: true });
    window.addEventListener('resize', schedule, { passive: true });
    function visible(el, r) {
      if (r.width < 4 || r.height < 4) return false;
      var cs = getComputedStyle(el);
      return cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0' && !el.disabled;
    }
    function candidates() {
      var h = window.innerHeight, w = window.innerWidth, out = [];
      var list = document.querySelectorAll(SEL);
      for (var i = 0; i < list.length; i++) {
        var el = list[i];
        var r = el.getBoundingClientRect();
        if (r.bottom < -h || r.top > 2 * h || r.right < -w || r.left > 2 * w) continue;
        if (!visible(el, r)) continue;
        // a link wrapping a single button (or the reverse) is one target: keep the outer one
        var p = el.parentElement, nested = false;
        for (var k = 0; p && k < 3; k++, p = p.parentElement) {
          if (p.matches && p.matches(SEL)) {
            var q = p.getBoundingClientRect();
            if (Math.abs(q.width - r.width) < 8 && Math.abs(q.height - r.height) < 8) { nested = true; break; }
          }
        }
        if (!nested) out.push({ el: el, r: r });
      }
      return out;
    }
    function inView(r) {
      return r.bottom > 0 && r.top < window.innerHeight && r.right > 0 && r.left < window.innerWidth;
    }
    function score(dir, a, b) {
      var acx = (a.left + a.right) / 2, acy = (a.top + a.bottom) / 2, bcx = (b.left + b.right) / 2, bcy = (b.top + b.bottom) / 2;
      var main, side;
      if (dir === 'down') { main = b.top - a.bottom; if (bcy <= acy + 1) return -1; side = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right)); }
      else if (dir === 'up') { main = a.top - b.bottom; if (bcy >= acy - 1) return -1; side = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right)); }
      else if (dir === 'right') { main = b.left - a.right; if (bcx <= acx + 1) return -1; side = Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom)); }
      else { main = a.left - b.right; if (bcx >= acx - 1) return -1; side = Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom)); }
      if (main < -Math.min(a.bottom - a.top, a.right - a.left) / 2) main = 0; // overlapping in the move axis
      // moving up or down, a carousel card cut off at the screen edge loses to a whole one in the same row
      var cut = (dir === 'down' || dir === 'up') && (b.left < -4 || b.right > window.innerWidth + 4) ? 400 : 0;
      return Math.max(0, main) + side * 3 + (side > 0 ? 40 : 0) + cut;
    }
    function focusEl(el) {
      cur = el;
      var r = el.getBoundingClientRect();
      if (!(r.top >= 0 && r.bottom <= window.innerHeight && r.left >= 0 && r.right <= window.innerWidth)) {
        try { el.scrollIntoView({ block: r.height > window.innerHeight * 0.8 ? 'start' : 'center', inline: 'nearest' }); }
        catch (e) { el.scrollIntoView(false); }
      }
      try { if (!/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) && !el.isContentEditable) el.focus({ preventScroll: true }); } catch (e) {}
      place();
    }
    function scrollerOf(el, horizontal) {
      for (var p = el; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
        var cs = getComputedStyle(p);
        if (horizontal && /(auto|scroll)/.test(cs.overflowX) && p.scrollWidth > p.clientWidth + 4) return p;
        if (!horizontal && /(auto|scroll)/.test(cs.overflowY) && p.scrollHeight > p.clientHeight + 4) return p;
      }
      return null;
    }
    function nav(cmd) {
      if (cmd === 'ok') {
        if (!cur || !cur.isConnected) return 'none';
        var r = cur.getBoundingClientRect();
        if (!inView(r)) { focusEl(cur); r = cur.getBoundingClientRect(); }
        var x = Math.max(1, Math.min(window.innerWidth - 2, (Math.max(r.left, 0) + Math.min(r.right, window.innerWidth)) / 2));
        var y = Math.max(1, Math.min(window.innerHeight - 2, (Math.max(r.top, 0) + Math.min(r.bottom, window.innerHeight)) / 2));
        var input = /^(INPUT|TEXTAREA)$/.test(cur.tagName) || cur.isContentEditable;
        return (input ? 'input:' : 'tap:') + Math.round(x) + ',' + Math.round(y) + ',' + window.innerWidth;
      }
      if (cmd === 'hide') { cur = null; place(); return 'ok'; }
      var c = candidates();
      var from = cur && cur.isConnected ? cur.getBoundingClientRect() : null;
      if (!from || !inView(from)) {
        // nothing focused on screen yet: start with the first target in view, top-left first
        var best = null, bs = 1e9;
        for (var i = 0; i < c.length; i++) {
          if (!inView(c[i].r)) continue;
          var s0 = Math.max(0, c[i].r.top) * 2 + Math.max(0, c[i].r.left);
          if (c[i].r.left < -4 || c[i].r.right > window.innerWidth + 4) s0 += 1e6; // whole targets first
          if (s0 < bs) { bs = s0; best = c[i].el; }
        }
        if (best) { focusEl(best); return 'moved'; }
      } else {
        var pick = null, ps = 1e9;
        for (var j = 0; j < c.length; j++) {
          if (c[j].el === cur) continue;
          var s = score(cmd, from, c[j].r);
          if (s >= 0 && s < ps) { ps = s; pick = c[j].el; }
        }
        // targets more than a screen away are reached by scrolling first, so content in between is seen
        if (pick && ps < window.innerHeight * 0.9) { focusEl(pick); return 'moved'; }
      }
      var horizontal = cmd === 'left' || cmd === 'right';
      var sign = cmd === 'up' || cmd === 'left' ? -1 : 1;
      var box = cur && scrollerOf(cur, horizontal);
      if (box) {
        var before = horizontal ? box.scrollLeft : box.scrollTop;
        if (horizontal) box.scrollLeft += sign * box.clientWidth * 0.8; else box.scrollTop += sign * box.clientHeight * 0.6;
        if ((horizontal ? box.scrollLeft : box.scrollTop) !== before) return 'scrolled';
      }
      if (horizontal) return 'none';
      var y0 = window.scrollY;
      window.scrollBy(0, sign * window.innerHeight * 0.6);
      if (window.scrollY !== y0) { if (cur && !inView(cur.getBoundingClientRect())) cur = null; place(); return 'scrolled'; }
      return sign < 0 ? 'edge-top' : 'none';
    }
    try { Object.defineProperty(window, '__ctvNav_' + TOKEN, { value: nav }); } catch (e) {}
  })();

  /* --------------------------------------------------------------------- main */
  var cfg;
  try { cfg = JSON.parse(B.snapshot(href)); } catch (e) { return; }
  if (!cfg || !cfg.on) return;
  var sl = cfg.sl || [];
  for (var i = 0; i < sl.length; i++) {
    var fn = S[sl[i][0]];
    if (!fn) continue;
    try { fn.apply(null, sl[i].slice(1)); } catch (e) {}
  }
  var start = function () {
    addSelectors(cfg.hide);
    addStyles(cfg.styles);
    if (cfg.lg) onReady(startGeneric);
  };
  if (document.documentElement) start();
  else document.addEventListener('readystatechange', start, { once: true });

  /* ------------------------------------------------------ scroll unlock
   * Consent dialogs often set overflow:hidden on html/body. When the dialog was hidden by a filter the page stays
   * locked. Shortly after load, if scrolling is locked although the document is taller than the window and no
   * visible overlay covers a quarter of the window, scrolling is restored. Top frame only, two cheap checks. */
  if (window === window.top) {
    var unlock = function () {
      var de = document.documentElement, body = document.body;
      if (!de || !body) return;
      var cs = getComputedStyle(de), bs = getComputedStyle(body);
      var locked = cs.overflowY === 'hidden' || bs.overflowY === 'hidden' || bs.position === 'fixed';
      if (!locked || Math.max(de.scrollHeight, body.scrollHeight) <= window.innerHeight + 20) return;
      var area = window.innerWidth * window.innerHeight;
      var els = body.querySelectorAll('body > *, body > * > *');
      for (var i = 0; i < els.length && i < 400; i++) {
        var st = getComputedStyle(els[i]);
        if ((st.position !== 'fixed' && st.position !== 'sticky') || st.display === 'none'
            || st.visibility === 'hidden' || st.opacity === '0') continue;
        var r = els[i].getBoundingClientRect();
        if (r.width * r.height > area / 4) return; // a real overlay is showing
      }
      var css = 'overflow-y:auto!important;';
      if (cs.overflowY === 'hidden') de.style.cssText += ';' + css;
      if (bs.overflowY === 'hidden') body.style.cssText += ';' + css;
      if (bs.position === 'fixed') body.style.cssText += ';position:static!important;';
    };
    window.addEventListener('load', function () {
      setTimeout(unlock, 1500);
      setTimeout(unlock, 4000);
    }, { once: true });
  }
})();
