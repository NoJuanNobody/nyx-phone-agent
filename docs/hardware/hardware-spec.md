# Hardware Spec — Nyx Phone Agent (Google Pixel / Google Tensor)

> Status: Draft. Early-stage spec. Targets a **Google Pixel** reference device
> built on a **Google Tensor** SoC. Supersedes the earlier
> "Qualcomm Snapdragon 8 Gen 3" reference-hardware assumption.
> Closes #60.

## 1. Why Tensor / Pixel

The reference device has been confirmed as a **Google Pixel** (Pixel 8 / 8a
or Pixel 9 series), built on a **Google Tensor** SoC rather than a Qualcomm
Snapdragon. This matters for the Nyx on-device agent for three reasons:

1. **Official AOSP support.** Google publishes official AOSP **device trees**,
   **kernel sources**, and **factory + OTA images** for Pixel hardware. This is
   a significant advantage for **Path A** (custom AOSP ROM with Nyx as a
   privileged system app): no reliance on leaked/vendor BSPs, an unlockable
   bootloader on most SKUs, and a documented re-lock + custom-AVB path for a
   trusted boot chain. Snapdragon-class non-Pixel devices generally lack this.
2. **First-class on-device AI.** Tensor is built around a Google-designed
   **TPU/NPU** and ships with **Gemini Nano** and the **AICore** system service
   on recent Pixels — a working, vendor-supported on-device LLM path that the
   Nyx LLM subsystem can target or benchmark against.
3. **Different HAL surface.** Tensor's NPU, DSP layout, and HAL/driver
   interfaces differ from Qualcomm's (no Hexagon DSP, no QNN/SNPE). Inference
   acceleration goes through **Android NNAPI**, the **TPU** path via AICore, or
   pure CPU/GPU compute (GPU via OpenCL/Vulkan, e.g. MediaPipe / llama.cpp).
   Any prior Qualcomm-specific acceleration assumption does **not** carry over.

## 2. SoC: Google Tensor

| Attribute | Detail (configurable / TBD per final SKU) |
|-----------|-------------------------------------------|
| **SoC family** | Google Tensor — **G3** (Pixel 8 / 8a) or **G4** (Pixel 9 series) |
| **Fab / process** | Samsung 4 nm class (G3); 4 nm class (G4) |
| **CPU** | Heterogeneous big.LITTLE-style cluster: 1× prime (Cortex-X3/X4 class) + mid (A715/A720 class) + efficiency (A510/A520 class) cores |
| **GPU** | Arm Mali (Immortalis/Mali-G715 class on G3; updated class on G4) — Vulkan / OpenCL capable |
| **TPU / NPU** | Google **Edge TPU**-derived Tensor Processing Unit — the AI accelerator that powers Gemini Nano / AICore |
| **ISP / DSP** | Google ISP + context hub / Tensor security core (Titan M2 secure element on Pixel) |

> **Generation is intentionally left configurable.** The Nyx hardware abstraction
> should not hard-code "G3" vs "G4". Pin the exact generation only once the final
> Pixel SKU is purchased (see Open Questions).

### 2.1 Edge TPU / TPU relevance to on-device LLM inference

- The Tensor TPU is descended from Google's **Edge TPU** line and is the
  accelerator Google itself uses for **Gemini Nano** on Pixel via the **AICore**
  system service.
- The TPU is **not** a general-purpose, openly programmable inference target for
  third-party apps the way the CPU/GPU are. Third-party access today is
  effectively **mediated**:
  - **AICore / ML Kit GenAI APIs** — call Gemini Nano (Google's model, Google's
    quantization) on the TPU. Fast and power-efficient, but Nyx does **not**
    control the model weights or runtime.
  - **Android NNAPI** — the standard path to offload ops to vendor accelerators.
    NNAPI coverage for large transformer/LLM graphs is **uneven** and NNAPI is
    **deprecated** as of recent Android releases, so this is not a durable bet
    for the Nyx custom-LLM plan.
- **Implication for the Nyx LLM subsystem (Gemma 3 / Llama 3 via MediaPipe LLM
  Inference API or llama.cpp):** the realistic acceleration target on Tensor for
  *our own* quantized weights is the **GPU** (MediaPipe GPU backend / llama.cpp
  Vulkan or OpenCL), with **CPU** as the always-available fallback. The **TPU**
  is reachable in practice only by adopting **Gemini Nano via AICore** as the
  model. Two tracks should be planned:
  - **Track A — Nyx-controlled model:** Gemma 3 / Llama 3, quantized (e.g.
    int4/int8), CPU+GPU via MediaPipe/llama.cpp. Full control, no TPU.
  - **Track B — Vendor model:** Gemini Nano via AICore on the TPU. Best
    efficiency, least control. Useful as a baseline/benchmark and a possible
    fast-path for the Intent Bridge.

