import { chromium } from "playwright";
import { preview } from "vite";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");
const SHOTS = resolve(ROOT, "../screenshots-viewports");

const PANEL_H = 270; // BOARD: every view matches the Chat panel (360 × 270)

function fail(msg) {
  throw new Error(msg);
}

async function run() {
  console.log("Starting Vite preview for Sprint 6.6 verification...");
  const server = await preview({
    root: ROOT,
    configFile: resolve(ROOT, "vite.config.android.ts"),
    preview: { port: 5466, strictPort: false },
  });
  const url = server.resolvedUrls.local[0] || "http://127.0.0.1:5466";
  console.log(`Preview running at ${url}`);

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 392, height: 850 } });
  // Deterministic mic denial: getUserMedia must exist (otherwise ensureMic
  // takes the preview path) but always reject as denied.
  await page.addInitScript(() => {
    const make = () => ({
      getUserMedia: () =>
        Promise.reject(new DOMException("Permission denied", "NotAllowedError")),
    });
    Object.defineProperty(navigator, "mediaDevices", { get: make });
  });
  await page.goto(url);
  await page.waitForLoadState("networkidle");
  await page.waitForTimeout(800);

  // ── 1. Pill secondary icons sit on the far-right edge ─────────────────────
  console.log("Checking pill icon alignment (far right)...");
  const pills = await page.evaluate(() => {
    return [...document.querySelectorAll(".pill")].map((p) => {
      const icon = p.querySelector(".pill-icon");
      const r = p.getBoundingClientRect();
      const ir = icon ? icon.getBoundingClientRect() : null;
      return {
        label: p.querySelector(".lbl")?.textContent?.trim() ?? "",
        pillW: Math.round(r.width),
        gapToRightEdge: ir ? Math.round(r.right - ir.right) : null,
        iconLeftOfLabel: ir ? ir.left > r.left + 40 : null,
        hasIcon: !!icon,
      };
    });
  });
  console.log(JSON.stringify(pills, null, 2));
  const hubPills = pills.filter((p) =>
    ["Tasks / Reminders", "Vault / File Drop", "Notes", "Live Voice Mode"].includes(p.label),
  );
  if (hubPills.length !== 4) fail(`expected 4 hub pills, found ${hubPills.length}`);
  for (const p of hubPills) {
    if (!p.hasIcon) fail(`pill "${p.label}" has no secondary icon`);
    if (p.gapToRightEdge == null || p.gapToRightEdge > 14)
      fail(`pill "${p.label}" icon gap from right edge is ${p.gapToRightEdge}px (want ≤ 14)`);
  }
  console.log("✓ all 4 hub pills carry their icon on the far-right edge");
  await page.screenshot({ path: resolve(SHOTS, "sprint66_home_overview.png") });

  // ── 2. Uniform panel height across every tab (no shrink) ─────────────────
  console.log("Checking uniform panel dimensions across tabs...");
  const islandRect = () =>
    page.evaluate(() => {
      const el = document.querySelector("#island");
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { w: Math.round(r.width), h: Math.round(r.height) };
    });

  const pillClick = (label) => page.locator(`.pill:has-text("${label}")`).first().click();
  const tabs = [
    { name: "overview", go: async () => page.click('button[title="Overview"]') },
    { name: "tasks", go: async () => pillClick("Tasks / Reminders") },
    { name: "vault", go: async () => pillClick("Vault / File Drop") },
    { name: "notes", go: async () => pillClick("Notes") },
    { name: "livevoice", go: async () => pillClick("Live Voice Mode") },
    { name: "chat", go: async () => page.click('button[title="Chat"]') },
  ];
  const rects = {};
  for (const t of tabs) {
    await t.go();
    await page.waitForTimeout(900); // let the geometry spring settle
    const r = await islandRect();
    if (!r) fail(`no #island element while on ${t.name}`);
    rects[t.name] = r;
    console.log(`  ${t.name.padEnd(10)} → ${r.w}×${r.h}`);
  }
  for (const [name, r] of Object.entries(rects)) {
    if (Math.abs(r.h - PANEL_H) > 1)
      fail(`${name} height ${r.h} !== ${PANEL_H} (panel must match the Chat panel)`);
  }
  const w0 = rects.overview.w;
  for (const [name, r] of Object.entries(rects)) {
    if (r.w !== w0) fail(`${name} width ${r.w} !== overview width ${w0}`);
  }
  console.log(`✓ every tab holds a steady ${w0}×${PANEL_H} — no shrink, no letterbox`);
  await page.screenshot({ path: resolve(SHOTS, "sprint66_chat.png") });

  // ── 3. Live Voice: modern chrome + Allow Microphone card ─────────────────
  console.log("Checking Live Voice room...");
  await page.click('button[title="Overview"]');
  await page.waitForTimeout(700);
  await pillClick("Live Voice Mode");
  await page.waitForTimeout(700);
  const voice = await page.evaluate(() => ({
    room: !!document.querySelector(".livevoice-room"),
    orb: !!document.querySelector(".livevoice-orb"),
    orbGlow: getComputedStyle(document.querySelector(".livevoice-orb")).boxShadow,
    waveBars: document.querySelectorAll(".livevoice-waves i").length,
    headerStroke: !!document.querySelector('.hub-title svg path[stroke]'),
    settingsBtn: !!document.querySelector(".voice-icon-btn"),
    startText: document.querySelector(".livevoice-controls .hub-btn")?.textContent?.trim(),
  }));
  console.log(JSON.stringify(voice, null, 2));
  if (!voice.room || !voice.orb || !voice.settingsBtn) fail("voice room chrome missing");
  if (voice.waveBars < 8) fail(`expected a wave-bar equalizer, found ${voice.waveBars} bars`);
  if (!voice.headerStroke) fail("header mic icon is not a Lucide-style stroke glyph");
  if (!voice.orbGlow.includes("24px")) fail(`orb glow missing BOARD token: ${voice.orbGlow}`);
  await page.screenshot({ path: resolve(SHOTS, "sprint66_live_voice.png") });

  console.log("Checking microphone-blocked permission card...");
  // Headless Chromium denies getUserMedia → ensureMic() must fail → card shows.
  await page.evaluate(() => {
    window.__openAppSettingsCalled = 0;
    window.CoucouAndroid = window.CoucouAndroid || {};
    window.CoucouAndroid.openAppSettings = () => {
      window.__openAppSettingsCalled += 1;
    };
  });
  await page.click(".livevoice-controls .hub-btn"); // Start
  await page.waitForSelector(".livevoice-room.perm", { timeout: 5000 });
  const perm = await page.evaluate(() => {
    const room = document.querySelector(".livevoice-room");
    const panel = document.querySelector(".perm-panel");
    const allow = document.querySelector(".m3-btn");
    return {
      visible: panel && getComputedStyle(panel.parentElement).display !== "none",
      title: document.querySelector(".perm-title")?.textContent ?? "",
      allowText: allow?.textContent?.trim() ?? "",
      orbHidden: getComputedStyle(document.querySelector(".livevoice-orb")).display === "none",
      panelRadius: panel ? getComputedStyle(panel).borderRadius : "",
    };
  });
  console.log(JSON.stringify(perm, null, 2));
  if (!perm.visible) fail("permission card not visible while mic is blocked");
  if (perm.title !== "Microphone blocked") fail(`unexpected card title: ${perm.title}`);
  if (perm.allowText !== "Allow Microphone") fail(`M3 button label is "${perm.allowText}"`);
  if (!perm.orbHidden) fail("orb should hide while the permission card is up");
  await page.screenshot({ path: resolve(SHOTS, "sprint66_voice_blocked.png") });

  await page.click(".m3-btn"); // Allow Microphone
  const opened = await page.evaluate(() => window.__openAppSettingsCalled);
  if (opened !== 1) fail(`Allow Microphone should call openAppSettings once, got ${opened}`);
  console.log("✓ [ Allow Microphone ] routes to openAppSettings()");

  await page.click(".perm-link"); // Not now
  await page.waitForTimeout(200);
  const dismissed = await page.evaluate(
    () => !document.querySelector(".livevoice-room").classList.contains("perm"),
  );
  if (!dismissed) fail('"Not now" did not dismiss the permission card');
  console.log("✓ Not now dismisses the card");

  await browser.close();
  await server.close();
  console.log("\nALL SPRINT 6.6 CHECKS PASSED");
}

run().catch((e) => {
  console.error(`Sprint 6.6 verification FAILED: ${e.message}`);
  process.exit(1);
});
