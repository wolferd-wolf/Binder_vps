// Island views — DOM ports of IslandViewContent.swift. Paddings, font sizes,
// colours and wording are copied from the Swift views so both platforms read
// identically.

import { h, svg, clear, dot } from "./dom";
import { ICONS } from "./icons";
import { Ticker } from "./ticker";
import { State, type AgentTask } from "../core/state";
import { washRGBA, type IslandViewName, type Wash } from "../core/layout";
import { createMiniBot, pruneMiniBots } from "../mochi/minibots";
import { buildPrompt } from "./chat";
import { buildChoose, buildUpload, buildUploading } from "./upload";
import { renderIntegrationCard, type IntegrationCardHooks } from "./integrations";
import { Bridge } from "../core/bridge";
import { handleNoteEnter } from "./notes";
import { buildTasksView } from "./hub-tasks";
import { buildVaultView } from "./hub-vault";
import { buildLiveVoiceView } from "./hub-voice";

export interface ViewActions {
  setView(v: IslandViewName): void;
  collapse(): void;
  setFocus(id: string): void;
  openTerminal(): void;
  /** The ↗ button: opens whatever the focused pill points at. */
  openTarget(): void;
  openUrl(url: string): void;
  decide(d: "allow" | "deny"): void;
  toggleSound(): void;
  setVolume(v: number): void;
  dragStart(x: number): void;
  dragBy(dx: number): void;
  dragEnd(): void;
  setAutoClose(seconds: number): void;
  openSettingsWindow(): void;
  blip(): void;
}

export interface ViewHost {
  el: HTMLElement;
  sync(): void;
  /** Called when the view becomes active, for views with a text field. */
  focus?(): void;
  /** Called every frame while the view is on screen. */
  tick?(nowMs: number): void;
}

// ── Shared pieces ─────────────────────────────────────────────────────────────

function card(wash: Wash, ...children: (Node | string)[]): HTMLElement {
  const el = h("div", { class: wash ? "card wash" : "card" }, ...children);
  if (wash) el.style.setProperty("--wash", washRGBA(wash));
  return el;
}

function btn(
  label: string,
  kind: "primary" | "secondary",
  onClick: () => void,
  kbd?: string,
): HTMLElement {
  return h(
    "button",
    { class: `btn ${kind}`, onclick: onClick },
    h("span", { text: label }),
    kbd ? h("span", { class: "kbd", text: kbd }) : null,
  );
}

/** AgentWho — coloured dot + task name + grey label. */
function agentWho(task: AgentTask | null, label: string): HTMLElement {
  const row = h("div", { class: "who-row" });
  if (task) {
    row.append(dot(task.color, 8), h("span", { class: "n", text: task.name }));
  }
  row.append(h("span", { text: label }));
  return row;
}

function stack(padLeft: number, padRight: number, ...children: Node[]): HTMLElement {
  const el = h("div", { class: "stack" }, ...children);
  el.style.padding = `4px ${padRight}px 4px ${padLeft}px`;
  return el;
}

// ── Header ────────────────────────────────────────────────────────────────────

export function buildHeader(actions: ViewActions): ViewHost {
  const tabHome = h("button", { class: "tab", title: "Overview", onclick: () => go("overview") }, svg(ICONS.house, 13));
  const tabChat = h("button", { class: "tab", title: "Chat", onclick: () => go("chat") }, svg(ICONS.bubble, 13));
  const tabDrop = h("button", { class: "tab", title: "Drop", onclick: () => go("upload") }, svg(ICONS.plus, 13));

  const gearBtn = h("button", { title: "Settings", onclick: () => go("settings") }, svg(ICONS.gear, 14));
  const soundBtn = h("button", { title: "Mute", onclick: () => actions.toggleSound() }, svg(ICONS.speakerOn, 14));

  function go(v: IslandViewName) {
    actions.blip();
    actions.setView(v);
  }

  const el = h(
    "div",
    { id: "header" },
    h("div", { class: "tabs" }, tabHome, tabChat, tabDrop),
    h("div", { class: "header-actions" }, gearBtn, soundBtn),
  );

  return {
    el,
    sync() {
      const v = State.view;
      tabHome.classList.toggle("on", v === "overview" || v === "empty");
      tabHome.style.display = v === "prompt" ? "none" : "";
      tabChat.classList.toggle("on", v === "chat");
      tabDrop.classList.toggle("on", v === "upload");
      gearBtn.classList.toggle("on", v === "settings");
      clear(gearBtn);
      gearBtn.append(svg(v === "settings" ? ICONS.gearFill : ICONS.gear, 14));
      clear(soundBtn);
      soundBtn.append(svg(State.settings.soundEnabled ? ICONS.speakerOn : ICONS.speakerOff, 14));
      el.style.opacity = v === "confused" ? "0" : "1";
    },
  };
}

