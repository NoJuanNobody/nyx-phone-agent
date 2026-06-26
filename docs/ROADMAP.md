# Nyx Phone Agent — Roadmap

> Phased roadmap for evolving the Nyx telephony/voice code into an on-device
> Android agent. This document supersedes the old "Hardware Integration (Q2
> 2026)" milestone language: **hardware integration is active NOW**, not a
> future quarter. (Closes #63.)

## Current Hardware

| Field | Value |
|-------|-------|
| **Target device** | Google Pixel (bring-up unit) |
| **SoC** | Google Tensor |
| **Secure element** | Titan M2 |
| **OS path** | AOSP / GrapheneOS-class build (Pixel bootloader is officially unlockable) |
| **Status** | In hand — active bring-up |

The Pixel replaces the previously assumed "reuse existing device" candidate
(Pixel 4/5 era). See [`docs/hardware/architecture-hardware-audit.md`](./hardware/architecture-hardware-audit.md)
for how this device choice affects the architecture specs, and
[`docs/hardware/assembly.md`](./hardware/assembly.md) as the active setup guide.

## Status legend

- ✅ **Done** — merged and stable
- 🟡 **In progress** — actively being worked NOW
- ⚪ **Planned** — scoped, not started
- 🔒 **Blocked** — gated on a dependency (noted inline)

---

## Phase 0 — Architecture specifications

Define every on-device subsystem (responsibility, interface, contracts,
security, acceptance checklist) before committing to a runtime. The repo is
deliberately spec-first; see [`CLAUDE.md`](../CLAUDE.md).

| Item | Status | Notes |
|------|--------|-------|
| Repo scaffold, docs layout, conventions | ✅ Done | `README.md`, `CLAUDE.md`, docs trees |
| Agent Control Protocol (ACP) spec | 🟡 In progress | `docs/architecture/acp.md` |
| Policy Engine spec | 🟡 In progress | `docs/architecture/policy-engine.md` |
| MCP Tool Registry spec | 🟡 In progress | `docs/architecture/mcp-registry.md` |
| Intent Bridge spec | 🟡 In progress | `docs/architecture/intent-bridge.md` |
| On-device LLM inference spec | 🟡 In progress | `docs/architecture/llm-inference.md` |
| Voice pipeline spec | 🟡 In progress | `docs/architecture/voice-pipeline.md` |

**Exit criteria:** each spec has a public interface, message/contract shapes,
security section, and acceptance checklist.

---

## Phase 1 — Hardware bring-up (Pixel) — 🟡 ACTIVE NOW

This is the **current active milestone.** A Google Pixel (Tensor SoC, Titan M2)
is in hand; bring-up is underway rather than a Q2 2026 future item.

| Item | Status | Notes |
|------|--------|-------|
| Confirm device model + record specs | 🟡 In progress | [`docs/hardware/hardware-spec.md`](./hardware/hardware-spec.md) |
| Phone assembly / setup procedure | 🟡 In progress | [`docs/hardware/assembly.md`](./hardware/assembly.md) — active setup guide |
| Bootloader unlock + AOSP build path | 🟡 In progress | [`docs/hardware/aosp-bootloader.md`](./hardware/aosp-bootloader.md) |
| Secure enclave / Titan M2 key story | 🟡 In progress | [`docs/hardware/secure-enclave.md`](./hardware/secure-enclave.md) |
| fastboot / ADB toolchain validated | ⚪ Planned | depends on unlock |
| Architecture hardware-assumption audit | ✅ Done | [`docs/hardware/architecture-hardware-audit.md`](./hardware/architecture-hardware-audit.md) (Closes #58) |

**Exit criteria:** Pixel boots a custom AOSP image, ADB/fastboot loop works, and
the hardware spec + secure-enclave docs reflect Tensor/Titan-M2 reality.

---

## Phase 2 — On-device LLM inference

Bring a quantized small model up on the Pixel and characterize it on real
Tensor silicon.

| Item | Status | Notes |
|------|--------|-------|
| Runtime choice (MediaPipe LLM Inference API vs llama.cpp) | ⚪ Planned | re-evaluate against Tensor TPU/GPU |
| Model selection (Gemma 3 / Llama 3, quantized) | ⚪ Planned | spec: `docs/architecture/llm-inference.md` |
| On-device latency / memory benchmark on Pixel | 🔒 Blocked | gated on Phase 1 bring-up |
| Context management + KV-cache strategy | ⚪ Planned | |

**Exit criteria:** a quantized model produces tokens on-device within an agreed
latency/memory budget on the Pixel.

---

## Phase 3 — Voice pipeline

| Item | Status | Notes |
|------|--------|-------|
| STT (Whisper.cpp) on-device | ⚪ Planned | spec: `docs/architecture/voice-pipeline.md` |
| TTS (Kokoro / Piper) | ⚪ Planned | |
| Barge-in detection | ⚪ Planned | |
| Wake-word engine | ⚪ Planned | |
| End-to-end voice loop on Pixel | 🔒 Blocked | gated on Phases 1–2 |

**Exit criteria:** spoken request → STT → intent → LLM → action → TTS round-trip
runs on the device.

---

## Phase 4 — Android delivery (Path A / Path B)

Two delivery strategies, carried in parallel until one proves out.

| Path | Description | Status |
|------|-------------|--------|
| **Path A** | Custom AOSP ROM with Nyx as a privileged **system app** | ⚪ Planned — aligns with Phase 1 AOSP work; Pixel unlockable bootloader makes this viable |
| **Path B** | Sideloaded **APK** with Shizuku/root bridge + Accessibility Service | ⚪ Planned — fallback / faster-iteration path |

**Exit criteria:** the agent can observe and act on the phone via ACP through at
least one path, with the Policy Engine enforcing consent on every action.

---

## Cross-cutting (every phase)

- **Policy Engine** consent + tamper-evident audit logging gates all actions.
- **ACP** is the bus every subsystem rides on; interface stability is a
  prerequisite for Phases 2–4.
- **Backlog hygiene:** one PR per issue/dedup-cluster; see `CLAUDE.md`.

## Milestone summary

| Phase | Milestone | Status |
|-------|-----------|--------|
| 0 | Architecture specifications | 🟡 In progress |
| 1 | **Hardware bring-up (Pixel)** | 🟡 **ACTIVE NOW** |
| 2 | On-device LLM inference | ⚪ Planned |
| 3 | Voice pipeline | ⚪ Planned |
| 4 | Android delivery (Path A/B) | ⚪ Planned |
