# Inference Retry & Timeout Handling

Spec for **retry + timeout handling of failed model-inference calls triggered by
OS events** — resolves issue **#47**.

> **Stage note.** This is a specification, not an implementation. The runtime and
> language for the Nyx Phone Agent are not yet committed. Concrete names, JSON
> shapes, and pseudo-state machines below are **illustrative** and define the
> contract a future implementation must satisfy.

## 1. Purpose & scope

OS events (incoming call, notification, accessibility change, wake word, etc.)
arrive on the inference dispatcher via the message queue. For each dequeued
event the dispatcher performs one or more **on-device model-inference calls**
(intent classification, the agent action loop, tool-argument synthesis). These
calls can **fail**, **hang**, or **time out**: the model backend (MediaPipe LLM
Inference / llama.cpp) may stall, be evicted under memory pressure, throw, or a
cloud-bridge tool call may drop its connection.

Without explicit handling a single failed or hung inference call silently
**stalls the agent** mid-action-loop — the triggering event is neither completed
nor surfaced. This spec defines the wrapper around every inference call so that
failures are bounded, retried where safe, and never silently dropped.

**In scope**

- A per-call **timeout/deadline** model with cancellation.
- A **retry policy**: exponential backoff with jitter, bounded attempts.
- **Failure classification** (transient vs permanent) driving retry decisions.
- **Dead-letter** handling for exhausted/non-retryable failures.
- A **circuit breaker** for sustained model failure.
- How a give-up **surfaces back** to the queue and ultimately the user.

**Out of scope**

- Queue dequeue/ack/priority semantics — owned by
  [`./message-queue.md`](./message-queue.md) and
  [`./queue-priority.md`](./queue-priority.md).
- Model loading, quantization, context management — owned by
  [`./llm-inference.md`](./llm-inference.md).
- Tool-execution retries downstream of the model decision — owned by the MCP
  registry ([`./mcp-registry.md`](./mcp-registry.md)).

```
  OS event ──▶ message-queue ──▶ inference dispatcher
                                       │
                                       ▼
                              ┌──────────────────┐
                              │  retry wrapper    │  ◀── this spec
                              │  (timeout + retry │
                              │   + breaker + DLQ)│
                              └──────────────────┘
                                       │
                                       ▼
                              model backend (Gemma/Llama)
```

## 2. Timeout model

Every inference call runs under a **deadline**. Timeouts come in two tiers:

| Tier | Default | Meaning | Action |
|------|---------|---------|--------|
| **Soft timeout** | 10s | Per-attempt call budget exceeded. The attempt is abandoned and counts as a (timeout-classed) failure eligible for retry. | Cancel the in-flight attempt; classify as transient; consider retry. |
| **Hard deadline** | 30s | Total wall-clock budget across *all* attempts for this event. | Stop retrying immediately; route to dead-letter even if attempts remain. |

- **Per-call (soft) timeout** is `inference.timeout_ms` (issue #47 default
  **10s**). It bounds a single attempt.
- **Hard deadline** bounds the whole retry sequence so a flapping backend cannot
  hold an OS event hostage indefinitely. When the next backoff sleep would push
  past the hard deadline, the wrapper gives up *now* rather than sleeping.
- Deadlines are **monotonic-clock** based (immune to wall-clock/NTP jumps).

### Cancellation

A soft timeout MUST actually **cancel** the in-flight inference, not merely
ignore its result:

- The backend call is issued with a cancellation token / abort handle.
- On timeout the wrapper signals cancellation so the backend can release the
  decode loop and free KV-cache / compute — critical on a memory-constrained
  phone where an orphaned decode starves the next attempt.
- If a cancelled call later returns, its result is **discarded** (the attempt is
  already accounted as a timeout).
- Cancellation is best-effort: if the backend cannot abort promptly, the wrapper
  still proceeds, but the breaker (§5) treats unkillable hangs as failures.

## 3. Retry policy

### 3.1 Backoff with jitter

Retries use **exponential backoff with full jitter**:

```
base_delay   = retry.base_delay_ms          (default 250ms)
max_delay    = retry.max_delay_ms            (default 8000ms)
attempt      = 1, 2, 3, ...                  (attempt 1 is the first try)

raw_delay    = min(max_delay, base_delay * 2^(attempt-1))
sleep_delay  = random_between(0, raw_delay)     # full jitter
```

Full jitter is chosen to avoid retry storms when many OS events fail
simultaneously (e.g., backend evicted under global memory pressure).

### 3.2 Attempt bound