// ── Overview ──────────────────────────────────────────────────────────────────

function buildOverview(actions: ViewActions): ViewHost {
  const ticker = new Ticker();
  const who = h("div", { class: "who" });
  const tickerBody = h("div", { class: "card-body" }, who, ticker.el);
  const leftBody = h("div", { class: "left-body" });
  const jump = h(
    "button",
    { class: "icon-btn jump", title: "Open", onclick: () => actions.openTarget() },
    svg(ICONS.arrowUpRight, 8),
  );
  const left = card(null, leftBody, jump);
  const pills = h("div", { class: "pills" });
  const right = card(null, pills);

  const el = h("div", { class: "view overview" },
    h("div", { class: "left" }, left),
    h("div", { class: "right" }, right),
  );

  let pillIds = "";
  let detailOpen = false;
  let lastFocus: string | null = null;
  let mode: "ticker" | "card" | null = null;
  let cardKey = "";

  const hooks: IntegrationCardHooks = {
    get detailOpen() {
      return detailOpen;
    },
    openDetail() {
      detailOpen = true;
      cardKey = "";
      State.notify();
    },
    closeDetail() {
      detailOpen = false;
      cardKey = "";
      State.notify();
    },
    openSettings: () => actions.openSettingsWindow(),
  };

  return {
    el,
    tick(nowMs: number) {
      if (mode === "ticker") ticker.tick(nowMs);
    },
    sync() {
      const task = State.focusTask;
      if (task?.id !== lastFocus) {
        lastFocus = task?.id ?? null;
        detailOpen = false;
        cardKey = "";
        mode = null;
      }

      // VS Code with a live Claude Code session keeps the ticker; every other
      // pill shows its own card, exactly like IntegrationCardView.
      const sessionActive =
        task?.id === "integration_claude" && (task.state !== "idle" || task.steps.length > 0);

      if (task && sessionActive) {
        if (mode !== "ticker") {
          clear(leftBody);
          leftBody.append(tickerBody);
          mode = "ticker";
          cardKey = "";
        }
        clear(who);
        who.append(
          dot(task.color, 7),
          h("span", { class: "name", text: task.name }),
          h("span", { class: "tool", text: task.source === "claudeCode" ? "Claude Code" : "n8n" }),
        );
        if (task.steps.length > 1) {
          who.append(h("span", {
            class: "count",
            text: `${Math.min(task.stepIndex + 1, task.steps.length)}/${task.steps.length}`,
          }));
        }
        ticker.sync(task);
      } else if (task) {
        const info = State.integrations[task.id];
        const key = [
          task.id, detailOpen, task.state, task.steps.join("|"),
          info?.loaded, info?.error, info?.configured,
          JSON.stringify(info?.data ?? {}),
        ].join("~");
        if (key !== cardKey) {
          cardKey = key;
          mode = "card";
          clear(leftBody);
          leftBody.append(renderIntegrationCard(task, hooks));
        }
      }

      jump.style.display = detailOpen ? "none" : "";

      const others = State.otherTasks.slice(0, 4);
      const pillKey = others.map((t) => `${t.id}:${t.pillBadge ?? ""}`).join("|");
      if (pillKey !== pillIds) {
        pillIds = pillKey;
        clear(pills);
        for (const t of others) pills.append(buildPill(t, actions));
        pruneMiniBots();
      }
    },
  };
}

