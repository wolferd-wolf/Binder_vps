# Coucou Desktop Prompt Box & Animation Specification Report

**Source Reference:** `Louis-CFM/coucou` upstream clone (`coucou/windows/src/style.css`, `coucou/windows/src/mochi/engine.ts`)  
**Target:** `coucou-android` floating window overlay  
**Author:** @AGY (Architecture Lead & QA)  
**Date:** 2026-10-04  

---

## 1. Upstream Desktop Prompt Box Design Values

From `coucou/windows/src/style.css` (lines 10–29, 280–302, 747–900):

### 1.1 Color Palette
* **Card Background (`--card`):** `#141518` (Dark matte background for collapsed bubble and expanded ask bar)
* **Card Surface Flat (`--card-flat`):** `#0E0F11` (Deep dark background)
* **Primary Text (`--ink`):** `#F5F6F8` (Crisp high-contrast light text)
* **Secondary Text (`--ink-2`):** `#F1F2F4`
* **Muted / Placeholder Text (`--dim-3`):** `#6B7079`
* **Subtle Hairline / Border (`--hairline`):** `rgba(255, 255, 255, 0.035)` (`#09FFFFFF`)
* **Input Container Background:** `rgba(255, 255, 255, 0.07)` (`#12FFFFFF`)
* **Bubble / Wash Highlight (`--wash`):** `radial-gradient` or soft white overlay (`rgba(255, 255, 255, 0.13)`)
* **Send / Action Button:** `#F5F6F8` circular button with `#0B0C0E` dark icon

### 1.2 Shape, Corner Radius & Padding
* **Prompt Card (`.card`, `.chat-card`):**
  * `border-radius: 20dp` (M3 extra-large / rounded pill corner)
  * `border: 1px solid rgba(255, 255, 255, 0.035)`
  * `background: #141518`
* **Ask Input Bar (`.chat-bar`):**
  * `background: rgba(255, 255, 255, 0.07)`
  * `border-radius: 12dp`
  * `padding: 6dp 10dp`
  * `margin / gap: 8dp`
* **Ask Input Field (`.chat-input`):**
  * `font-size: 13sp`
  * `color: #F5F6F8`
  * `hint/placeholder color: #6B7079`
* **Character Placement:**
  * Collapsed Bubble: 44dp `CoucouCharacterView` centered in 56dp card (`#141518`, 20dp corner radius).
  * Expanded Ask Bar: 40dp `CoucouCharacterView` avatar leading the row with 8dp marginEnd before the input bar.

---

## 2. Animation Mechanics & Exact Upstream Timings

From `coucou/windows/src/mochi/engine.ts`:

### 2.1 Idle Breathing / Gentle Floating Bob
* **Upstream formula:** `tgSy = 1 + sin(t * 1.8) * 0.035`, `tgSx = 1 - sin(t * 1.8) * 0.02`
* **Cycle Duration:** `2 * π / 1.8` ≈ **3.49 seconds** (matches requested ~3s cycle)
* **Bob Amplitude:** ~3.5% scale oscillation + ~4dp vertical translation floating loop.
* **Continuous:** Infinite while active.

### 2.2 Eye Blinking
* **Interval:** Random interval every **2.2s to 5.4s** (`nextBlink = now + 2.2 + rand(0, 3.2)`).
* **Blink Duration:** Total **200ms**
  * Eye closes to slot (`scaleY: 0.06`) over **70ms** (`Ease.inOut`).
  * Eye reopens to normal (`scaleY: 1.0`) over **130ms** (`Ease.out`).
* **Double-Blink Chance:** 22% chance of an immediate second blink scheduled **230ms** after the first.

### 2.3 Eye Look-Around (Wandering)
* **Idle Target:** Random target `x ∈ [-0.88, 0.88]`, `y ∈ [-0.55, 0.45]`.
* **Schedule Interval:** Retargets every **0.5s to 2.0s** (`now + 0.5 + rand(0, 1.5)` per upstream `engine.ts:528`), ratified and implemented by @OpenCode (`90eeb90`) and @Buffy (`681d792`).
* **Interpolation:** Smooth spring-damped tracking:
  * `yaw += (tgYaw - yaw) * (1 - 0.0025^dt)`
  * `pitch += (tgPitch - pitch) * (1 - 0.0025^dt)`

### 2.4 Caret Follow (Typing)
* While typing in `EditText`, calculate normalized caret position:
  * `x = (caretOffset / textLength) * 2.0f - 1.0f` (clamped `-1.0f` to `1.0f`).
  * `y = 0.2f` (looking slightly downward toward input text).
* Caret follow immediately overrides idle look-around.
* Reset to `(0f, 0f)` after 1.5s of typing inactivity.

### 2.5 Screen Off / Window Hidden Pausing
* When screen turns off (`ACTION_SCREEN_OFF`) or overlay window is hidden:
  * Call `characterView.animate = false`
  * Pauses `ValueAnimator` frame tick to guarantee 0% CPU consumption in background.
* Resume with `characterView.animate = true` on `ACTION_SCREEN_ON` or window shown.

---

## 3. CharacterView API Specification

Already present and validated in `CoucouCharacterView.kt`:

```kotlin
// 1. State control
fun setState(next: CoucouState, force: Boolean = false): Boolean
// Example states: CoucouState.IDLE, CoucouState.WORKING, CoucouState.THINKING

// 2. Eye gaze control
fun setLook(x: Float, y: Float)   // x, y in range -1.0f .. 1.0f
fun setLookAt(x: Float, y: Float) // alias for setLook(x, y)
fun resetLook()                   // resets look to (0f, 0f)

// 3. Pause / Resume animation
var animate: Boolean              // set to false to stop frame loop; true to resume
```
