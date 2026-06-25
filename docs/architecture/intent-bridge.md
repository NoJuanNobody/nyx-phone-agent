# Intent Bridge

NLP intent classification pipeline that routes LLM output to ACP tool calls.

> **Status:** Architecture spec. Early-stage repo — no runtime/language is
> committed yet, so this document defines contracts, stages, and message shapes
> rather than working code. Schemas are **illustrative** (language and exact
> field encoding are TBD).

## Purpose & scope

The Intent Bridge is the translation layer between the on-device LLM and the
rest of the agent. It takes the LLM's reasoning output for a turn, classifies
what the user actually wants (the *intent*), extracts the structured arguments
that fulfilling it requires, resolves the intent to a concrete tool advertised
by the **MCP Tool Registry**, and dispatches a validated **ACP `ToolCall`**
message. It then feeds the tool result back into the agent/LLM loop.

It sits between two existing subsystems:

- **Upstream — On-device LLM inference** (`llm-inference.md`): produces the raw
  natural-language and/or tool-call-shaped output for a conversation turn.
- **Downstream — MCP Tool Registry** (`mcp-registry.md`) and **ACP**
  (`acp.md`): the catalog of callable tools and the message bus over which calls
  are dispatched and results returned.
- **Lateral — Policy Engine** (`policy-engine.md`): destructive or
  consent-requiring intents must pass policy checks before execution.

In scope:

- Intent classification from LLM output.
- Parameter / slot extraction for the target tool.
- Tool resolution against the MCP Tool Registry.
- Ambiguity resolution and low-confidence / no-match fallback.
- Dispatch over ACP and returning results to the agent loop.
- Per-classification audit logging.

Out of scope (owned elsewhere):

- Running the LLM (LLM inference subsystem).
- Maintaining the tool catalog or executing tools in a sandbox (MCP Registry).
- Consent decisions and audit-log durability (Policy Engine).
- Transport, framing, and delivery guarantees of messages (ACP).
- STT/TTS and turn detection (Voice pipeline).

## Responsibilities

- Parse raw LLM output into one or more candidate **classified intents**.
- Extract and normalize slots/arguments required by the candidate tool.
- Resolve each intent to a concrete tool descriptor from the MCP Registry.
- Decide between execution, clarification, and graceful fallback based on
  confidence and match quality.
- Validate arguments against the tool's input schema before dispatch.
- Emit an ACP `ToolCall` for each accepted intent and route the result back.
- Gate destructive intents through the Policy Engine.
- Produce an audit log entry for every classification.

## Non-goals

- The Intent Bridge is **not** a planner. It classifies and routes a single
  turn's intent; multi-step planning and conversation state live in the agent
  loop / LLM.
- It does not invent tools. If no registered tool matches, it falls back — it
  never fabricates a call.
- It does not enforce policy itself; it *consults* the Policy Engine.
- It does not guarantee delivery; ACP owns transport reliability.

## Pipeline

Stages from raw LLM output to ACP tool call:

1. **Ingest** — receive the LLM output for the current turn (free text and/or
   structured tool-call hints) plus conversation context.
2. **Classify** — `IntentClassifier` determines the intent name and a
   confidence score. Returns zero or more ranked candidate intents.
3. **Extract** — `ParameterExtractor` pulls slots/arguments from the utterance
   and context, normalizing types (dates, phone numbers, contact references).
4. **Resolve** — map the intent to a concrete tool descriptor discovered via the
   MCP Tool Registry; bind extracted slots to the tool's declared parameters.
5. **Gate** — branch on match quality and confidence:
   - high confidence + single match → continue;
   - ambiguous (multiple plausible tools) → `AmbiguityResolver` clarification;
   - low confidence / no match → `FallbackRouter`.
6. **Validate** — check bound arguments against the tool's input schema.
7. **Policy** — if the tool is flagged destructive/consent-requiring, consult
   the Policy Engine; proceed only on approval.
8. **Dispatch** — emit a validated ACP `ToolCall`.
9. **Return** — receive the ACP `ToolResult`, attach it to the agent/LLM loop
   for the next turn. Audit-log the classification throughout.

