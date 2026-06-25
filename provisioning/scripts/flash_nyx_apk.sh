#!/usr/bin/env bash
# flash_nyx_apk.sh — sideload NyxAgent APK via ADB
# Usage: ./flash_nyx_apk.sh --apk <path-to-apk> [--serial <device>]
set -euo pipefail

APK=""
SERIAL=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --apk) APK="$2"; shift ;;
        --serial) SERIAL="$2"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
    shift
done

[[ -z "$APK" ]] && { echo "ERROR: --apk required"; exit 1; }
[[ ! -f "$APK" ]] && { echo "ERROR: APK not found: $APK"; exit 1; }

ADB_ARGS=()
[[ -n "$SERIAL" ]] && ADB_ARGS=(-s "$SERIAL")

echo "[flash] Installing NyxAgent.apk..."
adb "${ADB_ARGS[@]}" install -r -g "$APK"

echo "[flash] Granting overlay permission..."
adb "${ADB_ARGS[@]}" shell appops set com.nyx.agent SYSTEM_ALERT_WINDOW allow

echo "[flash] Enabling Accessibility Service..."
adb "${ADB_ARGS[@]}" shell settings put secure enabled_accessibility_services \
    com.nyx.agent/.accessibility.NyxAccessibilityService
adb "${ADB_ARGS[@]}" shell settings put secure accessibility_enabled 1

echo "[flash] APK installed and configured."
