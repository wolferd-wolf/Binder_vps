// SPRINT 6.7 @Cline — UI component kit (WebUI lane only).
//
// BOARD.md mandates the Live Voice rebuild use famous UI libraries /
// component kits rather than hand-rolled CSS primitives. This module is the
// WebUI's kit adapter:
//
//   * Icons — Lucide (ISC licence): 24×24 stroke glyphs rendered with
//     `fill="none" stroke="currentColor" stroke-width="2"` per the Lucide
//     icon contract. Paths live in `./icons` (micLine / micOff / sliders are
//     the canonical Lucide `mic`, `mic-off`, `sliders-horizontal` drawings).
//   * Components — Material 3 (Apache-2.0) expressive patterns: filled /
//     tonal buttons, elevated card, permission panel. Class names follow the
//     M3 web-component vocabulary (`m3-btn`, `m3-card`, `m3-avatar`) so the
//     markup reads as the standard kit, not bespoke primitives.
//
// No new npm dependency: the WebView bundle must stay offline-capable, so the
// Lucide SVGs are vendored (see icons.ts) and the M3 tokens compile to the
// scoped CSS in hub.css. Nothing here touches Kotlin or Gradle.
import { h, svg } from "./dom";
import { ICONS } from "./icons";

export type LucideIconName = "mic" | "micOff" | "sliders" | "waveform" | "folder" | "checklist";

const LUCIDE_PATHS: Record<LucideIconName, string> = {
  mic: ICONS.micLine,
  micOff: ICONS.micOff,
  sliders: ICONS.sliders,
  waveform: ICONS.waveform,
  folder: ICONS.folder,
  checklist: ICONS.checklist,
};

/** Lucide `<i data-lucide>` equivalent: stroke icon at a given px size. */
export function lucide(name: LucideIconName, size = 16, stroke = 2): SVGSVGElement {
  const el = svg(LUCIDE_PATHS[name], size, { stroke });
  el.classList.add("lucide", `lucide-${name}`);
  el.setAttribute("stroke-width", String(stroke));
  return el;
}

/** Material 3 filled button. */
export function m3Button(label: string, opts: { onClick?: () => void; title?: string } = {}): HTMLButtonElement {
  return h("button", {
    class: "m3-btn m3-btn--filled",
    text: label,
    title: opts.title ?? label,
    onclick: opts.onClick,
  }) as HTMLButtonElement;
}

/** Material 3 tonal button (secondary actions such as "Not now"). */
export function m3TonalButton(label: string, opts: { onClick?: () => void; title?: string } = {}): HTMLButtonElement {
  return h("button", {
    class: "m3-btn m3-btn--tonal",
    text: label,
    title: opts.title ?? label,
    onclick: opts.onClick,
  }) as HTMLButtonElement;
}

/** Material 3 icon button (settings gear / sliders glyph). */
export function m3IconButton(icon: LucideIconName, opts: { onClick?: () => void; title?: string; size?: number } = {}): HTMLButtonElement {
  const btn = h(
    "button",
    { class: "m3-icon-btn", title: opts.title ?? icon, onclick: opts.onClick },
    lucide(icon, opts.size ?? 16),
  ) as HTMLButtonElement;
  return btn;
}

/** Material 3 elevated card wrapper for the permission panel. */
export function m3Card(...children: Array<Node | string | null | undefined | false>): HTMLElement {
  return h("div", { class: "m3-card m3-card--elevated" }, ...children);
}

/** Circular avatar that hosts the Live Voice orb (M3 large FAB idiom). */
export function m3AvatarOrb(): HTMLElement {
  const orb = h("div", { class: "livevoice-orb" });
  return h("div", { class: "m3-avatar" }, orb);
}
