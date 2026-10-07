// Island geometry — ported from IslandTypes.swift + IslandWindowController.islandSize
// + IslandRootView.botPosition. All values are logical pixels, identical to the
// macOS app's points.

export type IslandMode = "hidden" | "compact" | "expanded";

export type IslandViewName =
  | "overview"
  | "empty"
  | "approval"
  | "question"
  | "error"
  | "finished"
  | "confused"
  | "upload"
  | "uploading"
  | "choose"
  | "mail"
  | "prompt"
  | "searching"
  | "result"
  | "note"
  | "settings"
  | "greeting";

export type BotStateName =
  | "idle"
  | "working"
  | "thinking"
  | "searching"
  | "approval"
  | "question"
  | "error"
  | "finished"
  | "ratelimit"
  | "sleeping"
  | "dizzy";

export type BotEmoteName = "love" | "surprised" | "proud" | "wink" | "yawn" | "happy" | "annoyed";

export type AgentLayoutMode = "none" | "grid" | "pills" | "column";

export interface ViewLayout {
  height: number;
  botX: number;
  botY: number | null; // null = auto-centred
  botDiameter: number;
  agentMode: AgentLayoutMode;
}

// On desktop the window is a fixed 720×320 (largest view) like the macOS panel; the
// island is drawn inside it, glued to the top edge and horizontally centred. On Android
// the window is the screen minus [PANEL_MARGIN] on each side, and this module measures
// the screen to match it.
export const PANEL_H = 320;

// No notch on a PC: these are the hidden/compact sizes from docs/SPEC.md.
export const NOTCH_W = 184;
export const NOTCH_H = 32;

/**
 * Panel margin per side, in CSS px — 16dp, so 32dp total.
 *
 * Must stay equal to `SCREEN_MARGIN_DP` in `IslandBridgeCommands.kt`: the overlay window
 * is sized natively as `screen − 2 × margin`, and this is what centres the island
 * inside that window. If the two drift, the island sits off-centre by the difference.
 */
export const PANEL_MARGIN = 16;

// Desktop defaults (used when screen width is not available)
const DESKTOP_COMPACT_W = 288; // NOTCH_W + 104
const DESKTOP_EXPANDED_W = 640;
const DESKTOP_PANEL_W = 720;
const DESKTOP_WAKE_STRIP_W = 240;

export const ROUNDED_CORNER = 14; // hidden / compact
export const EXPANDED_CORNER = 22;

export const WAKE_STRIP_H = 6;

/** Screen width in CSS px, i.e. dp on Android. Set by the host at boot. */
let screenWidth = 0;

/**
 * The width the host reported in `boot`, or 0 when there is no host (`npm run dev`).
 *
 * Held apart from [screenWidth] because the overlay window is sized *from* this: the
 * WebView's `innerWidth` is the window, and the window is derived from the screen, so
 * reading it back would close the loop and every rect would be measured against the
 * previous frame's window rather than the screen.
 */
let hostScreenWidth = 0;

/** Callbacks to notify when screen width changes. */
const screenWidthCallbacks: Array<(w: number) => void> = [];

/** Initialize screen width (call once on startup). */
export function setScreenWidth(w: number) {
  if (screenWidth === w) return;
  screenWidth = w;
  // Notify all callbacks
  for (const cb of screenWidthCallbacks) {
    try { cb(w); } catch { /* ignore */ }
  }
}

/**
 * Adopts the screen width the host reported in `boot`, in dp.
 *
 * A rotation re-reports through the same path, so the panel follows the screen rather
 * than whatever the window happens to be. Non-positive values are ignored: outside the
 * app there is no host, and the viewport is the fallback.
 */
export function setHostScreenWidth(w: number) {
  if (!(w > 0)) return;
  hostScreenWidth = w;
  setScreenWidth(w);
}

/** The host's screen width in dp, or 0 when there is no host. */
export function getHostScreenWidth(): number {
  return hostScreenWidth;
}

/** Subscribe to screen width changes. */
export function onScreenWidthChange(cb: (w: number) => void) {
  screenWidthCallbacks.push(cb);
  return () => {
    const idx = screenWidthCallbacks.indexOf(cb);
    if (idx >= 0) screenWidthCallbacks.splice(idx, 1);
  };
}

/** Get the panel width — the screen less its margins, capped at the desktop stage. */

