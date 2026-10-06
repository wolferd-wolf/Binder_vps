#!/usr/bin/env bash
set -euo pipefail

# Coucou Android - All-in-One Build Script (AGY)
# 1. npm ci
# 2. build webui
# 3. copy dist to app assets
# 4. gradle assembleDebug

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBUI_DIR="${ROOT_DIR}/webui"
ASSETS_DIR="${ROOT_DIR}/app/src/main/assets"
GRADLEW="${ROOT_DIR}/gradlew"

echo "=================================================="
echo " [COUCOU-ANDROID] All-in-One Build Pipeline"
echo "=================================================="

# 1. NPM CI
echo "--> [1/4] Running npm ci in ${WEBUI_DIR}..."
if [ ! -d "${WEBUI_DIR}" ]; then
  echo "Error: ${WEBUI_DIR} directory does not exist!" >&2
  exit 1
fi

cd "${WEBUI_DIR}"
if [ -f "package-lock.json" ]; then
  npm ci || npm install
else
  npm install
fi

# 2. BUILD WEBUI
echo "--> [2/4] Building WebUI..."
if grep -q '"build:android"' package.json; then
  npm run build:android
elif grep -q '"prebuild"' package.json; then
  # Skip cargo hook build by running tsc & vite directly
  npx tsc --noEmit && npx vite build
else
  npm run build
fi

if [ ! -d "dist" ]; then
  echo "Error: dist/ directory not found after build!" >&2
  exit 1
fi

# 3. PLAYWRIGHT VIEWPORT SCREENSHOTS GATE
echo "--> [3/5] Running Headless Chromium Playwright prompt view gate (360x800 & 412x915)..."
cd "${WEBUI_DIR}"
npm run screenshots

# 4. STAGE WEB ASSETS (assets/coucou)
echo "--> [4/5] Staging WebUI into Android assets (${ASSETS_DIR}/coucou)..."
mkdir -p "${ASSETS_DIR}"

if [ -f "${ROOT_DIR}/tools/stage-coucou-web.mjs" ]; then
  node "${ROOT_DIR}/tools/stage-coucou-web.mjs" "${WEBUI_DIR}"
else
  mkdir -p "${ASSETS_DIR}/coucou"
  rm -rf "${ASSETS_DIR}/coucou"/*
  cp -r dist/* "${ASSETS_DIR}/coucou/"
  if [ -f "${ROOT_DIR}/tools/tauri-shim.js" ]; then
    cp "${ROOT_DIR}/tools/tauri-shim.js" "${ASSETS_DIR}/coucou/"
  fi
fi

# Clean up any obsolete root asset duplicates
rm -f "${ASSETS_DIR}/index.html" "${ASSETS_DIR}/settings.html" "${ASSETS_DIR}/tauri-shim.js"
rm -rf "${ASSETS_DIR}/assets"

echo "Assets staged successfully:"
ls -lh "${ASSETS_DIR}/coucou"


# 5. GRADLE ASSEMBLE
echo "--> [5/5] Assembling Android Debug APK via Gradle..."
cd "${ROOT_DIR}"
chmod +x "${GRADLEW}"
"${GRADLEW}" assembleDebug

APK_PATH="${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "${APK_PATH}" ]; then
  mkdir -p "${ROOT_DIR}/../apks"
  cp "${APK_PATH}" "${ROOT_DIR}/../apks/coucou-android-debug.apk"
  cp "${APK_PATH}" "${ROOT_DIR}/../apks/test.apk"
  echo "=================================================="
  echo " [SUCCESS] Build completed successfully!"
  echo " APK location: ${APK_PATH}"
  ls -lh "${APK_PATH}"
  echo " Published to apks/coucou-android-debug.apk and apks/test.apk"
  echo "=================================================="
else
  echo "Error: APK not found at ${APK_PATH}" >&2
  exit 1
fi
