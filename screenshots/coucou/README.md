# Coucou Android Sprint 3 Phone Screenshots Breakdown

Inspection of the 4 Coucou screenshots captured on device (vivo T3x):

## File Mapping

1. **`Screenshot_20261005_111443.jpg`**: **Home View (Integrations)**
   - Active tab: Home icon (leftmost header tab).
   - Content: Integration cards (VS Code Integration, Resend, Vercel).

2. **`Screenshot_20261005_111446.jpg`**: **Chat View**
   - Active tab: Chat icon (speech bubble tab).
   - Content: Chat log ("Opened YouTube", user chip "Yo"), Mochi character on the left, prompt input bar ("Continue...") with circular send button.

3. **`Screenshot_20261005_111457.jpg`**: **"+" View (Drop Files Card)**
   - Active tab: "+" icon.
   - Content: Dashed drop zone card ("Drop your files here", chips for PDF, Images, Code, etc.) with centered Mochi.

4. **`Screenshot_20261005_111510.jpg`**: **Collapsed View**
   - Content: Small compact native rounded rectangle with Mochi floating over screen content.

---

## What WORKS

- **Chat View (`Screenshot_20261005_111446.jpg`)**:
  - The chat interface displays properly with the character on the left, chat message history, and the "Continue..." input bar with send button. Height fits the chat contents cleanly.
- **"+" View (`Screenshot_20261005_111457.jpg`)**:
  - The drop-zone card displays cleanly with dashed border outline, centered Mochi, and format chips (PDF, Images, Code, etc.).
- **Collapsed View (`Screenshot_20261005_111510.jpg`)**:
  - Small rounded black rectangle with Mochi floating cleanly on the screen, isolated touch bounds, no screen blockage.

---

## What is BROKEN

1. **Home View Cut Off & Mochi Overlapping (`Screenshot_20261005_111443.jpg`)**:
   - The Home view window height is truncated/cut off at the bottom, partially clipping the integration cards (Resend, Vercel).
   - The Mochi avatar is positioned directly on top of / overlapping the left side of the VS Code integration card and text ("Hooks not installed", "Open Visual Studio Code Settings...").
2. **Square Top Corners**:
   - In all expanded views (`111443`, `111446`, `111457`), the top-left and top-right corners of the main black panel are completely square (sharp 90-degree corners), whereas the bottom corners are rounded. All four corners of the panel need the same uniform rounded radius.
3. **Inconsistent Left Offset / Margins**:
   - The horizontal positioning and margins differ between views (e.g., the "+" view is positioned closer to the screen left edge than the chat view; left and right margins are uneven across views). Sizing and margins must be consistent across all views.