function hubPillLabel(id: string, fallback: string): string {
  if (id === "integration_resend") return "Tasks / Reminders";
  if (id === "integration_n8n") return "Vault / File Drop";
  if (id === "integration_notes") return "Notes";
  if (id === "integration_github") return "Live Voice Mode";
  return fallback;
}

function buildPill(task: AgentTask, actions: ViewActions): HTMLElement {
  const isNotes = task.id === "integration_notes";
  const label = task.id === "integration_claude" ? "VS Code" : task.name;
  const canvas = createMiniBot(task, 24);
  // SPRINT 6.5 @Buffy glyphs (fill-mode, plain svg() call, no stroke opt).
  const hubIcon =
    task.id === "integration_resend"
      ? svg(ICONS.checklist, 13)
      : task.id === "integration_n8n"
        ? svg(ICONS.folder, 13)
        : task.id === "integration_github"
          ? svg(ICONS.mic, 13)
          : task.id === "integration_notes"
            ? svg(ICONS.note, 13)
            : null;
  // SPRINT 6.6 @Buffy — the secondary indicator sits on the pill's far-right
  // edge (`.pill > svg { margin-left: auto }` in style.css); only the mini
  // Mochi stays left. The class is the styling hook for that rule.
  if (hubIcon) hubIcon.classList.add("pill-icon");
  const pill = h(
    "div",
    {
      class: "pill",
      onclick: () => {
        // SPRINT 6.5 @Cline — Assistant Hub routing (WebUI lane): the fixed
        // 4-pill overhaul maps historic integration ids onto hub views.
        // Notes keeps its existing view; Tasks/Vault/LiveVoice open the new
        // hub drawers. Unknown pills keep the old focus behaviour.
        if (isNotes) {
          actions.setView("note");
        } else if (task.id === "integration_resend") {
          actions.setView("tasks");
        } else if (task.id === "integration_n8n") {
          actions.setView("vault");
          try {
            const w = window as unknown as {
              IslandBridge?: { openFilePicker?: () => unknown };
            };
            if (typeof w.IslandBridge?.openFilePicker === "function") {
              w.IslandBridge.openFilePicker();
            } else {
              void Bridge.openFilePicker();
            }
          } catch {
            /* picker is best-effort until OpenCode's Kotlin lane lands */
          }
        } else if (task.id === "integration_github") {
          actions.setView("livevoice");
        } else {
          actions.setFocus(task.id);
        }
      },
    },
    canvas,
    h("span", { class: "lbl", text: hubPillLabel(task.id, label) }),
    hubIcon,
  );
  pill.style.borderColor = `${task.color}24`;
  pill.addEventListener("mouseenter", () => {
    pill.style.background = `${task.color}2e`;
    pill.style.borderColor = `${task.color}8c`;
    pill.style.boxShadow = `0 2px 10px ${task.color}59`;
    (pill.querySelector(".lbl") as HTMLElement).style.color = lighten(task.color, 0.3);
  });
  pill.addEventListener("mouseleave", () => {
    pill.style.background = "";
    pill.style.borderColor = `${task.color}24`;
    pill.style.boxShadow = "";
    (pill.querySelector(".lbl") as HTMLElement).style.color = "";
  });

  if (task.pillBadge) {
    const colors = { approval: "#F5A524", finished: "#22C55E", error: "#F4505E" } as const;
    const icons = { approval: ICONS.bang, finished: ICONS.check, error: ICONS.xmark } as const;
    const inner = h("i", { style: `background:${colors[task.pillBadge]}` }, svg(icons[task.pillBadge], 6, { stroke: task.pillBadge === "finished" ? 3 : 0 }));
    const badge = h("div", { class: "pill-badge" }, inner);
    badge.style.boxShadow = `0 0 4px ${colors[task.pillBadge]}99`;
    pill.append(badge);
    // The badge owns the trailing corner: step the icon left of it so both
    // indicators stay on the far right without overlapping.
    if (hubIcon) hubIcon.style.marginRight = "20px";
  }
  return pill;
}


