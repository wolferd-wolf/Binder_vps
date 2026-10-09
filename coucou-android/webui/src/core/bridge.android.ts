// Android bridge — the drop-in replacement for `core/bridge.ts` used by the WebView build.
//
// Upstream `windows/` reaches its host through Tauri's `invoke(cmd, args)`. There is no
// Tauri inside the Android overlay, so this file talks to Kotlin through the named-method
// surface `window.CoucouAndroid` (the Sprint 3 REDO contract). When that object is not
// injected — the Kotlin lane is still landing it — a JS facade of the same shape is
// installed on top of the synchronous `window.CoucouNative.invoke(cmd, argsJson)` host
// the Plan A shim already provides (`app/src/main/assets/coucou/tauri-shim.js`), so every
// call still reaches a real host instead of a name that does not exist yet.
//
// `vite.config.android.ts` aliases `core/bridge` to this file, so every `Bridge.*` call
// in `src/` resolves here in the Android build while the desktop build keeps the Tauri
// one. The public surface — `Bridge`, `IS_TAURI`, `onEvent`, `onDragDrop` and the types —
// is identical, so no consumer needs to know which one it got.
//
// Commands modelled for real on this platform:
//   boot · setIslandRect · setCollapsed · focusWindow · chatSend · openUrl · log
//   dragStart · dragBy · dragEnd · collapse                 (Sprint 3 REDO)
// Everything else stays the honest "not on this platform" no-op it was before.

import { listen } from "@tauri-apps/api/event";
import type {
  BootInfo,
  ChatContext,
  DragDropPayload,
  DroppedFile,
  HookPreview,
  HookStatus,
} from "./bridge";
import type { Settings } from "./state";

// The types are shared with the desktop bridge, so consumers importing them from
// `core/bridge` keep resolving whichever build is in play.
export type {
  BootInfo,
  BridgeEvent,
  ChatContext,
  DragDropPayload,
  DroppedFile,
  HookPreview,
  HookStatus,
  IntegrationUpdate,
} from "./bridge";

export const IS_TAURI =
  typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

type JsonRecord = Record<string, unknown>;

/** The envelope `IslandBridgeHost.execute` returns as a JSON string. */
interface Envelope {
  ok: boolean;
  value?: unknown;
  error?: string;
}

/**
 * The named surface the Kotlin side injects as `window.CoucouAndroid`.
 *
 * Keep the names and argument order stable: the web top bar calls `dragStart`/`dragBy`/
 * `dragEnd` straight off this object, and the facade below implements the same shape over
 * `CoucouNative`, so both hosts behave identically. `invoke` is the generic escape hatch
 * every other command goes through.
 */
interface CoucouAndroidApi {
  boot(): unknown;
  setIslandRect(x: number, y: number, width: number, height: number): unknown;
  setCollapsed(collapsed: boolean): unknown;
  focusWindow(focused: boolean): unknown;
  chatSend(query: string, context: ChatContext | null): unknown;
  openUrl(url: string): unknown;
  log(message: string): unknown;
  dragStart(): unknown;
  dragBy(dx: number, dy: number): unknown;
  dragEnd(): unknown;
  collapse(): unknown;
  openChat?(): unknown;
  getNotesJson?(): unknown;
  addNote?(text: string, isTask: boolean): unknown;
  deleteNote?(id: number): unknown;
  toggleNote?(id: number): unknown;
  invoke?(cmd: string, argsJson: string): unknown;
}

/** Accepts either a raw value (native named method) or the Kotlin JSON envelope. */
function parseEnvelope(raw: unknown): Envelope {
  if (typeof raw !== "string") return { ok: true, value: raw };
  try {
    const parsed = JSON.parse(raw) as Envelope;
    if (parsed && typeof parsed === "object" && "ok" in parsed) return parsed;
    return { ok: true, value: parsed };
  } catch {
    return { ok: false, error: "malformed native reply" };
  }
}

/** The one synchronous call into the Plan A shim's Kotlin host. */
function coucouNativeInvoke(cmd: string, args: JsonRecord): string {
  const host = (window as unknown as {
    CoucouNative?: { invoke?: (command: string, argsJson: string) => string };
  }).CoucouNative;
  if (!host || typeof host.invoke !== "function") {
    throw new Error("Coucou native bridge unavailable");
  }
  return host.invoke(cmd, JSON.stringify(args));
}

/**
 * `window.CoucouAndroid` implemented over `CoucouNative.invoke`, using the same snake_case
 * command names Kotlin's `IslandBridgeCommands.plan()` already models. Installed only when
 * no native `CoucouAndroid` exists, so a real JavascriptInterface wins once it lands.
 */
