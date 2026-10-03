# APKs

Prebuilt installable APKs, so you can test without setting up a build.

## coucou-android-1.0.0-debug.apk

| | |
|---|---|
| Package | `com.coucou.android` |
| Version | 1.0.0 (versionCode 1) |
| Size | 8.0 MB |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |
| Signed | Yes — Android **debug** key |

Sprint 1: floating bubble over other apps, drag to move, tap to expand,
type an app name to launch it (e.g. "chrome" opens Chrome).

### Install

```bash
adb install -r apks/coucou-android-1.0.0-debug.apk
```

Or copy the file to the phone and open it (needs "install unknown apps"
allowed for whatever app you open it with).

### Grant the overlay permission

This is the one manual step. The bubble cannot show without it, and it
cannot be granted automatically:

1. Open **Coucou** and tap **Grant Overlay Permission**
2. Enable the toggle for Coucou
3. Back in Coucou, tap **Start Floating Bubble**

The permission is `SYSTEM_ALERT_WINDOW`. On Android 10+ Android only
grants it when the app is in the foreground, which is why the app walks
you to the system settings screen.

### Known limitations (Sprint 1)

- **No sounds.** `SoundPlayer` is wired at every trigger point and
  resolves `res/raw/coucou_*` by name, but the audio assets are on hold
  pending an asset-source decision. The calls are silent no-ops until the
  files land, at which point sounds work with no code change.
- **No animation.** Same reason — no assets ported yet.
- **No voice input.** The mic button acknowledges the tap and shows a
  "not available yet" toast.
- **No AI.** Out of scope for Sprint 1 by design.

### Rebuilding

```bash
cd coucou-android
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 13 tests
```

A release build also exists but is **unsigned**
(`app-release-unsigned.apk`) and will not install until it is signed with
a real key, so it is not published here.