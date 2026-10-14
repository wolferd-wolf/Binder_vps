/* preload-shim.js - Android WebView preload companion.
 * Complete Hermes Desktop bridge stub with recursive proxy to ensure
 * all Electron IPC calls, subscriptions, and sub-objects resolve cleanly.
 */
(function () {
  try {
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

    function fetchTokenNative() {
      try {
        if (window.HermesAndroid && typeof window.HermesAndroid.fetchSessionToken === 'function') {
          return String(window.HermesAndroid.fetchSessionToken() || '').trim();
        }
        if (window.HermesAndroid && typeof window.HermesAndroid.getSessionToken === 'function') {
          return String(window.HermesAndroid.getSessionToken() || '').trim();
        }
        return 'ERROR: HermesAndroid native bridge is not available';
      } catch (e) {
        return 'ERROR: ' + (e && e.message ? e.message : String(e));
      }
    }

    var wsForCounter = 0;
    var connCounter = 0;
    var wsDialCounter = 0;
    var wsCloseCounter = 0;
    var sessionCreateCounter = 0;

    // Cache per gateway URL + profile
    var wsForCache = {};
    var connCache = {};
    var inFlightMints = {};
    var inFlightConnMints = {};

    function getIsoTime() {
      try { return new Date().toISOString(); } catch (e) { return String(Date.now()); }
    }

    function invalidateConnectionCache(reason) {
      var hadEntries = Object.keys(wsForCache).length > 0 || Object.keys(connCache).length > 0;
      console.log('[HermesWS] [' + getIsoTime() + '] Invalidate connection cache (reason="' + reason + '", hadEntries=' + hadEntries + ')');
      wsForCache = {};
      connCache = {};
      inFlightMints = {};
      inFlightConnMints = {};
    }

    try {
      window.__hermesInvalidateConnectionCache = invalidateConnectionCache;
    } catch (e) {}

    async function wsFor(profile) {
      wsForCounter++;
      var g = gw();
      var p = (profile || '').trim ? (profile || '').trim() : '';
      var cacheKey = g + '::' + p;

      // 1. Cache hit: return same object reference
      if (wsForCache[cacheKey]) {
        console.log('[HermesWS] [' + getIsoTime() + '] wsFor() call #' + wsForCounter + ' [cache hit]: profile="' + (p || 'default') + '"');
        return wsForCache[cacheKey];
      }

      // 2. In-flight deduping: share in-flight promise
      if (inFlightMints[cacheKey]) {
        console.log('[HermesWS] [' + getIsoTime() + '] wsFor() call #' + wsForCounter + ' [in-flight deduped]: profile="' + (p || 'default') + '"');
        return await inFlightMints[cacheKey];
      }

      console.log('[HermesWS] [' + getIsoTime() + '] wsFor() call #' + wsForCounter + ' [cache miss]: profile="' + (p || 'default') + '"');

      var mintPromise = (async function () {
        var tokenOrError = fetchTokenNative();
        if (!tokenOrError || tokenOrError.indexOf('ERROR:') === 0) {
          var err = tokenOrError ? tokenOrError.replace(/^ERROR:\s*/, '') : 'Session token is empty';
          console.error('[HermesWS] [' + getIsoTime() + '] Native session token error on wsFor() #' + wsForCounter + ':', err);
          invalidateConnectionCache('native-token-error');
          return { ok: false, error: err };
        }
        var ws = g.indexOf('https://') === 0 ? 'wss://' + g.slice(8) : g.replace(/^http:\/\//, 'ws://');
        var wsUrl = ws + '/api/ws?token=' + encodeURIComponent(tokenOrError) + (p ? '&profile=' + encodeURIComponent(p) : '');
        var masked = tokenOrError.length > 4 ? tokenOrError.substring(0, 4) + '...' : '****';
        var origin = (typeof window !== 'undefined' && window.location && window.location.origin) ? window.location.origin : 'unknown';
        console.log('[HermesWS] [' + getIsoTime() + '] Minted gateway WebSocket URL (origin=' + origin + '): ' + ws + '/api/ws?token=' + masked + (p ? '&profile=' + encodeURIComponent(p) : ''));
        var result = { ok: true, wsUrl: wsUrl, token: tokenOrError };
        wsForCache[cacheKey] = result;
        return result;
      })();

      inFlightMints[cacheKey] = mintPromise;
      try {
        return await mintPromise;
      } finally {
        delete inFlightMints[cacheKey];
      }
    }

    async function conn(profile) {
      connCounter++;
      var g = gw();
      var p = (profile || '').trim ? (profile || '').trim() : '';
      var cacheKey = g + '::' + p;

      // 1. Cache hit: return same object reference
      if (connCache[cacheKey]) {
        console.log('[HermesWS] [' + getIsoTime() + '] conn() call #' + connCounter + ' [cache hit]: profile="' + (p || 'default') + '"');
        return connCache[cacheKey];
      }

      // 2. In-flight deduping: share in-flight promise
      if (inFlightConnMints[cacheKey]) {
        console.log('[HermesWS] [' + getIsoTime() + '] conn() call #' + connCounter + ' [in-flight deduped]: profile="' + (p || 'default') + '"');
        return await inFlightConnMints[cacheKey];
      }

      console.log('[HermesWS] [' + getIsoTime() + '] conn() call #' + connCounter + ' [cache miss]: profile="' + (p || 'default') + '"');
      var connPromise = (async function () {
        var w = await wsFor(profile);
        var c = {
          baseUrl: g,
          isFullscreen: false,
          isMaximized: false,
          mode: 'local',
          source: 'local',
          token: w.ok ? (w.token || '') : '',
          wsUrl: w.ok ? w.wsUrl : '',
          error: w.ok ? undefined : w.error,
          logs: [],
          connectionId: 'local',
          windowButtonPosition: null,
          nativeOverlayWidth: 0,
          customWindowControls: false,
          registryScoped: false
        };
        if (p) { c.profile = p; c.sharedPrimary = true; }

        if (w.ok) {
          connCache[cacheKey] = c;
        }
        return c;
      })();

      inFlightConnMints[cacheKey] = connPromise;
      try {
        return await connPromise;
      } finally {
        delete inFlightConnMints[cacheKey];
      }
    }
    var noop = function () {};
    var noopUnsub = function () { return undefined; };

    var updatesObj = {
      check: function () { return Promise.resolve({ ok: false, error: 'android-managed' }); },
      apply: function () { return Promise.resolve({ ok: false }); },
      onProgress: function () { return noopUnsub; },
      onEvent: function () { return noopUnsub; },
      takePendingRun: function () { return Promise.resolve(null); },
      ackPendingRun: function () { return Promise.resolve(); },
      onPendingRun: function () { return noopUnsub; }
    };

    var stub = {
      getConnection: function (p) { return conn(p || undefined); },
      getConnectionFor: function (o) { return conn((o && o.profile) || undefined); },
      getGatewayWsUrl: function (p) { return wsFor(p || undefined); },
      getGatewayWsUrlFor: function (o) { return wsFor((o && o.profile) || undefined); },
      getAgentRoster: function () { return Promise.resolve({ agents: [], sources: [] }); },
      getProfileRoutes: function (profiles) {
        return Promise.resolve((profiles || []).map(function (profile) {
          return { connectionId: 'local', mode: 'local', primary: true, profile: profile, targetProfile: profile };
        }));
      },
      revalidateConnection: function () {
        invalidateConnectionCache('revalidate-connection');
        return Promise.resolve({ ok: true, rebuilt: false });
      },
      invalidateConnectionCache: function (r) {
        invalidateConnectionCache(r || 'stub-call');
        return Promise.resolve(true);
      },
      touchBackend: function () { return Promise.resolve({ ok: true }); },
      getPoolLimits: function () { return Promise.resolve({ maxBackends: 1, idleMs: 0 }); },
      setPoolLimits: function (l) { return Promise.resolve({ ok: true, limits: l || {} }); },
      api: function (r) {
        var g = gw(); var path = (r && r.path) || '/';
        if (path.charAt(0) !== '/') path = '/' + path;
        return fetch(g + path, { method: (r && r.method) || 'GET',
          headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
          body: r && r.body !== undefined && r.method !== 'GET' ? JSON.stringify(r.body) : undefined })
          .then(function (res) {
            if (!res.ok) throw new Error('Hermes gateway ' + res.status);
            return res.json();
          });
      },
      notify: function () { return Promise.resolve(true); },
      claimStartupLatency: function () { return Promise.resolve(null); },
      claimAmbientCue: function () { return Promise.resolve(true); },
      onBrowserPopoutClosed: function () { return noopUnsub; },
      onPreviewFileChanged: function () { return noopUnsub; },
      onBatteryChanged: function () { return noopUnsub; },
      onWindowStateChanged: function () { return noopUnsub; },
      onFocusSession: function () { return noopUnsub; },
      onNotificationAction: function () { return noopUnsub; },
      onNotificationActivate: function () { return noopUnsub; },
      onBackendExit: function () { return noopUnsub; },
      onPoolBackendRetiring: function () { return noopUnsub; },
      onConnectionApplied: function () { return noopUnsub; },
      onPowerResume: function () { return noopUnsub; },
      onBootProgress: function () { return noopUnsub; },
      onBootstrapEvent: function () { return noopUnsub; },
      onStatus: function () { return noopUnsub; },
      screenshot: {
        onStatus: function () { return noopUnsub; },
        getStatus: function () { return Promise.resolve({ available: false }); },
        capture: function () { return Promise.resolve({ ok: false }); }
      },
      getOnBattery: function () { return Promise.resolve(false); },
      getBootProgress: function () { return Promise.resolve({ stage: 'idle', percent: 100, message: '' }); },
      getBootstrapState: function () {
        return Promise.resolve({
          active: false, manifest: null, stages: {}, error: null, log: [],
          startedAt: null, completedAt: null, setupChoice: null,
          unsupportedPlatform: null, bundled: false
        });
      },
      probeLocalBackend: function () { return Promise.resolve({ bootstrapNeeded: false }); },
      continueBootstrapLocal: function () { return Promise.resolve({ ok: true }); },
      recycleBackend: function () { return Promise.resolve({ ok: true }); },
      resetBootstrap: function () { return Promise.resolve({ ok: true }); },
      repairBootstrap: function () { return Promise.resolve({ ok: true }); },
      cancelBootstrap: function () { return Promise.resolve({ ok: true, cancelled: false }); },
      getVersion: function () { return Promise.resolve({ version: '1.0.0', build: 'android' }); },
      getMachineProfile: function () { return Promise.resolve({ os: 'android', arch: 'unknown' }); },
      getSyncStatus: function () { return Promise.resolve(null); },
      signalDeepLinkReady: function () { return Promise.resolve({ ok: true }); },
      probePluginRepo: function () { return Promise.resolve({ ok: true, agent: false, desktop: false }); },
      installDesktopPlugin: function () { return Promise.resolve({ ok: true }); },
      removeDesktopPlugin: function () { return Promise.resolve({ ok: true }); },
      updateHold: {
        recheck: function () { return Promise.resolve({ ok: true }); },
        quit: function () { return Promise.resolve({ ok: true }); },
        startAnyway: function () { return Promise.resolve({ ok: true }); }
      },
      watchPreviewFile: function (u) { return Promise.resolve({ ok: false, error: 'android-no-watch', url: u }); },
      stopPreviewFileWatch: function () { return Promise.resolve(false); },
      normalizePreviewTarget: function () { return Promise.resolve(null); },
      windowControls: { custom: false, minimize: noop, toggleMaximize: noop, close: noop },
      wakeIndicator: { getState: function () { return Promise.resolve({ visible: false }); }, setState: noop, onState: function () { return noopUnsub; } },
      chatOnboarding: { size: noop },
      petOverlay: {
        open: function () { return Promise.resolve({ ok: false }); },
        close: function () { return Promise.resolve({ ok: true }); },
        setBounds: noop, setIgnoreMouse: noop, setFocusable: noop,
        pushState: noop, control: noop,
        onState: function () { return noopUnsub; },
        onControl: function () { return noopUnsub; }
      },
      quickEntry: {
        getSettings: function () { return Promise.resolve({ enabled: false }); },
        setSettings: function (s) { return Promise.resolve(s); },
        onSettingsChanged: function () { return noopUnsub; },
        onState: function () { return noopUnsub; },
        onSubmit: function () { return noopUnsub; },
        onShown: function () { return noopUnsub; },
        onLateResult: function () { return noopUnsub; }
      },
      machineProfile: function () { return Promise.resolve({ os: 'android', arch: 'unknown' }); },
      appVersion: function () { return Promise.resolve({ appName: 'Hermes Desktop Android', gatewayUrl: gw() }); },
      getInstallInfo: function () { return Promise.resolve({ kind: 'android-webview', gatewayUrl: gw() }); },
      updater: updatesObj,
      updates: updatesObj,
      relaunch: function () { return Promise.resolve(); },
      getConnectionConfig: function () {
        return Promise.resolve({
          envOverride: false, mode: 'local', profile: null, remoteAuthMode: 'token',
          remoteOauthConnected: false, remoteTokenPreview: null, remoteTokenSet: false,
          secureTokenStorage: false, remoteTokenPlainText: false, remoteUrl: gw(), cloudOrg: ''
        });
      },
      setConnectionConfig: function () { return stub.getConnectionConfig(); },
      testConnectionConfig: function () { return Promise.resolve({ ok: true }); },
      getSecretStorageEncryption: function () { return Promise.resolve({ on: false }); },
      setSecretStorageEncryption: function () { return Promise.resolve({ on: false }); },
      connections: {
        list: function () { return Promise.resolve({ primaryId: 'local', connections: [{ id: 'local', label: 'This device', kind: 'local', url: gw() }] }); },
        setPrimary: function () { return Promise.resolve({ ok: true }); },
        test: function () { return Promise.resolve({ ok: true }); }
      },
      profile: {
        getDefault: function () { return Promise.resolve(null); },
        setDefault: function (r) { return Promise.resolve(r); },
        onDefaultChanged: function () { return noopUnsub; },
        get: function () { return Promise.resolve({ profile: null }); },
        remember: function (n) { return Promise.resolve({ profile: n }); },
        set: function (n) { return Promise.resolve({ profile: n }); }
      },
      files: {
        pick: function () {
          try { window.HermesAndroid && window.HermesAndroid.triggerFilePicker(); } catch (e) {}
          return Promise.resolve({ canceled: true });
        },
        save: function () { return Promise.resolve({ saved: false, canceled: true }); }
      },
      glassSupported: false, translucencySupported: false, localModelsEnabled: false,
      assumeFreshInstall: false, localSkin: null,
      setTranslucency: noop, setKeepAwake: noop,
      minimizeToTray: {
        get: function () { return Promise.resolve({ enabled: false, available: false }); },
        set: function () { return Promise.resolve({ enabled: false, available: false }); },
        onChanged: function () { return noopUnsub; }
      },
      notifications: {
        show: function (p) {
          try { window.HermesAndroid && window.HermesAndroid.showToast(String(p && (p.body || p.title) || 'Notification')); } catch (e) {}
          return Promise.resolve(true);
        },
        onClicked: function () { return noopUnsub; }
      },
      shell: {
        openExternal: function (u) {
          try { window.HermesAndroid && window.HermesAndroid.openExternal(u); } catch (e) { window.open(u, '_blank'); }
        }
      },
      clipboard: {
        writeText: function (t) { try { return navigator.clipboard.writeText(t); } catch (e) { return Promise.resolve(); } },
        readText: function () { try { return navigator.clipboard.readText(); } catch (e) { return Promise.resolve(''); } }
      },
      gatewayOverlay: {
        show: function () { try { window.HermesAndroid && window.HermesAndroid.showGatewayOverlay(); } catch (e) {} },
        hide: function () { try { window.HermesAndroid && window.HermesAndroid.hideGatewayOverlay(); } catch (e) {} }
      },
      android: {
        getGatewayUrl: function () { return gw(); },
        setGatewayUrl: function (u) { try { window.HermesAndroid && window.HermesAndroid.setGatewayUrl(u); } catch (e) {} },
        showToast: function (m) { try { window.HermesAndroid && window.HermesAndroid.showToast(m); } catch (e) {} }
      }
    };

    function makeSafe(obj) {
      if (!obj || typeof obj !== 'object') return obj;
      return new Proxy(obj, {
        get: function (target, prop) {
          if (prop in target) {
            var v = target[prop];
            return (typeof v === 'object' && v !== null && !(v instanceof Promise)) ? makeSafe(v) : v;
          }
          if (typeof prop === 'symbol' || prop === 'then' || prop === 'catch') return undefined;
          if (typeof prop === 'string' && (prop.indexOf('on') === 0 || prop.indexOf('listen') === 0 || prop.indexOf('handle') === 0)) {
            return function () { return noopUnsub; };
          }
          var methodFallback = function () {
            var p = Promise.resolve({});
            return p;
          };
          methodFallback.then = function (cb) { return Promise.resolve({}).then(cb); };
          return makeSafe(methodFallback);
        }
      });
    }

    var proxyStub = makeSafe(stub);
    try {
      Object.defineProperty(window, 'hermesDesktop', { value: proxyStub, writable: false, configurable: false });
    } catch (e) { window.hermesDesktop = proxyStub; }
    try {
      Object.defineProperty(window, 'electron', { value: proxyStub, writable: false, configurable: false });
    } catch (e) { window.electron = proxyStub; }
    try {
      Object.defineProperty(window, 'api', { value: proxyStub, writable: false, configurable: false });
    } catch (e) { window.api = proxyStub; }

    // Temporary diagnostic logger: log final WebSocket URL (token masked to 4 chars), dial count, session.create send count, and close/error codes
    (function () {
      if (typeof window === 'undefined' || !window.WebSocket) return;
      var OrigWebSocket = window.WebSocket;
      var OrigSend = OrigWebSocket.prototype.send;
      function LoggedWebSocket(url, protocols) {
        wsDialCounter++;
        var currentDialId = wsDialCounter;
        var strUrl = String(url || '');
        var masked = strUrl.replace(/([?&](?:token|ticket)=)([^&]{1,4})[^&]*/g, '$1$2...');
        var origin = (typeof window !== 'undefined' && window.location && window.location.origin) ? window.location.origin : 'unknown';
        console.log('[HermesWS] [' + getIsoTime() + '] WebSocket dial #' + currentDialId + ' (origin=' + origin + '): ' + masked);
        var ws = protocols !== undefined ? new OrigWebSocket(url, protocols) : new OrigWebSocket(url);
        ws._dialId = currentDialId;
        ws._maskedUrl = masked;
        var hasOpened = false;

        ws.addEventListener('open', function () {
          hasOpened = true;
          console.log('[HermesWS] [' + getIsoTime() + '] WebSocket opened on dial #' + currentDialId + ' (' + masked + ')');
        });

        ws.addEventListener('close', function (event) {
          wsCloseCounter++;
          console.warn('[HermesWS] [' + getIsoTime() + '] WebSocket close #' + wsCloseCounter + ' on dial #' + currentDialId + ': code=' + event.code + ', reason="' + event.reason + '", wasClean=' + event.wasClean + ' on ' + masked);
          if (event.code === 4401 || event.code === 4403) {
            invalidateConnectionCache('auth-close-' + event.code);
          } else if (!hasOpened) {
            invalidateConnectionCache('close-before-open-' + event.code);
          }
        });

        ws.addEventListener('error', function () {
          console.error('[HermesWS] [' + getIsoTime() + '] WebSocket error before/during connection on dial #' + currentDialId + ' (' + masked + ')');
          if (!hasOpened) {
            invalidateConnectionCache('dial-error-before-open');
          }
        });
        return ws;
      }
      LoggedWebSocket.prototype = OrigWebSocket.prototype;
      LoggedWebSocket.prototype.send = function (data) {
        try {
          if (typeof data === 'string' && data.indexOf('"session.create"') !== -1) {
            sessionCreateCounter++;
            var dialNum = this._dialId || '?';
            console.log('[HermesWS] [' + getIsoTime() + '] session.create send #' + sessionCreateCounter + ' on dial #' + dialNum + ': ' + data);
          }
        } catch (e) {}
        return OrigSend.apply(this, arguments);
      };
      LoggedWebSocket.CONNECTING = OrigWebSocket.CONNECTING;
      LoggedWebSocket.OPEN = OrigWebSocket.OPEN;
      LoggedWebSocket.CLOSING = OrigWebSocket.CLOSING;
      LoggedWebSocket.CLOSED = OrigWebSocket.CLOSED;
      window.WebSocket = LoggedWebSocket;
    })();

    window.__HERMES_ANDROID_SHIM__ = true;
  } catch (e) { /* never break first paint */ }
})();
