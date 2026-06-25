# Agent Control Protocol (ACP) — Architecture Spec

> **Status:** Draft / early-stage. This is a **design spec**, not an
> implementation. Nyx has not yet committed to a runtime or language, so all
> code, schema, and IDL fragments below are **illustrative** — concrete
> serialization format, IDL, and transport bindings are TBD and will be pinned
> in a follow-up once the stack is chosen.
>
> Relates to GitHub issue **#3** and the *Agent Service Layer — Agent Control
> Protocol* section of the System Architecture Document (June 2026).

## Purpose & scope

The **Agent Control Protocol (ACP)** is the core **bidirectional OS↔Agent IPC
bus** that connects the Nyx Agent Service to every other on-device subsystem and
to external clients. It is the single message fabric that the rest of the system
rides on: nothing reaches the agent, and the agent reaches nothing, except
through ACP.

ACP exists to give Nyx **one** stable, versioned, authenticated, schema-checked
channel for:

- **OS → Agent**: events, observations, and inbound requests from the platform
  and from clients (Conversational UI, external MCP clients, system hooks).
- **Agent → OS**: tool invocations, action requests, and outbound events
  directed at Android subsystems — always **mediated by the Policy Engine**.

Because it is the foundation, the protocol must be defined and frozen (at least
at the major-version level) *before* downstream subsystems are built. ACP is
intentionally a thin, generic transport-and-envelope layer; domain semantics
(which tools exist, what an intent means, what is permitted) live in the
subsystems that ride on top of it.

### In scope

- The message **envelope** and its required fields.
- Interaction **patterns**: request/response, fire-and-forget events, and
  streaming.
- The **error model**.
- The **transport** binding on Android and its security boundary.
- **Versioning** and **capability negotiation**.

### Out of scope (covered elsewhere)

- *What* tools exist and how they execute → **MCP Tool Registry** spec.
- *Whether* an action is allowed → **Policy Engine** spec.
- *Mapping natural language to tool calls* → **Intent Bridge** spec.
- LLM prompting/inference and the voice pipeline → their respective specs.

## Responsibilities / non-goals

### Responsibilities

1. Define a **versioned message envelope** common to every message on the bus.
2. Provide **request/response correlation** (via `requestId` / correlation id).
3. Provide **event and streaming** delivery (one-way and multi-frame).
4. **Authenticate** every peer and **integrity-check** every message
   (HMAC-SHA256 over the envelope) before any payload is processed.
5. **Validate** every inbound message against its schema and reject malformed
   messages with a structured error.
6. Run a **transport server** that starts with `NyxAgentService`, accepts many
   concurrent clients (≥100), exposes a **heartbeat/health** endpoint, and
   **shuts down gracefully**.
7. Carry **capability negotiation** and **version handshake** as first-class
   message types.

### Non-goals

- ACP does **not** decide authorization. It transports requests; the **Policy
  Engine** gates them. ACP must never become a side-door that bypasses policy.
- ACP does **not** interpret payloads semantically. Payload meaning is the
  concern of the addressed subsystem (MCP Registry, Intent Bridge, etc.).
- ACP is **not** a service discovery / orchestration framework. It is an IPC
  envelope + transport + auth layer.
- ACP does **not** persist messages as a durable queue. Delivery is in-process /
  in-flight; durability (audit, replay) is the Policy Engine's audit log.
- ACP does **not** define cross-device or cloud transport. It is an on-device
  bus. Cloud reach is the MCP Registry's cloud-bridge concern.

## Architecture

ACP sits at the center of the agent service layer. Every subsystem is an ACP
peer: it connects to the bus, negotiates a version + capabilities, and then
exchanges enveloped messages. The **only** path from an inbound request to an
actual OS action runs **through the Policy Engine**.

```
            external MCP clients        Conversational UI
                     |                          |
                     v                          v
        +-----------------------------------------------+
        |                  ACP  BUS                      |
        |   (envelope · auth/HMAC · schema · routing)    |
        |   transport: Unix Domain Socket server         |
        +-----------------------------------------------+
            ^        ^          ^           ^        ^
            |        |          |           |        |
            |        |          |           |        |
       Intent     LLM       Voice       MCP Tool   System /
       Bridge   inference  pipeline     Registry   OS events
            |                              |
            |   ( tool call / action )     |
            +-------------+----------------+
                          |
                          v
                 +------------------+
                 |  POLICY ENGINE   |   <-- consent / permission gate
                 | (consent, perms, |       + tamper-evident audit log
                 |  audit logging)  |
                 +------------------+
                          |
                          v   (only if permitted)
                 +------------------+
                 |  Android OS /    |
                 |  subsystem exec  |
                 +------------------+
```

