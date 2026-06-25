# Nyx Phone Agent

Voice/call integration, meeting bot, transcription, and telephony features
extracted from the Nyx monorepo — evolving into an **on-device Android agent**
that can observe and act on a phone on the user's behalf.

> Early-stage repository. Most work today is **architecture specs and hardware
> docs**; a concrete runtime/language has not yet been committed to. Prefer
> specs, interfaces, and tests over speculative implementation until the stack
> is chosen.

## Architecture themes (from the backlog)

The system is being designed around a small set of on-device subsystems:

| Subsystem | What it does |
|-----------|--------------|
| **Agent Control Protocol (ACP)** | Core IPC service between the Nyx Agent and all Android subsystems — the bidirectional OS↔Agent message bus everything else rides on. |
| **Policy Engine** | Consent enforcement, permission gating, and tamper-evident audit logging for every agent action. |
| **MCP Tool Registry** | On-device tool catalog, capability discovery, sandboxed execution, and a bridge to cloud tools. |
| **Intent Bridge** | NLP intent classification routing LLM output to ACP tool calls. |
| **On-device LLM inference** | Gemma 3 / Llama 3 via MediaPipe LLM Inference API or llama.cpp, with quantization and context management. |
| **Voice pipeline** | On-device STT (Whisper.cpp), TTS (Kokoro / Piper), barge-in detection, and a wake-word engine. |
| **Android delivery** | Path A — custom AOSP ROM with Nyx as a privileged system app; Path B — sideloaded APK with Shizuku/root bridge + Accessibility Service. |

## Layout

```
docs/
  architecture/   ACP, Policy Engine, MCP Registry, Intent Bridge, LLM, voice specs
  hardware/       device assembly, AOSP/bootloader, secure-enclave, spec docs
```

## Working in this repo

See [CLAUDE.md](./CLAUDE.md) for workflow conventions (PR target branch, the
parallel-worktree swarm process, and the backlog-swarm loop).
