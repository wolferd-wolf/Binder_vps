/*
 * Coucou desktop IPC shim for the Android WebView.
 *
 * Upstream's `windows/` frontend talks to Tauri through exactly three internals:
 *
 *   window.__TAURI_INTERNALS__.invoke(cmd, args, options)   -> Promise
 *   window.__TAURI_INTERNALS__.transformCallback(fn, once)   -> number (id)
 *   window.__TAURI_EVENT_PLUGIN_INTERNALS__.unregisterListener(event, id)
 *
 * plus a synchronous read of `metadata.currentWebview.label` from `getCurrentWebview()`
 * and the literal probe `"__TAURI_INTERNALS__" in window` in `core/bridge.ts`.
 *
 * This file installs all of them on top of a single synchronous
 * `window.CoucouNative.invoke(cmd, argsJson)` method exposed by Kotlin, which answers
 * with the envelope `{"ok":true,"value":...}` or `{"ok":false,"error":"..."}`.
 * Nothing in `src/` is patched: the same bundle that ships on desktop runs here.
 *
 * Loaded as a classic <script> in <head>, so it is fully installed before the deferred
 * module bundle in <body> evaluates.
 *
 * Copyright (c) 2026 Louis Raillé — upstream `windows/` (MIT), see LICENSE.upstream.
 */
