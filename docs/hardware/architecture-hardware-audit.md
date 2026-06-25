# Architecture Hardware-Assumption Audit

> Audit of every hardware assumption baked into the architecture specs, triggered
> by the device replacement: the bring-up unit is now a **Google Pixel
> (Google Tensor SoC, Titan M2 secure element)** instead of the previously
> assumed "reuse existing device" (Pixel 4/5-era) candidate. (Closes #58.)

## Summary

**The architecture specs are largely hardware-agnostic.** The subsystem specs
([`docs/architecture/`](../architecture/)) describe responsibilities, IPC
contracts, and security properties — not specific chipsets. The hardware
coupling lives almost entirely in three places: the **on-device LLM inference**
tier, the **secure-enclave / TEE** story, and the **device candidate / "reuse
existing device"** framing. Those are the items that move with the Pixel.

This audit lists each assumption, flags whether the Pixel/Tensor/Titan-M2 change
affects it, and gives a recommendation. Companion hardware docs (authored in
parallel) carry the device-specific detail:

- [`assembly.md`](./assembly.md) — phone assembly / fastboot / ADB setup
- [`hardware-spec.md`](./hardware-spec.md) — SoC, sensors, component spec
- [`secure-enclave.md`](./secure-enclave.md) — secure-enclave / hardware-key references
- [`aosp-bootloader.md`](./aosp-bootloader.md) — custom AOSP build + bootloader/unlock path

## Severity legend

- 🔴 **Changes with Pixel** — assumption is chipset/device-specific and must be revisited
- 🟡 **Re-validate** — likely fine but should be confirmed on real silicon
- 🟢 **Hardware-agnostic** — no change needed

---

## Audited assumptions

### 1. Device candidate / "reuse existing device" path
- **Where:** `README.md` Android-delivery framing; historically a hardware
  comparison table assuming a Snapdragon 8 Gen 3 custom build vs. a Pixel 4/5
  "reuse" path.
- **Severity:** 🔴 Changes with Pixel.
- **Finding:** The Pixel 4/5-era "reuse" assumption no longer holds. The target
  is now a current Google Pixel on **Google Tensor**, not Snapdragon. Any
  RAM/storage/NPU figures tied to the old candidates are stale.
- **Recommendation:** Declare the "reuse existing device" path **superseded** by
  the in-hand Pixel and record the real specs in
  [`hardware-spec.md`](./hardware-spec.md). Mark any surviving comparison table
  as provisional pending confirmed Pixel model.

### 2. On-device LLM inference tier (NPU / GPU / CPU fallback)
- **Where:** `docs/architecture/llm-inference.md` (Gemma 3 / Llama 3 via
  MediaPipe LLM Inference API or llama.cpp, quantization, context management).
- **Severity:** 🔴 Changes with Pixel.
- **Finding:** Inference-tier assumptions were tied to a Snapdragon-class NPU.
  Google Tensor exposes its own **TPU/Edge-TPU + Mali GPU**; acceleration paths
  and quantization formats differ from Qualcomm's Hexagon/QNN stack.
- **Recommendation:** Re-evaluate the runtime choice on Tensor — **MediaPipe LLM
  Inference API** is the more natural fit on Pixel/Tensor than a Qualcomm-tuned
  path; keep **llama.cpp (CPU/GPU)** as the portable fallback. Re-benchmark
  latency/memory on the device (Roadmap Phase 2) before fixing the tier.

### 3. Secure enclave / TEE references
- **Where:** `docs/hardware/secure-enclave.md`; security sections of
  `policy-engine.md` (tamper-evident audit log) and `mcp-registry.md` (sandboxed
  execution) that may assume a hardware-backed keystore.
- **Severity:** 🔴 Changes with Pixel.
- **Finding:** The Pixel ships a **Titan M2** discrete security chip plus
  Android StrongBox Keymaster — a *stronger* and *different* root of trust than a
  generic ARM TrustZone-only assumption. Any TrustZone-only or non-Titan wording
  is now inaccurate.
- **Recommendation:** Standardize on **Titan M2 + StrongBox-backed Keystore** for
  hardware key storage and for anchoring the Policy Engine's tamper-evident audit
  log. Capture the Titan M2 specifics in [`secure-enclave.md`](./secure-enclave.md)
  and reference it from the Policy Engine security section.

### 4. Bootloader / OS-build path
- **Where:** `docs/hardware/aosp-bootloader.md`; Android delivery **Path A**
  (custom AOSP ROM, privileged system app).
- **Severity:** 🟡 Re-validate (favorable).
- **Finding:** Pixels have an **officially unlockable bootloader** and
  first-class AOSP/GrapheneOS support — this *strengthens* Path A versus a locked
  reuse-device. No blocking assumption, but unlock/flash steps are
  Pixel-specific.
- **Recommendation:** Document the Pixel `fastboot flashing unlock` + AOSP build
  steps in [`aosp-bootloader.md`](./aosp-bootloader.md). Note Path A is now
  *more* viable; keep Path B (Shizuku/APK) as fallback.

### 5. Agent Control Protocol (ACP)
- **Where:** `docs/architecture/acp.md` — IPC bus between Agent and Android
  subsystems.
- **Severity:** 🟢 Hardware-agnostic.
- **Finding:** ACP is defined over Android IPC (Binder/service contracts),
  independent of SoC.
- **Recommendation:** No change. Confirm IPC mechanism choice survives the AOSP
  build, but no device-specific edits required.

### 6. Intent Bridge
- **Where:** `docs/architecture/intent-bridge.md` — NLP intent → ACP tool calls.
- **Severity:** 🟢 Hardware-agnostic.
- **Finding:** Pure software routing layer above the LLM and ACP.
- **Recommendation:** No change.

### 7. Voice pipeline (STT / TTS / barge-in / wake word)
- **Where:** `docs/architecture/voice-pipeline.md` — Whisper.cpp, Kokoro/Piper.
- **Severity:** 🟡 Re-validate.
- **Finding:** The components are portable CPU/GPU libraries, so no hard chipset
  dependency. However, realtime STT/TTS budgets and any wake-word DSP offload
  should be measured on Tensor, and mic/audio HAL specifics depend on the Pixel.
- **Recommendation:** Keep the spec as-is; re-validate realtime budgets on the
  Pixel during Roadmap Phase 3. Record audio-HAL/mic details in
  [`hardware-spec.md`](./hardware-spec.md).

### 8. Policy Engine (consent, gating, audit log)
- **Where:** `docs/architecture/policy-engine.md`.
- **Severity:** 🟡 Re-validate (depends on item 3).
- **Finding:** The policy logic is hardware-agnostic, but its *tamper-evidence*
  guarantee is strongest when anchored to hardware-backed keys.
- **Recommendation:** Anchor the audit-log signing keys to **Titan M2 /
  StrongBox** (see item 3). No change to the policy model itself.

### 9. MCP Tool Registry
- **Where:** `docs/architecture/mcp-registry.md` — tool catalog, sandboxed
  execution, cloud bridge.
- **Severity:** 🟢 Hardware-agnostic.
- **Finding:** Catalog and sandbox model are OS/runtime concerns, not SoC.
- **Recommendation:** No change. Hardware-backed attestation for sandbox
  integrity (optional) can ride on Titan M2 if desired.

---

## Items requiring follow-up (maps to issue #58 acceptance criteria)

- [ ] Confirm exact Pixel model and record specs → [`hardware-spec.md`](./hardware-spec.md)
- [ ] Mark/replace the old hardware comparison table as provisional (item 1)
- [ ] Re-evaluate inference tier (NPU/GPU/CPU) for Tensor (item 2, Roadmap Phase 2)
- [ ] Standardize secure-enclave wording on Titan M2 + StrongBox (item 3)
- [ ] Declare "reuse existing device" path superseded by the Pixel (item 1)

See [`../ROADMAP.md`](../ROADMAP.md) for how these land across phases.