function lighten(hex: string, amount: number): string {
  const v = parseInt(hex.replace("#", ""), 16);
  const c = [(v >> 16) & 255, (v >> 8) & 255, v & 255].map((x) =>
    Math.min(255, Math.round(x + amount * 255)),
  );
  return `rgb(${c[0]},${c[1]},${c[2]})`;
}

// ── Empty ─────────────────────────────────────────────────────────────────────

function buildEmpty(actions: ViewActions): ViewHost {
  const body = h(
    "div",
    { class: "stack", style: "padding:0 18px 0 118px;flex-direction:row;align-items:center;gap:16px" },
    h(
      "div",
      { style: "display:flex;flex-direction:column;gap:5px" },
      h("div", { class: "title", text: "Nothing running right now." }),
      h("div", { class: "sub", text: "Drop a file or window, or ask me anything." }),
    ),
    h("div", { class: "grow" }),
    btn("Ask Claude", "primary", () => actions.setView("prompt")),
  );
  return { el: h("div", { class: "view" }, card(null, body)), sync() {} };
}

// ── Approval ──────────────────────────────────────────────────────────────────

function buildApproval(actions: ViewActions): ViewHost {
  const who = h("div");
  const code = h("div", { class: "code" });
  const row = h("div", { class: "actions" });
  const el = h("div", { class: "view" }, card("amber", stack(116, 16, who, code, row)));
  let rowKey = "";
  return {
    el,
    sync() {
      clear(who);
      who.append(agentWho(State.focusTask, "needs permission"));
      // The whole point of approving here rather than in the terminal: this line
      // is the command, the file path or the URL being authorised, not just the
      // name of the tool asking.
      code.textContent = State.pendingApproval?.command || State.pendingApproval?.tool || "…";
      // Two buttons, built once. Rebuilding them between a mouse-down and a
      // mouse-up would swallow the click, and there is nothing left to vary:
      // "Always" is gone until the remembered-rules list exists to back it.
      if (rowKey === "built") return;
      rowKey = "built";
      clear(row);
      row.append(
        btn("Deny", "secondary", () => actions.decide("deny"), "N"),
        btn("Allow", "primary", () => actions.decide("allow"), "Y"),
      );
    },
  };
}

// ── Question ──────────────────────────────────────────────────────────────────

function buildQuestion(): ViewHost {
  const who = h("div");
  const title = h("div", { class: "title" });
  const row = h("div", { class: "actions" });
  const el = h("div", { class: "view" }, card("cyan", stack(116, 16, who, title, row)));
  return {
    el,
    sync() {
      clear(who);
      who.append(agentWho(State.focusTask, "Claude Code is asking a question"));
      const task = State.focusTask;
      title.textContent = task?.steps.at(-1) ?? "Claude needs an answer.";
      clear(row);
      row.append(h("div", { class: "sub", text: "Answer in your terminal — Coucou can't reply for you yet." }));
    },
  };
}

// ── Error ─────────────────────────────────────────────────────────────────────

function buildError(actions: ViewActions): ViewHost {
  const who = h("div");
  const title = h("div", { class: "title", text: "Workflow stopped." });
  const detail = h("div", { class: "detail" });
  const row = h("div", { class: "actions" },
    btn("Retry", "primary", () => actions.setView(State.defaultView())),
    btn("Open in n8n", "secondary", () => actions.openUrl("")),
  );
  const el = h("div", { class: "view" }, card("red", stack(116, 16, who, title, detail, row)));
  return {
    el,
    sync() {
      const task = State.focusTask;
      clear(who);
      who.append(agentWho(task, task?.source === "n8n" ? "n8n" : "Claude Code"));
      title.textContent = task?.source === "n8n" ? "Workflow stopped." : "Session stopped on an error.";
      detail.textContent = task?.steps.at(-1) ?? "No detail available.";
    },
  };
}

// ── Finished ──────────────────────────────────────────────────────────────────

