/*
 * Drives the REAL staged bundle through the REAL tauri-shim.js with a fake Kotlin host,
 * and asserts that the commands Boss named as "real" actually reach the host and resolve.
 *
 * This is deliberately not a unit test of the shim. `tools/verify-shim.mjs` (OpenCode's
 * lane) already proves the shim in isolation against a hand-written fake. What nothing
 * else proves is that the *shipped* bundle — minified, chunked, tree-shaken — still
 * speaks the contract the shim implements. A refactor that dropped `IS_TAURI`, renamed
 * a command or changed how args are passed would leave every existing test green and
 * only fail on a device, where it reads as "the island is just blank".
 *
 *   node tools/verify-staged-bundle.mjs
 *
 * Exits non-zero on the first broken expectation.
 */
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const webuiRoot = resolve(here, "..");
const appRoot = resolve(webuiRoot, "..");
const staged = join(appRoot, "app/src/main/assets/coucou");

// This script reads the bundle that actually ships, so it is only meaningful once
// `tools/stage-coucou-web.mjs` has run. Say that, rather than surfacing an ENOENT
// stack trace from deep inside readdirSync.
for (const [label, path] of [
  ["the staged island bundle", staged],
  ["the staged bundle's assets", join(staged, "assets")],
  ["the tauri shim", join(appRoot, "tools/tauri-shim.js")]
]) {
  if (!existsSync(path)) {
    console.error(`Cannot verify: ${label} is missing at ${path}.`);
    console.error("Run: npm run build && node tools/stage-coucou-web.mjs webui");
    process.exit(1);
  }
}

