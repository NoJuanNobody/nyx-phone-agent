# AOSP / Bootloader Path — Google Pixel

> Status: hardware spec. Scope: why a **Google Pixel** is the reference device
> for the AOSP / custom-ROM delivery path (**Path A**), how the unlock + build
> pipeline works at a high level, and a validation of the custom-Android build
> strategy against the new device.
>
> Resolves **#62** (Pixel-specific AOSP/bootloader advantages) and **#59**
> (validate custom-Android build strategy against the new replacement device).

Related: [`README.md`](./README.md) (hardware overview), the Path A / Path B
split described in the root [`README.md`](../../README.md), and the architecture
specs under [`docs/architecture/`](../architecture/).

---

## 1. Why a Pixel for AOSP

The original architecture rated the **AOSP Extension path** as *High*
complexity because it assumed a generic OEM device: locked bootloaders,
no published kernel sources, no buildable device tree, and per-carrier unlock
roulette. A **Google Pixel is the exception that collapses most of that risk**,
and it is the single device family Google itself uses as the AOSP reference
platform. For the Pixel specifically, this path is downgraded to **Medium**
complexity.

Concrete Pixel advantages:

| Advantage | What it gives Nyx |
|-----------|-------------------|
| **Official device support in AOSP** | Pixel device trees and configs ship *in the AOSP source* — no reverse-engineering a board. |
| **Official factory images** | Google publishes full factory images (and OTA images) for Pixel 6+. A bad flash is recoverable by re-flashing stock; the device is effectively unbrickable via `fastboot`. |
| **Unlockable bootloader** | `fastboot flashing unlock` is officially supported. Unlock is **reversible** — you can `fastboot flashing lock` to re-lock and ship/demo securely. |
| **Published kernel sources** | Google releases the Pixel kernel sources and build configs, so a custom kernel (e.g. for an agent-specific SELinux policy or driver) is buildable, not guesswork. |
| **`lunch` targets exist upstream** | Each Pixel has a named build target (`aosp_<codename>-<buildtype>`, e.g. `aosp_oriole-userdebug` for Pixel 6), so the standard AOSP build flow works out of the box. |
| **7 years of OS + security updates (Pixel 8+)** | Long support window aligns with a long-term agent-platform commitment; the device stays patched even on stock. |
| **Verified Boot / AVB tooling** | `avbtool` + signing keys let a custom ROM re-establish a verified-boot chain after unlock — important for the Policy Engine's tamper-evidence goals. |

> **Pixel-first note:** factory images are available for **Pixel 6 and newer**.
> Target Pixel 8 or later to inherit the 7-year update commitment.

Because the device is recoverable and the unlock is reversible, the
"Phone Assembly Procedure" for a Pixel is **not physical assembly** — it is a
**factory-image flash / custom-ROM flash** procedure. There is no soldering,
no hardware mod; "modifying Android" here means flashing partitions over
`fastboot`.

---

## 2. Bootloader unlock + custom-ROM build pipeline (high level)

The end-to-end pipeline, deliberately high level — exact codenames, branch
tags, and blob versions are pinned at build time, not here.

### 2.1 Unlock the bootloader

1. Enable **Developer options** → toggle **OEM unlocking** and **USB debugging**.
2. Reboot to the bootloader: `adb reboot bootloader`.
3. Unlock: `fastboot flashing unlock` (confirm on-device). This **wipes
   userdata** — expected, and the reason to do it before provisioning.
4. (Reversible at any time with `fastboot flashing lock`.)

### 2.2 Sync AOSP

1. Install the `repo` tool and Google's build dependencies.
2. `repo init` against the AOSP manifest at the desired **release tag**
   (pick a tag whose monthly security patch level is current — see §4).
3. `repo sync` to pull the full source tree.
4. Download and extract the **Pixel vendor binaries / driver blobs** for the
   matching build tag (proprietary blobs are *not* in AOSP — see §4).

### 2.3 Choose target & build

1. `source build/envsetup.sh`
2. `lunch aosp_<codename>-<buildtype>` — e.g. `userdebug` for development
   (rooted `adb`, `su` on debug builds), `user` for a hardened release build.
3. `m` (full build) → produces the partition images (`boot`, `system`,
   `vendor`, `vbmeta`, …).

### 2.4 Flash

1. Reboot to bootloader, `fastboot flashall -w` (or flash individual images).
2. For a release build, **re-sign** with custom AVB keys and **re-lock** the
   bootloader so Verified Boot is enforced against *your* keys.
3. Validate boot, then provision Nyx as a system app (§3).

---

## 3. Path A — Nyx as a privileged system app on a custom AOSP ROM

**Path A** = bake the Nyx Agent into the ROM as a **privileged system app**
(under `/system/priv-app` or a vendor partition), rather than sideloading an
APK (**Path B**). The Pixel makes Path A materially better than on a generic
device:

- **Signature/privileged permissions.** As a system app signed with the
  platform key, Nyx can hold privileged and `signature`-level permissions
  (and `priv-app` allow-listed perms) that a sideloaded APK can never obtain —
  the cleanest substrate for the **Agent Control Protocol (ACP)** to bind
  OS-level services without Shizuku/root shims.
- **Custom SELinux policy.** Because the kernel and `sepolicy` are buildable,
  Nyx's daemons get **purpose-built SELinux domains** instead of fighting the
  stock policy — better isolation *and* the least-privilege posture the
  **Policy Engine** wants.
- **Survives factory reset / not user-removable.** A system-partition app is
  part of the image, supporting a durable, tamper-evident agent install.