```
                 ┌──────────────────────────┐
 raw LLM output ─►        Ingest             │
 + context       └────────────┬─────────────┘
                              │
                 ┌────────────▼─────────────┐
                 │  Classify (IntentClassifier)
                 │  -> intent + confidence  │
                 └────────────┬─────────────┘
                              │
                 ┌────────────▼─────────────┐
                 │ Extract (ParameterExtractor)
                 │  -> slots / arguments    │
                 └────────────┬─────────────┘
                              │
                 ┌────────────▼─────────────┐      ┌──────────────────┐
                 │ Resolve  ◄───────────────┼──────┤ MCP Tool Registry │
                 │  intent -> tool + args   │      └──────────────────┘
                 └────────────┬─────────────┘
                              │
                 ┌────────────▼─────────────┐
                 │          Gate            │
                 │  confidence / match?     │
                 └──┬──────────┬─────────┬──┘
            ambiguous│   low/no-match│   ok│
                 ┌───▼───┐   ┌──────▼────┐ │
                 │Ambiguity│ │ Fallback  │ │
                 │Resolver │ │ Router    │ │
                 │(clarify)│ │(escalate/ │ │
                 └───┬─────┘ │ "I don't  │ │
                     │       │  know")   │ │
                     │       └───────────┘ │
                     └─────────┐    ┌───────┘
                               ▼    ▼
                 ┌──────────────────────────┐
                 │ Validate args vs schema  │
                 └────────────┬─────────────┘
                              │
                 ┌────────────▼─────────────┐      ┌──────────────────┐
                 │  Policy gate (if         ├──────► Policy Engine     │
                 │  destructive)            │      └──────────────────┘
                 └────────────┬─────────────┘
                              │ approved
                 ┌────────────▼─────────────┐      ┌──────────────────┐
                 │  Dispatch ACP ToolCall   ├──────►       ACP        │
                 └────────────┬─────────────┘      └────────┬─────────┘
                              │                             │
                              │        ACP ToolResult       │
                 ┌────────────▼─────────────┐◄──────────────┘
                 │ Return to agent/LLM loop │
                 └──────────────────────────┘
```

## Intent representation

A classified intent is the internal artifact passed from classification through
resolution. It carries the intent name, a confidence score, the extracted
slots, and the resolved target tool (populated during the Resolve stage).

```json
// illustrative only — language/encoding TBD
{
  "intentName": "schedule_calendar_event",
  "confidence": 0.93,
  "utterance": "set up a dentist appointment for next Tuesday at 3pm",
  "slots": {
    "title": "Dentist appointment",
    "start": "2026-06-30T15:00:00-07:00",
    "durationMinutes": 60,
    "attendees": []
  },
  "targetTool": {
    "toolId": "calendar.create_event",
    "registryVersion": "2026-06-01",
    "matchScore": 0.91
  },
  "candidates": [
    { "toolId": "calendar.create_event", "matchScore": 0.91 },
    { "toolId": "reminders.create",       "matchScore": 0.42 }
  ],
  "destructive": false,
  "traceId": "5f1c…"
}
```

Field notes:

- `intentName` — the classifier's label, drawn from the registry's intent
  vocabulary where possible.
- `confidence` — classifier confidence in `[0, 1]`, used by the Gate stage.
- `slots` — extracted, normalized arguments; keys map to the target tool's
  declared parameters after resolution.
- `targetTool` — populated at Resolve; `null`/absent before resolution.
- `candidates` — ranked alternatives, retained for ambiguity handling and audit.
- `destructive` — set during resolution from the tool descriptor's metadata;
  drives the Policy gate.
- `traceId` — correlates the classification, the ACP `ToolCall`, its result,
  and the audit entry.

## Tool resolution

Resolution maps a classified intent to a concrete, callable tool.

1. **Discovery** — query the MCP Tool Registry for tools whose advertised
   capability/intent metadata matches the classified `intentName` and slot
   shape. The registry is the single source of truth for what tools exist, their
   input schemas, and their flags (e.g. `destructive`, required consent scopes).
2. **Scoring** — rank candidate tools by match quality (intent-label match, slot
   coverage against required parameters, capability tags). Each candidate gets a
   `matchScore`.
3. **Binding** — bind extracted slots to the chosen tool's declared parameters,
   coercing/renaming as the tool's schema requires.

Decision rules at the Gate stage (thresholds are tunable configuration):

- **Confident single match** — top candidate clears the acceptance threshold and
  no near-tie exists → proceed to validation.
- **Ambiguity** — two or more candidates are within a small margin of each other,
  or a single tool has multiple plausible parameter bindings → hand to
  `AmbiguityResolver`, which asks the user a clarifying question and **never
  silently guesses**. The chosen answer re-enters at Resolve/Validate.
- **Low confidence** — top `confidence` or `matchScore` is below the acceptance
  threshold → hand to `FallbackRouter`.