function buildFinished(actions: ViewActions): ViewHost {
  const who = h("div");
  const title = h("div", { class: "title" });
  const row = h("div", { class: "actions" },
    btn("Open terminal", "primary", () => actions.openTerminal()),
    btn("OK", "secondary", () => actions.collapse()),
  );
  const el = h("div", { class: "view" }, card("green", stack(116, 16, who, title, row)));
  return {
    el,
    sync() {
      clear(who);
      who.append(agentWho(State.focusTask, "Claude Code finished"));
      title.textContent = State.focusTask?.steps.at(-1) ?? "Session finished";
    },
  };
}

// ── Confused ──────────────────────────────────────────────────────────────────

function buildConfused(): ViewHost {
  const body = h(
    "div",
    { class: "stack", style: "padding:0 18px 0 128px" },
    h("div", { class: "title", text: "Too many hits at once." }),
    h("div", { class: "sub", text: "Give me a sec — back to work in three seconds." }),
  );
  return { el: h("div", { class: "view" }, card("pink", body)), sync() {} };
}

// ── Note / Tasks Drawer (Sprint 6.1; Sprint 6.4 syncs it with TaskStore) ──────

interface NoteItem {
  id: number;
  text: string;
  isDone?: boolean;
}

interface RawNote {
  id?: unknown;
  text?: unknown;
  isDone?: unknown;
  isTask?: unknown;
}

/**
 * SPRINT 6.4 @Cline — coerce one host payload into renderable notes.
 * Accepts the Kotlin `[{id,text,isDone}]` array (or the `{ok,value}` envelope
 * arriving un-unwrapped); returns null when the payload is unusable so the
 * caller keeps the previous list instead of blanking it.
 */
function coerceNotes(arr: unknown[]): NoteItem[] {
  const out: NoteItem[] = [];
  for (const n of arr) {
    const r = n as RawNote;
    if (!r || typeof r !== "object") continue;
    const id = Number(r.id);
    const text = String(r.text ?? "");
    if (!Number.isFinite(id) || !text) continue;
    out.push({ id, text, isDone: r.isDone === true || r.isTask === true });
  }
  return out;
}

function parseNotesJson(raw: unknown): NoteItem[] | null {
  try {
    let text = raw as string;
    if (typeof text === "string" && text.trimStart().startsWith("{")) {
      const env = JSON.parse(text) as { ok?: boolean; value?: unknown };
      if (env && typeof env === "object" && "value" in env) {
        if (typeof env.value === "string") text = env.value;
        else if (Array.isArray(env.value)) return coerceNotes(env.value);
      }
    }
    if (typeof text !== "string") return null;
    const parsed: unknown = JSON.parse(text);
    if (!Array.isArray(parsed)) return null;
    return coerceNotes(parsed);
  } catch {
    return null;
  }
}

/** Spec'd fallback probes for hosts that predate the Sprint 3 REDO facade. */
function probeLegacyNotesJson(): string | null {
  try {
    const w = window as unknown as {
      IslandBridge?: { getNotesJson?: () => unknown };
      CoucouNative?: { invoke?: (cmd: string, argsJson?: string) => unknown };
    };
    const direct = w.IslandBridge?.getNotesJson?.();
    if (typeof direct === "string" && direct) return direct;
    const viaInvoke = w.CoucouNative?.invoke?.("get_notes", "{}");
    if (typeof viaInvoke === "string" && viaInvoke) return viaInvoke;
  } catch {
    /* host not ready — caller keeps the previous list */
  }
  return null;
}

