# Third-party notice — vendored Coucou desktop UI

The files staged next to this notice are a build of upstream **`Louis-CFM/coucou`**,
branch `windows/` (`index.html` + `assets/*.js` + `assets/*.css`), produced by
`tools/stage-coucou-web.mjs` with `vite build`. They are bundled unmodified except for
the two adaptations that script applies and documents: the `viewport` width and the
`tauri-shim.js` script tag.

`tauri-shim.js` is ours: it replaces Tauri's IPC with a call into `CoucouNative.invoke()`
in `IslandBridgeHost.kt`. No file under upstream `src/` was edited.

## Licence

Upstream `windows/` is **MIT**, © 2026 Louis Raillé. The full text ships verbatim as
`LICENSE.upstream`; the copyright notice and permission grant are preserved here and in
the app.

## Assets that are NOT covered by that licence

Upstream's `LICENSE-ASSETS.md` reserves the Mochi character artwork, the app icons and
**all 28 sounds** for the author's own use, and requires forks to ship their own. None of
those files are vendored here:

- **sounds** — not copied. The 28 WAVs live once in `res/raw/coucou_*.wav` (@Buffy's
  lane) and are served to the page from there at `/sounds/<name>.wav`.
- **icons / artwork** — the page draws everything procedurally (canvas + inline SVG in
  `views/icons.ts`); the APK uses this project's own launcher icon.

The character this UI draws is therefore upstream's reserved Mochi design. That is
acceptable for **personal use only**, as recorded on the board; anything distributed needs
the author's written permission or a replacement character.

## Regenerating

```
node tools/stage-coucou-web.mjs              # defaults to /workspaces/Binder_vps/coucou/windows
node tools/stage-coucou-web.mjs <checkout>
node tools/verify-shim.mjs                   # checks the shim against the page's IPC contract
```

Requires `node` on `PATH`, and network access the first time for `npm ci`. `npx vite
build` is invoked directly so the `prebuild` cargo step never runs — no Rust toolchain is
involved. Everything under `assets/coucou/` is generated; edit `tools/tauri-shim.js`, not
the staged copy.