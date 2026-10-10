/**
 * SPRINT 6.7 verification (BOARD.md).
 *
 * 1. Corner inset: every hub view carries ≥20px horizontal inner padding, so the
 *    header icon/title never sits flush against the outer squircle.
 * 2. Live Voice is built from the UI kit (Lucide stroke icons + Material 3
 *    buttons/card) rather than hand-rolled primitives.
 * 3. "Choose file" fires `window.IslandBridge.openFilePicker()`.
 * 4. "Start" / "Allow Microphone" fire `window.IslandBridge.requestMicPermission()`
 *    (the native RECORD_AUDIO prompt), never the raw settings screen.
 * 5. Static: AndroidManifest declares RECORD_AUDIO and registers the two
 *    transparent activities; both Kotlin activity files exist.
 *
 * `node scripts/verify-sprint67.mjs`
 */
import { chromium } from "playwright";
import { preview } from "vite";
import { readFileSync, existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");
const APP = resolve(ROOT, "..", "app");
const KOTLIN = resolve(APP, "src/main/java/com/coucou/android");

function fail(msg) {
  throw new Error(msg);
}

async function run() {
  console.log("Starting Vite preview for Sprint 6.7 verification...");
  const server = await preview({
    root: ROOT,
    configFile: resolve(ROOT, "vite.config.android.ts"),
    preview: { port: 5467, strictPort: false },
  });
  const url = server.resolvedUrls.local[0] || "http://127.0.0.1:5467";
  console.log(`Preview running at ${url}`);

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 392, height: 850 } });
  await page.addInitScript(() => {
    const make = () => ({
      getUserMedia: () =>
        Promise.reject(new DOMException("Permission denied", "NotAllowedError")),
    });
    Object.defineProperty(navigator, "mediaDevices", { get: make });
    // Native-bridge markers: the WebUI must reach these named methods.
    window.__pick = 0;
    window.__mic = 0;
    window.__settings = 0;
    window.IslandBridge = window.IslandBridge || {};
    window.IslandBridge.openFilePicker = () => {
      window.__pick += 1;
    };
    window.IslandBridge.requestMicPermission = () => {
      window.__mic += 1;
    };
    window.CoucouAndroid = window.CoucouAndroid || {};
    window.CoucouAndroid.openAppSettings = () => {
      window.__settings += 1;
    };
  });
  await page.goto(url);
  await page.waitForLoadState("networkidle");
  await page.waitForTimeout(800);

  const tap = (sel) =>
    page.evaluate((s) => {
      const el = document.querySelector(s);
      if (!el) throw new Error(`no element for selector: ${s}`);
      el.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    }, sel);
  const tapPill = (label) =>
    page.evaluate((l) => {
      const el = [...document.querySelectorAll(".pill")].find((p) =>
        (p.textContent ?? "").includes(l),
      );
      if (!el) throw new Error(`pill not found in DOM: ${l}`);
      el.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    }, label);

  await tap('button[title="Overview"]');
  await page.waitForTimeout(1000);

  // ── 1. Corner inset padding on every hub view ─────────────────────────────
  console.log("Checking 20px inner padding across views...");
  const views = [
    { name: "tasks", sel: ".tasks-view", go: () => tapPill("Tasks / Reminders") },
    { name: "vault", sel: ".vault-view", go: () => tapPill("Vault / File Drop") },
    { name: "notes", sel: ".notes-view", go: () => tapPill("Notes") },
    { name: "chat", sel: ".chat-view", go: () => tap('button[title="Chat"]') },
    { name: "voice", sel: ".voice-view", go: () => tapPill("Live Voice Mode") },
  ];
  for (const v of views) {
    await tap('button[title="Overview"]');
    await page.waitForTimeout(500);
    await v.go();
    await page.waitForTimeout(800);
    const m = await page.evaluate((sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      const icon = el.querySelector(".hub-title svg");
      const ir = icon ? icon.getBoundingClientRect() : null;
      return {
        padL: Math.round(parseFloat(cs.paddingLeft)),
        padR: Math.round(parseFloat(cs.paddingRight)),
        iconInset: ir ? Math.round(ir.left - r.left) : null,
      };
    }, v.sel);
    if (!m) fail(`view ${v.name} (${v.sel}) is not in the DOM`);
    console.log(`  ${v.name.padEnd(6)} padding L/R ${m.padL}/${m.padR}px · icon inset ${m.iconInset ?? "n/a"}px`);
    if (m.padL < 20 || m.padR < 20)
      fail(`${v.name} inner padding ${m.padL}/${m.padR} < 20px — icons would clamp the corner`);
    if (m.iconInset != null && m.iconInset < 20)
      fail(`${v.name} header icon sits ${m.iconInset}px from the edge (want ≥ 20)`);
  }
  console.log("✓ every view holds a 20px inner gutter");

  // ── 2. Live Voice built from the UI kit ───────────────────────────────────
  console.log("Checking Live Voice UI-kit chrome...");
  const kit = await page.evaluate(() => ({
    lucide: document.querySelectorAll(".livevoice-room .lucide").length,
    lucideHeader: !!document.querySelector(".livevoice-room .hub-title svg.lucide-mic"),
    filled: !!document.querySelector(".livevoice-controls .m3-btn--filled"),
    tonal: !!document.querySelector(".livevoice-controls .m3-btn--tonal"),
    iconBtn: !!document.querySelector(".livevoice-controls .m3-icon-btn"),
    card: !!document.querySelector(".livevoice-perm .m3-card"),
  }));
  console.log(JSON.stringify(kit, null, 2));
  if (kit.lucide < 4) fail(`expected the Lucide kit in the room, found ${kit.lucide} icons`);
  if (!kit.lucideHeader) fail("header is not the Lucide `mic` glyph");
  if (!kit.filled || !kit.tonal || !kit.iconBtn)
    fail("Live Voice controls are not the M3 filled/tonal/icon buttons");
  if (!kit.card) fail("permission card is not an M3 card");

  // ── 3. "Choose file" → IslandBridge.openFilePicker ────────────────────────
  // (Tapping the Vault pill opens the picker too, so reset before measuring.)
  await page.evaluate(() => {
    window.__pick = 0;
  });
  await tap(".livevoice-controls .m3-btn--tonal");
  const picked = await page.evaluate(() => window.__pick);
  if (picked !== 1) fail(`Choose file should call openFilePicker once, got ${picked}`);
  console.log("✓ [ Choose file ] routes to IslandBridge.openFilePicker()");

  // ── 4. Start / Allow → IslandBridge.requestMicPermission ──────────────────
  await page.evaluate(() => {
    window.__mic = 0;
    window.__settings = 0;
  });
  await tap(".livevoice-controls .m3-btn--filled");
  await page.waitForSelector(".livevoice-room.perm", { timeout: 5000 });
  const asked = await page.evaluate(() => window.__mic);
  if (asked < 1) fail(`Start should call requestMicPermission, got ${asked}`);
  await tap(".perm-actions .m3-btn--filled");
  const after = await page.evaluate(() => ({ mic: window.__mic, settings: window.__settings }));
  if (after.mic < 2) fail(`Allow Microphone should call requestMicPermission again, got ${after.mic}`);
  if (after.settings !== 0) fail(`native prompt path must not open raw settings (${after.settings})`);
  console.log("✓ [ Start / Allow Microphone ] route to IslandBridge.requestMicPermission()");

  // ── 5. Static: manifest + transparent activities ──────────────────────────
  console.log("Checking Android manifest + transparent activities...");
  const manifest = readFileSync(resolve(APP, "src/main/AndroidManifest.xml"), "utf8");
  if (!/android:name="android\.permission\.RECORD_AUDIO"/.test(manifest))
    fail("AndroidManifest.xml does not declare RECORD_AUDIO");
  for (const activity of ["FilePickerActivity", "MicPermissionActivity"]) {
    if (!manifest.includes(`android:name=".${activity}"`))
      fail(`AndroidManifest.xml does not register .${activity}`);
    if (!existsSync(resolve(KOTLIN, `${activity}.kt`)))
      fail(`${activity}.kt is missing`);
  }
  if (!readFileSync(resolve(KOTLIN, "IslandBridgeHost.kt"), "utf8").includes("FLAG_ACTIVITY_NEW_TASK"))
    fail("IslandBridgeHost does not launch the transparent activities with NEW_TASK");
  console.log("✓ RECORD_AUDIO + both transparent activities are declared and implemented");

  await browser.close();
  await server.close();
  console.log("\nALL SPRINT 6.7 CHECKS PASSED");
}

run().catch((e) => {
  console.error(`Sprint 6.7 verification FAILED: ${e.message}`);
  process.exit(1);
});