- **Direct framework integration.** Accessibility, notification access, and
  intent routing for the **Intent Bridge** can be wired as platform components
  rather than user-granted runtime toggles.
- **Verified Boot over our keys.** Re-signing with custom AVB keys + re-locking
  gives a measured-boot chain attesting the agent image — supports the
  tamper-evidence and audit goals of the Policy Engine.
- **On-device LLM / voice stack** (Gemma/Llama via MediaPipe or llama.cpp,
  Whisper.cpp STT) can ship as system components with appropriate
  resource/SELinux access rather than sandbox-app constraints.

Path B (sideloaded APK + Shizuku/root + Accessibility Service) remains the
**fallback / fast-iteration** path and the option for users unwilling to unlock.
Path A is the **production target on Pixel** precisely because the Pixel makes it
achievable with supported tooling.

---

## 4. #59 — Validation of the custom-Android build strategy

**Question:** does the "modify existing device" / custom-Android build strategy
still hold now that the replacement device is a Google Pixel?

**Verdict: VALIDATED — and strengthened.** A Pixel is the *best-case* device
for this strategy, not a constraint on it. Findings against the #59 acceptance
criteria:

| Check | Finding |
|-------|---------|
| **Bootloader unlock policy** | Officially supported (`fastboot flashing unlock`) on retail/unlocked Pixels. **Caveat:** carrier-financed/locked SKUs (notably some US Verizon units) may disable OEM unlocking — **buy an unlocked Pixel direct from Google** to guarantee it. |
| **AOSP availability** | First-class: Pixel device trees ship in AOSP; factory + OTA images and kernel sources are published for Pixel 6+. |
| **LineageOS / custom ROM availability** | Pixels are among the best-supported LineageOS devices, and **GrapheneOS** (see below) targets Pixel *exclusively*. Custom-ROM availability is not a risk here. |
| **Android version / agentic control compat** | We control the shipped version because we build the ROM. The agentic control surface (Path A system app, or Path B Accessibility/ADB) is validated against the AOSP version we choose — no dependency on a vendor's frozen build. |
| **Escalate custom build as primary?** | Not forced by lack of support — but Path A (custom ROM, system app) is recommended as **primary on Pixel** because the device supports it cleanly. Path B stays as fallback. |

### 4.1 GrapheneOS as a reference point

[**GrapheneOS**](https://grapheneos.org) is a hardened, Pixel-only AOSP
derivative. We do not need to adopt it, but it is the **proof and the reference**
for this strategy:

- It demonstrates that a third party can ship a **production-grade custom AOSP
  ROM on Pixel**, re-lock the bootloader with **custom AVB keys**, and keep
  **Verified Boot** intact — exactly the security posture Path A wants.
- It tracks **monthly security patches** closely, proving the maintenance
  cadence is sustainable on Pixel.
- Its support-matrix and end-of-life notes are a good external signal for
  **which Pixel generation to target** (favor current, long-support models).

Whether or not we base Nyx's ROM on GrapheneOS, its existence de-risks #59:
the hard parts (re-lock with custom keys, sustained patching) are demonstrably
solvable on this exact hardware.

### 4.2 Vendor blobs

AOSP is not self-sufficient on real hardware: the modem, GPU, camera, sensors,
and other components need **proprietary vendor binaries** Google publishes
**per build tag**. Implications:

- Every ROM build must pull the **vendor blobs matching the chosen AOSP tag**;
  mismatched blob/source versions cause boot or radio failures.
- Blobs are a **closed dependency** — we can't patch them, only update to
  Google's next release. This is the main thing tying our update cadence to
  Google's.

### 4.3 Monthly security-patch cadence

- Google ships **monthly** Android security bulletins + Pixel-specific patches;
  Pixel 8+ carries a **7-year** support commitment.
- A custom ROM **inherits the responsibility** to re-sync the patched AOSP tag,
  pull updated vendor blobs, rebuild, re-sign, and OTA — **monthly**. Falling
  behind erodes the security advantage that justified Path A.
- This cadence is the single largest recurring cost of the strategy (see §5).

---

## 5. Risks, maintenance burden, open questions

**Risks**

- **Carrier-locked SKUs** can block OEM unlock — mitigate by sourcing unlocked
  Pixels from Google directly.
- **Re-lock with custom keys is unforgiving:** a bad signing setup can soft-lock
  a re-locked device. Validate the AVB key flow on a sacrificial unit first.
- **Vendor-blob coupling** ties our release timing to Google's blob drops.
- **Userdata wipe on unlock** — must happen before any provisioning.

**Maintenance burden**

- **Monthly** rebuild/re-sign/OTA cycle to stay on the current patch level.
- CI/build infra (full AOSP builds are heavy — large disk, long build times).
- Custom **AVB signing-key management** (secure storage, rotation, recovery).
- Track Google's blob + tag releases and any device-tree changes per Pixel gen.
- Per-generation porting when moving to a newer Pixel.

**Open questions**

- Base ROM: **stock AOSP**, fork **GrapheneOS**, or **LineageOS** as the
  starting tree? (Trade-off: hardening vs. customization freedom vs. effort.)
- Ship as **re-locked Verified-Boot release** (recommended) vs. unlocked
  `userdebug` for development — and what the demo/handoff posture is.
- Where does Nyx live: `/system/priv-app` vs. a dedicated **vendor partition**?
- Which **Pixel generation** to standardize on (favor longest support window)?
- OTA delivery mechanism for monthly agent + security updates.
- How custom SELinux domains for the agent reconcile with a GrapheneOS base if
  we fork it.
