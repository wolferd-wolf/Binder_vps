// SPRINT 6.5 @Cline — Vault / File Drop (WebUI lane only).
import { h, svg, clear } from "./dom";
import { ICONS } from "./icons";
import { Bridge } from "../core/bridge";
import { Sound } from "../core/sound";
import "./hub.css";
import { type HubActions } from "./hub-common";
import { handlePickedFile, getLastPickedFile, formatSize } from "./hub-helpers";
import type { HubFile } from "./hub-helpers";

export function buildVaultView(_actions: HubActions) {
  let file: HubFile | null = getLastPickedFile();
  let renderedKey = "";
  const chipWrap = h("div", { class: "hub-list" });
  const hint = h("div", { class: "hub-sub" });
  hint.textContent = "Pick any document — the system picker opens outside the overlay.";
  function renderChip(): void {
    clear(chipWrap);
    if (!file) {
      const e = h("div", { class: "hub-empty" });
      e.textContent = "No file picked yet.";
      chipWrap.append(e);
      return;
    }
    const current = file;
    const meta = [formatSize(current.size), current.mimeType].filter(Boolean).join(" · ");
    const name = h("b", { text: current.name });
    const sub = h("div", { class: "hub-sub", text: meta || current.uri });
    const col = h("div", { style: "flex:1 1 auto;min-width:0" }, name, sub);
    chipWrap.append(h("div", { class: "hub-chip" }, svg(ICONS.doc, 18), col));
  }
  function pick(): void {
    try {
      const w = window as unknown as { IslandBridge?: { openFilePicker?: () => unknown } };
      if (typeof w.IslandBridge?.openFilePicker === "function") w.IslandBridge.openFilePicker();
      else void Bridge.openFilePicker();
      Sound.play("blip");
    } catch { /* best-effort until Kotlin lands */ }
  }
  window.addEventListener("coucou:file-selected", (e: Event) => {
    const raw = (e as CustomEvent<string>).detail;
    if (typeof raw === "string") {
      const next = handlePickedFile(raw);
      if (next) {
        file = next;
        renderChip();
      }
    } else {
      file = getLastPickedFile();
      renderChip();
    }
  });
  const btn = h("button", { class: "hub-btn", text: "Choose file", onclick: pick });
  // SPRINT 6.7 @Cline — vault-view root carries the 16/20 corner-inset gutter.
  const el = h("div", { class: "view vault-view" },
    h("div", { class: "card" },
      h("div", { class: "hub-card" },
        h("div", { class: "hub-title" }, svg(ICONS.folder, 13), h("span", { text: "Vault / File Drop" })),
        hint, chipWrap,
        h("div", { class: "hub-actions" }, btn))));
  renderChip();
  return {
    el,
    sync() {
      const latest = getLastPickedFile();
      const key = latest ? `${latest.uri}|${latest.size}|${latest.name}` : "none";
      if (key !== renderedKey) {
        renderedKey = key;
        file = latest;
        renderChip();
      }
    },
  };
}
