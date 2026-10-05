/*
 * Cross-checks the web bundle's Tauri command surface against the Kotlin host that
 * answers it, without an emulator, a device or a DOM.
 *
 * Why this exists: upstream `windows/` talks to its host through exactly one call,
 * `invoke(cmd, args)`, wrapped in `src/core/bridge.ts` as `Bridge.*`. On Android that
 * lands in `CoucouIslandWebView.addJavascriptInterface(bridge, "CoucouNative")` and is
 * planned by `IslandBridgeCommands.plan()`. The host's `else ->` arm answers anything
 * it does not model with `null`, which upstream treats as "no-op" — so a command the
 * page can send but the host does not model is *silently* a dead control: no build
 * error, no exception, nothing in logcat. That is the failure this script catches.
 *
 * The two sets come from three places, deliberately:
 *  - the call sites, read from `src/core/bridge.ts` (the only place a command is sent);
 *  - the built `dist/assets/*.js`, to prove tree-shaking did not drop any of them;
 *  - the host's `when (command)` arms, read from `IslandBridgeCommands.kt`.
 *
 *   node tools/verify-bridge-contract.mjs
 *
 * Exits non-zero on the first broken expectation.
 */
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const webuiRoot = resolve(here, "..");
const appRoot = resolve(webuiRoot, "..");

const failures = [];
function check(name, condition, detail = "") {
  if (condition) console.log(`  ok   ${name}`);
  else {
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ""}`);
    failures.push(name);
  }
}

/** Commands Boss's Sprint 3 directive named as real rather than no-op. */
const REAL_COMMANDS = [
  "boot",
  "chat_send",
  "set_island_rect",
  "set_collapsed",
  "focus_window",
  "open_url",
  "log_line"
];

/**
 * Upstream commands with no Android equivalent. Each falls through the host's
 * `else ->` arm and resolves `null`, which is the honest "not on this platform"
 * answer: the integrations cards render their idle state, and upstream renders a
 * `null` as "nothing happened" rather than as an error.
 */
const DESKTOP_ONLY = new Set([
  "chat_reset",
  "open_in_vscode",
  "set_paused",
  "ingest_file",
  "hooks_status",
  "hooks_preview",
  "hooks_apply",
  "approval_decision",
  "approval_ack",
  "approval_decline",
  "secret_set",
  "secret_clear",
  "refresh_integration",
  "open_n8n"
]);

/**
 * The commands the page can send, from the call sites in `core/bridge.ts`.
 * Every arm is `call("name")` or `callOrThrow("name")`, optionally with a generic
 * parameter between the callee and the paren: `call<BootInfo>("boot")`.
 */
function pageCommands() {
  const source = readFileSync(join(webuiRoot, "src/core/bridge.ts"), "utf8");
  return new Set(
    [...source.matchAll(/\bcall(?:OrThrow)?<[^>]*>?\(\s*"([a-z_]+)"/g)].map((m) => m[1])
  );
}

/** The same commands, as they survive into the bundle that ships in the APK. */
function bundledCommands() {
  const assets = join(webuiRoot, "dist/assets");
  const shipped = new Set();
  for (const file of readdirSync(assets).filter((f) => f.endsWith(".js"))) {
    const code = readFileSync(join(assets, file), "utf8");
    for (const command of pageCommands()) {
      if (code.includes(`"${command}"`)) shipped.add(command);
    }
  }
  return shipped;
}

/** The `when (command)` arms `IslandBridgeCommands.plan()` actually models. */
function hostCommands() {
  const source = readFileSync(
    join(appRoot, "app/src/main/java/com/coucou/android/IslandBridgeCommands.kt"),
    "utf8"
  );
  const start = source.indexOf("fun plan(");
  const end = source.indexOf("private fun chatSend");
  if (start < 0 || end < 0) throw new Error("could not locate IslandBridgeCommands.plan()");
  const body = source.slice(start, end);
  return {
    // Kotlin arms read `"boot" -> BridgeAction.Boot`.
    modelled: new Set([...body.matchAll(/"([a-z_]+)"\s*->/g)].map((m) => m[1])),
    // The fallback arm is what makes an unmodelled command a no-op instead of a crash.
    hasFallback: /else\s*->/.test(body)
  };
}

console.log("bridge contract (core/bridge.ts -> dist bundle -> IslandBridgeCommands.plan)");

const page = pageCommands();
const shipped = bundledCommands();
const host = hostCommands();

const missingReal = REAL_COMMANDS.filter((c) => !host.modelled.has(c));
check("the Kotlin host models all 7 real commands",
  missingReal.length === 0,
  missingReal.length ? `not modelled: ${missingReal.join(", ")}` : "");

const dropped = [...page].filter((c) => !shipped.has(c)).sort();
check("no command is dropped between the source and the built bundle",
  dropped.length === 0,
  dropped.length ? `dropped by the build: ${dropped.join(", ")}` : "");

check("the host has a fallback arm for commands it does not model",
  host.hasFallback,
  "an unmodelled command would throw instead of resolving null");

// The check that matters: any command the page can send must either be modelled by
// the host or be on the desktop-only list. Anything else is a silently dead control.
const unaccounted = [...page]
  .filter((c) => !host.modelled.has(c) && !DESKTOP_ONLY.has(c))
  .sort();
check("every command the page can send is either modelled or desktop-only",
  unaccounted.length === 0,
  unaccounted.length ? `unaccounted: ${unaccounted.join(", ")}` : "");

const unreachable = REAL_COMMANDS.filter((c) => !page.has(c));
check("all 7 real commands are reachable from the page",
  unreachable.length === 0,
  unreachable.length ? `never sent: ${unreachable.join(", ")}` : "");

console.log(`\n  page sends ${page.size} · host models ${host.modelled.size} · desktop-only ${DESKTOP_ONLY.size}`);
if (failures.length) console.log(`\n${failures.length} FAILED`);
else console.log("\nALL BRIDGE CONTRACT CHECKS PASSED");
process.exit(failures.length === 0 ? 0 : 1);