function facadeOverNative(): CoucouAndroidApi {
  const send = (cmd: string, args: JsonRecord = {}) => coucouNativeInvoke(cmd, args);
  return {
    invoke: (cmd, argsJson) => coucouNativeInvoke(cmd, JSON.parse(argsJson || "{}")),
    boot: () => send("boot"),
    setIslandRect: (x, y, width, height) => send("set_island_rect", { x, y, width, height }),
    setCollapsed: (collapsed) => send("set_collapsed", { collapsed }),
    focusWindow: (focused) => send("focus_window", { focused }),
    chatSend: (query, context) => send("chat_send", { query, context }),
    openUrl: (url) => send("open_url", { url }),
    log: (message) => send("log_line", { message }),
    dragStart: () => send("drag_start"),
    dragBy: (dx, dy) => send("drag_by", { dx, dy }),
    dragEnd: () => send("drag_end"),
    // A user-initiated collapse is the same window transition as the low-level call, so it
    // reuses the modelled `set_collapsed` command rather than inventing a second one the
    // host would answer with a silent null.
    collapse: () => send("set_collapsed", { collapsed: true }),
    openChat: () => send("open_chat"),
  };
}

function getApi(): CoucouAndroidApi {
  const w = window as unknown as { CoucouAndroid?: CoucouAndroidApi };
  if (w.CoucouAndroid) return w.CoucouAndroid;
  const facade = facadeOverNative();
  w.CoucouAndroid = facade;
  return facade;
}

function rawInvoke(cmd: string, args: JsonRecord = {}): unknown {
  const api = getApi();
  if (typeof api.invoke === "function") return api.invoke(cmd, JSON.stringify(args));
  throw new Error(`CoucouAndroid has no generic invoke for '${cmd}'`);
}

function unwrap<T>(raw: unknown): T {
  const envelope = parseEnvelope(raw);
  if (!envelope.ok) throw new Error(envelope.error || "unknown error");
  return (envelope.value === undefined ? null : envelope.value) as T;
}

/**
 * Runs a call and swallows failures, exactly like the desktop bridge: a command without an
 * Android implementation resolves `null`, which upstream reads as "nothing happened".
 * `named` is the `CoucouAndroid` method for the commands this platform really implements,
 * so the call path matches the published contract rather than the generic escape hatch.
 */
async function call<T>(cmd: string, args: JsonRecord = {}, named?: () => unknown): Promise<T | null> {
  if (!IS_TAURI) return null;
  try {
    return unwrap<T>(named ? named() : rawInvoke(cmd, args));
  } catch (err) {
    console.error(`[coucou] ${cmd} failed`, err);
    return null;
  }
}

/** SPRINT 6.4 @Cline — fan-out for in-page notes mutations. The drawer listens on
 *  `coucou:notes-updated`, and a late-injected native host gets the same nudge
 *  via `window.CoucouAndroid.onNotesUpdated` so MainActivity-side adds converge. */
function notifyNotesUpdated() {
  try {
    if (typeof window !== "undefined") {
      window.dispatchEvent(new Event("coucou:notes-updated"));
      (window as unknown as { CoucouAndroid?: { onNotesUpdated?: () => void } })
        .CoucouAndroid?.onNotesUpdated?.();
    }
  } catch {
    /* listeners are best-effort; sync() re-fetch covers the rest */
  }
}

/** Same as `call`, but surfaces the error so the UI can show what went wrong. */
async function callOrThrow<T>(cmd: string, args: JsonRecord = {}, named?: () => unknown): Promise<T> {
  if (!IS_TAURI) throw new Error("not running inside Coucou");
  return unwrap<T>(named ? named() : rawInvoke(cmd, args));
}

