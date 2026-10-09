// SPRINT 6.5 @Cline — Tasks drawer (WebUI lane only).
import { h, svg, clear } from "./dom";
import { ICONS } from "./icons";
import { Bridge } from "../core/bridge";
import { Sound } from "../core/sound";
import "./hub.css";
import { parseTasks, type HubActions } from "./hub-common";
import type { HubTask } from "./hub-helpers";

export function buildTasksView(_actions: HubActions) {
  let rows: HubTask[] = [];
  let loaded = false;
  const list = h("div", { class: "hub-list" });
  const empty = h("div", { class: "hub-empty" });
  empty.textContent = "No tasks yet — add one below.";
  const status = h("div", { class: "hub-sub" });
  status.textContent = "Syncing tasks…";
  const input = h("input", {
    placeholder: "New task…",
    enterkeyhint: "done",
    autocomplete: "off",
  }) as HTMLInputElement;
  async function refresh(): Promise<void> {
    try {
      const raw = await Bridge.getTasksJson();
      rows = parseTasks(typeof raw === "string" ? raw : null);
      rows = rows.filter((t) => t.isTask !== false);
      loaded = true;
      const open = rows.filter((t) => !t.isDone).length;
      status.textContent = rows.length === 0 ? "No tasks yet" : `${open} open`;
      render();
    } catch {
      if (!loaded) status.textContent = "Tasks unavailable.";
      render();
    }
  }
  async function toggle(id: number): Promise<void> {
    try {
      await Bridge.toggleNote(id);
      Sound.play("blip");
    } catch { /* optimistic */ }
    rows = rows.map((t) => (t.id === id ? { ...t, isDone: !t.isDone } : t));
    render();
    void refresh();
  }
  function render(): void {
    clear(list);
    const ordered = [...rows.filter((t) => !t.isDone), ...rows.filter((t) => t.isDone)];
    if (ordered.length === 0) {
      list.append(empty);
      return;
    }
    for (const t of ordered) {
      const id = t.id;
      const check = h("button", {
        class: `note-check${t.isDone ? " on" : ""}`,
        title: t.isDone ? "Mark not done" : "Mark done",
        onclick: () => void toggle(id),
      });
      if (t.isDone) check.append(svg(ICONS.check, 12, { stroke: 3 }));
      const label = h("span", { class: "note-text", text: t.text });
      const del = h("button", {
        class: "note-del", title: "Delete", text: "×",
        onclick: () => void remove(id),
      });
      list.append(h("div", { class: `hub-row${t.isDone ? " done" : ""}` }, check, label, del));
    }
  }
  async function remove(id: number): Promise<void> {
    try {
      await Bridge.deleteNote(id);
    } catch { /* optimistic */ }
    rows = rows.filter((t) => t.id !== id);
    render();
    void refresh();
  }
  async function submit(): Promise<void> {
    const text = input.value.trim();
    if (!text) return;
    input.value = "";
    try {
      await Bridge.addNote(text, true);
      Sound.play("pop");
    } catch { /* optimistic */ }
    void refresh();
  }
  const addBtn = h("button", { class: "hub-btn", text: "+ Add", onclick: () => void submit() });
  input.addEventListener("keydown", (e: KeyboardEvent) => {
    if (e.key !== "Enter") return;
    e.preventDefault();
    e.stopPropagation();
    void submit();
  });
  window.addEventListener("coucou:notes-updated", () => void refresh());
  const el = h("div", { class: "view" },
    h("div", { class: "card" },
      h("div", { class: "hub-card" },
        h("div", { class: "hub-title" }, svg(ICONS.checklist, 13), h("span", { text: "Tasks / Reminders" })),
        status, list,
        h("div", { class: "note-add" }, input, addBtn))));
  return { el, sync() { render(); void refresh(); } };
}
