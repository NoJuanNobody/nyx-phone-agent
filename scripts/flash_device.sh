#!/usr/bin/env bash
# flash_device.sh — flashes a Nyx ROM to a connected Pixel device via fastboot.
# Usage: ./scripts/flash_device.sh [--wipe-data]
set -euo pipefail

AOSP_ROOT="${AOSP_ROOT:-$HOME/aosp}"
PRODUCT_OUT="$AOSP_ROOT/out/target/product/pixel"
WIPE_DATA=0

while [[ $# -gt 0 ]]; do
    case $1 in
        --wipe-data) WIPE_DATA=1 ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
    shift
done

echo "[nyx-flash] Checking fastboot device..."
if ! fastboot devices | grep -q fastboot; then
    echo "ERROR: No device in fastboot mode. Boot into bootloader first."
    exit 1
fi

echo "[nyx-flash] Unlocking bootloader..."
fastboot flashing unlock

echo "[nyx-flash] Flashing system partitions..."
fastboot flash boot "$PRODUCT_OUT/boot.img"
fastboot flash system "$PRODUCT_OUT/system.img"
fastboot flash vendor "$PRODUCT_OUT/vendor.img"

[[ "$WIPE_DATA" == "1" ]] && fastboot -w

echo "[nyx-flash] Rebooting device..."
fastboot reboot

echo "[nyx-flash] Flash complete. Wait for device to boot."
