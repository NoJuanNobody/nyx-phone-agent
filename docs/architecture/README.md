# Architecture docs

Subsystem specifications for the Nyx Phone Agent. Each spec defines the
subsystem's responsibility, public interface, message/contract shapes, security
considerations, and an acceptance checklist. Implementation follows once a
runtime is chosen.

- `acp.md` — Agent Control Protocol (core IPC bus)
- `policy-engine.md` — consent, permission gating, audit log
- `mcp-registry.md` — on-device tool catalog + sandboxed execution
- `intent-bridge.md` — NLP intent classification → ACP tool calls
- `llm-inference.md` — on-device Gemma/Llama inference
- `voice-pipeline.md` — STT / TTS / barge-in / wake word
