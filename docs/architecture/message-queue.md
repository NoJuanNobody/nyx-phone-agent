# Message Queue Layer

Spec for the **message queue between OS event capture and model inference**.
Tracks issue [#44](https://github.com/NoJuanNobody/nyx-phone-agent/issues/44).

> Status: **draft spec**. No runtime is committed for this repo yet, so this
> document defines responsibilities, interfaces, message shapes, and an
> acceptance checklist rather than working code. All code/JSON below is
> **illustrative** and language-agnostic.

## 1. Purpose & scope

The current architecture (see the "Action Layer / OS Primitives" section of the
Phone Agent spec) describes a **direct synchronous loop**:

```
OS event ──▶ model call ──▶ action
```

That loop couples OS event capture directly to LLM inference. On-device
inference (Gemma 3 / Llama 3 via MediaPipe or llama.cpp) is slow, bursty, and
occasionally unavailable, while OS events arrive in unpredictable bursts (a
scroll, a notification storm, a rapid sequence of UI changes). A synchronous
loop drops events whenever inference is busy and gives no place to throttle,
prioritize, or retry.

The **message queue layer** is an in-process buffer that **decouples the rate at
which OS observations are produced from the rate at which the inference
dispatcher can consume them**. Its responsibilities:

- Accept OS-observation envelopes from one or more **producers** (event capture).
- Buffer them with a **bounded** depth and a defined **overflow policy**.
- Hand them to a single **consumer** (the inference dispatcher) at the consumer's
  pace, applying **backpressure** when the consumer falls behind.
- Provide defined **delivery** and **ordering** guarantees.
- Optionally **persist** undelivered messages so an in-flight observation survives
  a process restart.

**In scope:** the queue contract, envelope schema, enqueue/dequeue semantics,
backpressure, bounded size, overflow, durability, delivery guarantees, ordering,
and producer/consumer interfaces.

**Out of scope (delegated to sibling specs):**

- Coalescing/rate-limiting of redundant events *before* enqueue —
  [`event-debounce.md`](./event-debounce.md).
- How dequeued items are ordered by importance —
  [`queue-priority.md`](./queue-priority.md).
- What happens when an inference call *fails* after dequeue —
  [`inference-retry.md`](./inference-retry.md).

The queue itself stays deliberately simple: it transports envelopes. Debounce
sits in front of it, priority orders its dequeue, and retry wraps the consumer.

## 2. Where it sits

```
        ┌─────────────┐
        │  OS events  │  notifications, UI/accessibility tree changes,
        │  (Android)  │  sensor + app signals, ACP inbound observations
        └──────┬──────┘
               │
        ┌──────▼───────┐
        │ Event capture │  Accessibility Service / Shizuku bridge /
        │  (producers)  │  ACP listeners normalize raw OS signals
        └──────┬───────┘
               │
       ┌───────▼────────┐
       │   debounce /   │   see event-debounce.md  (coalesce + drop dupes
       │   coalesce     │                            BEFORE enqueue)
       └───────┬────────┘
               │ enqueue(envelope)
   ┌───────────▼─────────────┐
   │   ▓▓ MESSAGE QUEUE ▓▓    │   <── THIS SPEC
   │  bounded, ordered,      │       priority ordering: queue-priority.md
   │  optionally persistent  │
   └───────────┬─────────────┘
               │ dequeue(envelope)
      ┌────────▼──────────┐
      │ Inference         │   single consumer / worker pool; pulls at its
      │ dispatcher        │   own pace; wraps each call in retry logic
      │ (consumer)        │   (inference-retry.md)
      └────────┬──────────┘
               │ model call (Gemma 3 / Llama 3)
      ┌────────▼──────────┐
      │  LLM inference    │
      └────────┬──────────┘
               │ structured output
      ┌────────▼──────────┐
      │  Intent Bridge    │   NLP intent → ACP tool calls
      │   → ACP bus       │   (acp.md, intent-bridge.md)
      └───────────────────┘
```

Related specs:

- [`event-debounce.md`](./event-debounce.md) — coalesces/throttles events
  upstream of `enqueue`.
- [`queue-priority.md`](./queue-priority.md) — defines dequeue ordering beyond
  FIFO.
- [`inference-retry.md`](./inference-retry.md) — defines consumer-side retry on
  inference failure.
- `acp.md` / `intent-bridge.md` — downstream consumers of inference output
  (authored separately; the queue does not depend on their internals).

## 3. Message / envelope schema

Every queued item is an **OS-observation envelope**: a normalized capture of one
OS event plus the metadata the queue and downstream stages need. The envelope is
opaque to the queue except for the fields it uses for ordering, dedup, and TTL.

```jsonc
// ILLUSTRATIVE — not a committed schema
{
  "id": "obs_01HZX9K3M7QF",          // ULID; unique, monotonic, dedup key
  "schemaVersion": 1,                 // envelope format version
  "enqueuedAt": "2026-06-25T14:03:11.482Z",
  "source": {
    "producer": "accessibility-service", // which capture module emitted it
    "captureSeq": 84213                   // per-producer monotonic sequence
  },
  "kind": "ui.tree_changed",          // OS observation type (taxonomy TBD)
  "priority": 5,                       // 0=highest; default class; see queue-priority.md
  "dedupKey": "ui.tree:com.example.app", // optional; coalesce key for debounce
  "ttlMs": 15000,                      // drop if not consumed within this window
  "attempts": 0,                       // dequeue attempts (incremented by consumer/retry)
  "payload": {                         // OPAQUE to the queue; shaped by capture layer
    "package": "com.example.app",
    "windowTitle": "Inbox",
    "summary": "3 new accessibility nodes added",
    "snapshotRef": "blob://ui/01HZX9K3M7QF"  // large blobs stored by ref, not inline
  },
  "trace": {                           // observability / correlation
    "traceId": "trc_7c1a...",
    "spanId": "spn_4e90..."
  }
}
```

Notes:

- **Large payloads (screenshots, full UI trees) are stored by reference**
  (`snapshotRef`), not inlined, to keep envelopes small and the queue's memory
  footprint predictable.
- `id` doubles as the idempotency/dedup key for at-least-once delivery.
- `dedupKey`, `priority`, `ttlMs` are populated by the capture/debounce layer;
  the queue only *reads* them.

## 4. Enqueue / dequeue semantics

### Enqueue

- `enqueue(envelope) -> EnqueueResult` is **non-blocking by default** and
  returns one of: `ACCEPTED`, `REJECTED_FULL`, `REJECTED_DUP`, `REJECTED_TTL`.
- The queue stamps `enqueuedAt` and validates `schemaVersion`.
- If the queue is at capacity, the configured **overflow policy** (§5) decides
  whether the new item, an existing item, or nothing is dropped.
- Optional `enqueueBlocking(envelope, timeoutMs)` variant for producers that
  *can* tolerate blocking (used as the `BLOCK` backpressure strategy).

### Dequeue

- `dequeue(timeoutMs) -> envelope | null` blocks up to `timeoutMs` waiting for an
  item (supports the "empty queue polling" acceptance case: returns `null`
  cleanly on timeout, no busy-spin).
- Dequeue order is **priority-then-FIFO** (FIFO within a priority class). Plain
  FIFO is the degenerate single-class case. Detailed ordering rules live in
  [`queue-priority.md`](./queue-priority.md).
- On dequeue the item moves to an **in-flight / unacked** state (durable mode) or
  is removed outright (ephemeral mode). The consumer must `ack(id)` or
  `nack(id, requeue?)`:
  - `ack(id)` — successfully dispatched to inference; remove permanently.
  - `nack(id, requeue=true)` — return to the queue (head of its priority class)
    for another attempt; `attempts` incremented. Retry/backoff policy is owned by
    [`inference-retry.md`](./inference-retry.md).
  - `nack(id, requeue=false)` — give up; route to the **dead-letter** sink (§6).
- Expired items (`now - enqueuedAt > ttlMs`) are skipped/discarded on dequeue and
  never handed to the consumer.

## 5. Backpressure, bounded size & overflow

The queue is always **bounded** by a configurable `maxDepth` (acceptance: "queue
supports configurable max depth to prevent unbounded growth"). When full, one of
the following **overflow policies** applies (acceptance: "queue backpressure
behavior is documented"):

| Policy | Behavior when full | When to use |
|--------|--------------------|-------------|
| `DROP_NEWEST` | Reject the incoming envelope (`REJECTED_FULL`). | Default. Protects the consumer; oldest context is most likely already stale-coalesced. |
| `DROP_OLDEST` | Evict the lowest-priority / oldest unacked item, then accept. | When the freshest OS state matters more than history. |
| `BLOCK` | Producer blocks (bounded by `timeoutMs`) until space frees. | When the producer can pause capture and no event may be lost. |

Recommended default: **`DROP_NEWEST`** with `maxDepth` sized to roughly one
inference round-trip's worth of bursty events. Because debounce already coalesces
redundant events upstream, overflow should be rare in practice; when it happens,
every dropped envelope is **counted and logged** (`queue.overflow.dropped` metric
with the policy and `kind`) so capacity can be tuned.

**Backpressure signals.** The queue exposes `depth()`, `highWaterMark`, and an
optional `onPressure(level)` callback so the capture layer can throttle or raise
debounce aggressiveness *before* the hard cap is hit. Soft thresholds (e.g. 70% /
90% of `maxDepth`) drive these signals; the hard cap drives the overflow policy.

### Persistence / durability across restarts

Two operating modes, chosen by config:

- **Ephemeral (in-memory).** Lowest latency, simplest. The queue lives in process
  memory; **undelivered items are lost on crash/restart**. Acceptable when OS
  events are continuously re-observable (a fresh UI snapshot supersedes a lost
  one). This is the recommended *default* for the first runtime.
- **Durable.** Envelopes are written to a local append-only store (e.g. an
  embedded WAL, SQLite, or a Redis-backed list/stream — issue #44 names
  "in-memory or Redis-backed") **before** `enqueue` returns `ACCEPTED`. On
  startup the queue **replays** unacked items from the store back into the
  in-flight set. `ack`/overflow-drop remove the record. Large `payload` blobs
  remain by-reference; the durable store holds only envelopes.

Durability and delivery guarantees (§6) are linked: durable mode is what enables
*at-least-once across restarts*.

## 6. Delivery guarantees & ordering

### Delivery

The queue is configured for **at-least-once** delivery by default:

- An item is removed only after the consumer `ack`s it. A crash between dequeue
  and `ack` (durable mode) → the item is replayed on restart and re-delivered.
- Consequence: the **consumer/inference stage must be idempotent or
  dedup-aware**. The envelope `id` is the idempotency key. Downstream stages
  (Intent Bridge → ACP) should treat a repeated `id` as a no-op.

**At-most-once** is available as an explicit, opt-in mode (dequeue removes
immediately, no ack) for envelope `kind`s where a duplicated action is worse than
a missed observation. This is a per-policy choice, not the default.

There is intentionally **no exactly-once** claim — it is approximated by
at-least-once delivery plus idempotent consumers.

### Ordering

- **Within a single priority class:** strict FIFO by `enqueuedAt` /
  `source.captureSeq`.
- **Across priority classes:** higher priority is dequeued first; this means
  global ordering is *not* strict FIFO. See
  [`queue-priority.md`](./queue-priority.md).
- **Per-producer ordering** is preserved within a class (a producer's
  `captureSeq` is monotonic and FIFO-respected). Cross-producer ordering is only
  best-effort by `enqueuedAt`.
- Requeued (`nack`) items return to the **head** of their priority class to be
  retried promptly, which can locally reorder relative to strict arrival order —
  an accepted trade-off for retry latency.

### Dead-letter

Items that exhaust retries (`nack(requeue=false)` or `attempts >= maxAttempts`)
go to a bounded **dead-letter sink** with the failure reason, for observability
and optional manual replay. The DLQ is itself bounded and drops oldest when full.

## 7. Interfaces

### Producer interface (event capture → queue)

```
// ILLUSTRATIVE
interface QueueProducer {
  enqueue(envelope: Envelope): EnqueueResult        // ACCEPTED | REJECTED_FULL | REJECTED_DUP | REJECTED_TTL
  enqueueBlocking(envelope: Envelope, timeoutMs: int): EnqueueResult
  depth(): int
  onPressure(cb: (level: PressureLevel) => void): void   // soft-threshold signals
}
```

Producers (Accessibility Service, Shizuku/root bridge, ACP inbound listeners)
construct envelopes, run them through the debounce/coalesce stage
([`event-debounce.md`](./event-debounce.md)), then call `enqueue`. Producers
**never block the OS event thread**; if `enqueue` would block, they use the
non-blocking form and react to the `EnqueueResult`.

### Consumer interface (queue → inference dispatcher)

```
// ILLUSTRATIVE
interface QueueConsumer {
  dequeue(timeoutMs: int): Envelope | null   // null on timeout (empty-queue polling)
  ack(id: string): void                      // dispatched successfully
  nack(id: string, requeue: boolean): void   // failed; requeue or dead-letter
}
```

The inference dispatcher runs a worker loop:

```
// ILLUSTRATIVE
loop {
  env = consumer.dequeue(timeoutMs = 1000)
  if (env == null) continue                 // idle poll, no busy-spin
  try {
    result = inference.run(env)             // wrapped per inference-retry.md
    intentBridge.submit(env.id, result)     // → ACP (acp.md / intent-bridge.md)
    consumer.ack(env.id)
  } catch (e) {
    consumer.nack(env.id, requeue = retryable(e))   // policy: inference-retry.md
  }
}
```

A single consumer preserves ordering most simply; a small worker pool is allowed
if downstream idempotency holds and ordering relaxation is acceptable.

## 8. Open questions

1. **Default mode** — ship ephemeral (in-memory) first and add durability later,
   or require durability from day one? (Leaning ephemeral default given events
   are re-observable.)
2. **Durable backend** — embedded WAL/SQLite vs Redis. Redis adds an external
   dependency on-device; SQLite/WAL keeps everything local. Decide once the
   runtime/stack is chosen.
3. **`maxDepth` default and sizing** — needs measured event burst rates and a
   measured inference round-trip distribution.
4. **TTL semantics** — global default vs per-`kind` TTLs; should an expired item
   ever be surfaced to the consumer as a "missed observation" signal?
5. **Priority taxonomy** — owned by [`queue-priority.md`](./queue-priority.md);
   this spec assumes an integer `priority` field exists.
6. **Blob lifecycle** — who garbage-collects `snapshotRef` blobs when an envelope
   is dropped/expired/dead-lettered? (Likely a ref-counted blob store keyed by
   envelope `id`.)
7. **Multi-consumer ordering** — if a worker pool is introduced, what ordering
   contract do we actually promise downstream?

## 9. Acceptance checklist

Restating issue [#44](https://github.com/NoJuanNobody/nyx-phone-agent/issues/44)
acceptance criteria, mapped to this spec:

- [ ] **A persistent message queue (in-memory or Redis-backed) sits between the
  OS observation layer and the model inference call.** → §1–§2 (placement), §5
  (ephemeral vs durable modes).
- [ ] **OS events are enqueued and consumed by a worker that dispatches model
  calls.** → §4 (enqueue/dequeue), §7 (producer + consumer worker loop).
- [ ] **Queue supports configurable max depth to prevent unbounded growth.** →
  §5 (`maxDepth`, bounded).
- [ ] **Queue backpressure behavior is documented (drop oldest, drop newest, or
  block).** → §5 overflow-policy table (`DROP_OLDEST` / `DROP_NEWEST` / `BLOCK`)
  + soft-threshold pressure signals.
- [ ] **Unit tests cover: normal enqueue/dequeue, queue full behavior, empty
  queue polling.** → test plan below.

### Test plan (to author once a runtime is chosen)

| Case | Expectation |
|------|-------------|
| Normal enqueue/dequeue | FIFO-within-priority item enqueued is dequeued intact; `ack` removes it. |
| Queue full — `DROP_NEWEST` | At `maxDepth`, `enqueue` returns `REJECTED_FULL`; depth unchanged; overflow metric incremented. |
| Queue full — `DROP_OLDEST` | At `maxDepth`, oldest/lowest-priority evicted; new item accepted; depth stable. |
| Queue full — `BLOCK` | Producer blocks until space frees or `timeoutMs` elapses. |
| Empty-queue polling | `dequeue(timeoutMs)` returns `null` after timeout with no busy-spin / no exception. |
| TTL expiry | Item past `ttlMs` is skipped on dequeue, never handed to consumer. |
| Delivery (at-least-once) | Dequeue without `ack`, then restart (durable) → item re-delivered. |
| Dedup | Re-enqueue of an existing `id` returns `REJECTED_DUP`. |
| Dead-letter | `attempts >= maxAttempts` routes item to DLQ with reason. |