## 3. Memory / Storage / Modem / Sensors

### 3.1 RAM and storage targets

| Resource | Minimum | Target | Notes |
|----------|---------|--------|-------|
| **RAM** | 8 GB | **12 GB+** | On-device LLM weights + KV cache dominate. A 3–4B model at int4 needs ~2–3 GB resident, plus KV cache that grows with context. 12 GB (Pixel 8 Pro / Pixel 9 Pro class) leaves headroom for the agent stack, voice pipeline, and Android. 8 GB (Pixel 8a / base Pixel 8) is workable only with aggressive quantization + small context. |
| **Storage** | 128 GB | **256 GB** | Multiple quantized model variants (LLM + Whisper STT + TTS voices), tamper-evident audit logs (Policy Engine), and a writable AOSP system partition. UFS 3.1 / 4.0 class on Pixel. |

### 3.2 Modem / connectivity

- **Modem:** Samsung **Exynos Modem** integrated with Tensor (5G sub-6 / mmWave
  on supported SKUs) — note this is a known thermal/power contributor under
  sustained radio load and should be considered alongside inference thermals.
- **Wi-Fi 6E / 7** (SKU-dependent), **Bluetooth 5.3+**, **NFC**, **UWB** (Pro
  SKUs) — relevant for future device-to-device and presence features.
- **Telephony:** Pixel exposes the standard Android telephony stack; the Nyx
  voice/call integration relies on this rather than a vendor-specific path.

### 3.3 Sensors relevant to a phone agent

| Sensor | Agent relevance |
|--------|-----------------|
| **Microphone array (3 mics)** | STT (Whisper.cpp), wake-word, barge-in detection, beamforming/noise suppression for the voice pipeline. |
| **Speaker / earpiece** | TTS (Kokoro / Piper) output, call audio. |
| **Accelerometer / gyroscope** | Activity / device-state context (in-pocket, on-call, in-hand) for the Policy Engine and consent gating. |
| **Proximity / ambient light** | Call-state and screen-state context. |
| **Fingerprint + face unlock** | User presence / step-up authentication for sensitive agent actions. |
| **Titan M2 secure element** | **Secure enclave** for key storage — backs the Policy Engine audit-log signing keys and any device-bound credentials (the original Section 3A "secure enclave for key storage" requirement, now satisfied by Titan M2 on Pixel). |
| **GNSS / location** | Location context for intent routing (consent-gated). |
| **Context Hub / low-power sensor core** | Always-on, low-power sensing (e.g. wake-word audio gating) without spinning up the main CPU. |

## 4. Old vs. New Comparison

| Dimension | **Old assumption — Snapdragon 8 Gen 3** | **New — Google Tensor (G3/G4, Pixel)** | Implication for the on-device LLM plan |
|-----------|------------------------------------------|----------------------------------------|----------------------------------------|
| **Vendor / availability** | Qualcomm; non-Pixel reference device | Google; Pixel reference device | Pixel ships **official AOSP device trees + factory images** → far cleaner Path A (custom ROM). |
| **AI accelerator** | Hexagon NPU/DSP | Google **Edge-TPU-derived TPU** | No Hexagon. TPU is vendor-mediated (Gemini Nano/AICore), not an open target for our own weights. |
| **Vendor inference SDK** | Qualcomm **QNN / SNPE**, AI Engine Direct | Android **NNAPI** (deprecated) + **AICore** (Gemini Nano) | Drop QNN/SNPE plans. For Nyx-controlled models, lean on **GPU (Vulkan/OpenCL) + CPU** via MediaPipe / llama.cpp, not a vendor NPU SDK. |
| **MediaPipe LLM Inference** | CPU/GPU backends | CPU/GPU backends | Largely portable — MediaPipe LLM Inference targets CPU/GPU, both present on Tensor. Re-benchmark on Mali GPU. |
| **llama.cpp** | CPU + OpenCL/Vulkan | CPU + OpenCL/Vulkan (Mali) | Portable. Validate Vulkan backend on Mali; expect GPU offload gains to be more modest than on Adreno/Snapdragon. |
| **Vendor on-device LLM** | None first-party | **Gemini Nano** via AICore | New option: a supported, TPU-accelerated baseline model (Track B). |
| **Secure enclave** | Snapdragon Secure Processing Unit | **Titan M2** secure element | Satisfies the "secure enclave for key storage" requirement; back Policy Engine signing keys with Titan M2 / StrongBox Keystore. |
| **GPU** | Adreno (strong ML compute) | Arm Mali / Immortalis | Generally **lower raw GPU compute** than Adreno → tune quantization and context length more conservatively; CPU fallback matters more. |

