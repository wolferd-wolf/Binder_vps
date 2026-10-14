/* preload-shim.js - Android WebView preload companion (plain script, runs before module bundle).
 * Re-installs a minimal window.hermesDesktop synchronously so any script that
 * touches it before the TS module evaluates still sees a bridge. The full
 * implementation lives in /src/electron-shim.ts and replaces these stubs. */
(function () {
  try {
    if (window.__HERMES_ANDROID_SHIM__ && window.hermesDesktop) return;
    var GATEWAY_KEY = 'hermes.gateway.url';
    var DEFAULT_GW = 'http://127.0.0.1:9119';
    function gw() {
      try {
        if (typeof window.__HERMES_GATEWAY_URL__ === 'string' && window.__HERMES_GATEWAY_URL__.trim())
          return window.__HERMES_GATEWAY_URL__.replace(/\/$/, '');
      } catch (e) {}
      try {
        if (window.HermesAndroid && window.HermesAndroid.getGatewayUrl) {
          var n = String(window.HermesAndroid.getGatewayUrl() || '').trim();
          if (n) return n.replace(/\/$/, '');
        }
      } catch (e) {}
      try {
        var s = localStorage.getItem(GATEWAY_KEY);
        if (s && s.trim()) return s.trim().replace(/\/$/, '');
      } catch (e) {}
      return DEFAULT_GW;
    }
    function wsFor(profile) {
      var g = gw();
      var ws = g.indexOf('https://') === 0 ? 'wss://' + g.slice(8) : g.replace(/^http:\/\//, 'ws://');
      var p = (profile || '').trim ? (profile || '').trim() : '';
      return { ok: true, wsUrl: ws + (p ? '?profile=' + encodeURIComponent(p) : '') };
    }
    function conn(profile) {
      var g = gw();
      var c = { baseUrl: g, isFullscreen: false, mode: 'local', source: 'local',
        token: '', wsUrl: wsFor(profile).wsUrl, logs: [], connectionId: 'local',
        windowButtonPosition: null, nativeOverlayWidth: 0 };
      if (profile) { c.profile = profile; c.sharedPrimary = true; }
      return c;
    }
    var noop = function () {};
    var stub = {
      getConnection: function (p) { return Promise.resolve(conn(p || undefined)); },
      getConnectionFor: function (o) { return Promise.resolve(conn((o && o.profile) || undefined)); },
      getGatewayWsUrl: function (p) { return Promise.resolve(wsFor(p || undefined)); },
      getGatewayWsUrlFor: function (o) { return Promise.resolve(wsFor((o && o.profile) || undefined)); },
      revalidateConnection: function () { return Promise.resolve({ ok: true, rebuilt: false }); },
      touchBackend: function () { return Promise.resolve({ ok: true }); },
      getPoolLimits: function () { return Promise.resolve({ maxBackends: 1, idleMs: 0 }); },
      setPoolLimits: function (l) { return Promise.resolve({ ok: true, limits: l || {} }); },
      api: function (r) {
        var g = gw(); var path = (r && r.path) || '/';
        if (path.charAt(0) !== '/') path = '/' + path;
        return fetch(g + path, { method: (r && r.method) || 'GET',
          headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
          body: r && r.body !== undefined ? JSON.stringify(r.body) : undefined })
          .then(function (res) {
            if (!res.ok) throw new Error('Hermes gateway ' + res.status);
            return res.json();
          });
      },
      notify: function () { return Promise.resolve(true); },
      claimAmbientCue: function () { return Promise.resolve(true); },
      onBrowserPopoutClosed: function () { return noop; },
      onPreviewFileChanged: function () { return noop; },
      watchPreviewFile: function (u) { return Promise.resolve({ ok: false, error: 'android-no-watch', url: u }); },
      stopPreviewFileWatch: function () { return Promise.resolve(false); },
      normalizePreviewTarget: function () { return Promise.resolve(null); },
      windowControls: { custom: false, minimize: noop, toggleMaximize: noop, close: noop },
      getConnectionConfig: function () { return Promise.resolve({ mode: 'local', remoteUrl: gw() }); },
      testConnectionConfig: function () { return Promise.resolve({ ok: true }); },
      connections: { list: function () { return Promise.resolve({ primaryId: 'local', connections: [] }); },
        setPrimary: function () { return Promise.resolve({ ok: true }); },
        test: function () { return Promise.resolve({ ok: true }); } },
      profile: { getDefault: function () { return Promise.resolve(null); },
        setDefault: function (r) { return Promise.resolve(r); },
        onDefaultChanged: function () { return noop; },
        get: function () { return Promise.resolve({ profile: null }); },
        remember: function (n) { return Promise.resolve({ profile: n }); },
        set: function (n) { return Promise.resolve({ profile: n }); } },
      glassSupported: false, translucencySupported: false, localModelsEnabled: false,
      assumeFreshInstall: false, localSkin: null,
      shell: { openExternal: function (u) { try { window.HermesAndroid.openExternal(u); } catch (e) { window.open(u, '_blank'); } } },
      gatewayOverlay: { show: function () { try { window.HermesAndroid.showGatewayOverlay(); } catch (e) {} },
        hide: function () { try { window.HermesAndroid.hideGatewayOverlay(); } catch (e) {} } },
    };
    var safeHandler = {
      get: function (target, prop) {
        if (prop in target) return target[prop];
        if (typeof prop === 'string' && (prop.indexOf('on') === 0 || prop.indexOf('handle') === 0 || prop.indexOf('listen') === 0)) {
          return function () { return noop; };
        }
        return function () { return Promise.resolve({}); };
      }
    };
    var proxyStub = (typeof Proxy !== 'undefined') ? new Proxy(stub, safeHandler) : stub;
    try {
      Object.defineProperty(window, 'hermesDesktop', { value: proxyStub, writable: true, configurable: true });
    } catch (e) { window.hermesDesktop = proxyStub; }
    if (!window.electron) window.electron = window.hermesDesktop;
    if (!window.api) window.api = window.hermesDesktop;
  } catch (e) { /* never break first paint */ }
})();
