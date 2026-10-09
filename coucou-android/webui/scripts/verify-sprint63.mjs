import { chromium } from "playwright";
import { preview } from "vite";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");

async function run() {
  console.log("Starting Vite preview server...");
  const server = await preview({
    root: ROOT,
    configFile: resolve(ROOT, "vite.config.android.ts"),
    preview: { port: 5299, strictPort: false },
  });
  const url = server.resolvedUrls.local[0] || "http://127.0.0.1:5299";
  console.log(`Preview server running at ${url}`);

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 360, height: 800 } });

  await page.goto(url);
  await page.waitForLoadState("networkidle");

  console.log("Verifying window.CoucouEngine and touch tracking...");
  const touchResult = await page.evaluate(() => {
    const hasEngine = typeof window.CoucouEngine?.setTargetLook === "function";
    const initialMouse = { ...window.__coucouState?.mouse };
    window.CoucouEngine?.setTargetLook(150, 250);
    const updatedMouse = { ...window.__coucouState?.mouse };
    return {
      hasEngine,
      initialMouse,
      updatedMouse,
    };
  });
  console.log("Touch tracking result:", touchResult);
  if (!touchResult.hasEngine || touchResult.updatedMouse.x !== 150 || touchResult.updatedMouse.y !== 250) {
    throw new Error("Touch tracking verification failed!");
  }
  console.log("✓ Touch tracking verified!");

  console.log("Switching to chat view and checking pinned input prompt box...");
  await page.evaluate(() => {
    window.__coucouIsland?.showPrompt?.();
  });
  await page.waitForTimeout(300);

  const chatStyles = await page.evaluate(() => {
    const chatBar = document.querySelector(".chat-bar, .chat-input-bar");
    const chatView = document.querySelector(".chat-view, .chat-card");
    const chatLog = document.querySelector(".chat-log, .chat-messages");

    const barStyle = chatBar ? window.getComputedStyle(chatBar) : null;
    const viewStyle = chatView ? window.getComputedStyle(chatView) : null;
    const logStyle = chatLog ? window.getComputedStyle(chatLog) : null;

    const barRect = chatBar ? chatBar.getBoundingClientRect() : null;

    return {
      hasBar: !!chatBar,
      barFlexShrink: barStyle?.flexShrink,
      barBottom: barRect ? barRect.bottom : 0,
      barHeight: barRect ? barRect.height : 0,
      viewDisplay: viewStyle?.display,
      viewFlexDirection: viewStyle?.flexDirection,
      logFlexGrow: logStyle?.flexGrow,
      logOverflowY: logStyle?.overflowY,
    };
  });
  console.log("Chat layout check:", chatStyles);
  if (!chatStyles.hasBar || chatStyles.barFlexShrink !== "0") {
    throw new Error("Chat input bar flex-shrink is not 0!");
  }
  console.log("✓ Chat input bar flexbox pinning verified!");

  // Take screenshot of chat view
  await page.screenshot({ path: resolve(ROOT, "../chat_preview_sprint63.png") });
  console.log("✓ Saved chat screenshot to chat_preview_sprint63.png");

  await browser.close();
  await server.close();
  console.log("All Sprint 6.3 WebUI verifications passed!");
}

run().catch((e) => {
  console.error("Verification failed:", e);
  process.exit(1);
});
