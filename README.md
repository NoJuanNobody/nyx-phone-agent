# Nyx Phone Agent

On-device Android agent that observes and acts on a phone on the user's behalf.

## Architecture

Multi-module Gradle project (Kotlin/JVM):

```
agent-core/     ACP, Policy Engine, MCP Registry, Intent Bridge, LLM Inference, Skill Router
agent-app/      NyxAgentDaemon, background loop, orchestration
skills/         UI interaction, app-launch, notifications, voice I/O, SMS, system controls
aosp/           AOSP build configs (Path A)
ci/             CI pipeline definitions
```

## Building

```bash
./gradlew build
./gradlew test
```

## Issues

All issues are code-only — no markdown documentation files accepted as deliverables.
Spec content is inlined as KDoc comments in source files.
