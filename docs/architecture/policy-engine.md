# Policy Engine

> **Status:** Architecture spec. Early-stage repository — no application runtime
> or language is committed yet. This document defines the Policy Engine's
> responsibilities, model, contracts, and security properties. Code-shaped
> fragments below are **illustrative pseudocode / schemas, language TBD**, not
> working code.

Relates to **§ Policy Engine & Consent Manager** in the System Architecture
Document and GitHub issue #5. See also:

- `acp.md` — Agent Control Protocol (the IPC bus the Policy Engine rides on)
- `mcp-registry.md` — on-device tool catalog + sandboxed execution

---

## 1. Purpose & scope

The Policy Engine is the **security and trust core** of the Nyx Phone Agent. It
is the single synchronous gate that **every tool call passes through before
execution**, regardless of where the call originated (LLM-driven intent, a
user-initiated command, a scheduled job, or a remote/cloud tool bridge).

It exists to enforce four guarantees on every agent action:

1. **Consent enforcement** — a tool runs only when the user has granted a valid,
   unexpired consent for the required scope.
2. **Permission gating** — Android runtime permissions (contacts, calendar,
   phone, SMS, health, etc.) required by a tool are verified to be held before
   the tool executes; a missing permission produces a structured error, never a
   crash.
3. **Rate limiting** — per-user, per-tool limits prevent runaway or abusive
   loops (including the agent looping on itself).
4. **Tamper-evident audit logging** — every decision and invocation is recorded
   in an append-only, cryptographically chained log.

The Policy Engine is intentionally **boring, deterministic, and fast**: it makes
a yes/no decision and records it. It does not interpret intent, plan, or call
LLMs. Its decisions must be reproducible from the policy state and the request
alone.

### Scope boundary

```
        Intent Bridge / LLM / scheduler / user command
                          │  (proposes a tool call)
                          ▼
                  ┌───────────────┐
   ACP request →  │ POLICY ENGINE │  ← consent store, permission state,
                  │   (this doc)  │    rate-limit buckets, policy rules
                  └───────┬───────┘
              allow │     │ deny / needs-consent
                    ▼     ▼
        ┌────────────────────┐     structured ACP error
        │  MCP Tool Registry │     (DENIED / NEEDS_CONSENT /
        │ (sandboxed exec)   │      PERMISSION_DENIED / RATE_LIMIT)
        └────────────────────┘
```

The Policy Engine sits **between ACP and the MCP Tool Registry**. The Registry
must not be reachable except through the Policy Engine; this is an architectural
invariant, not a convention.

---

## 2. Responsibilities / non-goals

### Responsibilities

- Evaluate every tool-execution request against consent, permission, rate-limit,
  and policy-rule state, and return a single authoritative decision.
- Issue, validate, and revoke **consent tokens** scoped to (subject, action,
  resource, scope) with expiry.
- Verify required Android runtime permissions are held at call time.
- Maintain per-user, per-tool rate-limit buckets.
- Drive **consent flows** (first-use prompts, destructive-action confirmation)
  by returning a `NEEDS_CONSENT` decision that ACP surfaces to the user.
- Append a tamper-evident audit entry for **every** decision and invocation
  outcome.
- Load policy rules from a configurable rule DSL without code changes.

### Non-goals

- **Not** an intent classifier or planner — it does not decide *what* the agent
  should do, only whether a concrete proposed call is allowed.
- **Not** the tool sandbox — process/permission isolation of tool execution is
  the MCP Tool Registry's job. The Policy Engine authorizes; the Registry
  isolates and runs.
- **Not** an authentication service — establishing *who the user is* (device
  unlock, biometric session) is upstream. The Policy Engine consumes an
  authenticated subject identity.
- **Not** a network/firewall layer — egress control for cloud tools is the
  Registry's cloud bridge concern, though policy rules may *gate* whether a
  cloud tool is permitted at all.