(function () {
  "use strict";

  var NATIVE = "CoucouNative";
  var callbacks = Object.create(null); // id -> { fn, once, event }
  var listeners = Object.create(null); // event -> [id]
  var nextCallbackId = 0;

  function listenersFor(event) {
    if (!listeners[event]) listeners[event] = [];
    return listeners[event];
  }

  function registerCallback(fn, once, event) {
    var id = ++nextCallbackId;
    callbacks[id] = { fn: fn, once: !!once, event: event || null };
    if (event) listenersFor(event).push(id);
    return id;
  }

  function dropCallback(id) {
    var slot = callbacks[id];
    if (!slot) return;
    delete callbacks[id];
    if (!slot.event) return;
    var ids = listeners[slot.event] || [];
    var at = ids.indexOf(id);
    if (at >= 0) ids.splice(at, 1);
  }

  /**
   * Hands a Tauri event object to one registered callback, mirroring what the Rust
   * side sends over IPC: `{ event, id, payload }`. `once` listeners are dropped first,
   * because upstream's own `once()` wrapper calls unlisten from inside the handler.
   */
  function deliverCallback(id, event, payloadJson) {
    var slot = callbacks[id];
    if (!slot) return;
    if (slot.once) dropCallback(id);
    var payload = null;
    if (payloadJson !== null && payloadJson !== undefined) {
      try {
        payload = JSON.parse(payloadJson);
      } catch (err) {
        payload = null;
      }
    }
    try {
      slot.fn({ event: event, id: Number(id), payload: payload });
    } catch (err) {
      console.error("[coucou-shim] listener for " + event + " threw", err);
    }
  }

  /**
   * `plugin:event|listen`, `|unlisten` and `|emit` are bookkeeping between the page's
   * own callbacks and the host, not real native calls: the host has no event bus, so they
   * are answered here. Returns the value the Promise should settle with, or null when
   * this is not an event command at all.
   */
  function trackEventListener(cmd, args) {
    if (cmd === "plugin:event|listen") {
      var event = args && args.event;
      var id = args && args.handler;
      if (typeof event === "string" && typeof id === "number") {
        listenersFor(event).push(id);
        if (callbacks[id]) callbacks[id].event = event;
      }
      return typeof id === "number" ? id : 0;
    }
    if (cmd === "plugin:event|unlisten") {
      dropCallback(args && args.eventId);
      return 0;
    }
    if (cmd === "plugin:event|emit") {
      var name = args && args.event;
      if (typeof name === "string") emitEvent(name, args.payload === undefined ? null : JSON.stringify(args.payload));
      return null;
    }
    return null;
  }

  function invoke(cmd, args, options) {
    return new Promise(function (resolve, reject) {
      var tracked = trackEventListener(cmd, args);
      if (tracked !== null) {
        resolve(tracked);
        return;
      }
      var native = window[NATIVE];
      if (!native || typeof native.invoke !== "function") {
        reject("Coucou native bridge unavailable");
        return;
      }
      var raw;
      try {
        raw = native.invoke(cmd, JSON.stringify(args || {}));
      } catch (err) {
        reject(String((err && err.message) || err));
        return;
      }
      var envelope;
      try {
        envelope = JSON.parse(raw);
      } catch (err) {
        console.error("[coucou-shim] malformed reply for " + cmd, raw);
        resolve(null);
        return;
      }
      if (envelope && envelope.ok) {
        resolve(envelope.value === undefined ? null : envelope.value);
      } else {
        reject((envelope && envelope.error) || "unknown error");
      }
    });
  }

  // ── the three internals upstream actually reads ──────────────────────────────
  var internals = window.__TAURI_INTERNALS__ || {};
  internals.invoke = invoke;
  internals.transformCallback = function (callback, once) {
    // Listeners get their event name attached by trackEventListener, the only path that
    // knows which event a callback belongs to; anything else stays unaddressable.
    return registerCallback(callback, once, null);
  };
  internals.metadata = internals.metadata || {};
  internals.metadata.currentWindow = internals.metadata.currentWindow || { label: "main" };
  internals.metadata.currentWebview = internals.metadata.currentWebview || { label: "main" };
  internals.convertFileSrc = function (path) {
    return path;
  };
  window.__TAURI_INTERNALS__ = internals;

  window.__TAURI_EVENT_PLUGIN_INTERNALS__ = window.__TAURI_EVENT_PLUGIN_INTERNALS__ || {};
  window.__TAURI_EVENT_PLUGIN_INTERNALS__.unregisterListener = function (event, id) {
    dropCallback(id);
  };

  /**
   * `listen()` from @tauri-apps/api funnels through `plugin:event|listen`, so tagging
   * callbacks by event name happens in [invoke] rather than in a patched copy of the
   * library — the library itself stays untouched.
   */

  // ── Kotlin -> JS helpers (the "tray" channel of the desktop app) ─────────────
  function emitEvent(event, payloadJson) {
    var ids = (listeners[event] || []).slice();
    for (var i = 0; i < ids.length; i++) {
      deliverCallback(ids[i], event, payloadJson);
    }
    return ids.length;
  }

  function clickTab(index) {
    var tab = document.querySelectorAll("#header .tabs .tab")[index];
    if (tab) tab.click();
  }

  window.CoucouIsland = {
    /** Delivers a synthetic event, exactly like the Rust side emitting one. */
    emit: function (event, payloadJson) {
      return emitEvent(event, payloadJson === undefined ? null : payloadJson);
    },

    /** Reveals the island on its home view (tab bar + home cards). */
    showHome: function () {
      return window.CoucouIsland.emit("tray", JSON.stringify("open"));
    },

    /** Reveals the island directly on the settings card. */
    showSettings: function () {
      return window.CoucouIsland.emit("tray", JSON.stringify("settings"));
    },

    /**
     * Opens the chat view. There is no desktop event for it — the chat tab is a plain
     * click — so reveal first and then click the tab, once the reveal has settled.
     */
    showChat: function () {
      window.CoucouIsland.emit("tray", JSON.stringify("open"));
      window.setTimeout(function () {
        clickTab(1);
      }, 380);
      return true;
    },

    /** Diagnostics for logcat: island geometry and which view is on screen. */
    describe: function () {
      var island = document.getElementById("island");
      var content = document.getElementById("content");
      var shown = document.querySelector("#views .view.on");
      return JSON.stringify({
        ready: !!island,
        width: island ? island.style.width : null,
        height: island ? island.style.height : null,
        opacity: content ? content.style.opacity : null,
        view: shown ? shown.className : null
      });
    }
  };

  console.log("[coucou-shim] Tauri IPC shim installed");
})();