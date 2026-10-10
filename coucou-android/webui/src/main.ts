// Entry point: boot the bridge, wire the island, start the greeting.

import "./style.css";
import { Bridge, IS_TAURI, onEvent } from "./core/bridge";
import { Sound } from "./core/sound";
import { State, type Settings } from "./core/state";
import { Island } from "./island/island";
import { registerHookHandlers } from "./island/hooks";
import { registerIntegrationHandlers, refreshConfigured } from "./island/integrations";
import { handlePickedFile } from "./views/hub-helpers";

// SPRINT 6.5 @Cline — BOARD contract: `window.IslandBridge.openFilePicker()` /
// `getTasksJson()`, and `window.CoucouAndroid.onFileSelected(...)` for the SAF
// result. Kotlin injects `CoucouAndroid` itself; these aliases only add the
// *names* BOARD specifies when the host has not provided them, so pills work
// in `npm run dev` and light up fully once OpenCode's lane lands.
function installHubAliases(): void {
  try {
    const w = window as unknown as {
      IslandBridge?: Record<string, unknown>;
      CoucouAndroid?: Record<string, unknown>;
    };
    if (!w.IslandBridge) w.IslandBridge = {};
    const bridge = w.IslandBridge;
    if (typeof bridge["openFilePicker"] !== "function") {
      bridge["openFilePicker"] = () => Bridge.openFilePicker();
    }
    if (typeof bridge["getTasksJson"] !== "function") {
      bridge["getTasksJson"] = () => Bridge.getTasksJson();
    }
    if (typeof bridge["toggleNote"] !== "function") {
      bridge["toggleNote"] = (id: number) => Bridge.toggleNote(Number(id));
    }
    // SPRINT 6.7 — Live Voice mic hook. Kotlin exposes the named
    // `requestMicPermission` method (transparent activity → native prompt);
    // this alias covers the dev/desktop path.
    if (typeof bridge["requestMicPermission"] !== "function") {
      bridge["requestMicPermission"] = () => Bridge.requestMicPermission();
    }
    if (!w.CoucouAndroid) w.CoucouAndroid = {};
    const android = w.CoucouAndroid;
    if (typeof android["onFileSelected"] !== "function") {
      android["onFileSelected"] = (payload: string) => {
        handlePickedFile(payload);
      };
    }
    // SPRINT 6.7 — Kotlin's answer to the RECORD_AUDIO prompt lands here and is
    // re-broadcast as a window event the Live Voice view listens for.
    if (typeof android["onMicPermission"] !== "function") {
      android["onMicPermission"] = (granted: boolean) => {
        window.dispatchEvent(new CustomEvent("coucou:mic-permission", { detail: !!granted }));
      };
    }
  } catch {
    /* aliases are best-effort; direct Bridge calls cover the rest */
  }
}

installHubAliases();

async function main() {
  const root = document.getElementById("root");
  if (!root) return;

  void Sound.preload();

  // Boot first: `screen.width` is the screen in dp, and the whole island geometry is
  // measured from it. The WebView viewport cannot stand in for that — it is the overlay
  // window, which is itself sized from the screen, so it closes the loop instead.
  const boot = await Bridge.boot();
  if (boot) {
    State.settings = { ...State.settings, ...boot.settings };
  }

  const island = new Island(root, boot?.screen.width ?? 0);
  island.applySettings();
  State.loadIntegrationTasks();
  if (boot && !boot.cursorPoll) island.followPageCursor();

  await onEvent<{ x: number; y: number }>("cursor", ({ x, y }) => island.onCursor(x, y));

  /** Pause has to reach Rust too, or the pollers keep calling out. */
  const setPaused = (on: boolean) => {
    if (State.paused === on) return;
    State.paused = on;
    void Bridge.setPaused(on);
  };

  await onEvent<string>("tray", (what) => {
    switch (what) {
      case "settings":
        setPaused(false);
        island.alert("settings");
        break;
      case "open":
        setPaused(false);
        island.alert(State.defaultView());
        break;
      case "pause":
        setPaused(!State.paused);
        if (State.paused) island.fsm.forceHidden();
        else island.reveal();
        break;
    }
  });

  await onEvent<null>("screen-changed", () => void Bridge.reposition());

  // The settings window writes preferences; apply them here without a restart.
  await onEvent<Settings>("settings-changed", (s) => {
    State.settings = { ...State.settings, ...s };
    island.applySettings();
    State.loadIntegrationTasks();
    void refreshConfigured();
  });

  registerHookHandlers(island);
  registerIntegrationHandlers(island);

  island.launch();

  // Expose for testing and automation
  const w = window as unknown as {
    __coucouIsland: Island;
    __coucouState: typeof State;
    // SPRINT 6.3 @Cline — spec'd touch hook: chat touch listeners call
    // `window.CoucouEngine?.setTargetLook?.(x, y)`; wire it to the live island
    // so eyes track fingers even though the engine has no such method itself.
    CoucouEngine?: { setTargetLook?: (x: number, y: number) => void };
  };
  w.__coucouIsland = island;
  w.__coucouState = State;
  w.CoucouEngine = { setTargetLook: (x, y) => island.setTargetLook(x, y) };

  // In a plain browser there is no wake strip behind the cursor: make the whole
  // page wake the island so the visuals can be checked with `npm run dev`.
  if (!IS_TAURI) {
    document.addEventListener("click", () => Sound.resume(), { once: true });
  }
}

void main();