Key relationships:

- **Intent Bridge** turns LLM/voice output into ACP **request** messages
  targeted at tools; it is an ACP client, not a transport peer of the OS.
- **MCP Tool Registry** is the catalog/executor that receives tool-call requests
  over ACP; it is the canonical `target` for `tool.invoke` requests.
- **Policy Engine** is interposed on the action path. Any request that would
  cause a side effect must be admitted by policy first; ACP carries the request
  and the policy decision but does not make the decision.
- **LLM inference** and the **voice pipeline** are ACP peers that emit and
  consume events (e.g. transcripts, partial tokens) as streams.

## Message model

Every message — request, response, event, stream frame, heartbeat, handshake —
shares **one envelope**. The envelope is what ACP authenticates, validates, and
routes; the `payload` is opaque to ACP.

### Envelope shape

> *Illustrative only — serialization (JSON vs. protobuf vs. CBOR) is TBD.*

```jsonc
// illustrative envelope (JSON form)
{
  "version":       "1.0",            // protocol version, semver "MAJOR.MINOR"
  "id":            "msg_01HV…",       // unique message id (ULID/UUID)
  "type":          "request",        // request | response | event | stream | heartbeat | handshake | error
  "source":        "intent-bridge",  // logical peer id of sender (== clientId for clients)
  "target":        "mcp-registry",   // logical peer id of intended recipient
  "correlationId": "msg_01HV…",       // request id this message answers; null for unsolicited
  "timestamp":     "2026-06-25T18:04:11.221Z", // RFC3339 / epoch-ms, TBD
  "payload":       { /* opaque to ACP; schema owned by target subsystem */ },
  "signature":     "hmac-sha256:…"   // HMAC-SHA256 over canonicalized envelope (minus signature)
}
```

Mapping to the issue's required envelope fields:

| Issue field   | Envelope field                 | Notes                                   |
|---------------|--------------------------------|-----------------------------------------|
| `version`     | `version`                      | semver; major mismatch is fatal         |
| `timestamp`   | `timestamp`                    | RFC3339 or epoch-ms (TBD)               |
| `requestId`   | `id` (and `correlationId`)     | `id` of a request is its request id     |
| `clientId`    | `source`                       | authenticated peer identity             |
| `messageType` | `type`                         | REQUEST / RESPONSE / EVENT / HEARTBEAT… |
| `payload`     | `payload`                      | opaque blob, validated by target schema |
| `signature`   | `signature`                    | HMAC-SHA256, verified before processing |

### Patterns

**1. Request / response.** A `request` carries an `id`. The peer answers with a
`response` (or `error`) whose `correlationId` equals that `id`. Requests SHOULD
declare a deadline; the server enforces a timeout and replies with a
`DEADLINE_EXCEEDED` error if exceeded.

```jsonc
// illustrative request
{ "type": "request", "id": "req_1", "source": "intent-bridge",
  "target": "mcp-registry",
  "payload": { "op": "tool.invoke", "tool": "sms.send",
               "args": { "to": "+1…", "body": "…" } } }

// illustrative response
{ "type": "response", "id": "res_9", "correlationId": "req_1",
  "source": "mcp-registry", "target": "intent-bridge",
  "payload": { "status": "ok", "result": { "messageId": "…" } } }
```

**2. Event (fire-and-forget).** An `event` has no expected response;
`correlationId` is null. Used for observations and notifications (e.g. an
incoming call, a wake-word trigger, a policy-audit notice).

**3. Stream.** A `stream` is a sequence of frames sharing a `correlationId`,
terminated by a frame flagged final. Used for partial LLM tokens and partial STT
transcripts.

```jsonc
// illustrative stream frames
{ "type": "stream", "correlationId": "req_7", "payload": { "seq": 0, "delta": "Hel",  "final": false } }
{ "type": "stream", "correlationId": "req_7", "payload": { "seq": 1, "delta": "lo",   "final": true  } }
```

**4. Heartbeat / health.** A lightweight `heartbeat` request the server answers
within the latency budget (target **< 200 ms** under normal load) so peers and
supervisors can detect a hung or dead bus.

### Error model