function buildNotesView(actions: ViewActions): ViewHost {
  const listEl = h("div", { class: "notes-list" });
  const inputEl = h("input", {
    type: "text",
    placeholder: "+ Add a note or task…",
  }) as HTMLInputElement;
  handleNoteEnter(inputEl, async () => {
    void refreshNotes();
  });

  const drawer = h(
    "div",
    { class: "notes-drawer" },
    h("div", { class: "note-add" }, inputEl),
    listEl,
  );

  const header = h(
    "div",
    {
      style: "display:flex;align-items:center;gap:8px;padding:4px 8px;margin-bottom:4px;cursor:pointer;",
      onclick: () => actions.setView("overview"),
    },
    svg(ICONS.chevronLeft, 12),
    h("span", { style: "font:600 12px var(--font);color:var(--ink);", text: "Notes & Tasks" }),
  );

  const el = h("div", { class: "view notes-view" }, card(null, h("div", { class: "stack", style: "padding:8px 12px;height:100%;display:flex;flex-direction:column;box-sizing:border-box;" }, header, drawer)));

  function renderNotes(notes: NoteItem[]) {
    clear(listEl);
    if (notes.length === 0) {
      listEl.append(h("div", { class: "note-empty", text: "No saved notes yet" }));
      return;
    }
    for (const item of notes) {
      const row = h(
        "div",
        { class: `note-row${item.isDone ? " done" : ""}` },
        h(
          "button",
          {
            class: `note-check${item.isDone ? " on" : ""}`,
            onclick: async () => {
              await Bridge.toggleNote(item.id);
              void refreshNotes();
            },
          },
          item.isDone ? svg(ICONS.check, 12, { stroke: 3 }) : h("span", {}),
        ),
        h("span", { class: "note-text", text: item.text }),
        h(
          "button",
          {
            class: "note-del",
            onclick: async () => {
              await Bridge.deleteNote(item.id);
              void refreshNotes();
            },
          },
          svg(ICONS.trash, 12),
        ),
      );
      listEl.append(row);
    }
  }

  function renderEmpty(hint: string) {
    clear(listEl);
    listEl.append(h("div", { class: "note-empty", text: hint }));
  }

  // SPRINT 6.4 @Cline — mount/show fetch: Bridge facade first (named method or
  // CoucouNative.invoke), then the spec'd legacy probes. A failed load keeps
  // the previous list so a transient host hiccup never blanks the drawer.
  const refreshNotes = async () => {
    let parsed: NoteItem[] | null = null;
    try {
      parsed = parseNotesJson(await Bridge.getNotesJson());
    } catch {
      parsed = null;
    }
    if (parsed == null) parsed = parseNotesJson(probeLegacyNotesJson());
    if (parsed == null) return; // keep previous list; host not ready yet
    renderNotes(parsed);
  };

  if (typeof window !== "undefined") {
    // SPRINT 6.4 @Cline — immediate re-fetch when a note lands via chat or the
    // input bar. Tolerates the host object arriving after this view is built.
    const w = window as unknown as {
      CoucouAndroid?: { onNotesUpdated?: () => void };
    };
    const hookRefresh = () => {
      const host = w.CoucouAndroid;
      if (!host || (host as { __notesHooked?: boolean }).__notesHooked) return;
      (host as { __notesHooked?: boolean }).__notesHooked = true;
      const prev = host.onNotesUpdated;
      host.onNotesUpdated = () => {
        if (typeof prev === "function") prev();
        void refreshNotes();
      };
    };
    hookRefresh();
    window.addEventListener("coucou:notes-updated", () => void refreshNotes());
    // Poll-once for a late-injected host (first show wins, then self-cancels).
    const t = window.setInterval(() => {
      if (w.CoucouAndroid) {
        hookRefresh();
        window.clearInterval(t);
      }
    }, 500);
    window.setTimeout(() => window.clearInterval(t), 10_000);
    // First paint: preserve @Buffy's card aesthetic until the host answers.
    renderEmpty("No saved notes yet");
    void refreshNotes();
  }

  return {
    el,
    sync() {
      // Re-fetch every time the drawer becomes visible — the "show" half of
      // mount/show, so notes added in MainActivity appear on next open.
      void refreshNotes();
    },
  };
}

// ── In-island settings ────────────────────────────────────────────────────────

