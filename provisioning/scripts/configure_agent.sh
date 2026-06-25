#!/usr/bin/env bash
# configure_agent.sh — pushes default config and registers device with Nyx backend
# Usage: ./configure_agent.sh [--license <key>] [--config <path>] [--serial <device>]
set -euo pipefail

LICENSE_KEY=""
SERIAL=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_PATH="$SCRIPT_DIR/../config/default_agent_config.json"

while [[ $# -gt 0 ]]; do
    case $1 in
        --license) LICENSE_KEY="$2"; shift ;;
        --config) CONFIG_PATH="$2"; shift ;;
        --serial) SERIAL="$2"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
    shift
done

ADB_ARGS=()
[[ -n "$SERIAL" ]] && ADB_ARGS=(-s "$SERIAL")

DEVICE_UUID=$(adb "${ADB_ARGS[@]}" shell settings get secure android_id 2>/dev/null | tr -d '\r')
echo "[configure] Device UUID: $DEVICE_UUID"

REMOTE_CONFIG="/data/data/com.nyx.agent/files/agent_config.json"

# Inject license key into config
TMP_CONFIG=$(mktemp)
if [[ -n "$LICENSE_KEY" ]]; then
    python3 -c "
import json, sys
cfg = json.load(open('$CONFIG_PATH'))
cfg['license_key'] = '$LICENSE_KEY'
cfg['device_uuid'] = '$DEVICE_UUID'
json.dump(cfg, sys.stdout, indent=2)
" > "$TMP_CONFIG"
else
    cp "$CONFIG_PATH" "$TMP_CONFIG"
fi

echo "[configure] Pushing config..."
adb "${ADB_ARGS[@]}" push "$TMP_CONFIG" "$REMOTE_CONFIG"
rm "$TMP_CONFIG"

echo "[configure] Config pushed to $REMOTE_CONFIG"
