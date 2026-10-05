#!/usr/bin/env node
// ─────────────────────────────────────────────────────────────────────────────
// Coucou Android – Headless Playwright viewport screenshot check
//
// Captures every view of the built web UI at two phone viewports (360×800 and
// 412×915) and reports overflow / crop issues. Run this before every APK
// handoff.
//
// Usage:
//   node scripts/screenshot-viewports.mjs [--out DIR]
//
// The script:
//   1. Spins up a local Vite preview server on the dist/ folder.
//   2. Opens headless Chromium at each viewport size.
//   3. For each page (island + settings), injects state to force every view
//      into its active state, screenshots it, and checks for overflow.
//   4. Saves PNGs to <out>/screenshots-viewports/ (default: coucou-android/).
//   5. Prints a summary and reports any overflow/crop issues.
// ─────────────────────────────────────────────────────────────────────────────

import { chromium } from "playwright";
import { preview } from "vite";
import { resolve, join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { mkdirSync, existsSync } from "node:fs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");
const DEFAULT_OUT = resolve(ROOT, ".."); // coucou-android root

// ── Configuration ────────────────────────────────────────────────────────────

const VIEWPORTS = [
  { tag: "360x800", width: 360, height: 800 },
  { tag: "412x915", width: 412, height: 915 },
];

// Island views to test — these map to IslandViewName in layout.ts.
const ISLAND_VIEWS = [
  "overview",
  "empty",
  "approval",
  "question",
  "error",
  "finished",
  "confused",
  "upload",
  "uploading",
  "choose",
  "prompt",
  "settings",
  "note",
];

// ── CLI args ─────────────────────────────────────────────────────────────────

let outBase = DEFAULT_OUT;
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === "--out" && process.argv[i + 1]) {
    outBase = resolve(process.argv[++i]);
  }
}