- **No match** — discovery returns no candidate → hand to `FallbackRouter`,
  which escalates to a human or responds gracefully ("I don't know how to do
  that yet"), without crashing the agent loop.

## Routing to ACP

Once an intent is resolved, validated, and (if needed) policy-approved, the
Intent Bridge constructs an ACP `ToolCall` message and dispatches it over the
ACP bus (see `acp.md`).

- The `ToolCall` carries `toolId`, the bound arguments, and the `traceId`/call
  correlation id so the response can be matched back to this classification.
- ACP routes the call to the MCP Registry's sandboxed executor and returns a
  `ToolResult` (success payload or structured error) addressed to the same
  correlation id.
- The Intent Bridge attaches the `ToolResult` to the **agent/LLM loop** as
  context for the next turn (e.g. to summarize the outcome to the user, or to
  drive a follow-up tool call). The Intent Bridge does not interpret the result
  beyond routing — turn-level reasoning belongs to the LLM/agent loop.

```json
// illustrative ACP ToolCall — shape governed by acp.md
{
  "type": "ToolCall",
  "callId": "c-9a3…",
  "traceId": "5f1c…",
  "toolId": "calendar.create_event",
  "arguments": {
    "title": "Dentist appointment",
    "start": "2026-06-30T15:00:00-07:00",
    "durationMinutes": 60
  }
}
```

## Safety

- **Destructive intents must route through the Policy Engine before
  execution.** Any tool whose registry descriptor is flagged destructive (delete,
  send, pay, place a call, modify health data, share data externally) or that
  requires a consent scope is held at the Policy gate. The Intent Bridge submits
  the intent + bound arguments to the Policy Engine and dispatches the ACP
  `ToolCall` only on an explicit approval decision. A denial is surfaced back to
  the agent loop, not executed.
- The Intent Bridge never bypasses the Registry to call a tool directly, and
  never fabricates a tool that the Registry did not advertise.
- Ambiguous destructive intents are clarified *before* the Policy gate so the
  user confirms both *what* and *which* before any consent decision.

## Error handling

- **Malformed LLM output** — output that cannot be parsed into a classified
  intent is treated as a classification miss and routed to `FallbackRouter`; the
  agent loop is never crashed. The raw output and parse error are audit-logged.
- **Hallucinated tools** — an intent referencing a `toolId` not present in the
  MCP Registry is rejected at Resolve and routed to fallback; the bridge does
  **not** attempt the call. The hallucinated id is recorded for evaluation.
- **Argument validation failure** — bound arguments that fail the tool's input
  schema (missing required field, wrong type, out-of-range) do not dispatch.
  Depending on configuration, the bridge either re-extracts, asks the user to
  clarify the missing/invalid slot, or falls back. The validation error is
  audit-logged.
- **ACP / execution errors** — a `ToolResult` error (tool failure, sandbox
  rejection, timeout) is returned to the agent loop as a structured error for
  the LLM to handle or surface to the user.
- **Audit on every path** — happy path, clarification, fallback, and every error
  branch produce an audit entry containing at minimum the intent type, confidence
  score, and extracted parameters.

## Open questions

- **Classification mechanism** — LLM-as-classifier (structured/tool-call output)
  vs. a separate lightweight on-device classifier model. Latency budget
  (≤ 500 ms p95 on-device) may force the latter for the classify+extract path.
- **Intent vocabulary ownership** — does the MCP Registry publish the canonical
  intent vocabulary, or does the Intent Bridge maintain its own mapping layer?
- **Confidence calibration** — how `confidence` is derived (logits, self-report,
  ensemble) and how acceptance/ambiguity thresholds are tuned per tool class.
- **Slot extraction strategy** — single combined classify+extract LLM call vs.
  staged calls; trade-off between latency and accuracy.
- **Multi-intent turns** — handling utterances that imply several tool calls in
  one turn (sequencing, partial failure) — likely deferred to the agent loop.
- **Clarification UX** — voice vs. on-screen disambiguation, and how many
  clarification rounds before escalating to `FallbackRouter`.
- **Audit transport** — whether audit entries go through the Policy Engine's
  tamper-evident log or a bridge-local log first.

## Acceptance checklist

Restated from issue #4:

- [ ] Intent classification correctly routes ≥ 95% of test utterances to the
      correct tool in the MCP registry.
- [ ] Parameter extraction populates all required fields for each tool type
      (calendar, contacts, phone, SMS, health).
- [ ] Ambiguous intents trigger a clarification dialog before execution — never
      silently guess.
- [ ] Unrecognized intents fall back gracefully without crashing the agent loop.
- [ ] All classified intents produce a valid ACP `ToolCall` message (validated
      against the ACP schema).
- [ ] Intent Bridge latency (classification + parameter extraction) ≤ 500 ms p95
      on-device.
- [ ] Each intent classification produces an audit log entry with intent type,
      confidence score, and extracted parameters.
- [ ] Unit tests cover: happy path, ambiguous input, unknown intent, malformed
      LLM response.
