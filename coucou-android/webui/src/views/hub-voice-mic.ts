// SPRINT 6.5 @Cline — Live Voice room part 1: shell + phase + mic.
import { h } from "./dom";
import { Bridge } from "../core/bridge";
import { Sound } from "../core/sound";
import "./hub.css";

export type LivePhase = "idle" | "listening" | "thinking" | "speaking";

export interface LiveCtx {
  phase: LivePhase;
  started: boolean;
  stopping: boolean;
  recognition: { start?: () => void; stop?: () => void } | null;
  audioCtx: AudioContext | null;
  analyser: AnalyserNode | null;
  stream: MediaStream | null;
  raf: number;
  silenceSince: number;
  heardSince: number;
  lastLevel: number;
  room: HTMLElement;
  stateLabel: HTMLElement;
  caption: HTMLElement;
  waves: HTMLElement;
  startBtn: HTMLElement;
  setPhase(next: LivePhase, label?: string): void;
  think(heard: string): void;
}

export function createShell(
  setPhaseImpl: (ctx: LiveCtx, next: LivePhase, label?: string) => void,
  thinkImpl: (ctx: LiveCtx, heard: string) => void,
  toggleImpl: (ctx: LiveCtx) => void,
): LiveCtx {
  const ctx = {} as LiveCtx;
  ctx.phase = "idle";
  ctx.started = false;
  ctx.stopping = false;
  ctx.recognition = null;
  ctx.audioCtx = null;
  ctx.analyser = null;
  ctx.stream = null;
  ctx.raf = 0;
  ctx.silenceSince = 0;
  ctx.heardSince = 0;
  ctx.lastLevel = 0;
  ctx.room = h("div", { class: "livevoice-room" });
  ctx.stateLabel = h("div", { class: "livevoice-state", text: "Tap Start to talk" });
  ctx.caption = h("div", {
    class: "livevoice-caption",
    text: "Two-way voice: speak, Mochi thinks, then answers aloud.",
  });
  const bars: HTMLElement[] = [];
  for (let i = 0; i < 16; i++) bars.push(h("i", {}));
  ctx.waves = h("div", { class: "livevoice-waves" }, ...bars);
  ctx.startBtn = h("button", { class: "hub-btn", text: "Start", onclick: () => toggleImpl(ctx) });
  ctx.setPhase = (next, label) => setPhaseImpl(ctx, next, label);
  ctx.think = (heard) => thinkImpl(ctx, heard);
  return ctx;
}

export async function ensureMic(ctx: LiveCtx): Promise<boolean> {
  try {
    const granted = await Bridge.checkMicPermission();
    if (granted === false) {
      ctx.caption.textContent = "Microphone blocked — allow it in settings, then Start again.";
      return false;
    }
  } catch { /* no host opinion; try getUserMedia directly */ }
  try {
    const w = window as unknown as {
      SpeechRecognition?: new () => LiveRec;
      webkitSpeechRecognition?: new () => LiveRec;
    };
    const SR = w.SpeechRecognition ?? w.webkitSpeechRecognition;
    if (typeof SR === "function") {
      const rec = new SR();
      rec.lang = "en-US";
      rec.interimResults = false;
      rec.onresult = (e) => {
        try {
          const last = e.results[e.results.length - 1];
          const text = String(last?.[0]?.transcript ?? "").trim();
          if (text) ctx.think(text);
        } catch { /* ignore partial failures */ }
      };
      rec.onerror = () => {};
      rec.onend = () => {
        if (ctx.started && !ctx.stopping && ctx.phase === "listening") {
          try {
            rec.start();
          } catch { /* retry on next toggle */ }
        }
      };
      ctx.recognition = rec;
    }
  } catch {
    ctx.recognition = null;
  }
  try {
    if (!navigator.mediaDevices?.getUserMedia) {
      ctx.caption.textContent = "This WebView has no mic capture — showing the voice room preview.";
      return true;
    }
    ctx.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const w = window as unknown as { webkitAudioContext?: typeof AudioContext };
    const Ctx = window.AudioContext ?? w.webkitAudioContext;
    if (Ctx) {
      ctx.audioCtx = new Ctx();
      const src = ctx.audioCtx.createMediaStreamSource(ctx.stream);
      ctx.analyser = ctx.audioCtx.createAnalyser();
      ctx.analyser.fftSize = 1024;
      src.connect(ctx.analyser);
    }
    Sound.play("pop");
    return true;
  } catch {
    ctx.caption.textContent = "Microphone blocked — allow it in settings, then Start again.";
    return false;
  }
}

export interface LiveRec {
  lang: string;
  interimResults: boolean;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null;
  onerror: (() => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
}