const SCREENSHOT_DIR = join(outBase, "screenshots-viewports");
mkdirSync(SCREENSHOT_DIR, { recursive: true });

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Check if any element overflows its parent or the viewport. */
async function checkOverflow(page, viewportWidth, viewportHeight) {
  return page.evaluate(
    ({ vw, vh }) => {
      const issues = [];
      const all = document.querySelectorAll("*");
      for (const el of all) {
        const r = el.getBoundingClientRect();
        const styles = window.getComputedStyle(el);
        // Skip invisible elements
        if (
          styles.display === "none" ||
          styles.visibility === "hidden" ||
          styles.opacity === "0" ||
          el.closest(".view:not(.on)") ||
          (typeof el.checkVisibility === "function" && !el.checkVisibility({ checkOpacity: true })) ||
          (r.width === 0 && r.height === 0)
        ) {
          continue;
        }

        // Check horizontal overflow past viewport
        if (r.right > vw + 2) {
          issues.push({
            tag: el.tagName.toLowerCase(),
            id: el.id || undefined,
            cls: el.className?.toString().slice(0, 60) || undefined,
            overflow: "right",
            amount: Math.round(r.right - vw),
          });
        }
        // Check if content is clipped (scrollWidth > clientWidth)
        if (
          el.scrollWidth > el.clientWidth + 2 &&
          styles.overflow !== "hidden" &&
          styles.overflowX !== "hidden" &&
          styles.overflow !== "scroll" &&
          styles.overflowX !== "scroll" &&
          styles.textOverflow !== "ellipsis" &&
          styles.whiteSpace !== "nowrap"
        ) {
          issues.push({
            tag: el.tagName.toLowerCase(),
            id: el.id || undefined,
            cls: el.className?.toString().slice(0, 60) || undefined,
            overflow: "scroll-x",
            amount: el.scrollWidth - el.clientWidth,
          });
        }
        // Check vertical overflow past viewport (for fixed/absolute positioned)
        if (
          r.bottom > vh + 2 &&
          (styles.position === "fixed" || styles.position === "absolute")
        ) {
          issues.push({
            tag: el.tagName.toLowerCase(),
            id: el.id || undefined,
            cls: el.className?.toString().slice(0, 60) || undefined,
            overflow: "bottom",
            amount: Math.round(r.bottom - vh),
          });
        }
      }
      // Deduplicate: keep unique by tag+id+cls+overflow
      const seen = new Set();
      return issues.filter((i) => {
        const key = `${i.tag}:${i.id}:${i.cls}:${i.overflow}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      });
    },
    { vw: viewportWidth, vh: viewportHeight },
  );
}

// ── Main ─────────────────────────────────────────────────────────────────────

async function run() {
  console.log("╔══════════════════════════════════════════════════════╗");
  console.log("║  Coucou Android — Viewport Screenshot Check        ║");
  console.log("╚══════════════════════════════════════════════════════╝");

  // Check dist exists
  const distDir = join(ROOT, "dist");
  if (!existsSync(distDir)) {
    console.error("❌  dist/ not found. Run `npm run build` first.");
    process.exit(1);
  }

  // Start Vite preview server
  console.log("\n→ Starting Vite preview server...");
  const previewServer = await preview({
    root: ROOT,
    configFile: join(ROOT, "vite.config.android.ts"),
    preview: { port: 5199, host: "127.0.0.1" },
  });
  const port = previewServer.httpServer.address().port;
  const baseUrl = `http://127.0.0.1:${port}`;
  console.log(`  Preview server running at ${baseUrl}`);

  let totalIssues = 0;
  const report = [];
  const promptGateFailures = [];

  try {
    const browser = await chromium.launch({
      headless: true,
      args: ["--no-sandbox", "--disable-setuid-sandbox"],
    });

    for (const vp of VIEWPORTS) {
      console.log(`\n━━ Viewport: ${vp.tag} ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);

      const context = await browser.newContext({
        viewport: { width: vp.width, height: vp.height },
        deviceScaleFactor: 2,
        isMobile: true,
        hasTouch: true,
        userAgent:
          "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
      });

      // ── Island page ──────────────────────────────────────────────────────
      for (const viewName of ISLAND_VIEWS) {
        const page = await context.newPage();
        await page.goto(`${baseUrl}/index.html`, {
          waitUntil: "networkidle",
          timeout: 10000,
        });

        // Setup state & switch view
        await page.evaluate((targetView) => {
          const island = window.__coucouIsland;
          const state = window.__coucouState;
          if (!island || !state) return;

          if (targetView === "approval") {
            state.pendingApproval = { command: "git push origin main --force", tool: "Bash" };
            state.isPinned = true;
            island.fsm.pinned = true;
          } else if (targetView === "error") {
            state.updateTask("integration_claude", "error");
            const t = state.focusTask;
            if (t) t.steps = ["Command failed with exit code 1", "npm install --production"];
          } else if (targetView === "finished") {
            state.updateTask("integration_claude", "idle");
            const t = state.focusTask;
            if (t) t.steps = ["Build complete: 34 assets generated"];
          } else if (targetView === "question") {
            const t = state.focusTask;
            if (t) t.steps = ["Should we overwrite existing configuration in /etc/binder?"];
          } else if (targetView === "note") {
            state.noteMessage = "Sync completed successfully";
          }

          island.setView(targetView);
          island.reveal();
        }, viewName);

        // Wait for spring / transition animation to complete
        await page.waitForTimeout(600);

        const filename = `island-${viewName}_${vp.tag}.png`;
        const filepath = join(SCREENSHOT_DIR, filename);
        const screenshotBuf = await page.screenshot({
          path: filepath,
          fullPage: false,
          omitBackground: true,
        });

        // ── Extended Gate Checks for Prompt View ────────────────────────────
        if (viewName === "prompt") {
          // 1. Check prompt input is visible and tappable
          try {
            const chatInput = page.locator(".chat-input");
            await chatInput.waitFor({ state: "visible", timeout: 3000 });
            const isVisible = await chatInput.isVisible();
            if (!isVisible) {
              promptGateFailures.push({
                viewport: vp.tag,
                reason: "Prompt input (.chat-input) is not visible",
              });
            } else {
              await chatInput.click();
              const isFocused = await page.evaluate(
                () => document.activeElement === document.querySelector(".chat-input")
              );
              if (!isFocused) {
                promptGateFailures.push({
                  viewport: vp.tag,
                  reason: "Prompt input (.chat-input) did not gain focus after tap/click",
                });
              } else {
                await chatInput.fill("Gate test query");
                const typed = await chatInput.inputValue();
                if (typed !== "Gate test query") {
                  promptGateFailures.push({
                    viewport: vp.tag,
                    reason: `Prompt input text entry failed: got '${typed}'`,
                  });
                }
                await chatInput.fill("");
              }
            }
          } catch (e) {
            promptGateFailures.push({
              viewport: vp.tag,
              reason: `Prompt input test error: ${e.message}`,
            });
          }

          // 2. Check that pixels outside the island card are fully transparent
          try {
            const rect = await page.evaluate(() => {
              const el = document.querySelector("#island");
              if (!el) return null;
              const r = el.getBoundingClientRect();
              return { left: r.left, right: r.right, top: r.top, bottom: r.bottom };
            });

            if (!rect) {
              promptGateFailures.push({
                viewport: vp.tag,
                reason: "Island element (#island) not found in DOM",
              });
            } else {
              const transResult = await page.evaluate(
                async ({ base64, rect, vw }) => {
                  const img = new Image();
                  await new Promise((resolve, reject) => {
                    img.onload = resolve;
                    img.onerror = reject;
                    img.src = "data:image/png;base64," + base64;
                  });
                  const canvas = document.createElement("canvas");
                  canvas.width = img.width;
                  canvas.height = img.height;
                  const ctx = canvas.getContext("2d", { willReadFrequently: true });
                  ctx.drawImage(img, 0, 0);

                  const scale = img.width / vw;
                  const left = Math.floor(rect.left * scale);
                  const right = Math.ceil(rect.right * scale);
                  const top = Math.floor(rect.top * scale);
                  const bottom = Math.ceil(rect.bottom * scale);

                  let nonTransparentOutside = 0;
                  let totalOutside = 0;
                  const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;

                  for (let y = 0; y < canvas.height; y += 4) {
                    for (let x = 0; x < canvas.width; x += 4) {
                      if (x < left || x > right || y < top || y > bottom) {
                        totalOutside++;
                        const idx = (y * canvas.width + x) * 4;
                        if (data[idx + 3] > 0) nonTransparentOutside++;
                      }
                    }
                  }

                  let insideNonTransparent = 0;
                  for (let y = top; y < bottom; y += 4) {
                    for (let x = left; x < right; x += 4) {
                      const idx = (y * canvas.width + x) * 4;
                      if (data[idx + 3] > 0) insideNonTransparent++;
                    }
                  }

                  return { nonTransparentOutside, totalOutside, insideNonTransparent };
                },
                { base64: screenshotBuf.toString("base64"), rect, vw: vp.width }
              );

              if (transResult.nonTransparentOutside > 0) {
                promptGateFailures.push({
                  viewport: vp.tag,
                  reason: `${transResult.nonTransparentOutside} non-transparent pixels found outside island card bounds`,
                });
              } else if (transResult.insideNonTransparent === 0) {
                promptGateFailures.push({
                  viewport: vp.tag,
                  reason: "Island card itself has 0 non-transparent pixels (blank card)",
                });
              } else {
                console.log(
                  `     [Transparency] ${transResult.totalOutside} outside px sampled -> 100% transparent (alpha=0)`
                );
                console.log("     [Input] Prompt input (.chat-input) verified visible and tappable");
              }
            }
          } catch (e) {
            promptGateFailures.push({
              viewport: vp.tag,
              reason: `Transparency check error: ${e.message}`,
            });
          }
        }

        const issues = await checkOverflow(page, vp.width, vp.height);
        if (issues.length > 0) {
          totalIssues += issues.length;
          console.log(`  ⚠  ${viewName} @ ${vp.tag}: ${issues.length} overflow issue(s)`);
          for (const iss of issues.slice(0, 5)) {
            const ident = iss.id ? `#${iss.id}` : iss.cls ? `.${iss.cls.split(" ")[0]}` : iss.tag;
            console.log(`     → ${ident} overflows ${iss.overflow} by ${iss.amount}px`);
          }
          report.push({ page: "island", view: viewName, viewport: vp.tag, issues });
        } else {
          console.log(`  ✓  ${viewName} @ ${vp.tag}`);
        }

        await page.close();
      }

      // ── Settings page ────────────────────────────────────────────────────
      {
        const page = await context.newPage();
        await page.goto(`${baseUrl}/settings.html`, {
          waitUntil: "networkidle",
          timeout: 10000,
        });
        await page.waitForTimeout(800);

        const filename = `settings_${vp.tag}.png`;
        const filepath = join(SCREENSHOT_DIR, filename);
        await page.screenshot({ path: filepath, fullPage: true, omitBackground: true });

        const issues = await checkOverflow(page, vp.width, vp.height);
        if (issues.length > 0) {
          totalIssues += issues.length;
          console.log(`  ⚠  settings @ ${vp.tag}: ${issues.length} overflow issue(s)`);
          for (const iss of issues.slice(0, 5)) {
            const ident = iss.id ? `#${iss.id}` : iss.cls ? `.${iss.cls.split(" ")[0]}` : iss.tag;
            console.log(`     → ${ident} overflows ${iss.overflow} by ${iss.amount}px`);
          }
          report.push({ page: "settings", view: "main", viewport: vp.tag, issues });
        } else {
          console.log(`  ✓  settings @ ${vp.tag}`);
        }

        await page.close();
      }

      await context.close();
    }

    await browser.close();
  } finally {
    previewServer.httpServer.close();
  }

  // ── Summary ──────────────────────────────────────────────────────────────

  const totalScreenshots = VIEWPORTS.length * (ISLAND_VIEWS.length + 1);

  console.log("\n══════════════════════════════════════════════════════");
  console.log(`  Screenshots saved to: ${SCREENSHOT_DIR}`);
  console.log(`  Total screenshots: ${totalScreenshots}`);
  console.log(`  Viewports tested: ${VIEWPORTS.map((v) => v.tag).join(", ")}`);

  // Prompt view gate verification per Sprint 3 REDO
  const promptIssues = report.filter(
    (r) => r.view === "prompt" && r.issues && r.issues.length > 0
  );

  if (totalIssues > 0) {
    console.log(`\n  ⚠  ${totalIssues} total overflow/crop issue(s) detected across views.`);
    for (const r of report) {
      console.log(`    • ${r.page}/${r.view} @ ${r.viewport} — ${r.issues.length} issue(s)`);
      for (const iss of r.issues.slice(0, 4)) {
        const ident = iss.id ? `#${iss.id}` : iss.cls ? `.${iss.cls.split(" ")[0]}` : iss.tag;
        console.log(`        - [${iss.overflow}] ${ident} (${iss.amount}px)`);
      }
    }
  }

  if (promptIssues.length > 0 || promptGateFailures.length > 0) {
    console.error("\n❌ [AGY GATE FAILED] Prompt view gate requirements failed!");
    for (const fail of promptGateFailures) {
      console.error(`   → [Gate Failure @ ${fail.viewport}]: ${fail.reason}`);
    }
    for (const r of promptIssues) {
      console.error(`   → Prompt overflow @ ${r.viewport}: ${r.issues.length} issue(s)`);
      for (const iss of r.issues) {
        const ident = iss.id ? `#${iss.id}` : iss.cls ? `.${iss.cls.split(" ")[0]}` : iss.tag;
        console.error(`       - [${iss.overflow}] ${ident} (${iss.amount}px)`);
      }
    }
    console.error("\n[AGY GATE] No APK will be built until the prompt view screenshot gate passes.\n");
    console.log("══════════════════════════════════════════════════════\n");
    process.exit(1);
  }

  console.log("\n✅ [AGY GATE PASSED] Prompt view is uncropped, transparent outside card, and input is visible & tappable!");
  console.log("══════════════════════════════════════════════════════\n");

  return { totalIssues, report, totalScreenshots, screenshotDir: SCREENSHOT_DIR };
}

run().catch((err) => {
  console.error("Fatal error in screenshot script:", err);
  process.exit(2);
});