// Backward compatibility exports (deprecated — use responsive getters above)
// These stay as desktop defaults for code that references them directly.
// For responsive widths that update on screen size change, use:
//   getPanelWidth(), getCompactWidth(), getExpandedWidth(), getWakeStripWidth()
// And subscribe with: onScreenWidthChange(cb => { ... })
export const PANEL_W = DESKTOP_PANEL_W;
export const COMPACT_W = DESKTOP_COMPACT_W;
export const EXPANDED_W = DESKTOP_EXPANDED_W;
export const WAKE_STRIP_W = DESKTOP_WAKE_STRIP_W;

function currentScreenWidth(): number {
  if (screenWidth > 0) return screenWidth;
  if (typeof window !== "undefined" && window.innerWidth > 0) {
    return window.innerWidth;
  }
  return DESKTOP_PANEL_W;
}

// Responsive getters (update when screen width changes via setScreenWidth or window.innerWidth)
// Use these in new code for phone-responsive layout
export function getPanelWidth(): number {
  const sw = currentScreenWidth();
  return Math.min(sw - 2 * PANEL_MARGIN, DESKTOP_PANEL_W);
}

export function getCompactWidth(): number {
  const sw = currentScreenWidth();
  return Math.min(Math.max(sw * 0.4, NOTCH_W + 24), DESKTOP_COMPACT_W);
}

export function getExpandedWidth(): number {
  const sw = currentScreenWidth();
  return Math.min(Math.max(sw - 2 * PANEL_MARGIN, 280), DESKTOP_EXPANDED_W);
}

export function getWakeStripWidth(): number {
  const sw = currentScreenWidth();
  return Math.min(sw * 0.5, DESKTOP_WAKE_STRIP_W);
}

// Re-export for convenience (used by island.ts panelSize getter)
export { getPanelWidth as panelWidth, getCompactWidth as compactWidth, getExpandedWidth as expandedWidth, getWakeStripWidth as wakeStripWidth };

export const VIEW_LAYOUTS: Record<IslandViewName, ViewLayout> = {
  overview: { height: 264, botX: 65, botY: 84, botDiameter: 58, agentMode: "pills" },
  empty: { height: 160, botX: 70, botY: null, botDiameter: 62, agentMode: "none" },
  approval: { height: 160, botX: 62, botY: null, botDiameter: 56, agentMode: "column" },
  question: { height: 160, botX: 62, botY: null, botDiameter: 56, agentMode: "column" },
  error: { height: 160, botX: 62, botY: null, botDiameter: 58, agentMode: "column" },
  finished: { height: 160, botX: 62, botY: null, botDiameter: 58, agentMode: "column" },
  confused: { height: 160, botX: 76, botY: null, botDiameter: 66, agentMode: "column" },
  upload: { height: 176, botX: 85, botY: 104, botDiameter: 62, agentMode: "column" },
  // botY 103 = bar top (42 + 58) + 3, so the dot really rides the bar. The Swift
  // layout says 118 while its own comment says 103; the comment matches the spec.
  uploading: { height: 176, botX: 46, botY: 103, botDiameter: 20, agentMode: "none" },
  choose: { height: 176, botX: 60, botY: 101, botDiameter: 52, agentMode: "column" },
  mail: { height: 240, botX: 56, botY: null, botDiameter: 46, agentMode: "column" },
  prompt: { height: 160, botX: 52, botY: null, botDiameter: 44, agentMode: "column" },
  searching: { height: 160, botX: 52, botY: null, botDiameter: 44, agentMode: "column" },
  result: { height: 160, botX: 52, botY: null, botDiameter: 44, agentMode: "column" },
  note: { height: 160, botX: 60, botY: null, botDiameter: 50, agentMode: "column" },
  settings: { height: 160, botX: 54, botY: null, botDiameter: 46, agentMode: "none" },
  greeting: { height: 175, botX: 180, botY: 85, botDiameter: 0, agentMode: "none" },
};

// The upload views above are only the fallback geometry. Once a file is actually
// dropped the whole sequence — Mochi included — is drawn by src/upload, which
// owns its own constants (USC) straight from UploadSequenceEngine.swift.

/** Chat view grows with the conversation — IslandContainer.chatPromptHeight. */
export function chatPromptHeight(messageCount: number): number {
  return Math.min(300, 240 + messageCount * 40);
}