export const Bridge = {
  boot: () => call<BootInfo>("boot", {}, () => getApi().boot()),

  saveSettings: (settings: Settings) => call<void>("save_settings", { settings }),

  /** Shrink the window down to the invisible wake strip (hidden) or back to full. */
  setCollapsed: (collapsed: boolean) =>
    call<void>("set_collapsed", { collapsed }, () => getApi().setCollapsed(collapsed)),

  /**
   * Pushes the island shape in window coordinates. Kotlin resizes the overlay window to
   * match, so the window is exactly the island and touches outside it pass through.
   */
  setIslandRect: (x: number, y: number, width: number, height: number) =>
    call<void>("set_island_rect", { x, y, width, height }, () =>
      getApi().setIslandRect(x, y, width, height),
    ),

  /** Give the window keyboard focus (chat field) and take it away again. */
  focusWindow: (focused: boolean) =>
    call<void>("focus_window", { focused }, () => getApi().focusWindow(focused)),

  reposition: () => call<void>("reposition"),

  openUrl: (url: string) => call<void>("open_url", { url }, () => getApi().openUrl(url)),

  /** "Open terminal" → opens the folder in VS Code when `code` is on PATH. */
  openInVSCode: (path: string | null) => call<boolean>("open_in_vscode", { path }),

  quit: () => call<void>("quit_app"),

  openSettingsWindow: () => call<void>("open_settings_window"),
  openChat: () => call<void>("open_chat", {}, () => getApi().openChat?.()),

  /** Writes a line into logcat, next to the Kotlin lines. */
  log: (message: string) => call<void>("log_line", { message }, () => getApi().log(message)),

  // ── Window drag (Sprint 3 REDO) ─────────────────────────────────────────────
  // The expanded panel's top bar is the drag handle: pointer down → dragStart, each move
  // → dragBy(dx, dy), pointer up → dragEnd. The host moves the overlay window; the page
  // never repositions itself. The start/axis arguments are accepted for the call sites and
  // ignored here: the host already owns the window position, so a base it has to be told
  // about would be a second source of truth.
  dragStart: (_x?: number) => call<void>("drag_start", {}, () => getApi().dragStart()),
  dragBy: (dx: number, dy = 0) =>
    call<void>("drag_by", { dx, dy }, () => getApi().dragBy(dx, dy)),
  dragEnd: () => call<void>("drag_end", {}, () => getApi().dragEnd()),

  /** Close button / back / outside tap: shrink back to the collapsed rectangle. */
  collapse: () => call<void>("set_collapsed", { collapsed: true }, () => getApi().collapse()),

  // ── Claude Code hooks ─────────────────────────────────────────────────────
  hooksStatus: () => call<HookStatus>("hooks_status"),
  /** Diff to show before anything is written. `install: false` previews removal. */
  hooksPreview: (install: boolean) =>
    callOrThrow<HookPreview>("hooks_preview", { install }),
  /**
   * Writes ~/.claude/settings.json — only ever after an explicit click, and only
   * when the file still matches the preview the user looked at.
   */
  hooksApply: (install: boolean, fingerprint: string) =>
    callOrThrow<string>("hooks_apply", { install, fingerprint }),

  approvalDecision: (requestId: string, decision: "allow" | "deny") =>
    call<void>("approval_decision", { requestId, decision }),
  /** "The card is up" — until this lands the relay only waits a moment. */
  approvalAck: (requestId: string) => call<void>("approval_ack", { requestId }),
  /** "Nobody can act on this" — Claude Code asks in the terminal right away. */
  approvalDecline: (requestId: string) => call<void>("approval_decline", { requestId }),

  // ── Chat, files, secrets ──────────────────────────────────────────────────
  /** One chat turn. Routed by `CommandRouter` and always answers `{text}`. */
  chatSend: (query: string, context: ChatContext | null) =>
    callOrThrow<{ text: string }>("chat_send", { query, context }, () =>
      getApi().chatSend(query, context),
    ),
  chatReset: () => call<void>("chat_reset"),
  /** Copies a dropped file into the inbox. */
  ingestFile: (path: string) => callOrThrow<DroppedFile>("ingest_file", { path }),
  /** Only ever tells you whether a key exists — never its value. */
  secretPresent: (key: string) => call<boolean>("secret_present", { key }),
  secretSet: (key: string, value: string) => callOrThrow<void>("secret_set", { key, value }),
  secretClear: (key: string) => callOrThrow<void>("secret_clear", { key }),

  // ── Integrations ──────────────────────────────────────────────────────────
  refreshIntegration: (id: string) => call<void>("refresh_integration", { id }),
  /** Opens the configured n8n instance in the browser. */
  openN8n: () => call<void>("open_n8n"),

  /** Tray → Pause. Stops the integration pollers, not just the island. */
  setPaused: (paused: boolean) => call<void>("set_paused", { paused }),

  // ── Notes & Tasks (Sprint 6.1; Sprint 6.4 syncs the drawer) ────────────────
  getNotesJson: () =>
    call<string>("get_notes_json", {}, () => getApi().getNotesJson?.() as string),
  addNote: (text: string, isTask: boolean) => {
    const p = call<string>("add_note", { text, isTask }, () => getApi().addNote?.(text, isTask) as string);
    void p.then(() => notifyNotesUpdated());
    return p;
  },
  deleteNote: (id: number) => {
    const p = call<boolean>("delete_note", { id }, () => getApi().deleteNote?.(id) as boolean);
    void p.then(() => notifyNotesUpdated());
    return p;
  },
  toggleNote: (id: number) => {
    const p = call<boolean>("toggle_note", { id }, () => getApi().toggleNote?.(id) as boolean);
    void p.then(() => notifyNotesUpdated());
    return p;
  },
};

/** Files dragged onto the island. Android has no OLE drag-and-drop, so this is inert. */
export async function onDragDrop(_handler: (e: DragDropPayload) => void) {
  return () => {};
}

export async function onEvent<T>(name: string, handler: (payload: T) => void) {
  if (!IS_TAURI) return () => {};
  return listen<T>(name, (e) => handler(e.payload));
}

// Install the `CoucouAndroid` facade at module load, before any bridge call: the top
// bar's drag handle reads `window.CoucouAndroid` directly and must find it as soon as the
// page is alive, not only after `boot()` has run. A native interface, if Kotlin injected
// one, wins untouched.
if (typeof window !== "undefined") void getApi();