Errors are a dedicated `type: "error"` message whose `correlationId` points at
the failed request. The payload is a **structured** error object:

```jsonc
// illustrative error payload
{
  "code":    "SCHEMA_INVALID",   // stable machine-readable code (see table)
  "status":  400,                // coarse class, HTTP-like
  "message": "field 'payload.tool' is required",
  "details": { "pointer": "/payload/tool" },
  "retryable": false
}
```

| `code`                | `status` | Meaning                                            |
|-----------------------|----------|----------------------------------------------------|
| `SCHEMA_INVALID`      | 400      | Inbound message failed schema validation           |
| `UNAUTHENTICATED`     | 401      | Missing/invalid signature or unknown peer          |
| `SIGNATURE_INVALID`   | 401      | HMAC verification failed (emits security audit)    |
| `PERMISSION_DENIED`   | 403      | Policy Engine refused the action                   |
| `TARGET_UNKNOWN`      | 404      | No peer registered for `target`                    |
| `VERSION_INCOMPATIBLE`| 426      | Major protocol version mismatch                    |
| `DEADLINE_EXCEEDED`   | 504      | Request timed out in flight                        |
| `INTERNAL`            | 500      | Unhandled server error                             |

Invalid inbound messages (failed schema validation) MUST receive a structured
`SCHEMA_INVALID` / 400 error rather than being silently dropped.

## Transport

ACP needs a fast, local, authenticatable, many-client IPC mechanism on Android.
Candidates:

| Mechanism | Pros | Cons |
|-----------|------|------|
| **Binder / AIDL** | Native Android RPC; kernel-mediated; per-call UID/PID via `getCallingUid()`; well-supported in a system app (Path A) | Tightly couples ACP to Android RPC semantics & generated stubs; awkward for non-Android/external clients; harder to evolve a custom versioned envelope; heavier for streaming |
| **Unix domain socket (UDS)** | Transport-agnostic byte stream; works for any client (system, sideloaded, native, external MCP); filesystem **ACL** (Path A `/data/nyx/acp.sock`) or **abstract namespace** (Path B `@nyx_acp_socket`); easy to layer our own envelope + HMAC; natural for request/response **and** streaming; scales to many concurrent clients with one accept loop | We implement framing/auth/version ourselves; abstract-namespace sockets lack filesystem ACLs (mitigated by HMAC + peer-cred checks) |
| **Messenger / Handler** | Simple for UI↔service; built on Binder | Message-oriented and Android-only; poor fit for streaming, external clients, and a custom signed envelope |

### Recommended default: **Unix Domain Socket**

UDS is the recommended transport because it decouples ACP from Android RPC
specifics while still being purely on-device and fast, and it serves **all**
client classes uniformly — the privileged system app (Path A), the sideloaded
APK + Shizuku bridge (Path B), and external MCP clients — over the same socket
with the same signed envelope. It also maps cleanly onto the two delivery paths:

- **Path A (AOSP system app):** filesystem socket at `/data/nyx/acp.sock` with
  **kernel ACL** (owner/group + mode) as the first-line access control. Peer
  identity is additionally checked via socket peer credentials.
- **Path B (sideloaded APK):** **abstract-namespace** socket `@nyx_acp_socket`
  (no filesystem path to ACL), so HMAC-SHA256 authentication and peer-credential
  checks carry the security weight.

In both paths, **HMAC-SHA256 over the envelope is mandatory and verified before
any payload is processed**; a verification failure emits a security audit event
to the Policy Engine. The server is a single accept loop dispatching a
handler per connected client, sized to accept **≥100 concurrent clients**, and
it tears down cleanly when `NyxAgentService` stops (drain in-flight requests,
then close).

> Binder/AIDL remains a viable **alternative or complementary** binding for the
> in-process UI on Path A; if adopted it must carry the identical envelope, HMAC,
> and schema rules so policy can never be bypassed by choice of transport.

## Security

Security is non-negotiable and layered:

1. **Authentication.** Every peer is identified by `source`/`clientId` and proven
   by an **HMAC-SHA256** signature over the canonicalized envelope. The
   signature is **verified before any payload is read or processed**. Failures
   return `SIGNATURE_INVALID` / `UNAUTHENTICATED` **and emit a security audit
   event**.
2. **Transport access control.** Path A relies on filesystem ACLs on the socket
   plus socket peer-credential checks; Path B relies on the abstract namespace
   plus HMAC + peer-credential checks.
