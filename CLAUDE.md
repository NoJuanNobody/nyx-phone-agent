# CLAUDE.md — Nyx Phone Agent

Conventions for working in this repository.

## Stack

Kotlin/JVM multi-module Gradle project. Modules:
- `agent-core` — ACP, Policy Engine, MCP Registry, Intent Bridge, LLM Inference, Skill Router
- `agent-app` — NyxAgentDaemon, background loop, orchestration
- `skills` — UI interaction, app-launch, notifications, voice I/O, SMS, system controls

## Code-only policy

All issues must produce source code, tests, config files, or build scripts. No markdown
documentation files are accepted as deliverables. Spec content is inlined as KDoc comments.

## Branching & PRs

- Default/integration branch: **`main`**.
- One branch and **one PR per issue (or per dedup-cluster)**.
- Branch naming: `feat/issue-<N>-<slug>`.
- PR body must end with `Closes #<N>` for every issue it resolves.
- Never push directly to `main`.

## Building

```bash
./gradlew build
./gradlew test
```

## Package structure

```
com.nyx.agent.acp       Agent Control Protocol
com.nyx.agent.policy    Policy Engine
com.nyx.agent.mcp       MCP Tool Registry
com.nyx.agent.intent    Intent Bridge
com.nyx.agent.llm       LLM Inference
com.nyx.agent.skill     Skill Router + skill implementations
com.nyx.agent.daemon    NyxAgentDaemon
```