- **Not** a UI — it produces machine-readable decisions; ACP/UI render prompts.

---

## 3. Policy model

### 3.1 Core entities

A **permission grant** (consent) is a tuple:

| Field      | Meaning                                                              |
|------------|---------------------------------------------------------------------|
| `subject`  | The authenticated principal the grant applies to (the device user). |
| `action`   | The verb being authorized (e.g. `read`, `send`, `delete`, `call`).  |
| `resource` | The resource class the action targets (e.g. `contacts`, `sms`, `calendar.event`, `tool:send_sms`). |
| `scope`    | Refinement of the resource (e.g. a contact group, a calendar id, "destructive"). |
| `expiry`   | Absolute time after which the grant is invalid. May be `session`, a timestamp, or `none` (persistent). |
| `grant_id` | Stable identifier, used for revocation and audit correlation.       |

Grants are materialized as **signed consent tokens** (illustrative: JWT-style,
signed by a device-held key — Titan M2-backed on Pixel, software keystore on
Path B). A token's claims encode the tuple above plus issuance metadata. The
Policy Engine validates the signature and claims; it does not trust the caller's
assertion of consent.

A **policy rule** (from the rule DSL) maps a request shape to a *requirement*:
which consent scope is needed, which Android permissions are needed, the rate
limit, and whether the action is **destructive** (requires explicit per-call
confirmation even if a standing grant exists).

### 3.2 Default-deny posture

The engine is **default-deny**. A request is allowed only if an explicit chain
of positive conditions holds. Absence of a rule, absence of a grant, an expired
grant, a missing permission, or an unparseable request all resolve to **deny**.
There is no implicit allow path.

### 3.3 Decision flow

A tool-execution request is evaluated as an ordered pipeline. The **first**
failing stage short-circuits and determines the outcome (and the audit reason).
Every path — allow or deny — terminates in an audit append.

```
            ┌──────────────────────────────────────────────┐
            │  Tool-execution request arrives via ACP       │
            │  { subject, tool_id, action, resource,        │
            │    scope, params }                            │
            └───────────────────────┬──────────────────────┘
                                    ▼
                 (1) Resolve policy rule for request shape
                          │                       │
                   no rule / unparseable          rule found
                          ▼                        │
                      DENY (no_rule) ◄─────────────┘ (continue)
                                    ▼
                 (2) Consent check: valid, unexpired token
                     for required (subject, action,
                     resource, scope)?
                          │ no                     │ yes
                          ▼                        │
                   NEEDS_CONSENT  ─────────────────┤
                   (first-use / revoked / expired) │
                                    ▼
                 (3) Destructive? require fresh
                     per-call confirmation token
                          │ missing                │ present / n/a
                          ▼                        │
                   NEEDS_CONFIRMATION ─────────────┤
                                    ▼
                 (4) Android runtime permission(s)
                     for tool held?
                          │ no                     │ yes
                          ▼                        │
                   PERMISSION_DENIED ──────────────┤
                                    ▼
                 (5) Rate-limit bucket has capacity?
                          │ no                     │ yes
                          ▼                        │
                      RATE_LIMIT  ─────────────────┤
                                    ▼
                 (6) Evaluate remaining policy-rule
                     predicates (allow/deny lists,
                     time-of-day, network state, …)
                          │ deny                   │ allow
                          ▼                        ▼
                      DENY (rule)             ALLOW (token issued
                                              to Registry)
                                    │              │
                                    └──────┬───────┘
                                           ▼
                            (7) AUDIT APPEND (outcome,
                                params hash, grant_id, ts)
                                           ▼
                            Return decision to ACP
```

The allow result is an **execution authorization** handed to the MCP Tool
Registry — a short-lived, single-use capability bound to this exact request
(tool, params hash, subject). The Registry rejects any execution lacking a valid
authorization. This prevents a tool call from being "smuggled" around the gate.

### 3.4 Performance budget

