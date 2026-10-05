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
        await page.screenshot({ path: filepath, fullPage: false });

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
        await page.screenshot({ path: filepath, fullPage: true });

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

  if (totalIssues > 0) {
    console.log(`\n  ⚠  ${totalIssues} overflow/crop issue(s) detected.`);
    console.log("  Review the screenshots and check layout details:\n");
    for (const r of report) {
      console.log(`    • ${r.page}/${r.view} @ ${r.viewport} — ${r.issues.length} issue(s)`);
      for (const iss of r.issues) {
        const ident = iss.id ? `#${iss.id}` : iss.cls ? `.${iss.cls.split(" ")[0]}` : iss.tag;
        console.log(`        - [${iss.overflow}] ${ident} (${iss.amount}px)`);
      }
    }
    console.log("══════════════════════════════════════════════════════\n");
  } else {
    console.log(`\n  ✅ No overflow or crop issues detected!`);
    console.log("══════════════════════════════════════════════════════\n");
  }

  return { totalIssues, report, totalScreenshots, screenshotDir: SCREENSHOT_DIR };
}

run().catch((err) => {
  console.error("Fatal error in screenshot script:", err);
  process.exit(2);
});