function buildSettings(actions: ViewActions): ViewHost {
  const soundSwitch = h("button", { class: "switch", onclick: () => actions.toggleSound() });
  const volume = h("input", {
    type: "range", min: "0", max: "0.2", step: "0.005",
    oninput: (e: Event) => actions.setVolume(Number((e.target as HTMLInputElement).value)),
  }) as HTMLInputElement;
  const autoLabel = h("span", {});
  const segButtons = [10, 15, 30].map((s) =>
    h("button", { onclick: () => actions.setAutoClose(s) }, `${s}s`),
  );
  const claudeBadge = h("span", { class: "status-badge" });
  const apiBadge = h("span", { class: "status-badge" });

  const rows = h(
    "div",
    { class: "settings-rows" },
    h("div", { class: "settings-row" }, soundSwitch, h("span", { text: "Sound" }), volume),
    h(
      "div",
      { class: "settings-row" },
      svg(ICONS.timer, 12),
      autoLabel,
      h("div", { class: "seg" }, ...segButtons),
    ),
    h(
      "div",
      { class: "settings-row", style: "gap:14px" },
      claudeBadge,
      apiBadge,
      h("div", { class: "grow" }),
      h("button", {
        class: "link-btn",
        style: "color:#8e939c;font-size:11.5px",
        text: "Settings…",
        onclick: () => actions.openSettingsWindow(),
      }),
    ),
  );

  const el = h("div", { class: "view" },
    card(null, h("div", { class: "stack", style: "padding:14px 16px 14px 84px" }, rows)));

  return {
    el,
    sync() {
      const s = State.settings;
      soundSwitch.classList.toggle("on", s.soundEnabled);
      volume.value = String(s.soundVolume);
      volume.style.opacity = s.soundEnabled ? "1" : "0.4";
      autoLabel.textContent = `Auto-close · ${Math.round(s.autoCloseInterval)}s`;
      segButtons.forEach((b, i) => b.classList.toggle("on", s.autoCloseInterval === [10, 15, 30][i]));
      clear(claudeBadge);
      claudeBadge.append(
        dot(s.hooksInstalled ? "#22C55E" : "#F4505E", 6),
        h("span", { text: "Claude Code" }),
      );
      clear(apiBadge);
      apiBadge.append(dot("#F4505E", 6), h("span", { text: "API" }));
    },
  };
}

// ── Placeholders filled in later stages ───────────────────────────────────────

function buildPlaceholder(title: string, sub: string): ViewHost {
  const body = h(
    "div",
    { class: "stack", style: "padding:0 18px 0 118px" },
    h("div", { class: "title", text: title }),
    h("div", { class: "sub", text: sub }),
  );
  return { el: h("div", { class: "view" }, card(null, body)), sync() {} };
}

// ── Registry ──────────────────────────────────────────────────────────────────

export function buildViews(
  actions: ViewActions,
  onChatHeightChange: () => void,
): Map<IslandViewName, ViewHost> {
  const map = new Map<IslandViewName, ViewHost>();
  map.set("overview", buildOverview(actions));
  map.set("empty", buildEmpty(actions));
  map.set("approval", buildApproval(actions));
  map.set("question", buildQuestion());
  map.set("error", buildError(actions));
  map.set("finished", buildFinished(actions));
  map.set("confused", buildConfused());
  map.set("note", buildNotesView(actions));
  // SPRINT 6.5 @Cline — Assistant Hub drawers (fixed 4-pill overhaul).
  map.set("tasks", buildTasksView(actions));
  map.set("vault", buildVaultView(actions));
  map.set("livevoice", buildLiveVoiceView(actions));
  map.set("settings", buildSettings(actions));
  const promptView = buildPrompt(onChatHeightChange);
  map.set("prompt", promptView);
  map.set("chat", promptView);
  map.set("upload", buildUpload());
  map.set("uploading", buildUploading());
  map.set("choose", buildChoose(actions));
  // Not in the Windows v1: sending a file by email, window attach + web result.
  map.set("mail", buildPlaceholder("Sending by email isn't in this version.", ""));
  map.set("searching", buildPlaceholder("Claude is searching…", ""));
  map.set("result", buildPlaceholder("Result", ""));
  return map;
}
