# Coucou Android — Subsystems Reference

This document provides a concise reference to the internal subsystems of Coucou Android (`com.coucou.android`). For the full architectural guide and usage manual, please see [README.md](file:///workspaces/Binder_vps/apk/app_systeans/README.md).

## Quick Subsystem Index

| Subsystem | Primary Class | Key Responsibilities | Android APIs Used |
|---|---|---|---|
| **Host Activity** | [`MainActivity`](file:///workspaces/Binder_vps/coucou-android/app/src/main/java/com/coucou/android/MainActivity.kt) | Permissions check & request, service start/stop UI, status observation | `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`, `ActivityResultContracts` |
| **Window Overlay** | [`OverlayService`](file:///workspaces/Binder_vps/coucou-android/app/src/main/java/com/coucou/android/OverlayService.kt) | Floating window lifecycle, touch drag & tap gesture discrimination, state switching | `WindowManager`, `TYPE_APPLICATION_OVERLAY`, `ForegroundService` |
| **Command Router** | [`CommandRouter`](file:///workspaces/Binder_vps/coucou-android/app/src/main/java/com/coucou/android/CommandRouter.kt) | Lexical tokenization, action verb extraction, extensible handler routing | Kotlin sealed classes (`CommandResult`), interface chain |
| **App Launcher** | [`AppLauncher`](file:///workspaces/Binder_vps/coucou-android/app/src/main/java/com/coucou/android/AppLauncher.kt) | 5-tier fuzzy app matching, package discovery, intent launching, 30s TTL cache | `PackageManager`, `Intent.CATEGORY_LAUNCHER`, `FLAG_ACTIVITY_NEW_TASK` |
| **Audio Subsystem** | [`SoundPlayer`](file:///workspaces/Binder_vps/coucou-android/app/src/main/java/com/coucou/android/SoundPlayer.kt) | Low-latency UI sound playback, dynamic resource resolution, no-op fallback | Android `SoundPool`, `AudioAttributes.USAGE_ASSISTANCE_SONIFICATION` |

---

## State Transition Table

```
           [Service Stopped]
                  │  ACTION_START / MainActivity
                  ▼
     ┌───────────────────────────┐
     │   COLLAPSED BUBBLE        │
     │   - Wrap Content width    │◄────────────────────────┐
     │   - FLAG_NOT_FOCUSABLE    │                         │
     └─────────────┬─────────────┘                         │
                   │                                       │
            Tap    │   Drag                                │ Tap Close or
          Gesture  │  Gesture                              │ Launch Success
                   ▼     │                                 │ (400ms delay)
     ┌───────────────────▼───────┐                         │
     │   DRAG REPOSITIONING      │                         │
     │   - Bounds Clamping       │                         │
     └─────────────┬─────────────┘                         │
                   │                                       │
            Release│                                       │
                   ▼                                       │
     ┌───────────────────────────┐                         │
     │   EXPANDED ASK BAR        │                         │
     │   - Match Parent width    │─────────────────────────┘
     │   - Soft Keyboard IME     │
     └───────────────────────────┘
```
