/*
 * Verifies `tauri-shim.js` against the contract upstream's bundle actually uses,
 * without an emulator: the internals probe, the Promise envelope, and a full
 * `listen()` → `emit()` round trip with the real command strings from
 * `@tauri-apps/api`.
 *
 *   node tools/verify-shim.mjs
 *
 * Exits non-zero on the first broken expectation.
 */
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const shim = readFileSync(join(here, "tauri-shim.js"), "utf8");

const failures = [];
function check(name, condition, detail = "") {
  if (condition) {
    console.log(`  ok   ${name}`);
  } else {
    console.log(`  FAIL ${name} ${detail}`);
    failures.push(name);
  }
}

/** A fake native side: answers `boot`, records everything else. */
const calls = [];
const window = {
  CoucouNative: {
    invoke(command, argsJson) {
      calls.push({ command, args: JSON.parse(argsJson) });
      if (command === "boot") {
        return JSON.stringify({ ok: true, value: { settings: { soundEnabled: true } } });
      }
      if (command === "chat_send") {
        return JSON.stringify({ ok: true, value: { text: "Opened Chrome" } });
      }
      return JSON.stringify({ ok: false, error: "API key missing." });
    }
  }
};
const sandbox = { window, console, Promise, JSON };
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(shim, sandbox, { filename: "tauri-shim.js" });

console.log("shim contract");

// 1. The probe `core/bridge.ts` evaluates at module load.
check("__TAURI_INTERNALS__ exists", "__TAURI_INTERNALS__" in window);
check("metadata.currentWebview.label", window.__TAURI_INTERNALS__.metadata.currentWebview.label === "main");
check("event plugin internals", typeof window.__TAURI_EVENT_PLUGIN_INTERNALS__.unregisterListener === "function");

// 2. invoke settles the Promise from the envelope.
const boot = await window.__TAURI_INTERNALS__.invoke("boot", {});
check("boot resolves the payload", (await boot).settings.soundEnabled === true);

const chat = await window.__TAURI_INTERNALS__.invoke("chat_send", { query: "chrome", context: null });
check("chat resolves {text}", chat.text === "Opened Chrome", JSON.stringify(chat));

let rejected = null;
await window.__TAURI_INTERNALS__.invoke("open_url", { url: "x" }).catch((error) => (rejected = error));
check("an error envelope rejects", rejected === "API key missing.");

let threw = null;
await window.__TAURI_INTERNALS__.invoke("save_settings", {}).catch((error) => (threw = error));
check("save_settings rejects too", threw === "API key missing.");

// 3. A real `listen()`, wired the way @tauri-apps/api/event does it, then an emit.
const received = [];
const handlerId = window.__TAURI_INTERNALS__.transformCallback((event) => received.push(event), false);
const eventId = await window.__TAURI_INTERNALS__.invoke("plugin:event|listen", {
  event: "tray",
  target: { kind: "Any" },
  handler: handlerId
});
check("listen resolves an event id", typeof eventId === "number");

window.CoucouIsland.showHome();
check("showHome delivered one event", received.length === 1);
check("payload is the tray string", received[0]?.payload === "open", JSON.stringify(received[0]));
check("event name matches", received[0]?.event === "tray");

window.CoucouIsland.emit("settings-changed", JSON.stringify({ soundEnabled: false }));
check("a second event reaches its own listener", received.length === 1);

// 4. unlisten removes the callback.
await window.__TAURI_INTERNALS__.invoke("plugin:event|unlisten", { event: "tray", eventId });
window.CoucouIsland.showHome();
check("unlisten detaches the listener", received.length === 1);

// 5. The args the page sends reach the native side intact.
const last = calls[calls.length - 1];
check("args are forwarded as JSON", last.command === "save_settings");

console.log(failures.length === 0 ? "\nALL SHIM CHECKS PASSED" : `\n${failures.length} FAILED`);
process.exit(failures.length === 0 ? 0 : 1);