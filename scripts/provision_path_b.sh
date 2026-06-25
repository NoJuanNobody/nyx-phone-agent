#!/usr/bin/env bash
# provision_path_b.sh — sideloads NyxAgent APK on an existing Android device.
# Requires ADB connected device, Shizuku installed.
# Usage: ./scripts/provision_path_b.sh --apk <path-to-NyxAgent.apk>
set -euo pipefail

APK=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --apk) APK="$2"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
    shift
done

[[ -z "$APK" ]] && { echo "ERROR: --apk is required"; exit 1; }
[[ ! -f "$APK" ]] && { echo "ERROR: APK not found: $APK"; exit 1; }

echo "[path-b] Checking ADB device..."
adb wait-for-device

echo "[path-b] Installing NyxAgent APK..."
adb install -r -g "$APK"

echo "[path-b] Enabling Accessibility Service..."
adb shell settings put secure enabled_accessibility_services com.nyx.agent/.accessibility.NyxAccessibilityService
adb shell settings put secure accessibility_enabled 1

echo "[path-b] Granting overlay permission (for confirmation prompts)..."
adb shell appops set com.nyx.agent SYSTEM_ALERT_WINDOW allow

echo "[path-b] Done. Launch NyxAgent and activate Shizuku to complete setup."
