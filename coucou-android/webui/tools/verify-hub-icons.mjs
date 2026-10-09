// Sprint 6.5 @Buffy — standalone render check for the hub glyph paths.
// The icons are tree-shaken from the island bundle until @Cline's markup
// consumes them, so they're verified here directly from icons.ts source.
import { chromium } from "playwright";
import { ICONS } from "../src/views/icons.ts";

const NEED = ["checklist", "folder", "mic", "waveform"];
const missing = NEED.filter((k) => !(k in ICONS));
if (missing.length) {
  console.error(`MISSING icons: ${missing.join(", ")}`);
  process.exit(1);
}

const browser = await chromium.launch();
const page = await browser.newPage();
const results = await page.evaluate((paths) => {
  const out = [];
  for (const [name, d] of Object.entries(paths)) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    const p = document.createElementNS("http://www.w3.org/2000/svg", "path");
    p.setAttribute("d", d);
    svg.append(p);
    document.body.append(svg);
    const bb = p.getBBox();
    out.push({ name, x: bb.x, y: bb.y, w: bb.width, h: bb.height });
    svg.remove();
  }
  return out;
}, Object.fromEntries(NEED.map((k) => [k, ICONS[k]])));

let failed = false;
for (const r of results) {
  const ok =
    r.w > 10 && r.h > 4 && r.x >= 0 && r.y >= 0 && r.x + r.w <= 24.01 && r.y + r.h <= 24.01;
  console.log(
    `${ok ? "PASS" : "FAIL"} ${r.name.padEnd(10)} bbox=(${r.x.toFixed(1)},${r.y.toFixed(1)}) ${r.w.toFixed(1)}×${r.h.toFixed(1)}`,
  );
  if (!ok) failed = true;
}
await browser.close();
process.exit(failed ? 1 : 0);