**Net:** The migration **costs** us Adreno-class GPU compute and the QNN/SNPE
NPU path, but **gains** us official AOSP support (cleaner Path A), a Titan M2
secure enclave, and an optional vendor LLM (Gemini Nano) — while the **core**
Nyx LLM strategy (Gemma 3 / Llama 3, quantized, MediaPipe / llama.cpp on
**CPU + GPU**) remains valid and portable.

## 5. Thermal & Power Notes (Sustained Inference)

- **Tensor runs warm.** Tensor SoCs (especially G2/G3) are known to thermally
  throttle under sustained heavy compute. The Exynos modem under load adds to
  the thermal budget. Sustained LLM inference on CPU/GPU will hit thermal limits
  faster than a burst benchmark suggests — **plan for throttled, not peak,
  throughput.**
- **Budget for sustained, not peak, tokens/sec.** Capacity planning for the LLM
  subsystem should use a **sustained throughput** figure (after thermal
  steady-state), not a cold-start peak. Benchmark must run to thermal
  equilibrium (≥ several minutes of continuous decode).
- **Prefer the TPU/AICore path for always-on, low-intensity work.** The TPU is
  markedly more power-efficient than CPU/GPU for inference. Where Gemini Nano /
  AICore is acceptable (e.g. lightweight Intent Bridge classification), it
  preserves battery and thermal headroom and avoids waking the big cores.
- **Use the low-power context hub** for always-on sensing (wake-word gating) so
  the main cluster idles until the agent is actually invoked.
- **Power-state awareness in the agent loop.** The Policy Engine / scheduler
  should gate heavy inference on **charge state, battery level, and skin
  temperature** (throttle or defer Track-A inference when hot / on battery;
  allow full throughput when charging and cool).
- **Quantization is a thermal lever, not just a memory one.** int4/int8 reduces
  both memory bandwidth and compute heat — favor it on Tensor.

## 6. Open Questions

- [ ] **Exact Tensor generation / Pixel SKU.** Confirm the final purchased
      device: **Tensor G3 (Pixel 8 / 8a)** vs **Tensor G4 (Pixel 9 series)**.
      Pin RAM (8 vs 12 GB) and storage to the chosen SKU.
- [ ] **NNAPI status & coverage.** NNAPI is deprecated in recent Android — what
      is its real op-coverage for our transformer graphs on Tensor today, and is
      it worth targeting at all vs. GPU/CPU only?
- [ ] **Edge TPU / TPU third-party access.** Is there any supported path to run
      **our own** quantized weights (Gemma 3 / Llama 3) on the Tensor TPU, or is
      the TPU effectively reachable only via **AICore / Gemini Nano**?
- [ ] **Gemini Nano / AICore as Track B.** Confirm AICore availability, model
      version, and licensing/terms on the chosen SKU; decide whether Gemini Nano
      is an acceptable component for the Intent Bridge baseline.
- [ ] **Mali GPU inference performance.** Benchmark MediaPipe (GPU backend) and
      llama.cpp (Vulkan) on the Mali GPU at thermal steady-state; compare vs CPU.
- [ ] **Titan M2 / StrongBox integration.** Confirm StrongBox Keystore + Titan
      M2 can hold the Policy Engine audit-log signing keys under a custom
      (re-locked, custom-AVB) AOSP build.
- [ ] **Sustained thermal envelope.** Measure tokens/sec at thermal equilibrium
      for the candidate models and set the LLM subsystem's capacity targets from
      that, not peak.

## 7. References / Cross-links

- Architecture doc — **Section 3A: Custom Nyx Phone (Full-Stack Build)**
  (hardware bullet updated from Snapdragon 8 Gen 3 → Google Tensor).
- AOSP Extension doc — **Section 3: Architecture Options** (Pixel official
  device trees / factory images as a Path A advantage).
- `docs/architecture/` — On-device LLM inference spec (Gemma 3 / Llama 3,
  MediaPipe LLM Inference API / llama.cpp), Intent Bridge, Policy Engine.
- `docs/hardware/` — device assembly, AOSP/bootloader, secure-enclave docs.
