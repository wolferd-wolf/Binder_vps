/*
 * Stages upstream `coucou/windows` into the Android app's WebView assets.
 *
 * Runs `vite build` in the existing clone (no second clone, no cargo) and then adapts
 * the one file the browser would otherwise reject:
 *
 *  1. the shim — `tauri-shim.js` is injected as the first classic script in <head> so
 *     `window.__TAURI_INTERNALS__` exists before the deferred module bundle runs
 *     (`core/bridge.ts` probes it at module-eval time).
 *
 * The viewport is deliberately left at upstream's `width=device-width`. It used to be
 * pinned to the 720px desktop stage so the whole 640px island would fit, but that made
 * one CSS px stop being a dp: the WebView squeezed 720 CSS px into the window and the
 * island's 12.5px type rendered at ~6dp on a phone. The window is now the screen minus
 * its margins and `boot` reports the screen in dp, so the page can measure the real
 * screen and lay out in dp — which is what the rest of the geometry assumes.
 *
 * Sounds are deliberately NOT copied: they are already in `res/raw/coucou_*.wav`
 * (@Buffy's lane) and are served from there at `/sounds/<name>.wav` by
 * `CoucouIslandWebView`, so the 28 files exist once in the APK rather than twice.
 *
 * Usage: node tools/stage-coucou-web.mjs [path-to-coucou-windows]
 */
import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, copyFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..");
const upstream = resolve(process.argv[2] ?? "/workspaces/Binder_vps/coucou/windows");
const outDir = join(repoRoot, "app/src/main/assets/coucou");

if (!existsSync(join(upstream, "vite.config.ts")) || !existsSync(join(upstream, "vite.config.android.ts"))) {
  console.error(`Not a coucou/windows checkout with the Android build config: ${upstream}`);
  process.exit(1);
}

const dist = join(upstream, "dist");
if (!existsSync(join(upstream, "node_modules"))) {
  console.log("· npm ci");
  execFileSync("npm", ["ci"], { cwd: upstream, stdio: "inherit" });
}
// Build the Android bundle, not the desktop one: `vite.config.android.ts` aliases
// `core/bridge` to `bridge.android.ts`, which talks to `window.CoucouAndroid` instead of
// Tauri. Building with the default config here would silently overwrite the dist with the
// desktop Tauri bundle and lose the drag/collapse commands at staging time.
console.log("· vite build --config vite.config.android.ts");
execFileSync("npx", ["vite", "build", "--config", "vite.config.android.ts"], {
  cwd: upstream,
  stdio: "inherit",
});

const index = readFileSync(join(dist, "index.html"), "utf8");
const patched = index.replace("<head>", '<head>\n    <script src="/tauri-shim.js"></script>');

if (!patched.includes("/tauri-shim.js")) throw new Error("shim was not injected into <head>");
if (/name="viewport"[^>]*width=720/.test(patched)) {
  throw new Error("viewport is pinned to 720; CSS px would stop being dp");
}

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });
cpSync(join(dist, "assets"), join(outDir, "assets"), { recursive: true });
writeFileSync(join(outDir, "index.html"), patched);
copyFileSync(join(here, "tauri-shim.js"), join(outDir, "tauri-shim.js"));
const lic = existsSync(join(upstream, "../LICENSE")) ? join(upstream, "../LICENSE") : "/workspaces/Binder_vps/coucou/LICENSE";
if (existsSync(lic)) copyFileSync(lic, join(outDir, "LICENSE.upstream"));
copyFileSync(join(here, "THIRD-PARTY-NOTICE.md"), join(outDir, "THIRD-PARTY-NOTICE.md"));

console.log(`· staged ${outDir}`);