3. **Schema validation.** Every inbound message is validated against its schema;
   invalid messages are rejected with a structured `SCHEMA_INVALID` / 400.
4. **Policy gating — the critical invariant.** ACP transports requests; it does
   **not** authorize them. **Every action that produces a side effect routes
   through the Policy Engine for consent/permission gating, and ACP must never
   provide a path that bypasses the Policy Engine.** A `PERMISSION_DENIED` from
   policy is surfaced as a structured ACP error, and the attempt is recorded in
   the Policy Engine's tamper-evident audit log.

This ordering is intentional: **authenticate → integrity-check → schema-validate
→ policy-gate → execute.** No stage may be skipped, and no transport binding may
re-order or omit a stage.

## Versioning & capability negotiation

ACP versions follow **semver** at the protocol level (`MAJOR.MINOR`).

- **Handshake.** The first message on a new connection is a `handshake` carrying
  the peer's supported protocol version(s) and a **capability set** (e.g.
  `streaming`, `compression`, supported message types, max payload size).
- **Version compatibility.** A **major** version mismatch is **fatal**: the
  server replies with `VERSION_INCOMPATIBLE` / 426 and closes the connection.
  Compatible **minor** differences negotiate down to the highest version both
  peers support.
- **Capability negotiation.** The negotiated capability set is the intersection
  of both peers' advertised capabilities; features outside the intersection MUST
  NOT be used on that connection.
- **Forward compatibility.** Within a major version, unknown **optional**
  envelope fields are ignored, not rejected, so minor versions can add fields
  without breaking older peers.

```jsonc
// illustrative handshake
{ "type": "handshake", "id": "hs_1", "source": "voice-pipeline",
  "payload": { "versions": ["1.0", "1.1"],
               "capabilities": ["streaming", "heartbeat"] } }
```

## Schema artifacts

The normative message schemas referenced by this document live under
`docs/acp/schemas/` (created alongside the implementation in issue #3):
`acp-request.schema.json`, `acp-response.schema.json`, `acp-event.schema.json`.
This architecture doc is the human-readable companion; the schema files are the
machine-checkable source of truth for validation. (Serialization/IDL — JSON
Schema vs. protobuf — is still TBD; the table above maps each illustrative
field to its issue requirement.)

## Open questions

- **Serialization & IDL:** JSON (easy, debuggable) vs. protobuf/CBOR (compact,
  fast, schema-first)? Affects how `signature` canonicalization is defined.
- **Key management for HMAC:** where do per-peer keys live and rotate
  (Android Keystore / secure enclave)? How are sideloaded (Path B) clients
  provisioned a key without a system trust root?
- **Signature canonicalization:** exact byte-canonical form of the envelope so
  sender and verifier compute the same HMAC across languages.
- **Binder coexistence:** do we ship a Binder/AIDL binding for in-process UI on
  Path A, or force everything onto UDS for uniformity?
- **Backpressure & flow control** for streams (token/transcript floods).
- **Multiplexing:** one socket connection per peer, or multiplexed logical
  channels over a shared connection?
- **Audit boundary:** which ACP-level events (auth failures, version rejects)
  go to the Policy Engine audit log vs. a local ACP log?
- **Abstract-namespace spoofing (Path B):** is peer-credential + HMAC sufficient,
  or is an additional attestation step required?

## Acceptance checklist

Restated from issue #3:

- [ ] ACP message envelope includes: `version`, `timestamp`, `requestId`,
      `clientId`, `messageType`, `payload`, `signature`.
- [ ] JSON Schema validation enforced on every inbound message; invalid
      messages return a structured 400 error.
- [ ] Unix Domain Socket server starts with `NyxAgentService` and accepts ≥100
      concurrent clients.
- [ ] Path A uses a filesystem socket (`/data/nyx/acp.sock`) with kernel ACL
      enforcement; Path B uses an abstract-namespace socket (`@nyx_acp_socket`).
- [ ] HMAC-SHA256 signature verified before any payload is processed; failures
      emit a security audit event.
- [ ] Protocol version negotiation rejects incompatible major versions with a
      structured error.
- [ ] Heartbeat / health-check endpoint responds within 200 ms under normal
      load.
- [ ] ACP server shuts down gracefully (all in-flight requests complete or time
      out) when `NyxAgentService` stops.
- [ ] All message schemas documented in `docs/acp/schemas/` and referenced from
      this architecture doc.