The hot path (stages 1–7) targets **≤ 10 ms p99** overhead per call. Consent
token validation, permission state, and rate-limit buckets must be served from
in-memory/cached state; the audit append must not block the decision return
(append is durable-before-execute but pipelined — see §5.4).

---

## 4. Consent flows

Consent is the user's explicit, scoped, revocable authorization. The engine
never fabricates consent; it requests it via ACP and records the user's answer.

### 4.1 First-use prompt

When a request requires a consent scope the user has never granted (stage 2
returns `NEEDS_CONSENT`), the engine returns a structured prompt descriptor to
ACP:

- what tool/action/resource/scope is being requested,
- a human-readable justification (supplied by the rule),
- the grant options offered (see persistence below).

ACP renders the prompt; the user's choice is returned and, on approval, the
engine **issues and persists a consent token** before the original request is
re-evaluated.

### 4.2 Persistent grants

On approval the user may choose a grant lifetime, which sets the token `expiry`:

- **Once** — single-use; consumed by this call, not stored.
- **This session** — `expiry = session`; cleared on device lock / agent restart.
- **Until revoked** — persistent token; survives restart, stored in the consent
  store, valid until explicitly revoked or it hits any absolute expiry.

Persistent grants make subsequent matching requests pass stage 2 with no prompt.

### 4.3 Revocation

