# Phone Assembly Procedure — Google Pixel

> **Status:** Hardware doc (early-stage repo). This procedure prepares a
> reference device for the Nyx on-device Android agent. It supersedes the
> earlier Pixel-4 / generic-hardware version. See
> [What changed vs. the old Pixel-4-era procedure](#what-changed-vs-the-old-pixel-4-era-procedure).

## Overview

"Assembly" for a Google Pixel is **not physical assembly**. Modern Pixels ship
fully built; the work here is *software bring-up*: unlocking the bootloader,
flashing a clean OS image, optionally rooting, and granting the Nyx agent the
privileges it needs. This produces a known-good, reproducible reference device.

There are two flashing paths, and you should know both:

- **Factory image flash** (primary) — wipes and writes a complete Google
  factory image via `fastboot`/`flash-all`. Use for the initial bring-up and
  any time you need a clean baseline.
- **ADB sideload** (recovery / OTA) — applies a signed OTA package from
  recovery without a full unlock-and-wipe cycle. Use to update or repair a
  device, or to flash an OTA where `fastboot` access is unavailable. (See #64.)

## Target device

| Field | Value |
|-------|-------|
| **Device family** | Google Pixel |
| **SoC** | Google Tensor |
| **Secure element** | Titan M2 |
| **Bootloader** | `fastboot` (unlockable on retail / unlocked-channel units) |
| **Base OS options** | Stock Pixel Android, or privacy-hardened **GrapheneOS** / **CalyxOS** |
| **Codename** | Set `PIXEL_CODENAME` to the unit's codename (e.g. shown in `fastboot getvar product`) and use it throughout |

> **Carrier note:** Buy an **unlocked / Google-channel** Pixel. Carrier-locked
> units (especially US Verizon SKUs) frequently disable OEM unlocking and
> **cannot** complete this procedure.

## Prerequisites

- A workstation with the **Android SDK Platform Tools** (`adb`, `fastboot`)
  on `PATH`. Use a recent build — older `fastboot` cannot flash current Tensor
  devices.
- A **USB-C data cable** (not a charge-only cable).
- A Google account is **not** required for flashing, but is needed if you want
  stock setup.
- On the device: **Developer options** enabled (tap *Build number* 7×), then
  **USB debugging** and **OEM unlocking** turned on.
- Back up anything important — every full flash **erases all user data**.
- Read the companion **AOSP / bootloader** doc in `docs/hardware/` before doing
  root or privileged-system-app work; this doc references it rather than
  duplicating it.

## Bill of materials / budget

| Item | Notes | Est. cost (USD) |
|------|-------|-----------------|
| Google Pixel (unlocked) | New mid-tier or refurb flagship; must allow OEM unlock | $250–$600 (new) / $150–$350 (refurb) |
| USB-C data cable | Quality cable avoids flashing dropouts | $5–$15 |
| Workstation | Existing dev machine (macOS / Linux / Windows) | $0 (reuse) |
| Platform Tools | Free download from Google | $0 |
| **Total (typical)** | Refurb device + cable | **~$160–$365** |

**Sourcing:** Google Store (new, guaranteed unlockable), or reputable refurb
sellers (Back Market, Swappa). Verify the listing is **unlocked / GSM
factory-unlocked**, not carrier-financed.

## Bootloader unlock

> Unlocking **factory-resets the device** and trips the Titan M2 / verified-boot
> state. This is expected. Do it deliberately — repeatedly toggling lock state
> is what risks a Titan M2 lockout, not a single clean unlock.

1. Enable **OEM unlocking** in Developer options (greyed out ⇒ carrier-locked
   or network-restricted; resolve before continuing).
2. Reboot to the bootloader:
   ```bash
   adb reboot bootloader
   ```
3. Confirm the device is visible:
   ```bash
   fastboot devices
   fastboot getvar product   # records the codename → PIXEL_CODENAME
   ```
4. Unlock:
   ```bash
   fastboot flashing unlock
   ```
   Confirm the on-screen prompt with the volume/power keys. The device wipes
   and reboots.

**Avoiding a Titan M2 lockout:** unlock once, do all your flashing, and
**re-lock only after** you have a verified, signed image installed (re-locking
with an unsigned/custom image bricks the device). Do not interrupt power during
an unlock/lock transition.

## Path A — Factory image flash (primary)

1. Download the **official factory image** for the exact `PIXEL_CODENAME` and
   target Android version from Google's Pixel factory image page:
   <https://developers.google.com/android/images>
2. Verify the download against the SHA-256 checksum published next to it:
   ```bash
   shasum -a 256 <image>.zip   # compare to the listed checksum
   ```
3. Extract the archive. It contains `flash-all.sh` (macOS/Linux) /
   `flash-all.bat` (Windows) plus the bootloader, radio, and image zips.
4. With the device in **bootloader** mode, run the bundled script:
   ```bash
   ./flash-all.sh
   ```
   > To keep user data during a re-flash, remove the `-w` flag inside
   > `flash-all.sh`. For a clean reference build, **leave `-w` in** (full wipe).
5. The device flashes bootloader → radio → system, then reboots. First boot
   takes several minutes.
6. **Verify:**
   ```bash
   adb shell getprop ro.build.fingerprint   # matches the image you flashed
   adb shell getprop ro.boot.verifiedbootstate
   ```

For a privacy-hardened base, substitute the **GrapheneOS** or **CalyxOS**
factory image and their respective flashing tooling at step 1; the unlock and
verify steps are otherwise identical.

## Path B — ADB sideload (OTA / recovery)

Use this to apply a signed full-OTA package without a fastboot wipe — e.g.
updating or repairing an existing build. (Issue #64.)

1. Download the matching **full OTA** `.zip` (not the factory image) for
   `PIXEL_CODENAME` from the OTA page:
   <https://developers.google.com/android/ota>
2. Verify its SHA-256 checksum as above.
3. Boot to recovery:
   ```bash
   adb reboot recovery
   ```
   At the "no command" screen, hold **Power** + tap **Volume Up** to reach the
   recovery menu, then select **Apply update from ADB**.
4. Sideload:
   ```bash
   adb sideload <ota-package>.zip
   ```
5. When it completes, choose **Reboot system now** and verify with the same
   `getprop` checks as Path A.

> Sideloaded OTAs are Google-signed, so they apply on a **locked** bootloader.
> If your build needs root or custom partitions, use Path A instead.

## Root / privileged-agent setup

The Nyx agent runs either as a **privileged system app** (custom-ROM, Path A in
the README's Android delivery table) or as a **sideloaded APK + Shizuku/root
bridge** (Path B). The unlock + flash steps above are the prerequisite; the
actual root install (Magisk patch of `boot.img`, system-app integration, SELinux
and `adb shell` privilege grants) is owned by the **AOSP / bootloader doc** in
`docs/hardware/`.

Do **not** duplicate those steps here. After flashing:

1. Follow the AOSP/bootloader doc to patch/boot a rooted image (Magisk) **or**
   build the AOSP target with Nyx as a system app.
2. Return here for the [validation checklist](#validation-checklist).

## Validation checklist

- [ ] `fastboot getvar product` returned the expected `PIXEL_CODENAME`.
- [ ] Device boots to the OS after flashing (no boot loop).
- [ ] `ro.build.fingerprint` matches the flashed image (or GrapheneOS/CalyxOS build).
- [ ] `adb devices` lists the device as `device` (authorized).
- [ ] Nyx agent APK installs (`adb install nyx-agent.apk`) **or** the privileged
      system app is present (`pm list packages | grep nyx`).
- [ ] Required runtime permissions / Accessibility Service / Shizuku binding are
      granted per the AOSP/bootloader doc.
- [ ] Policy Engine audit log records the first agent action (consent path works).
- [ ] If re-locked: `ro.boot.verifiedbootstate` is `green` and the device still boots.

## What you'll lose

Unlocking the bootloader has trade-offs on a Pixel:

- **Verified-boot warning** at every boot (yellow/orange state).
- **Google Wallet tap-to-pay** and other hardware-attestation features may stop
  working under an unlocked / custom-OS state (Play Integrity fails).
- **Pixel Pass / Google-bundled services** tied to a stock, locked device may be
  unavailable.
- OTA convenience is reduced for custom OSes — updates come from the OS vendor,
  not Google, and may need re-flashing.

## What changed vs. the old Pixel-4-era procedure

| Old (Pixel 4 / generic) | New (this doc) |
|--------------------------|----------------|
| Reference device: **Pixel 4** | Reference device: **Google Pixel (Tensor SoC)** |
| Budget line ~$80, Pixel-4 sourcing link | Refurb/new Pixel, ~$160–$365 budget table |
| Pixel-4-specific fastboot/ADB commands and lunch target | Codename-parameterized (`PIXEL_CODENAME`) commands |
| "Physical assembly" / generic-hardware framing | Software bring-up only; no physical assembly |
| Single flash path | **Two paths:** factory image (`flash-all`) **and** ADB sideload (#64) |
| No secure-element guidance | **Titan M2** unlock-lockout guidance added |
| No privacy-OS options | **GrapheneOS / CalyxOS** documented as optional bases |
| Pixel-4 driver/kernel notes | Removed; replaced by Tensor-generic, codename-driven steps |

---

Related issues: closes **#57** (replace Pixel-4 reference device) and **#64**
(Pixel rewrite with factory-image + ADB-sideload paths).