const failures = [];
function check(name, condition, detail = "") {
  if (condition) console.log(`  ok   ${name}`);
  else {
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ""}`);
    failures.push(name);
  }
}

/** The payload Kotlin's `IslandBridgeCommands.bootJson` really returns. */
const BOOT_PAYLOAD = {
  settings: {
    soundEnabled: true,
    soundVolume: 0.12,
    soundCloseInterval: 15,
    absenceInterval: 180,
    activeIntegrations: ["integration_resend", "integration_vercel", "integration_github"],
    screen: "primary",
    autostart: false,
    hooksInstalled: false,
    model: "claude-opus-5"
  },
  screen: { x: 0, y: 0, width: 1080, height: 2400, scale: 2.75 },
  version: "1.0.0-android",
  hookPath: "",
  cursorPoll: false
};

const calls = [];
const window = {
  CoucouNative: {
    invoke(command, argsJson) {
      const args = JSON.parse(argsJson || "{}");
      calls.push({ command, args });
      if (command === "boot") return JSON.stringify({ ok: true, value: BOOT_PAYLOAD });
      if (command === "chat_send") return JSON.stringify({ ok: true, value: { text: "Chrome" } });
      if (command === "open_url") return JSON.stringify({ ok: true, value: null });
      // set_island_rect / set_collapsed / focus_window / log_line all answer null.
      return JSON.stringify({ ok: true, value: null });
    }
  },
  document: { querySelectorAll: () => [], getElementById: () => null },
  setTimeout: () => 0,
  clearTimeout: () => {}
};
const sandbox = { window, console, Promise, JSON };
sandbox.globalThis = sandbox;
vm.createContext(sandbox);

// 1. The shim, exactly as staged.
vm.runInContext(readFileSync(join(appRoot, "tools/tauri-shim.js"), "utf8"), sandbox, {
  filename: "tauri-shim.js"
});

console.log("staged bundle against the real shim");

// 2. The internals the bundle probes at module-eval time (`IS_TAURI`).
const internals = window.__TAURI_INTERNALS__;
check("the shim installs __TAURI_INTERNALS__", !!internals);
check("invoke is callable", typeof internals?.invoke === "function");
check("transformCallback is callable", typeof internals?.transformCallback === "function");

/**
 * Every command the *shipped* bundle can send: the call sites in either bridge (the
 * Android build aliases `core/bridge` to bridge.android.ts, so both are the page's own
 * code), filtered to the ones whose literals survived into `dist/assets/*.js`.
 */
function shippedCommands() {
  const callSites = new Set();
  for (const file of ["src/core/bridge.ts", "src/core/bridge.android.ts"]) {
    const source = readFileSync(join(webuiRoot, file), "utf8");
    for (const m of source.matchAll(/\bcall(?:OrThrow)?<[^>]*>?\(\s*"([a-z_]+)"/g)) {
      callSites.add(m[1]);
    }
  }
  const code = readdirSync(join(staged, "assets"))
    .filter((f) => f.endsWith(".js"))
    .map((f) => readFileSync(join(staged, "assets", f), "utf8"))
    .join("\n");
  return [...callSites].filter((c) => code.includes(`"${c}"`));
}

// 3. Every command the shipped bundle contains, driven through the real shim.
const unresolvable = [];
for (const command of shippedCommands()) {
  try {
    await internals.invoke(command, {});
  } catch (error) {
    // A rejection is a legitimate answer for the commands Kotlin answers with
    // BridgeAction.Fail, but a crash or an unparseable envelope is not.
    if (typeof error !== "string") unresolvable.push(`${command}: ${error}`);
  }
}
check("every shipped command reaches the host and settles",
  unresolvable.length === 0,
  unresolvable.join("; "));

// 4. The commands Boss named as real, with the arguments the page actually sends.
const boot = await internals.invoke("boot", {});
check("boot resolves settings", boot?.settings?.soundEnabled === true);
check("boot resolves the screen", boot?.screen?.width === 1080);
check("boot reports cursorPoll false", boot?.cursorPoll === false);

const chat = await internals.invoke("chat_send", { query: "chrome", context: null });
check("chat_send resolves {text}", chat?.text === "Chrome", JSON.stringify(chat));

const before = calls.length;
await internals.invoke("set_island_rect", { x: 0, y: 0, width: 640, height: 320 });
await internals.invoke("set_collapsed", { collapsed: true });
await internals.invoke("focus_window", { focused: true });
await internals.invoke("open_url", { url: "https://example.com" });
await internals.invoke("log_line", { message: "island rect" });
check("the 5 geometry/window commands all reached the host",
  calls.length === before + 5,
  `saw ${calls.length - before}`);

// The sweep above already sent each command with empty args, so these must read the
// calls made after `before` rather than the first match for the name.
const since = calls.slice(before);
const rect = since.find((c) => c.command === "set_island_rect");
check("set_island_rect forwards width and height",
  rect?.args.width === 640 && rect?.args.height === 320,
  JSON.stringify(rect?.args));

const openUrl = since.find((c) => c.command === "open_url");
check("open_url forwards the url",
  openUrl?.args.url === "https://example.com", JSON.stringify(openUrl?.args));

const collapsed = since.find((c) => c.command === "set_collapsed");
check("set_collapsed forwards the flag",
  collapsed?.args.collapsed === true, JSON.stringify(collapsed?.args));

// Sprint 3 REDO window drag: the three commands the top bar's drag handle sends, with the
// arguments the page actually produces.
const dragBefore = calls.length;
await internals.invoke("drag_start", {});
await internals.invoke("drag_by", { dx: 12, dy: 0 });
await internals.invoke("drag_end", {});
check("the 3 drag commands all reached the host",
  calls.length === dragBefore + 3,
  `saw ${calls.length - dragBefore}`);
const dragBy = calls.slice(dragBefore).find((c) => c.command === "drag_by");
check("drag_by forwards dx and dy",
  dragBy?.args.dx === 12 && dragBy?.args.dy === 0, JSON.stringify(dragBy?.args));

// 5. The reveal path: no host event means the island stays hidden, so the host
// synthesises a tray event. This is the one non-obvious trap in the whole bridge.
const received = [];
const handlerId = internals.transformCallback((event) => received.push(event), false);
await internals.invoke("plugin:event|listen", {
  event: "tray",
  target: { kind: "Any" },
  handler: handlerId
});
window.CoucouIsland.showHome();
check("showHome delivers the tray event that reveals the island",
  received.length === 1 && received[0]?.payload === "open",
  JSON.stringify(received[0]));

if (failures.length) console.log(`\n${failures.length} FAILED`);
else console.log("\nALL STAGED BUNDLE CHECKS PASSED");
process.exit(failures.length === 0 ? 0 : 1);
