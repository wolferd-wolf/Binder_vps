// SPRINT 6.5 @Cline — Live Voice room part 2: VAD loop + speak/think + view.
import { h, svg } from "./dom";
import { ICONS } from "./icons";
import { Bridge } from "../core/bridge";
import { Sound } from "../core/sound";
import type { HubActions } from "./hub-common";
import { createShell, ensureMic, type LiveCtx, type LivePhase } from "./hub-voice-mic";

function setPhase(ctx: LiveCtx, next: LivePhase, label?: string): void {
  ctx.phase = next;
  ctx.room.dataset["live"] = next;
  ctx.stateLabel.textContent = label ?? next.toUpperCase();
  ctx.stateLabel.className = `livevoice-state ${next}`;
}

function level(ctx: LiveCtx): number {
  if (!ctx.analyser) return 0;
  const buf = new Uint8Array(ctx.analyser.fftSize);
  ctx.analyser.getByteTimeDomainData(buf);
  let sum = 0;
  for (const v of buf) {
    const d = (v - 128) / 128;
    sum += d * d;
  }
  return Math.sqrt(sum / buf.length);
}

function tick(ctx: LiveCtx): void {
  if (ctx.stopping) return;
  ctx.raf = window.requestAnimationFrame(() => tick(ctx));
  ctx.lastLevel = level(ctx);
  const bars = ctx.waves.querySelectorAll("i");
  const now = Date.now();
  bars.forEach((bar, i) => {
    const live = ctx.phase === "listening" || ctx.phase === "speaking";
    const hgt = 22 + Math.min(78, ctx.lastLevel * 420 * (0.5 + 0.5 * Math.sin(now / 180 + i)));
    (bar as HTMLElement).style.height = `${live ? hgt : 26}%`;
  });
  if (ctx.phase === "idle" || ctx.phase === "speaking") {
    if (ctx.lastLevel > 0.04) {
      setPhase(ctx, "listening", "Listening…");
      ctx.heardSince = now;
      ctx.silenceSince = now;
    }
  } else if (ctx.phase === "listening") {
    if (ctx.lastLevel > 0.04) {
      ctx.heardSince = now;
      ctx.silenceSince = now;
    } else if (now - ctx.silenceSince > 900 && now - ctx.heardSince > 600) {
      think(ctx, "Got it — thinking…");
    }
  }
}

function speak(ctx: LiveCtx, text: string): void {
  setPhase(ctx, "speaking", "Speaking…");
  ctx.caption.textContent = text;
  Sound.play("send");
  let done = false;
  const release = () => {
    if (done || ctx.stopping) return;
    done = true;
    setPhase(ctx, "listening", "Listening…");
  };
  try {
    if ("speechSynthesis" in window) {
      window.speechSynthesis.cancel();
      const utter = new SpeechSynthesisUtterance(text);
      utter.onend = release;
      window.speechSynthesis.speak(utter);
      setTimeout(release, 6000);
    } else {
      setTimeout(release, 2500);
    }
  } catch {
    setTimeout(release, 2500);
  }
}

async function think(ctx: LiveCtx, heard: string): Promise<void> {
  if (ctx.stopping || ctx.phase === "thinking" || ctx.phase === "speaking") return;
  setPhase(ctx, "thinking", "Thinking…");
  const short = heard.length > 140 ? `${heard.slice(0, 140)}…` : heard;
  ctx.caption.textContent = short || "Thinking…";
  Sound.play("think");
  try {
    const reply = await Bridge.chatSend(heard || "Hello", null);
    const text =
      typeof reply?.text === "string" && reply.text.trim().length > 0
        ? reply.text.trim()
        : "I'm here — say that again?";
    if (!ctx.stopping) speak(ctx, text);
  } catch {
    if (!ctx.stopping) speak(ctx, "I'm here — say that again?");
  }
}

async function toggle(ctx: LiveCtx): Promise<void> {
  if (!ctx.started) {
    ctx.started = true;
    ctx.stopping = false;
    ctx.startBtn.textContent = "Stop";
    setPhase(ctx, "idle", "Starting mic…");
    const ok = await ensureMic(ctx);
    if (!ok) {
      ctx.started = false;
      ctx.startBtn.textContent = "Start";
      setPhase(ctx, "idle", "Mic blocked");
      return;
    }
    ctx.silenceSince = Date.now();
    ctx.heardSince = Date.now();
    setPhase(ctx, "listening", "Listening…");
    ctx.caption.textContent = "Speak — silence ends your turn automatically.";
    try {
      ctx.recognition?.start?.();
    } catch { /* VAD path covers it */ }
    window.cancelAnimationFrame(ctx.raf);
    tick(ctx);
    return;
  }
  stop(ctx);
}

function stop(ctx: LiveCtx): void {
  ctx.stopping = true;
  ctx.started = false;
  ctx.startBtn.textContent = "Start";
  window.cancelAnimationFrame(ctx.raf);
  try {
    ctx.recognition?.stop?.();
  } catch { /* already stopped */ }
  ctx.recognition = null;
  try {
    if ("speechSynthesis" in window) window.speechSynthesis.cancel();
  } catch { /* no TTS */ }
  try {
    ctx.stream?.getTracks().forEach((t) => t.stop());
  } catch { /* already stopped */ }
  ctx.stream = null;
  try {
    void ctx.audioCtx?.close();
  } catch { /* already closed */ }
  ctx.audioCtx = null;
  ctx.analyser = null;
  setPhase(ctx, "idle", "Tap Start to talk");
  ctx.caption.textContent = "Two-way voice: speak, Mochi thinks, then answers aloud.";
  ctx.stopping = false;
}

export function buildLiveVoiceView(_actions: HubActions) {
  const ctx = createShell(setPhase, think, toggle);
  const orb = h("div", { class: "livevoice-orb" });
  // SPRINT 6.5 @Buffy glyphs (fill-mode, plain svg() call, no stroke opt):
  // mic crowns the room header, waveform accents the wave strip.
  const header = h(
    "div",
    { class: "hub-title", style: "align-self:center" },
    svg(ICONS.mic, 13),
    h("span", { text: "Live Voice Mode" }),
  );
  const waveAccent = svg(ICONS.waveform, 13);
  waveAccent.style.opacity = "0.7";
  ctx.room.append(header, orb, ctx.stateLabel, ctx.waves, waveAccent, ctx.caption);
  ctx.room.append(h("div", { class: "livevoice-controls" }, ctx.startBtn));
  const el = h("div", { class: "view" }, h("div", { class: "card" }, ctx.room));
  return { el, sync() {} };
}
