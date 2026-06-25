#!/usr/bin/env bash
# build_nyx_rom.sh — builds a full Nyx AOSP ROM for the Pixel target.
# Usage: ./scripts/build_nyx_rom.sh [--clean] [--jobs N]
# Prerequisites: AOSP source tree at $AOSP_ROOT, NyxAgent.apk in aosp/packages/apps/NyxAgent/
set -euo pipefail

AOSP_ROOT="${AOSP_ROOT:-$HOME/aosp}"
TARGET="nyx_phone-userdebug"
JOBS="${JOBS:-$(nproc)}"

while [[ $# -gt 0 ]]; do
    case $1 in
        --clean) CLEAN=1 ;;
        --jobs) JOBS="$2"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
    shift
done

echo "[nyx-rom] Syncing AOSP source..."
cd "$AOSP_ROOT"
repo sync -j"$JOBS" --force-sync

echo "[nyx-rom] Copying Nyx device config..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp -r "$SCRIPT_DIR/../aosp/device/nyx" "$AOSP_ROOT/device/nyx"
cp -r "$SCRIPT_DIR/../aosp/packages/apps/NyxAgent" "$AOSP_ROOT/packages/apps/NyxAgent"
cp "$SCRIPT_DIR/../aosp/frameworks/base/core/res/res/xml/privapp-permissions-nyx.xml" \
   "$AOSP_ROOT/frameworks/base/core/res/res/xml/"

echo "[nyx-rom] Setting up build environment..."
source "$AOSP_ROOT/build/envsetup.sh"
lunch "$TARGET"

[[ "${CLEAN:-0}" == "1" ]] && make clean

echo "[nyx-rom] Building ROM (jobs=$JOBS)..."
make -j"$JOBS" 2>&1 | tee build_nyx_rom.log

echo "[nyx-rom] Build complete. OTA package at: $AOSP_ROOT/out/target/product/pixel/nyx_phone-ota.zip"
