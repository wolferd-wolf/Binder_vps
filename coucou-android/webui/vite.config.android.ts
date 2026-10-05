import { defineConfig } from "vite";
import { resolve } from "node:path";

export default defineConfig({
  plugins: [],
  clearScreen: false,
  // The Android build talks to Kotlin through window.CoucouAndroid, not Tauri. Swap the
  // desktop bridge for its Android twin so every `Bridge.*` call in `src/` resolves to
  // named `CoucouAndroid` methods; the desktop build keeps the Tauri bridge untouched.
  resolve: {
    alias: [
      {
        find: /^\.\.?\/(?:.*\/)?core\/bridge$/,
        replacement: resolve(__dirname, "src/core/bridge.android.ts"),
      },
    ],
  },
  server: { port: 1420, strictPort: true, host: "127.0.0.1" },
  envPrefix: ["VITE_", "TAURI_ENV_"],
  build: {
    target: "chrome110",
    minify: "esbuild",
    sourcemap: false,
    emptyOutDir: true,
    rollupOptions: {
      input: {
        island: resolve(__dirname, "index.html"),
        settings: resolve(__dirname, "settings.html"),
      },
    },
  },
});