- A user may revoke any grant at any time (per-grant, per-resource, or "revoke
  all") via the consent UI surfaced over ACP.
- Revocation **deletes/tombstones** the token in the consent store and is
  authoritative immediately. The engine maintains a revocation set checked on
  every consent validation, so a revoked `grant_id` fails stage 2 even if the
  caller still holds a syntactically valid token.
- **In-flight requests:** revocation must take effect within **1 second** for
  requests already accepted but not yet executed. The execution authorization
  handed to the Registry is short-lived and is re-checked against the revocation
  set immediately before tool execution; a revoked grant aborts the in-flight
  call with a `DENIED (revoked)` outcome.

### 4.4 Destructive-action confirmation

Rules may mark an (action, resource) as **destructive** (e.g. `delete` calendar
events, `send` SMS to a new number, placing a call). For destructive actions the
engine requires a **fresh per-call confirmation** (stage 3) — a standing
"until revoked" grant is *not* sufficient to skip it. The confirmation produces a
single-use confirmation token bound to the exact request params hash, so the
user confirms the specific action they are shown, not a generic capability.

---

## 5. Audit log

Every decision (allow and deny) and every execution outcome produces exactly one
audit entry. The log is **append-only and tamper-evident**.

### 5.1 Tamper-evident design

Entries form a **hash chain**: each entry includes the hash of the previous
entry, so any insertion, deletion, or modification of a historical entry breaks
the chain and is detectable by re-walking it.

```
entry[n].prev_hash = H(entry[n-1].canonical_bytes)
entry[n].entry_hash = H(entry[n].canonical_bytes_including_prev_hash)
```

- `H` is a cryptographic hash (illustrative: SHA-256).
- Periodically (and at chain head) a **checkpoint** is signed by a device-held
  key. On Pixel hardware the signing key is **Titan M2-backed** (hardware
  attestation of log integrity); on Path B (sideloaded APK) a **software keystore
  fallback** is used, with the reduced trust assumption documented at the device
  level.
- The log is append-only at the storage layer: no update/delete API is exposed;
  corrections are themselves new entries.

Tamper detection = re-walk the chain and verify every `prev_hash` link plus the
latest signed checkpoint. A mismatch surfaces as an integrity alarm.

### 5.2 What each entry records

Per the acceptance criteria, every tool invocation (and every blocked attempt)
records at minimum:

- `tool_id` — the tool the request targeted.
- `subject` / `user_id` — the authenticated principal.
- `action`, `resource`, `scope` — what was requested.
- `outcome` — `ALLOW` / `DENIED` / `NEEDS_CONSENT` / `NEEDS_CONFIRMATION` /
  `PERMISSION_DENIED` / `RATE_LIMIT`, plus a machine reason code.
- `timestamp` — trusted monotonic + wall-clock time of the decision.
- `params_hash` — hash of the request parameters (the **hash**, not the raw
  params, to avoid logging sensitive payloads).
- `grant_id` — the consent grant relied upon, if any (for correlation with
  consent/revocation events).
- Chain fields: `seq`, `prev_hash`, `entry_hash`.

### 5.3 Illustrative entry schema

> **Illustrative only — serialization/format TBD.** Shown as JSON for clarity.

```json
{
  "seq": 10427,
  "timestamp": {
    "wall": "2026-06-25T17:04:31.882Z",
    "monotonic_ns": 184529931002
  },
  "subject": "user:device-owner",
  "tool_id": "send_sms",
  "action": "send",
  "resource": "sms",
  "scope": "contact:family",
  "outcome": "ALLOW",
  "reason": "consent_valid",
  "grant_id": "grant_5f3c9a...",
  "params_hash": "sha256:9b1e0c4d...",
  "policy_rule": "rule.sms.send.v3",
  "prev_hash": "sha256:7a22f0bd...",
  "entry_hash": "sha256:c4e911aa..."
}
```

A `checkpoint` entry additionally carries `signature` and `signer` (e.g.
`titan-m2` or `software-keystore`) over the running chain head hash.

### 5.4 Durability ordering

For high-stakes (destructive) actions, the relevant audit intent entry is
**durable before execution** so a crash cannot hide that an action was
authorized. The decision-return path and the durable append are pipelined to
stay within the latency budget; the execution authorization handed to the
Registry references the audit `seq` so execution and audit are correlated.

---

## 6. Integration

### 6.1 Position in the stack

```
Intent Bridge ──▶ ACP ──▶ Policy Engine ──▶ MCP Tool Registry ──▶ tool
                   ▲            │
                   └── prompts ─┘  (NEEDS_CONSENT / NEEDS_CONFIRMATION)
```

- **From ACP:** the engine receives a normalized tool-execution request
  (subject, tool_id, action, resource, scope, params). ACP is the only producer
  of these requests; the engine trusts ACP's authenticated `subject`.
- **To the MCP Tool Registry:** on `ALLOW` the engine issues a single-use,
  request-bound **execution authorization**. The Registry must validate this
  authorization before running any tool and must have no other execution entry
  point. This is what makes "every tool call passes through the Policy Engine"
  enforceable rather than merely conventional.

### 6.2 How it says yes/no

The engine returns one decision per request:

| Decision            | ACP behavior                                              | ACP error code  |
|---------------------|-----------------------------------------------------------|-----------------|
| `ALLOW`             | forward execution authorization to Registry               | —               |
| `NEEDS_CONSENT`     | render first-use/grant prompt, then re-submit             | —               |
| `NEEDS_CONFIRMATION`| render destructive-action confirmation, then re-submit    | —               |
| `PERMISSION_DENIED` | structured error; offer to open Android permission settings | `PERMISSION_DENIED` |
| `RATE_LIMIT`        | structured error; caller should back off                  | `RATE_LIMIT`    |
| `DENIED`            | structured error with reason code                         | `DENIED`        |

Error codes are surfaced as **structured ACP errors** (see `acp.md`), never as
exceptions/crashes. Rate-limit exhaustion specifically returns the `RATE_LIMIT`
ACP error code per the acceptance criteria.

### 6.3 Inputs the engine depends on

- **Consent store** — persistent grants + revocation set.
- **Permission state** — current Android runtime permission grants for the app.
- **Rate-limit buckets** — per-(subject, tool) token buckets.
- **Policy rules** — compiled from the rule DSL; reloadable.
- **Signing keys** — Titan M2 / software keystore for tokens and audit
  checkpoints.

---

## 7. Threat model & security considerations

| Threat | Mitigation |
|--------|------------|
| **Gate bypass** — a caller invokes a tool without going through the engine. | Registry accepts only single-use, request-bound execution authorizations issued by the engine; no other execution path exists (architectural invariant). |
| **Forged/replayed consent** — caller asserts consent it was never granted, or replays an old token. | Tokens are signed by a device-held key and validated server-side (in-process); single-use confirmation tokens are bound to the params hash; revocation set is checked on every validation. |
| **Confused-deputy / param tampering** — user confirms action A, a different action B is executed. | Confirmation/authorization tokens bind to `params_hash`; the Registry re-validates the hash against the executed params. |
| **Stale authorization after revocation** — in-flight call proceeds after the user revokes. | Short-lived authorizations + revocation re-check immediately before execution; ≤ 1 s effect window. |
| **Audit tampering** — attacker edits/deletes log entries to hide actions. | Append-only storage + hash chain + signed checkpoints (Titan M2 / software fallback); tamper is detectable by chain re-walk. |
| **Sensitive data in logs** — audit entries leak message bodies, contact data. | Log `params_hash`, never raw params; entries carry references, not payloads. |
| **Permission downgrade / crash on missing permission** | Permissions verified at call time; missing → structured `PERMISSION_DENIED`, never a crash. |
| **Resource exhaustion / agent self-loops** | Per-(subject, tool) token-bucket rate limits; exhaustion → `RATE_LIMIT`. |
| **Default-allow regression** — a missing rule accidentally permits an action. | Default-deny posture; no rule ⇒ deny; covered by tests. |
| **Key compromise (software fallback, Path B)** | Reduced trust on Path B documented; Titan M2 hardware path preferred for tamper-evidence and key isolation. |
| **Clock manipulation to extend expiry** | Trusted monotonic time alongside wall clock for expiry and audit ordering. |

---

## 8. Open questions

- **Rule DSL form** — declarative config (e.g. a constrained data format) vs. an
  embedded expression language? How are rules versioned, signed, and safely
  hot-reloaded without opening a code-injection path?
- **Consent store backing** — encrypted local DB vs. keystore-wrapped blobs;
  how do grants survive OS backup/restore and factory-reset semantics?
- **Revocation latency** — does the ≤ 1 s in-flight guarantee require active
  cancellation signaling into the Registry, or is the pre-execution re-check
  sufficient for the expected concurrency?
- **Audit retention & rotation** — chain segmentation, checkpoint cadence,
  storage growth bounds, and export/verification tooling for an external auditor.
- **Multi-subject devices** — is more than one `subject` ever in scope on a
  single device, and how are grants partitioned if so?
- **Titan M2 availability** — exact attestation API surface and the precise
  trust delta of the Path B software fallback.
- **Performance** — can the full pipeline + durable audit append hold ≤ 10 ms
  p99 on target hardware, or does destructive-action durability need a separate
  budget?
- **Offline cloud-tool policy** — how policy rules gate cloud/bridged tools when
  the device is offline or egress is restricted.

---

## 9. Acceptance checklist

Restated from issue #5:

- [ ] No tool executes without a valid, unexpired consent token for the required scope.
- [ ] Android runtime permissions checked before tool execution; missing permissions surface a structured error (not a crash).
- [ ] Rate limits enforced per-user per-tool; excess requests return the `RATE_LIMIT` ACP error code.
- [ ] Audit log entry created for every tool invocation: tool ID, user ID, outcome, timestamp, parameters hash.
- [ ] Audit log is append-only and cryptographically signed (Titan M2 on Pixel, software fallback on Path B).
- [ ] Consent can be revoked at runtime; revocation takes effect within 1 second for in-flight requests.
- [ ] Policy rules are configurable via a rule DSL without requiring code changes.
- [ ] Policy Engine adds ≤ 10 ms overhead to the tool-call path (p99).
- [ ] Tests cover: consent grant, consent revoke, permission denied, rate limit exceeded, audit tamper detection.
