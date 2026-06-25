#!/usr/bin/env bash
# run_acceptance_tests.sh — automated smoke tests for a provisioned NyxPhone
# Tests: ACP socket alive, daemon running, accessibility enabled
set -euo pipefail

SERIAL=""
PASS=0
FAIL=0

while [[ $# -gt 0 ]]; do
    case $1 in
        --serial) SERIAL="$2"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
    shift
done

ADB_ARGS=()
[[ -n "$SERIAL" ]] && ADB_ARGS=(-s "$SERIAL")

check() {
    local name="$1"; local cmd="$2"; local expected="$3"
    result=$(eval "$cmd" 2>/dev/null | tr -d '\r')
    if echo "$result" | grep -q "$expected"; then
        echo "[TEST PASS] $name"
        ((PASS++))
    else
        echo "[TEST FAIL] $name (got: $result)"
        ((FAIL++))
    fi
}

echo "[accept] Running acceptance tests..."

check "NyxAgent installed" \
    "adb ${ADB_ARGS[*]} shell pm list packages com.nyx.agent" \
    "com.nyx.agent"

check "NyxAgentDaemon running" \
    "adb ${ADB_ARGS[*]} shell pidof com.nyx.agent" \
    "[0-9]"

check "Accessibility service enabled" \
    "adb ${ADB_ARGS[*]} shell settings get secure enabled_accessibility_services" \
    "com.nyx.agent"

check "Agent config present" \
    "adb ${ADB_ARGS[*]} shell ls /data/data/com.nyx.agent/files/agent_config.json" \
    "agent_config.json"

echo ""
echo "[accept] Results: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]] || exit 1
