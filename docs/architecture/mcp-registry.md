# MCP Tool Registry — Architecture Spec

> Status: **Draft spec** (Issue [#6](https://github.com/NoJuanNobody/nyx-phone-agent/issues/6)).
> Early-stage repository: no runtime/language is committed. This document
> specifies behaviour, contracts, and data shapes. Code-shaped fragments are
> **illustrative only** — concrete language and serialization format are TBD.

## 1. Purpose & scope

The **MCP Tool Registry** is the canonical, on-device catalog of every
capability the Nyx Agent can invoke. It is the single place that answers two
questions for the rest of the system:

1. *What can the agent do right now?* (capability discovery)
2. *How do I invoke a given capability safely?* (validated, sandboxed,
   policy-gated execution)

It implements the **MCP (Model Context Protocol)** surface for Nyx, adapted to
an on-device Android agent. In scope:

- **On-device tool catalog** — registration/deregistration of tools at runtime
  and persistence of registrations across agent-service restarts.
- **Capability discovery** — a machine-readable **Capability Manifest** that the
  Intent Bridge and LLM consume to know which tools exist and how to call them.
- **Schema validation** — each tool declares an input/output schema; arguments
  are validated *before* execution.
- **Sandboxed execution** — every tool runs in an isolated execution context
  with resource limits (timeout, and where the runtime allows it, memory/IO
  caps).
- **Capability manifest & versioning** — tools carry a semantic version;
  manifests are versioned and negotiated.
- **Cloud / external tool bridge** — remote MCP servers (Zapier, custom HTTP/SSE
  endpoints) are proxied through the registry behind a single trust boundary.

### Where it sits

```
            ┌──────────────┐
            │ Intent Bridge│  enumerates tools, emits structured tool calls
            └──────┬───────┘
                   │ (over ACP)
            ┌──────▼───────┐        ┌───────────────┐
            │  MCP Tool    │◄──────►│ Policy Engine  │  approve / deny / audit
            │  Registry    │        └───────────────┘
            └──┬────────┬──┘
   built-in    │        │   external bridge
   tools       │        │
 ┌─────────────▼─┐   ┌──▼───────────────┐
 │ ExecutionSand-│   │ ExternalMCPClient │──► remote MCP servers (HTTP/SSE)
 │ box (isolated)│   └───────────────────┘
 └───────────────┘
```

The registry is a **service behind ACP** (the [Agent Control
Protocol](./acp.md), Issue tracked separately). It never speaks to the LLM or
the OS directly; all inbound requests and outbound results travel as ACP
messages, and all execution authorization is delegated to the [Policy
Engine](./policy-engine.md).

## 2. Responsibilities / non-goals

### Responsibilities

- Maintain the authoritative set of registered tools (built-in + external).
- Support **dynamic** registration and deregistration at runtime, no restart.
- **Persist** registrations so they survive an agent-service restart.
- Validate tool-call arguments against each tool's declared input schema and
  reject invalid calls before execution.
- Publish the **Capability Manifest** over ACP for the Intent Bridge.
- Dispatch every approved call into a sandbox with an enforced timeout and
  return a structured result (or structured error) over ACP.
- Submit **every** execution to the Policy Engine and honor its decision.
- Proxy external MCP servers, normalizing their tools into the same catalog and
  schema model as built-in tools.

### Non-goals

- **Intent classification / NLP.** Deciding *which* tool to call from a user
  utterance belongs to the Intent Bridge; the registry only exposes the catalog
  and executes validated calls.
- **Policy authoring / consent UX.** The registry *consults* the Policy Engine;
  it does not define policies, prompt the user, or store consent state.
- **Transport / IPC.** Message framing and routing is ACP's job.
- **Tool business logic correctness.** The registry guarantees isolation,
  validation, and gating — not that a given tool implementation is bug-free.
- **Model inference / prompt construction.** Owned by the LLM subsystem.

## 3. Tool catalog & manifest

A **tool** is the unit of capability. Every tool — built-in or external —
declares the same descriptor:

| Field             | Meaning                                                              |
|-------------------|---------------------------------------------------------------------|
| `id`              | Stable, unique identifier (e.g. `nyx.calendar.create_event`).       |
| `name`            | Human-readable display name.                                        |
| `description`     | Natural-language description used by the Intent Bridge / LLM.       |
| `version`         | Semantic version of the tool's capability contract (see §8).        |
| `inputSchema`     | JSON Schema describing/validating the call arguments.               |
| `outputSchema`    | JSON Schema describing the result payload.                          |
| `permissions`     | Required permission scopes the Policy Engine evaluates.             |
| `source`          | `builtin` or `external:<serverId>`.                                 |
| `sandboxProfile`  | Isolation/resource profile to run under (see §5).                   |

### Built-in tools (target set)

| Tool id prefix      | Capability                              | Permission scope(s)        |
|---------------------|-----------------------------------------|----------------------------|
| `nyx.calendar.*`    | Calendar read / write                   | `calendar.read/write`      |
| `nyx.contacts.*`    | Contacts read / write                   | `contacts.read/write`      |
| `nyx.phone.*`       | Phone dial / hangup                     | `phone.call`               |
| `nyx.sms.*`         | SMS send / read                         | `sms.send`, `sms.read`     |
| `nyx.health.*`      | Health read                             | `health.read`              |
| `nyx.web.*`         | Web search                              | `network.web`              |

### Illustrative manifest entry

> **Illustrative only** — JSON used for readability; the committed serialization
> format and tool-definition language are TBD.

```jsonc
// Capability manifest entry for one tool (illustrative)
{
  "id": "nyx.calendar.create_event",
  "name": "Create calendar event",
  "description": "Create a calendar event on the user's primary calendar.",
  "version": "1.2.0",
  "source": "builtin",
  "permissions": ["calendar.write"],
  "sandboxProfile": "default",
  "inputSchema": {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["title", "start", "end"],
    "properties": {
      "title":      { "type": "string", "maxLength": 256 },
      "start":      { "type": "string", "format": "date-time" },
      "end":        { "type": "string", "format": "date-time" },
      "attendees":  { "type": "array", "items": { "type": "string", "format": "email" } },
      "location":   { "type": "string" }
    },
    "additionalProperties": false
  },
  "outputSchema": {
    "type": "object",
    "required": ["eventId"],
    "properties": {
      "eventId": { "type": "string" },
      "htmlLink": { "type": "string", "format": "uri" }
    }
  }
}
```

The full **Capability Manifest** is the versioned envelope wrapping all such
entries:

```jsonc
// Capability manifest envelope (illustrative)
{
  "manifestVersion": "1.0",
  "generatedAt": "2026-06-25T12:00:00Z",
  "tools": [ /* ...tool entries as above... */ ]
}
```

## 4. Capability discovery

The Intent Bridge (and, transitively, the LLM) discovers tools through the
Capability Manifest exposed over ACP. Two access patterns:

1. **Pull** — `registry.listTools` ACP request returns the current manifest,
   optionally filtered (by `source`, permission scope, or a query string). The
   Intent Bridge uses this to build the tool list it offers to the LLM for
   structured tool-calling.
2. **Push / subscribe** — because tools register and deregister at runtime, the
   manifest is **versioned** and emits a `registry.manifestChanged` event over
   ACP whenever the catalog changes. Subscribers re-pull (or apply a delta) so
   the agent never offers a tool that has since disappeared.

Discovery returns *declarations only* (id, description, schemas, permissions,
version). It performs no execution and triggers no Policy Engine evaluation —
enumeration is free; invocation is gated.

```
Intent Bridge ──registry.listTools──► Registry ──manifest──► Intent Bridge
Intent Bridge ◄─registry.manifestChanged (event)── Registry   (on catalog change)
```

## 5. Sandboxed execution

### Isolation model

Every tool call runs inside an **ExecutionSandbox** — an isolated execution
context distinct from the registry's own control plane. Goals:

- A misbehaving or hostile tool cannot stall the registry, the ACP bus, or other
  tool calls.
- Resource consumption is bounded and attributable per call.
- Failures are converted into **structured errors**, never uncaught crashes that
  take down the agent service.

Per-call controls (driven by the tool's `sandboxProfile`):

- **Timeout** — configurable, **default 30s**. On expiry the call is cancelled
  and a structured `TIMEOUT` error is returned.
- **Concurrency / cancellation** — each call has a cancellation handle; the
  registry can cancel in-flight calls (e.g. on deregistration or shutdown).
- **Resource caps** *(runtime-dependent)* — memory and IO ceilings where the
  chosen runtime supports them; otherwise timeout + Policy gating are the
  primary guards. Captured as an open question (§9).
- **External tools** are additionally bounded by network egress rules and the
  external bridge's trust boundary (§7).

### Tool-call lifecycle

Dispatch is strictly ordered: **validate → authorize (Policy) → execute
(sandbox) → return**. Authorization happens *before* the sandbox is entered, so
a denied call never runs.

```
 Intent Bridge                Registry              Policy Engine        Sandbox
      │                          │                       │                  │
      │ tool.call (id, args) ───►│                       │                  │
      │      [via ACP]           │                       │                  │
      │                          │ 1. validate args      │                  │
      │                          │    vs inputSchema      │                 │
      │                  invalid │──────────────────────────► VALIDATION_ERROR ─┐
      │                          │ 2. authorize(call) ──►│                  │    │
      │                          │                  deny │                  │    │
      │                  ◄───────│◄── POLICY_DENIED ─────│   (never runs)   │    │
      │                          │                 allow │                  │    │
      │                          │ 3. dispatch ─────────────────────────────►│   │
      │                          │    (timeout, limits)  │     execute      │   │
      │                          │                       │  ◄── result ─────│   │
      │                          │ 4. validate result    │                  │   │
      │                          │    vs outputSchema     │                 │   │
      │ ◄── tool.result ─────────│                       │                  │   │
      │      [via ACP]           │◄──────────────────────────────────────────────┘
      │                          │   (all errors returned as structured tool.result)
```

### Result & error envelope

Every call resolves to exactly one structured outcome returned over ACP:

```jsonc
// tool.result (illustrative)
{
  "callId": "…",
  "toolId": "nyx.calendar.create_event",
  "status": "ok",                  // ok | error
  "result": { /* matches outputSchema when ok */ },
  "error":  null                   // when error: { code, message, details }
}
```

Defined error codes (extensible): `VALIDATION_ERROR`, `POLICY_DENIED`,
`TIMEOUT`, `TOOL_NOT_FOUND`, `EXTERNAL_UNAVAILABLE`, `INTERNAL_ERROR`.

## 6. Permissions

Authorization is **not** the registry's decision — it is the [Policy
Engine](./policy-engine.md)'s. The registry's contract is mechanical and
absolute:

- Every tool declares its required `permissions` scopes in the manifest.
- For **every** call, after schema validation and before entering the sandbox,
  the registry submits the call (tool id, resolved arguments, required scopes,
  caller context) to the Policy Engine.
- The registry executes **only** on an explicit *allow*. Any *deny*, error, or
  timeout from the Policy Engine results in `POLICY_DENIED` and **no execution**.
- There is **no bypass path**: discovery cannot execute, external tools follow
  the identical gate, and persisted registrations do not pre-authorize anything.
- The Policy Engine owns the tamper-evident audit log; the registry supplies the
  call metadata it needs to record each decision.

> Invariant: *the registry never executes a tool the Policy Engine has not
> explicitly approved for that specific call.*

## 7. Cloud tool bridge

External capabilities (Zapier, custom HTTP/SSE MCP servers) are integrated
through the **ExternalMCPClient** and surfaced in the same catalog as built-in
tools, so callers cannot tell the difference at the call site.

- **Connection** — external MCP servers are connectable over **HTTP + SSE**. On
  connect, the client fetches the server's manifest and **auto-discovers** its
  tools.
- **Normalization** — discovered tools are mapped into the registry's tool
  descriptor (§3) with `source: "external:<serverId>"` and their schemas adopted
  as-is. They register/deregister dynamically as servers connect/disconnect.
- **Proxying** — an external tool call is forwarded to the remote server by the
  client; the registry wraps the round-trip in the same sandbox timeout and
  returns a normalized `tool.result`. Remote unavailability surfaces as
  `EXTERNAL_UNAVAILABLE`.

### Trust boundary

```
        on-device (trusted)                 │   network (untrusted)
 ┌──────────────────────────────────────┐   │
 │ Registry → Policy gate → Sandbox      │   │   ┌────────────────────┐
 │              │                         │   │   │ remote MCP server  │
 │              └► ExternalMCPClient ─────┼───┼──►│ (Zapier / custom)  │
 │                 (egress, TLS, auth)    │   │   └────────────────────┘
 └──────────────────────────────────────┘   │
```

Key properties of the boundary:

- **Same gate.** External calls pass the Policy Engine exactly like built-in
  ones — the trust boundary is *outside* the policy gate, never around it.
- **No inbound trust.** Remote manifests and results are treated as untrusted
  input: schemas are still validated, and a remote server cannot register a tool
  with elevated built-in permissions implicitly.
- **Egress only.** The on-device registry initiates connections; remote servers
  do not call into the device.
- **Credential handling** for external servers (per-server auth/secrets) is an
  open question (§9), to be reconciled with the Policy Engine's secret model.

## 8. Versioning

- **Tool capability version** — each tool carries a SemVer `version`. Breaking
  changes to a tool's `inputSchema`/`outputSchema` or semantics bump **major**;
  backward-compatible additions bump **minor**.
- **Manifest version** — the manifest envelope carries `manifestVersion`,
  negotiated between the registry and consumers (Intent Bridge / LLM) so a newer
  registry can serve an older consumer.
- **Compatibility rule** — a caller may request a tool by id with an optional
  version constraint; the registry resolves to a compatible registered version
  or returns `TOOL_NOT_FOUND`. Within a major version, additive-only changes are
  guaranteed safe for existing callers.
- **External tools** carry the remote server's declared version verbatim; the
  registry does not rewrite it. Version skew across a reconnect triggers a
  `registry.manifestChanged` event so consumers re-discover.

## 9. Open questions

- **Runtime & language.** No stack is committed (CLAUDE.md). The sandbox model
  (OS process isolation vs. in-process coroutine/thread isolation vs.
  Android-component boundary) depends on this choice.
- **Resource caps beyond timeout.** Whether memory/CPU/IO ceilings are
  enforceable depends on the runtime; timeout is the only guaranteed control.
- **Registration persistence store.** What backs persisted registrations
  (file, on-device DB, ACP-managed state) and how external-server re-discovery
  reconciles with stale persisted entries on restart.
- **External credential / secret management.** Where per-server auth lives and
  how it integrates with the Policy Engine's secret/consent model.
- **Schema dialect.** JSON Schema draft and whether a richer tool-definition
  language is adopted; manifest serialization format.
- **Manifest delta protocol.** Whether `manifestChanged` carries full manifests
  or deltas, and how large catalogs are paginated over ACP.
- **Concurrency policy.** Per-tool concurrency limits and fairness across
  simultaneous calls.

## 10. Acceptance checklist

Restated from Issue #6 acceptance criteria — this spec must enable each:

- [ ] Registry supports **dynamic** tool registration and deregistration at
      runtime without restart (§3, §4).
- [ ] Each tool defines a **JSON Schema** for its parameters; invalid parameters
      are **rejected before execution** (§3, §5).
- [ ] **Built-in tools** specified: Calendar (read/write), Contacts
      (read/write), Phone (dial/hangup), SMS (send/read), Health (read) (§3).
- [ ] **External MCP servers** connectable via **HTTP + SSE**; tool capabilities
      **auto-discovered** from the server manifest (§7).
- [ ] **Capability manifest exposed over ACP** for intent classification by the
      Intent Bridge (§3, §4).
- [ ] Tool execution wrapped in a **sandbox** with configurable timeout
      (**default 30s**); timeouts produce a structured **`TIMEOUT`** error (§5).
- [ ] **All** tool executions pass through the **Policy Engine** before running
      (Policy Engine is a prerequisite) (§5, §6).
- [ ] Registry **persists** tool registrations across agent-service restarts
      (§2, §9 — backing store TBD).

## Related specs

- [Agent Control Protocol (ACP)](./acp.md) — transport for discovery, calls, and
  results.
- [Policy Engine](./policy-engine.md) — authorization gate and audit log.
- [Intent Bridge](./intent-bridge.md) — primary consumer of the Capability
  Manifest.
