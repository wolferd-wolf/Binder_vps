import react from '@vitejs/plugin-react'
import { createRequire } from 'module'
import path from 'path'
import { fileURLToPath } from 'url'
import { defineConfig } from 'vite'

import tailwindcss from '@tailwindcss/vite'

// HermesAndroid standalone renderer build.
// Upstream apps/desktop/vite.config.ts pins its React-compiler cache toolchain
// to the repo-root package-lock.json (../../package-lock.json) and resolves
// react/driver.js from a root-hoisted install. This standalone config keeps
// the same aliases (including ../shared) and base './' for the WebView, but
// resolves everything from webui/node_modules so `npm install && npm run
// build:android` works without the hermes-agent repo root.
const __dirname = path.dirname(fileURLToPath(import.meta.url))

const requireFromApp = createRequire(path.join(__dirname, 'vite.android.config.ts'))
const driverDir = path.dirname(requireFromApp.resolve('driver.js'))
const driverIife = path.join(driverDir, 'driver.js.iife.js')

export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss()],
  css: { postcss: { plugins: [] } },
  build: {
    outDir: 'dist-android',
    emptyOutDir: true,
    chunkSizeWarningLimit: 25000,
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@hermes/plugin-sdk': path.resolve(__dirname, './src/sdk/index.ts'),
      '@hermes/shared/billing': path.resolve(__dirname, '../shared/src/billing-types.ts'),
      '@hermes/shared/color': path.resolve(__dirname, '../shared/src/color.ts'),
      '@hermes/shared': path.resolve(__dirname, '../shared/src'),
      // Absolute file path bypasses driver.js's `exports` map (which does
      // not expose the prebuilt IIFE used as `?raw` source for the tour).
      'driver.js/dist/driver.js.iife.js?raw': `${driverIife}?raw`,
      'driver.js/dist/driver.js.iife.js': driverIife,
    },
    dedupe: ['react', 'react-dom', 'react-router', '@tanstack/react-query'],
  },
})
