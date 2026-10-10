// SPRINT 6.7 @Cline — Live Voice room rebuilt on the UI-kit adapter (BOARD.md).
// Live Voice visuals stay in the room markup; the pulsing ripple keyframes
// remain @Buffy's lane in style.css.
import { h } from "./dom";
import { Bridge } from "../core/bridge";
import { Sound } from "../core/sound";
import type { HubActions } from "./hub-common";
import { createShell, ensureMic, syncStartLabel, type LiveCtx, type LivePhase } from "./hub-voice-mic";
import { lucide, m3AvatarOrb, m3Button, m3Card, m3IconButton, m3TonalButton } from "./ui-kit";

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
    syncStartLabel(ctx, "Stop");
    setPhase(ctx, "idle", "Starting mic…");
    const ok = await ensureMic(ctx);
    if (!ok) {
      ctx.started = false;
      syncStartLabel(ctx, "Start");
      setPhase(ctx, "idle", "Mic blocked");
      // SPRINT 6.6 @Buffy — sleek permission card instead of a bare caption.
      ctx.room.classList.add("perm");
      return;
    }
    ctx.room.classList.remove("perm");
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
  syncStartLabel(ctx, "Start");
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
  // SPRINT 6.7 — `requestMicPermission` shows Android's native prompt from a
  // transparent activity; the user's answer arrives back here as a window event
  // (Kotlin re-broadcasts it after `onRequestPermissionsResult`).
  window.addEventListener("coucou:mic-permission", (e: Event) => {
    const detail = (e as CustomEvent<boolean | string>).detail;
    const granted = detail === true || detail === "true";
    if (granted) {
      ctx.room.classList.remove("perm");
      if (!ctx.started) setPhase(ctx, "idle", "Mic allowed — tap Start");
    } else {
      ctx.room.classList.add("perm");
      setPhase(ctx, "idle", "Mic blocked");
    }
  });
  // Lucide icon kit (ISC): `mic` crowns the header, `audio-waveform` accents
  // the VAD bars, `mic-off` fronts the permission card, `sliders-horizontal`
  // opens mic settings — no hand-rolled glyph primitives.
  const avatar = m3AvatarOrb();
  const header = h(
    "div",
    { class: "hub-title", style: "align-self:center" },
    lucide("mic", 14),
    h("span", { text: "Live Voice Mode" }),
  );
  const waveAccent = lucide("waveform", 14);
  waveAccent.classList.add("livevoice-accent");
  waveAccent.style.opacity = "0.7";
  const allowBtn = m3Button("Allow Microphone", { onClick: () => requestMicAccess(ctx) });
  const laterBtn = m3TonalButton("Not now", { onClick: () => ctx.room.classList.remove("perm") });
  const micIcon = lucide("micOff", 20, 1.8);
  const permCard = h(
    "div",
    { class: "livevoice-perm" },
    m3Card(
      micIcon,
      h("div", { class: "perm-title", text: "Microphone blocked" }),
      h("div", {
        class: "perm-sub",
        text: "Allow microphone access so Mochi can hear you.",
      }),
      h("div", { class: "perm-actions" }, allowBtn, laterBtn),
    ),
  );
  // Material 3 icon button: sliders glyph routes to app settings.
  const settingsBtn = m3IconButton("sliders", {
    title: "Microphone settings",
    size: 16,
    onClick: () => openAppSettings(),
  });
  // Material 3 filled Start/Stop: fires the BOARD-mandated IslandBridge mic
  // permission request first, then runs the existing VAD toggle.
  const startBtn = m3Button("Start", {
    title: "Start Live Voice",
    onClick: () => {
      requestMicAccess(ctx, true);
      void toggle(ctx);
    },
  });
  ctx.startBtn.replaceWith(startBtn);
  ctx.startBtn = startBtn;
  // "Choose file" (BOARD.md): straight to the SAF transparent activity via
  // `window.IslandBridge?.openFilePicker`, Bridge fallback otherwise.
  const fileBtn = m3TonalButton("Choose file", {
    title: "Choose file",
    onClick: () => {
      try {
        if (typeof window.IslandBridge?.openFilePicker === "function") {
          window.IslandBridge.openFilePicker();
        } else {
          void Bridge.openFilePicker();
        }
        Sound.play("blip");
      } catch { /* picker is best-effort until Kotlin lands */ }
    },
  });
  ctx.room.append(header, avatar, ctx.stateLabel, ctx.waves, waveAccent, ctx.caption, permCard);
  ctx.room.append(h("div", { class: "livevoice-controls" }, ctx.startBtn, fileBtn, settingsBtn));
  // SPRINT 6.7: voice-view root carries the 16/20 corner-inset gutter.
  const el = h("div", { class: "view voice-view" }, h("div", { class: "card" }, ctx.room));
  return { el, sync() {} };
}

/**
 * SPRINT 6.7 @Cline — BOARD-mandated mic wiring: "Start" / "Allow Access"
 * fires `window.IslandBridge?.requestMicPermission?.()` so OpenCode's
 * transparent-activity lane shows the native RECORD_AUDIO prompt (not raw
 * settings). Falls back to the Bridge check + settings route otherwise.
 */
function requestMicAccess(ctx: LiveCtx, silent = false): void {
  try {
    if (typeof window.IslandBridge?.requestMicPermission === "function") {
      window.IslandBridge.requestMicPermission();
      if (!silent) ctx.room.classList.remove("perm");
      return;
    }
  } catch { /* fall through to the settings route */ }
  if (!silent) openAppSettings();
}

/**
 * SPRINT 6.6 @Buffy — 1-tap route into this app's system details page so the
 * user can grant RECORD_AUDIO. OpenCode's Kotlin lane exposes it as
 * `window.CoucouNative.openAppSettings()` (JavascriptInterface); the generic
 * `open_app_settings` command reaches the same handler through any facade.
 */
function openAppSettings(): void {
  try {
    const w = window as unknown as {
      CoucouAndroid?: {
        openAppSettings?: () => unknown;
        invoke?: (cmd: string, argsJson: string) => unknown;
      };
      CoucouNative?: {
        openAppSettings?: () => unknown;
        invoke?: (cmd: string, argsJson: string) => unknown;
      };
    };
    if (typeof w.CoucouAndroid?.openAppSettings === "function") {
      w.CoucouAndroid.openAppSettings();
      return;
    }
    if (typeof w.CoucouNative?.openAppSettings === "function") {
      w.CoucouNative.openAppSettings();
      return;
    }
    (w.CoucouAndroid ?? w.CoucouNative)?.invoke?.("open_app_settings", "{}");
  } catch {
    /* best-effort: the card stays up so the user can retry */
  }
}