export function islandSize(
  mode: IslandMode,
  view: IslandViewName,
  chatCount = 0,
): { w: number; h: number } {
  switch (mode) {
    case "hidden":
      // No notch to hide inside on a PC: the island retracts to zero height and
      // slides into the top edge of the screen instead of sitting there as a bar.
      return { w: NOTCH_W, h: 0 };
    case "compact":
      return { w: getCompactWidth(), h: NOTCH_H };
    case "expanded": {
      if (view === "greeting") {
        return { w: 360, h: 175 };
      }
      // For overview view: height matches content space (~264px) so nothing is cut off or overlaps
      const isOverview = view === "overview";
      const h = isOverview ? 264 : (view === "prompt" ? chatPromptHeight(chatCount) : VIEW_LAYOUTS[view].height);
      return { w: getExpandedWidth(), h };
    }
  }
}

export interface BotPlacement {
  cx: number;
  cy: number;
  diameter: number;
  opacity: number;
}

/** IslandRootView.botPosition — cy is measured from the island's top edge. */
export function botPosition(
  mode: IslandMode,
  view: IslandViewName,
  islandH: number,
  uploadProgress = 0,
): BotPlacement {
  switch (mode) {
    case "hidden":
      return { cx: 46, cy: 16, diameter: 6, opacity: 0 };
    case "compact":
      return { cx: 40, cy: 16, diameter: 20, opacity: 1 };
    case "expanded": {
      const layout = VIEW_LAYOUTS[view];
      if (view === "uploading") {
        return {
          cx: 36 + uploadProgress * 526,
          cy: layout.botY ?? 103,
          diameter: layout.botDiameter,
          opacity: 1,
        };
      }
      if (layout.botY != null) {
        return { cx: layout.botX, cy: layout.botY, diameter: layout.botDiameter, opacity: 1 };
      }
      // Centre of the fixed 84 pt card (8 pt top inset + 34 pt header → content at y = 42)
      const headerBottom = 42;
      const cardH = 84;
      const cy = headerBottom + (islandH - headerBottom - cardH) / 2 + cardH / 2;
      return { cx: layout.botX, cy, diameter: layout.botDiameter, opacity: 1 };
    }
  }
}

export function botGlowColor(s: BotStateName): string {
  switch (s) {
    case "working":
      return "#3B9EFF";
    case "thinking":
      return "#A78BFA";
    case "searching":
      return "#6366F1";
    case "approval":
      return "#F5A524";
    case "error":
      return "#F4505E";
    case "finished":
      return "#34D399";
    case "ratelimit":
      return "#F59E0B";
    default:
      return "#FFFFFF";
  }
}

export function botGlowOpacity(s: BotStateName): number {
  switch (s) {
    case "idle":
    case "sleeping":
      return 0.15;
    case "dizzy":
      return 0;
    default:
      return 0.65;
  }
}

// Project colours (IslandConst.projectColors)
const PROJECT_COLORS: Record<string, string> = {
  korus: "#FF5A4E",
  "sbe hub": "#2EC4A0",
  "morning ai brief": "#F29B38",
  "publication ig": "#7C5CFF",
  "ig post": "#7C5CFF",
  "louisraille.fr": "#38BDF8",
  louisraille: "#38BDF8",
  "notch buddy": "#EC4899",
  "notch-buddy": "#EC4899",
  notchbuddy: "#EC4899",
};

const FALLBACK_COLORS = ["#22C55E", "#EAB308", "#60A5FA", "#E879F9"];

export function colorForProject(name: string): string {
  const key = name.toLowerCase().trim();
  const exact = PROJECT_COLORS[key];
  if (exact) return exact;
  for (const [k, c] of Object.entries(PROJECT_COLORS)) {
    if (key.startsWith(k) || key.includes(k)) return c;
  }
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
  return FALLBACK_COLORS[Math.abs(hash) % FALLBACK_COLORS.length];
}

// Card wash colours (CardBackground.washColor)
export type Wash = "red" | "green" | "pink" | "amber" | "cyan" | "indigo" | "soft" | null;

export function washRGBA(wash: Wash): string {
  switch (wash) {
    case "red":
      return "rgba(244,80,94,0.55)";
    case "green":
      return "rgba(52,211,153,0.5)";
    case "pink":
      return "rgba(244,114,182,0.55)";
    case "amber":
      return "rgba(245,165,36,0.42)";
    case "cyan":
      return "rgba(34,211,238,0.38)";
    case "indigo":
      return "rgba(99,102,241,0.5)";
    case "soft":
      return "rgba(255,255,255,0.08)";
    default:
      return "rgba(0,0,0,0)";
  }
}
