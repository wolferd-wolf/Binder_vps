# 001_architecture_baseline.md

## Concept
Android floating assistant inspired by `Louis-CFM/coucou`.

## System Overlay
Uses `SYSTEM_ALERT_WINDOW` permission with a foreground `OverlayService`.

## States
1. **Collapsed draggable bubble on screen edge with physics** — The UI appears as a small, draggable bubble anchored to a screen edge, with spring-physics drag behavior.
2. **Tap to expand into a query/ask bar** — Tapping the bubble expands it into a full-width ask bar containing an input text field, a microphone button for voice input, and a close (dismiss) action.

## Intent Dispatch
`CommandRouter` and `AppLauncher` via `PackageManager` route intents to appropriate handlers or launch external apps.

## States Transitions
- Bubble (collapsed) → Ask bar (expanded) on tap
- Ask bar → Bubble on close/collapse
- Ask bar → System dismissal on back-press or outside-tap

## Pod Roles
- `@AGY` (Pane 0): AndroidManifest, overlay permission gate, emulator QA.
- `@OpenCode` (Pane 1): `OverlayService`, coordinate drag physics, `CommandRouter`.
- `@Buffy` (Pane 2): Sounds, animations, design tokens from `Louis-CFM/coucou`.
- `@Cline` (Pane 3): Collapsed bubble XML and expanded ask bar layouts.