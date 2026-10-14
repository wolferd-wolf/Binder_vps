/**
 * electron-shim.ts - Android WebView compatibility shim for Hermes Desktop UI.
 * Upstream apps/desktop runs inside Electron where main exposes
 * `window.hermesDesktop` via electron/preload.ts. On Android there is no
 * Electron: the renderer runs in a Chromium WebView (MainActivity.kt) which
 * exposes `window.HermesAndroid` (@JavascriptInterface) and serves this bundle
 * from file:///android_asset/hermes/index.html.
 * Imported for side effects FIRST in index.html, ahead of /src/main.tsx.
 * Routes everything to the local gateway (default http://127.0.0.1:9119)
 * plus window.HermesAndroid. Legacy window.electron / window.api aliased.
 */
declare global {
  interface Window {
    HermesAndroid?: any;
    hermesDesktop?: any;
    electron?: any;
    api?: any;
    __HERMES_ANDROID_SHIM__?: boolean;
    __HERMES_GATEWAY_URL__?: string;
  }
}
const DEFAULT_GATEWAY_URL = 'http://127.0.0.1:9119';
const GATEWAY_URL_KEY = 'hermes.gateway.url';
function trimSlash(url: string): string {
  return url.endsWith('/') ? url.slice(0, -1) : url;
}
export function resolveGatewayUrl(): string {
  try {
    const inj = (window as any).__HERMES_GATEWAY_URL__;
    if (typeof inj === 'string' && inj.trim()) return trimSlash(inj.trim());
  } catch { /* ignore */ }
  try {
    const b = window.HermesAndroid;
    if (b && typeof b.getGatewayUrl === 'function') {
      const n = String(b.getGatewayUrl() || '').trim();
      if (n) return trimSlash(n);
    }
  } catch { /* bridge not ready */ }
  try {
    const s = localStorage.getItem(GATEWAY_URL_KEY);
    if (s && s.trim()) return trimSlash(s.trim());
  } catch { /* ignore */ }
  return DEFAULT_GATEWAY_URL;
}
export function persistGatewayUrl(url: string): void {
  try { localStorage.setItem(GATEWAY_URL_KEY, trimSlash(url.trim())); } catch { /* ignore */ }
  try { window.HermesAndroid?.setGatewayUrl?.(url); } catch { /* ignore */ }
}
function toWsUrl(baseUrl: string): string {
  const base = trimSlash(baseUrl || DEFAULT_GATEWAY_URL);
  if (base.startsWith('https://')) return 'wss://' + base.slice(8);
  if (base.startsWith('wss://') || base.startsWith('ws://')) return base;
  if (base.startsWith('http://')) return 'ws://' + base.slice(7);
  return 'ws://' + base.replace(/^[a-z]+:\/\//i, '');
}
function profileQuery(profile?: null | string): string {
  const p = (profile ?? '').trim();
  return p ? ('?profile=' + encodeURIComponent(p)) : '';
}
type HermesApiRequest = {
  path: string; method?: string; body?: unknown;
  query?: Record<string, string | number | boolean | undefined | null>;
  headers?: Record<string, string>; profile?: string;
  connectionId?: string; priority?: string; timeoutMs?: number;
};
async function fetchTimeout(input: string, init: RequestInit, ms?: number): Promise<Response> {
  if (!ms || ms <= 0) return fetch(input, init);
  const c = new AbortController();
  const t = window.setTimeout(() => c.abort(), ms);
  try { return await fetch(input, { ...init, signal: c.signal }); }
  finally { window.clearTimeout(t); }
}
async function hermesRestApi<T>(request: HermesApiRequest): Promise<T> {
  const gateway = resolveGatewayUrl();
  const raw = request.path || '/';
  const path = raw.startsWith('/') ? raw : ('/' + raw);
  const params = new URLSearchParams();
  if (request.query) for (const [k, v] of Object.entries(request.query)) {
    if (v !== undefined && v !== null && v !== '') params.set(k, String(v));
  }
  const sp = (request.profile ?? '').trim();
  if (sp && !params.has('profile')) params.set('profile', sp);
  const qs = params.toString();
  const url = gateway + path + (qs ? ('?' + qs) : '');
  const method = (request.method || 'GET').toUpperCase();
  const headers: Record<string, string> = { ...(request.headers || {}) };
  let body: BodyInit | undefined;
  if (request.body !== undefined && method !== 'GET' && method !== 'HEAD') {
    if (typeof request.body === 'string' || request.body instanceof FormData) body = request.body as BodyInit;
    else { headers['Content-Type'] = headers['Content-Type'] || 'application/json'; body = JSON.stringify(request.body); }
  }
  headers['Accept'] = headers['Accept'] || 'application/json';
  const res = await fetchTimeout(url, { method, headers, body }, request.timeoutMs);
  if (!res.ok) {
    let d = '';
    try { d = await res.text(); } catch { /* ignore */ }
    throw new Error('Hermes gateway ' + res.status + ' for ' + method + ' ' + path + (d ? (': ' + d.slice(0, 400)) : ''));
  }
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) return (await res.json()) as T;
  const text = await res.text();
  try { return JSON.parse(text) as T; } catch { return text as unknown as T; }
}
function buildConnection(profile?: null | string): any {
  const gateway = resolveGatewayUrl();
  const p = (profile ?? '').trim();
  const c: any = {
    baseUrl: gateway, customWindowControls: false, isFullscreen: false,
    isMaximized: false, mode: 'local', source: 'local', token: '',
    wsUrl: toWsUrl(gateway), logs: [], connectionId: 'local',
    registryScoped: false, windowButtonPosition: null, nativeOverlayWidth: 0,
  };
  if (p) { c.profile = p; c.sharedPrimary = true; }
  return c;
}
const noopUnsub = () => undefined;
export function installElectronShim(): any {
  if ((window as any).__HERMES_ANDROID_SHIM__ && window.hermesDesktop) return window.hermesDesktop;
  const toast = (m: string) => { try { window.HermesAndroid?.showToast?.(m); } catch { /* ignore */ } };
  const gw = () => resolveGatewayUrl();
  const wsFor = (profile?: null | string) => ({ ok: true as const, wsUrl: toWsUrl(gw()) + profileQuery(profile) });
  const bridge: any = {
    getConnection: async (profile?: string | null) => buildConnection(profile ?? undefined),
    getConnectionFor: async (p?: { connectionId?: null | string; profile?: null | string }) => buildConnection(p?.profile ?? undefined),
    getGatewayWsUrl: async (profile?: null | string) => wsFor(profile),
    getGatewayWsUrlFor: async (p?: { connectionId?: null | string; profile?: null | string }) => wsFor(p?.profile),
    getAgentRoster: async () => ({ agents: [], sources: [] }),
    getProfileRoutes: async (profiles: string[]) => (profiles || []).map((profile) => ({
      connectionId: 'local', mode: 'local' as const, primary: true as const, profile, targetProfile: profile,
    })),
    getEmbedHostOrigin: async () => gw(),
    revalidateConnection: async () => ({ ok: true, rebuilt: false }),
    touchBackend: async () => ({ ok: true }),
    getPoolLimits: async () => ({ maxBackends: 1, idleMs: 0 }),
    setPoolLimits: async (l: any) => ({ ok: true, limits: { maxBackends: l?.maxBackends ?? 1, idleMs: l?.idleMs ?? 0 } }),
    api: <T>(r: HermesApiRequest) => hermesRestApi<T>(r),
    notify: async () => true,
    claimStartupLatency: async () => null,
    openSessionWindow: async () => ({ ok: false as const, error: 'android-single-window' }),
    openSessionInTerminal: async () => ({ ok: false as const, error: 'android-no-terminal' }),
    openWindow: async () => ({ ok: false as const, error: 'android-single-window' }),
    openBrowserWindow: async () => ({ ok: false as const, error: 'android-single-window' }),
    onBrowserPopoutClosed: () => noopUnsub,
    claimAmbientCue: async () => true,
    windowControls: { custom: false, minimize: () => undefined, toggleMaximize: () => undefined, close: () => undefined },
    wakeIndicator: { getState: async () => ({ visible: false }), setState: () => undefined, onState: () => noopUnsub },
    chatOnboarding: { size: () => undefined },
    petOverlay: {
      open: async () => ({ ok: false as const }), close: async () => ({ ok: true as const }),
      setBounds: () => undefined, setIgnoreMouse: () => undefined, setFocusable: () => undefined,
      pushState: () => undefined, control: () => undefined, onState: () => noopUnsub, onControl: () => noopUnsub,
    },
    quickEntry: { getSettings: async () => ({ enabled: false }), setSettings: async (s: unknown) => s, onSettingsChanged: () => noopUnsub },
    machineProfile: async () => ({ os: 'android', arch: 'unknown' }),
    appVersion: async () => {
      try { const raw = window.HermesAndroid?.getAppInfo?.(); if (raw) return JSON.parse(raw); }
      catch { /* ignore */ }
      return { appName: 'Hermes Desktop Android', gatewayUrl: gw() };
    },
    getInstallInfo: async () => ({ kind: 'android-webview', gatewayUrl: gw() }),
    updater: { check: async () => ({ ok: false as const, error: 'android-managed' }), onEvent: () => noopUnsub },
    relaunch: async () => undefined,
    getConnectionConfig: async () => ({
      envOverride: false, mode: 'local', profile: null, remoteAuthMode: 'token',
      remoteOauthConnected: false, remoteTokenPreview: null, remoteTokenSet: false,
      secureTokenStorage: false, remoteTokenPlainText: false, remoteUrl: gw(), cloudOrg: '',
    }),
    setConnectionConfig: async (input: any) => {
      if (input?.remoteUrl) persistGatewayUrl(String(input.remoteUrl));
      return bridge.getConnectionConfig();
    },
    testConnectionConfig: async () => ({ ok: true }),
    getSecretStorageEncryption: async () => ({ on: false }),
    setSecretStorageEncryption: async () => ({ on: false }),
    connections: {
      list: async () => ({ primaryId: 'local', connections: [{ id: 'local', label: 'This device', kind: 'local', url: gw() }] }),
      save: async () => { throw new Error('hermesDesktop.connections.save unavailable on Android'); },
      remove: async () => { throw new Error('hermesDesktop.connections.remove unavailable on Android'); },
      setPrimary: async () => ({ ok: true, registry: { primaryId: 'local', connections: [] } }),
      test: async () => ({ ok: true }),
    },
    sshConfigHosts: async () => ({ hosts: [] }),
    sshResolveHost: async (host: string) => ({ ok: false, host, error: 'android-no-ssh' }),
    probeConnectionConfig: async (remoteUrl: string) => ({ ok: true, url: remoteUrl }),
    oauthLoginConnectionConfig: async () => ({ ok: false, error: 'android-no-oauth' }),
    oauthLogoutConnectionConfig: async () => ({ ok: true }),
    cloud: {
      status: async () => ({ connected: false }),
      login: async () => ({ connected: false, ok: false }),
      logout: async () => ({ connected: false, ok: true }),
      discover: async () => ({ ok: false, agents: [], error: 'android-no-cloud' }),
      agentSignIn: async () => ({ ok: false, error: 'android-no-cloud' }),
    },
    profile: {
      getDefault: async () => null,
      setDefault: async (route: any) => route,
      onDefaultChanged: () => noopUnsub,
      get: async () => ({ profile: null }),
      remember: async (name: string | null) => ({ profile: name }),
      set: async (name: string | null) => ({ profile: name }),
    },
    files: {
      pick: async () => { try { window.HermesAndroid?.triggerFilePicker?.(); } catch { /* ignore */ } return { canceled: true as const }; },
      save: async () => ({ saved: false, canceled: true }),
    },
    saveImageFromUrl: async () => false,
    savePastedText: async (text: string) => text,
    saveClipboardImage: async () => '',
    getPathForFile: (file: File) => file?.name || '',
    normalizePreviewTarget: async () => null,
    watchPreviewFile: async (url: string) => ({ ok: false as const, error: 'android-no-watch', url }),
    stopPreviewFileWatch: async () => false,
    setActiveWork: () => undefined,
    setTitleBarTheme: () => undefined,
    setNativeTheme: () => undefined,
    glassSupported: false,
    translucencySupported: false,
    localModelsEnabled: false,
    assumeFreshInstall: false,
    localSkin: null,
    setTranslucency: () => undefined,
    setKeepAwake: () => undefined,
    minimizeToTray: {
      get: async () => ({ enabled: false, available: false }),
      set: async () => ({ enabled: false, available: false }),
      onChanged: () => noopUnsub,
    },
    notifications: {
      show: async (p: any) => { toast(String(p?.body || p?.title || 'Notification')); return true; },
      onClicked: () => noopUnsub,
    },
    shell: { openExternal: (url: string) => { try { window.HermesAndroid?.openExternal?.(url); } catch { window.open(url, '_blank'); } } },
    clipboard: {
      writeText: async (t: string) => { try { await navigator.clipboard.writeText(t); } catch { /* ignore */ } },
      readText: async () => { try { return await navigator.clipboard.readText(); } catch { return ''; } },
    },
    gatewayOverlay: {
      show: () => { try { window.HermesAndroid?.showGatewayOverlay?.(); } catch { /* ignore */ } },
      hide: () => { try { window.HermesAndroid?.hideGatewayOverlay?.(); } catch { /* ignore */ } },
    },
    android: {
      getGatewayUrl: () => gw(),
      setGatewayUrl: (u: string) => persistGatewayUrl(u),
      showToast: (m: string) => toast(m),
    },
  };
  Object.defineProperty(window, 'hermesDesktop', { value: bridge, writable: true, configurable: true });
  if (!window.electron) window.electron = bridge;
  if (!window.api) window.api = bridge;
  window.__HERMES_ANDROID_SHIM__ = true;
  return bridge;
}
try { installElectronShim(); } catch (e) { console.warn('[hermes-android] shim failed:', e); }