- `retry.max_attempts` (issue #47 default **3**) total attempts = 1 initial +
  up to 2 retries. After the last attempt fails, the event is dead-lettered.
- The **hard deadline** (§2) can cut the sequence short before `max_attempts`.

### 3.3 Idempotency & what is safe to retry

Retrying a *pure* inference call (prompt → text/JSON) is inherently safe: it has
no external side effects. The danger is retrying a call whose **side effects may
already have been applied**.

| Situation | Safe to retry? | Rationale |
|-----------|----------------|-----------|
| Inference failed/timed out **before** any tool/ACP action emitted | **Yes** | Pure compute, no observable effect. |
| Inference produced output but it failed **during/after** dispatching a side-effecting ACP action (e.g. sent a reply, dismissed a call) | **No (not automatically)** | Re-running could double-send. Treat as permanent; dead-letter for review. |
| Multi-step agent loop, failure mid-loop | **Resume**, don't restart | Replay only the failed *step*; completed side-effecting steps must not re-run. |

Requirements:

- Each event carries a stable **`event_id`**; each attempt a monotonically
  increasing **`attempt`** counter. Together `(event_id, attempt)` is the retry
  key used in logs and idempotency guards.
- Side-effecting steps (any ACP tool call that mutates device/world state) MUST
  be guarded by an **idempotency key** so that a retried step the policy engine
  has already executed is a no-op rather than a duplicate. The retry wrapper
  only auto-retries calls **upstream** of the first committed side effect; once a
  side effect commits, the action loop is responsible for resume-vs-restart.

## 4. Failure classification & dead-letter

### 4.1 Transient vs permanent

| Class | Examples | Retry? |
|-------|----------|--------|
| **Transient** | soft timeout, backend busy/evicted, OOM-recoverable, transient cloud-bridge network error, rate limit (with backoff) | **Yes**, within bounds |
| **Permanent** | malformed/oversized prompt, context-length overflow, model-not-loaded config error, schema-invalid output that fails re-parse, unsupported request | **No** — dead-letter immediately |
| **Ambiguous** | unknown backend error code | Treated as transient up to `max_attempts`, then dead-lettered |

Classification is a pure function of `(error_kind, http/abi status, message)` and
MUST be centralized so policy is testable in isolation.

### 4.2 Dead-letter handling

When retries are exhausted, the hard deadline trips, the breaker is open, or the
failure is permanent, the event is moved to a **dead-letter queue (DLQ)** — it is
**never silently dropped** (issue #47 AC).

A DLQ entry is logged with **full context**: the observation snapshot that
triggered the event, timestamp, attempt history, final error, and classification.
The original queue message is **ack'd-as-failed** (not redelivered) so the live
queue drains; the durable record lives in the DLQ. DLQ entries are inspectable,
support manual/operator replay, and feed alerting/metrics.

### 4.3 Surfacing a give-up

A give-up MUST become **observable**, not a silent stall:

1. **Queue:** the message is settled as `failed`; the dispatcher emits a
   `inference.gave_up` signal referencing the DLQ `event_id` (see
   [`./message-queue.md`](./message-queue.md) for settle/ack semantics).
2. **Audit:** the policy engine logs the give-up (tamper-evident audit trail).
3. **User:** for **user-visible** triggers (e.g. "answer this call", "reply to
   this text") the agent surfaces a graceful fallback — a brief notification that
   it could not complete the action — rather than appearing to hang. For
   background/best-effort triggers, the give-up is silent to the user but fully
   recorded in the DLQ + audit log.

## 5. Circuit breaker

To stop hammering a model backend that is **sustainedly** failing (e.g. backend
crashed, device thermally throttled), the wrapper wraps a **circuit breaker**
around the backend:

- **Closed** (normal): calls flow. A rolling window of failures is tracked.
- **Open:** once failures exceed `breaker.failure_threshold` within
  `breaker.window_ms`, the breaker opens. New inference requests **fail fast**
  (no attempt, no backoff sleeps) and are immediately dead-lettered or, for
  low-priority events, deferred — preventing retry storms and wasted battery.
- **Half-open:** after `breaker.cooldown_ms`, a limited number of **probe** calls
  are allowed. Success → close; failure → re-open with cooldown.

The breaker is **per-backend** (local model vs cloud bridge tracked separately)
so a flaky cloud bridge does not trip the on-device model and vice-versa. While
open, the dispatcher MAY apply backpressure to the queue (see
[`./queue-priority.md`](./queue-priority.md)) so high-priority events are first in
line when the breaker recovers.

## 6. Illustrative config & failure record

> **Illustrative only** — shapes, not a committed schema.

### Retry config (illustrative)

```json
{
  "inference": {
    "timeout_ms": 10000,
    "hard_deadline_ms": 30000
  },
  "retry": {
    "max_attempts": 3,
    "base_delay_ms": 250,
    "max_delay_ms": 8000,
    "jitter": "full",
    "retry_on": ["timeout", "backend_busy", "oom_recoverable", "network", "rate_limit"],
    "never_retry_on": ["context_overflow", "schema_invalid", "model_not_loaded", "post_side_effect"]
  },
  "breaker": {
    "failure_threshold": 5,
    "window_ms": 60000,
    "cooldown_ms": 15000,
    "half_open_probes": 1,
    "scope": "per_backend"
  }
}
```

### Dead-letter / failure record (illustrative)

```json
{
  "event_id": "evt_01J9X8K2QZ7M",
  "trigger": { "source": "os_event", "kind": "incoming_call" },
  "observation_snapshot": { "screen": "...", "notif": "...", "ts": "2026-06-25T14:03:11.220Z" },
  "backend": "local_gemma3",
  "final_classification": "transient_exhausted",
  "attempts": [
    { "attempt": 1, "outcome": "timeout",       "duration_ms": 10000, "error": "soft_deadline_exceeded" },
    { "attempt": 2, "outcome": "backend_busy",  "duration_ms": 1840,  "error": "ENGINE_BUSY" },
    { "attempt": 3, "outcome": "timeout",       "duration_ms": 10000, "error": "soft_deadline_exceeded" }
  ],
  "gave_up_reason": "max_attempts_exhausted",
  "first_seen": "2026-06-25T14:02:51.000Z",
  "dead_lettered_at": "2026-06-25T14:03:13.700Z",
  "breaker_state_at_giveup": "closed"
}
```

## 7. State diagram

```
                      ┌───────────┐
        dequeued ───▶ │  PENDING  │
                      └─────┬─────┘
                            │ start attempt (breaker CLOSED)
                            ▼
                      ┌───────────┐
                      │  RUNNING  │◀──────────────┐
                      └─────┬─────┘               │
            ┌───────────────┼───────────────┐     │ backoff + jitter
            │               │               │     │ (attempt < max
        success         soft timeout    error/fail │  AND within
            │               │               │     │  hard deadline
            ▼               ▼               ▼     │  AND transient)
      ┌──────────┐   ┌──────────┐   ┌──────────┐  │
      │ COMPLETE │   │ TIMEOUT  │   │  FAILED  │  │
      └──────────┘   └────┬─────┘   └────┬─────┘  │
                          │  classify    │        │
                          └──────┬───────┘        │
                                 ▼                │
                          ┌──────────────┐        │
                          │ retryable? ──┼── yes ─┘
                          └──────┬───────┘
                                 │ no  (permanent | max_attempts
                                 │      | hard deadline | breaker OPEN)
                                 ▼
                          ┌──────────────┐
                          │  DEAD-LETTER │ ──▶ settle queue `failed`,
                          └──────────────┘     audit log, surface to user
```

## 8. Open questions

- **Resume vs restart granularity:** at what step boundary in the agent action
  loop is it safe to resume? Needs the action-loop checkpoint contract defined.
- **Per-priority overrides:** should high-priority OS events get a larger
  `timeout_ms` / `max_attempts` than background events? Coordinate with
  [`./queue-priority.md`](./queue-priority.md).
- **DLQ retention & replay UX:** retention window, on-device storage budget, and
  whether replay is operator-only or user-triggerable.
- **Breaker thresholds on constrained hardware:** tune against real thermal /
  memory-pressure behavior once a device target is chosen.
- **Cloud-bridge vs local-model defaults:** likely need distinct timeout/retry
  profiles per backend rather than one global config.
- **User-notification policy:** which trigger kinds are "user-visible" enough to
  warrant a give-up notification vs. silent DLQ.

## 9. Acceptance checklist

Restates issue **#47** acceptance criteria:

- [ ] Model calls retry up to **N** times (configurable, default **3**) with
      exponential backoff (full jitter, bounded `max_delay`).
- [ ] After **N** failures the triggering OS event is moved to a **dead-letter
      queue** (not silently dropped).
- [ ] DLQ entries are logged with **full context** (observation snapshot,
      timestamp, attempt history, error, classification).
- [ ] Per-model-call **timeout is configurable** (default **10s**), with a hard
      total deadline and real cancellation of hung calls.
- [ ] Failures are **classified** transient vs permanent; only transient
      failures are retried; permanent failures dead-letter immediately.
- [ ] A **circuit breaker** prevents retry storms during sustained backend
      failure.
- [ ] Give-up is **surfaced** back to the queue/audit/user, never a silent stall.
- [ ] Tests cover: **transient failure + recovery**, **exhausted retries**,
      **timeout behavior** (plus permanent-classification and breaker
      open/half-open transitions).

## See also

- [`./message-queue.md`](./message-queue.md) — dequeue / ack / settle-failed
  semantics the wrapper consumes.
- [`./queue-priority.md`](./queue-priority.md) — priority & backpressure during
  breaker-open.
- [`./llm-inference.md`](./llm-inference.md) — backend the wrapper calls and
  cancels.
</content>
</invoke>
