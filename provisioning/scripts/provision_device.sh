#!/usr/bin/env bash
# provision_device.sh — end-to-end NyxPhone provisioning: detect → install → configure → validate
# Usage: ./provisioning/scripts/provision_device.sh [--serial <device-serial>] [--apk <path>] [--license <key>]
set -euo pipefail

SERIAL=""
APK_PATH=""
LICENSE_KEY=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_DIR="$SCRIPT_DIR/../config"

while [[ $# -gt 0 ]]; do
    case $1 in
        --serial) SERIAL="$2"; shift ;;
        --apk) APK_PATH="$2"; shift ;;
        --license) LICENSE_KEY="$2"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
    shift
done

ADB_ARGS=()
[[ -n "$SERIAL" ]] && ADB_ARGS=(-s "$SERIAL")

echo "[provision] Waiting for ADB device..."
adb "${ADB_ARGS[@]}" wait-for-device

echo "[provision] Checking device info..."
MODEL=$(adb "${ADB_ARGS[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID_VER=$(adb "${ADB_ARGS[@]}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "[provision]   Model: $MODEL, Android: $ANDROID_VER"

if [[ -n "$APK_PATH" ]]; then
    echo "[provision] Installing NyxAgent APK..."
    "$SCRIPT_DIR/flash_nyx_apk.sh" --apk "$APK_PATH" ${SERIAL:+--serial "$SERIAL"}
fi

echo "[provision] Configuring agent..."
"$SCRIPT_DIR/configure_agent.sh" ${LICENSE_KEY:+--license "$LICENSE_KEY"} ${SERIAL:+--serial "$SERIAL"}

echo "[provision] Running acceptance tests..."
"$SCRIPT_DIR/run_acceptance_tests.sh" ${SERIAL:+--serial "$SERIAL"}

echo "[provision] ✓ Provisioning complete for $MODEL"
