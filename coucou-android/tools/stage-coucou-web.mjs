/*
 * Stages upstream `coucou/windows` into the Android app's WebView assets.
 *
 * Runs `vite build` in the existing clone (no second clone, no cargo) and then adapts
 * the two files the browser would otherwise reject:
 *
 *  1. `viewport` — upstream ships `width=device-width`, but the island geometry is a
 *     fixed 720px stage (`layout.ts` PANEL_W, `#island { left:50% }`). On a phone that
 *     viewport would be ~360 CSS px and the 640px island would be clipped, so the
 *     viewport is pinned to 720 and the WebView scales it to the overlay width.
 *  2. the shim — `tauri-shim.js` is injected as the first classic script in <head> so
 *     `window.__TAURI_INTERNALS__` exists before the deferred module bundle runs
 *     (`core/bridge.ts` probes it at module-eval time).
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

if (!existsSync(join(upstream, "vite.config.ts"))) {
  console.error(`Not a coucou/windows checkout: ${upstream}`);
  process.exit(1);
}

const dist = join(upstream, "dist");
if (!existsSync(join(upstream, "node_modules"))) {
  console.log("· npm ci");
  execFileSync("npm", ["ci"], { cwd: upstream, stdio: "inherit" });
}
console.log("· vite build");
execFileSync("npx", ["vite", "build"], { cwd: upstream, stdio: "inherit" });

const index = readFileSync(join(dist, "index.html"), "utf8");
const patched = index
  .replace(
    /<meta name="viewport" content="width=device-width, initial-scale=1"/,
    '<meta name="viewport" content="width=720, initial-scale=1, maximum-scale=1, user-scalable=no"'
  )
  .replace("<head>", '<head>\n    <script src="/tauri-shim.js"></script>');

if (!patched.includes("/tauri-shim.js")) throw new Error("shim was not injected into <head>");
if (!patched.includes("width=720")) throw new Error("viewport was not pinned to 720");

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });
cpSync(join(dist, "assets"), join(outDir, "assets"), { recursive: true });
writeFileSync(join(outDir, "index.html"), patched);
copyFileSync(join(here, "tauri-shim.js"), join(outDir, "tauri-shim.js"));
const lic = existsSync(join(upstream, "../LICENSE")) ? join(upstream, "../LICENSE") : "/workspaces/Binder_vps/coucou/LICENSE";
if (existsSync(lic)) copyFileSync(lic, join(outDir, "LICENSE.upstream"));
copyFileSync(join(here, "THIRD-PARTY-NOTICE.md"), join(outDir, "THIRD-PARTY-NOTICE.md"));

console.log(`· staged ${outDir}`);