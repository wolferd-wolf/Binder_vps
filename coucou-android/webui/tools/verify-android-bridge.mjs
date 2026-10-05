/*
 * Drives the REAL `bridge.android.ts` with a fake Kotlin host, in-process, so the Android
 * bridge's own behaviour is proven rather than just its command literals.
 *
 * Why this exists: `verify-bridge-contract.mjs` proves the page sends commands the host
 * models and `verify-staged-bundle.mjs` proves the shim's `CoucouNative` surface, but
 * neither runs the Android bridge itself. This one bundles `src/core/bridge.android.ts`
 * with esbuild and calls it exactly as the page does — `Bridge.dragBy(12, 0)` on the
 * `Bridge` object, and `window.CoucouAndroid.dragBy(12, 0)` the way the top bar's drag
 * handle does — asserting which command actually reaches `CoucouNative.invoke`.
 *
 * Also checks the two directions of the `CoucouAndroid` hand-off:
 *   - no native interface → the JS facade is installed and forwards every command;
 *   - a native interface present → it is left untouched and used instead.
 *
 *   node tools/verify-android-bridge.mjs
 *
 * Exits non-zero on the first broken expectation.
 */
import { build } from "esbuild";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const webuiRoot = resolve(here, "..");

const failures = [];
function check(name, condition, detail = "") {
  if (condition) console.log(`  ok   ${name}`);
  else {
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ""}`);
    failures.push(name);
  }
}

// ── Bundle the Android bridge once, then load it fresh per scenario ─────────────

const built = await build({
  entryPoints: [join(webuiRoot, "src/core/bridge.android.ts")],
  bundle: true,
  format: "esm",
  platform: "browser",
  write: false,
  logLevel: "silent",
});
const outFile = join(mkdtempSync(join(tmpdir(), "coucou-bridge-")), "bridge.mjs");
writeFileSync(outFile, built.outputFiles[0].text);

let instance = 0;
/** Loads a fresh copy of the bridge with `window` set to `win`. */
async function load(win) {
  globalThis.window = win;
  // Node's `navigator` is a getter-only global; define only if it is missing.
  if (!globalThis.navigator) Object.defineProperty(globalThis, "navigator", { value: {}, configurable: true });
  return import(`${pathToFileURL(outFile).href}?v=${++instance}`);
}

/** A fake Kotlin host recording every command, the way the shim would forward them. */
function kotlinHost() {
  const calls = [];
  return {
    calls,
    window: {
      __TAURI_INTERNALS__: {
        invoke: async () => null,
        transformCallback: () => 0,
        metadata: {},
      },
      CoucouNative: {
        invoke(command, argsJson) {
          const args = JSON.parse(argsJson || "{}");
          calls.push({ command, args });
          if (command === "boot") {
            return JSON.stringify({
              ok: true,
              value: { settings: { soundEnabled: true }, screen: { width: 360, height: 800 }, cursorPoll: false },
            });
          }
          if (command === "chat_send") return JSON.stringify({ ok: true, value: { text: "Chrome" } });
          return JSON.stringify({ ok: true, value: null });
        },
      },
    },
  };
}

console.log("android bridge (src/core/bridge.android.ts)");

// ── 1. No native CoucouAndroid: the JS facade must be installed and used ────────

{
  const host = kotlinHost();
  const mod = await load(host.window);

  const api = host.window.CoucouAndroid;
  check("the facade installs window.CoucouAndroid when none exists", !!api);
  check("the facade exposes the drag handle methods",
    !!api && ["dragStart", "dragBy", "dragEnd", "collapse"].every((m) => typeof api[m] === "function"));

  await mod.Bridge.dragStart(40);
  await mod.Bridge.dragBy(12, 0);
  await mod.Bridge.dragEnd();
  await mod.Bridge.collapse();
  await mod.Bridge.setIslandRect(16, 0, 328, 160);
  await mod.Bridge.focusWindow(true);
  await mod.Bridge.log("island rect");

  const sent = host.calls.map((c) => c.command);
  check("Bridge.drag* reaches the host as drag_start/drag_by/drag_end",
    sent.includes("drag_start") && sent.includes("drag_by") && sent.includes("drag_end"),
    sent.join(", "));
  const by = host.calls.find((c) => c.command === "drag_by");
  check("dragBy forwards dx and dy", by?.args.dx === 12 && by?.args.dy === 0, JSON.stringify(by?.args));

  const rect = host.calls.find((c) => c.command === "set_island_rect");
  check("setIslandRect forwards the full rect",
    rect?.args.x === 16 && rect?.args.width === 328 && rect?.args.height === 160,
    JSON.stringify(rect?.args));

  const collapsed = host.calls.find((c) => c.command === "set_collapsed");
  check("collapse reuses the modelled set_collapsed command", collapsed?.args.collapsed === true,
    JSON.stringify(collapsed?.args));

  const focused = host.calls.find((c) => c.command === "focus_window");
  check("focusWindow forwards the flag", focused?.args.focused === true, JSON.stringify(focused?.args));

  check("log reaches the host as log_line", sent.includes("log_line"), sent.join(", "));

  const boot = await mod.Bridge.boot();
  check("boot resolves the host payload", boot?.screen?.width === 360 && boot?.settings?.soundEnabled === true,
    JSON.stringify(boot));

  const reply = await mod.Bridge.chatSend("chrome", null);
  check("chatSend resolves {text}", reply?.text === "Chrome", JSON.stringify(reply));
}

// ── 2. The drag handle's direct path: window.CoucouAndroid.dragBy ──────────────

{
  const host = kotlinHost();
  await load(host.window);
  host.window.CoucouAndroid.dragStart(10);
  host.window.CoucouAndroid.dragBy(7, 0);
  host.window.CoucouAndroid.dragEnd();
  const sent = host.calls.map((c) => c.command);
  check("the top bar's direct CoucouAndroid.drag* calls reach the host",
    sent.includes("drag_start") && sent.includes("drag_by") && sent.includes("drag_end"),
    sent.join(", "));
}

// ── 3. A native CoucouAndroid is left untouched and used instead ───────────────

{
  const host = kotlinHost();
  const seen = [];
  const native = {
    boot: () => JSON.stringify({ ok: true, value: { settings: {}, screen: { width: 411 } } }),
    dragStart: () => (seen.push("dragStart"), JSON.stringify({ ok: true, value: null })),
    dragBy: (dx, dy) => (seen.push(`dragBy:${dx},${dy}`), JSON.stringify({ ok: true, value: null })),
    dragEnd: () => (seen.push("dragEnd"), JSON.stringify({ ok: true, value: null })),
    collapse: () => (seen.push("collapse"), JSON.stringify({ ok: true, value: null })),
  };
  host.window.CoucouAndroid = native;
  const mod = await load(host.window);

  check("a native CoucouAndroid is not overwritten", host.window.CoucouAndroid === native);
  await mod.Bridge.dragBy(3, 0);
  await mod.Bridge.collapse();
  const boot = await mod.Bridge.boot();
  check("Bridge calls the native interface, not the facade",
    seen.includes("dragBy:3,0") && seen.includes("collapse"), seen.join(", "));
  check("boot reads the native interface", boot?.screen?.width === 411, JSON.stringify(boot));
  check("no command leaked to CoucouNative when a native interface exists",
    host.calls.length === 0, JSON.stringify(host.calls));
}

if (failures.length) console.log(`\n${failures.length} FAILED`);
else console.log("\nALL ANDROID BRIDGE CHECKS PASSED");
process.exit(failures.length === 0 ? 0 : 1);
