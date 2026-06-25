# CLAUDE.md — Nyx Phone Agent

Conventions for working in this repository.

## Stage

Early-stage. No application runtime/language is committed yet. Most issues are
**architecture specs** or **hardware docs**. Until a stack is chosen:

- Produce **specs, interfaces, and tests** rather than fictional "working" code.
- Match whatever already exists; don't introduce a new language/framework
  unless an issue explicitly chooses one.

## Branching & PRs

- Default/integration branch: **`main`**.
- One branch and **one PR per issue (or per dedup-cluster)**.
- Branch naming: `feat/issue-<N>-<slug>` or `docs/issue-<N>-<slug>`.
- PR body must end with `Closes #<N>` for every issue it resolves.
- Never push directly to `main` (the scaffold commit is the only exception).

## Parallel-worktree swarm

Large or batchable work is implemented by parallel sub-agents, each in its own
git worktree under `.claude/worktrees/` (gitignored). Each agent owns a disjoint
set of files to avoid conflicts; branches merge back into the issue's feature
branch before the PR opens. See the `nyx-backlog-swarm` skill for the full loop.

## Docs layout

```
docs/architecture/   subsystem specs (acp, policy-engine, mcp-registry, intent-bridge, llm, voice)
docs/hardware/       device assembly, AOSP/bootloader, secure enclave, hardware spec
```

## Backlog hygiene

The issue backlog has historically contained many near-duplicates (the same
subsystem filed 4–5 times). Triage and dedupe **before** starting work: pick a
canonical issue per cluster and close the rest as duplicates with a comment
pointing at the canonical.
