# Queue Priority — Prioritized OS→Model Messages

> Status: **Spec** (early-stage). No runtime is committed yet; this document
> defines the priority model, scheduling semantics, contracts, and an acceptance
> checklist. Implementation follows once a stack is chosen. See
> [CLAUDE.md](../../CLAUDE.md).

Tracks issue **#46** — *Implement priority levels for queued OS-to-model messages
(interrupt vs. background)*. Relates to the **Action Layer / OS Primitives** and
**Interruption Handling** themes of the Phone Agent architecture.

## Purpose & scope

The agent observes a continuous stream of OS events (incoming calls, system
alerts, permission dialogs, app/UI changes, sensor and background-task
observations) and feeds derived messages to on-device model inference. Inference
is a scarce, serialized resource: one context window, limited compute, and a
single in-flight generation at a time.

When the queue is FIFO, an urgent observation — *"the phone is ringing right
now"* — can sit behind a backlog of low-value background observations and be
delivered to the model after it is already stale. **Queue priority** lets urgent
observations **preempt** background ones so the model reacts to what matters now.

**In scope**

- Priority levels for queued OS→Model messages and their definitions.
- The scheduling model (ordering, preemption, starvation avoidance).
- How a message's priority is assigned (source, content, policy hints).
- How priority interacts with the debounce layer and the inference dispatcher.

**Out of scope**

- Transport/delivery of OS events into the queue — see
  [`message-queue.md`](./message-queue.md).
- Coalescing/rate-limiting of raw events before they become messages — see
  [`event-debounce.md`](./event-debounce.md).
- Retry, backoff, and cancellation of inference itself — see
  [`inference-retry.md`](./inference-retry.md).
- Policy/consent gating of actions the model *takes* — see
  [`policy-engine.md`](./policy-engine.md).

This spec **extends** the base message queue
([`message-queue.md`](./message-queue.md)) with a priority dimension; it does not
replace its enqueue/dequeue contract.

## Priority levels

Four tiers, highest to lowest. (Issue #46 requires *at least three*; we define a
distinct **INTERRUPT** tier above HIGH because preemption semantics differ from
mere ordering — see [Scheduling model](#scheduling-model).)

| Level | Numeric | Intent | Preempts in-flight? | Example triggers |
|------|--------:|--------|---------------------|------------------|
| **INTERRUPT** | 0 | "Act on this now; a slower reaction is a user-visible failure." | **Yes** | Incoming call ringing; permission/consent dialog awaiting response; system alert (low battery shutdown, security prompt); wake-word / barge-in; user-initiated cancel. |
| **HIGH** | 10 | Time-sensitive but not preemptive; jump the queue, finish the current job first. | No | Foreground app state change the user is waiting on; notification the user just tapped; a deadline-bound reminder firing. |
| **NORMAL** | 20 | Default for routine observations. | No | Routine UI/screen changes; periodic context refresh; completed tool result that isn't user-blocking. |
| **BACKGROUND** | 30 | Best-effort; safe to defer, batch, or drop under load. | No | Idle sensor sampling; speculative pre-fetch; bookkeeping; low-confidence observations. |

Lower numeric value = higher priority. Numeric values are spaced (0/10/20/30) so
intermediate sub-priorities can be introduced later without renumbering.

Only **INTERRUPT** carries preemption authority. HIGH/NORMAL/BACKGROUND differ
only in *ordering*. This keeps preemption rare, auditable, and intentional.

## Scheduling model

### Structure — multi-level queues with priority dequeue

The dispatcher maintains **one sub-queue per level** (a multi-level queue),
rather than a single comparator-sorted priority queue. Rationale:

- **Per-level policy.** BACKGROUND can have a bounded ring buffer that drops
  oldest under pressure; INTERRUPT must never drop. A single heap can't express
  this cleanly.
- **Stable FIFO within a level.** Messages of equal priority preserve arrival
  order — important for causally related observations.
- **Cheap aging.** Promotion (below) is a move between sub-queues, not a re-heap.

Dequeue selects the **oldest message from the highest non-empty sub-queue**,
after applying aging promotions.

### Preemption rules

1. Only an **INTERRUPT** message preempts. HIGH/NORMAL/BACKGROUND never preempt a
   running inference; they only reorder what runs next.
2. On enqueue of an INTERRUPT message while a NORMAL/BACKGROUND inference is in
   flight, the dispatcher issues a **cancel** of that in-flight job (see the
   cancellation contract in [`inference-retry.md`](./inference-retry.md)) and
   starts the INTERRUPT message next.
   - A preempted job is **re-enqueued at the head of its own level** with a
     `preempted: true` annotation and an incremented `preemptionCount`, so it
     resumes promptly once the INTERRUPT clears — it is not lost.
3. An INTERRUPT message does **not** preempt another **INTERRUPT** already in
   flight; INTERRUPTs run to completion in FIFO order among themselves. This
   bounds preemption thrash (no INTERRUPT-vs-INTERRUPT ping-pong).
4. Preemption is **disabled** for jobs that have side effects mid-flight or are
   past a no-cancel checkpoint; such a job finishes, then the INTERRUPT runs.
   This is signaled by the dispatcher, not the queue.
5. Every preemption emits an audit event (who preempted whom, why) for the
   [`policy-engine.md`](./policy-engine.md) audit log.

### Starvation avoidance (aging)

Strict priority can starve lower tiers if higher tiers stay busy. We age by
**promotion on wait time**:

- Each message carries `enqueuedAt`. While it waits, the scheduler computes its
  effective level from age.
- When a message's wait exceeds the level's `agingThresholdMs`, it is **promoted
  one level** (e.g. BACKGROUND→NORMAL→HIGH). Promotion stops at HIGH:
  **aging never manufactures an INTERRUPT** — interrupt authority is reserved for
  genuinely urgent sources, never earned by waiting.
- On promotion, `effectivePriority` and `agedFrom` are recorded; the original
  `basePriority` is preserved for audit and for resetting on requeue.
- Suggested defaults (tunable): BACKGROUND age 30 s → NORMAL; NORMAL age 10 s →
  HIGH. INTERRUPT and HIGH do not age upward.

Aging guarantees a finite, bounded delay for every non-dropped message under
sustained higher-priority load.

### Backpressure & shedding

- **INTERRUPT / HIGH**: never dropped; the queue blocks or rejects new
  *lower*-priority enqueues before it drops these.
- **NORMAL**: bounded; oldest-of-equal-priority may be coalesced.
- **BACKGROUND**: bounded ring buffer; oldest dropped first under pressure
  (with a `dropped` audit counter). Debounce should already have thinned these —
  see [`event-debounce.md`](./event-debounce.md).

## How priority is assigned

Priority is resolved **at enqueue time** by a classifier that layers three inputs
(later layers override earlier ones):

1. **Source-based (default).** Each OS event source maps to a default level via a
   static table. Incoming-call, permission-dialog, and system-alert sources map
   to **INTERRUPT**; idle sensor/bookkeeping sources map to **BACKGROUND**. This
   table satisfies the issue's classifier requirement and is the primary signal.
2. **Content-based (refinement).** Lightweight rules inspect the event payload to
   raise or lower the source default — e.g. a notification whose payload marks it
   "silent"/low-importance is demoted from HIGH to NORMAL; a "critical" system
   alert is confirmed at INTERRUPT. Content rules **may not** invent INTERRUPT
   from a non-INTERRUPT source (guards against payload spoofing).
3. **Policy hints (override).** The [`policy-engine.md`](./policy-engine.md) may
   pin or cap a level for a source/context (e.g. cap a noisy app at BACKGROUND,
   or pin "accessibility-critical" flows to HIGH). Policy is authoritative and is
   applied last.

The resolved value is stamped as `basePriority`; the scheduler derives
`effectivePriority` from base + aging at dequeue time.

### Illustrative prioritized message

> **Illustrative only** — not a committed schema. Field names/shapes are
> placeholders pending the chosen runtime; the canonical envelope lives in
> [`message-queue.md`](./message-queue.md). This example shows the priority
> *extension fields* over that base envelope.

```jsonc
{
  // --- base envelope fields (from message-queue.md) ---
  "id": "msg_01HZX2K9QowieF",
  "kind": "os.event.observation",
  "source": "android.telephony.incoming_call",
  "payload": { "number": "+1•••••••1234", "state": "RINGING" },
  "enqueuedAt": "2026-06-25T17:04:12.881Z",

  // --- priority extension (this spec) ---
  "priority": {
    "level": "INTERRUPT",        // resolved tier label
    "numeric": 0,                // tie-break / comparator value
    "basePriority": "INTERRUPT", // pre-aging level, preserved for audit
    "effectivePriority": "INTERRUPT",
    "assignedBy": "source",      // "source" | "content" | "policy"
    "policyPinned": false,       // true if policy-engine pinned/capped it
    "preemptsInFlight": true     // derived: only true for INTERRUPT
  },

  // --- scheduler bookkeeping (set/updated by dispatcher) ---
  "scheduling": {
    "agedFrom": null,            // prior level if promoted by aging, else null
    "preempted": false,          // true if this job was itself preempted+requeued
    "preemptionCount": 0,
    "debounceKey": "incoming_call:+1•••••••1234"  // coalescing key; see event-debounce.md
  }
}
```

## Interaction with adjacent layers

### Debounce layer — upstream of the queue

[`event-debounce.md`](./event-debounce.md) coalesces and rate-limits **raw OS
events** *before* they become queued messages. Ordering and contract:

- **Debounce runs first, per-priority.** BACKGROUND/NORMAL events are debounced
  aggressively (longer windows, coalesce by `debounceKey`). INTERRUPT events are
  passed through with a **near-zero or zero debounce window** — an incoming call
  must not be delayed by a coalescing timer.
- Debounce sets the `debounceKey`; the priority classifier then stamps
  `priority`. A burst of identical BACKGROUND events collapses to one NORMAL-or-
  lower message; a single INTERRUPT event is never held back to "wait for more."
- Net effect: debounce controls *how many* messages enter the queue; this spec
  controls *which order and which preempt* once they are in.

### Inference dispatcher — downstream of the queue

The dispatcher is the single consumer; it must **respect priority ordering** (an
explicit acceptance criterion). Contract:

- Pulls via the priority dequeue (highest non-empty level, FIFO within level,
  after aging).
- On INTERRUPT enqueue, performs the preemption handshake in
  [Preemption rules](#preemption-rules): cancel in-flight (per the cancellation
  contract in [`inference-retry.md`](./inference-retry.md)), requeue the
  preempted job at its level head, dispatch the INTERRUPT.
- Retry/backoff of a failed inference is owned by
  [`inference-retry.md`](./inference-retry.md); a retried job retains its
  `basePriority` and re-enters at that level (re-aging from its retry time).
- The dispatcher, not the queue, decides whether a given in-flight job is
  preemptible (no-cancel checkpoints).

## Open questions

1. **Tier count.** Ship the full four-tier model, or collapse to three
   (INTERRUPT/NORMAL/BACKGROUND) and treat HIGH as aged-NORMAL? Four is proposed;
   issue #46 only mandates three.
2. **Aging thresholds.** Are 30 s / 10 s sane on-device defaults, or should they
   be derived from observed inference latency? Should thresholds adapt under
   load?
3. **Preemption granularity.** Hard-cancel in-flight inference, or only preempt
   at token/checkpoint boundaries to avoid wasting partial generations? Depends
   on the chosen inference runtime's cancellation support.
4. **Coalescing across preemption.** If an INTERRUPT preempts a job and a second,
   newer INTERRUPT for the *same* `debounceKey` arrives, should they merge?
5. **Spoofing.** Content-based rules can't *raise* to INTERRUPT, but can a
   compromised source *claim* an INTERRUPT-mapped source id? Trust/attestation of
   the source is deferred to [`policy-engine.md`](./policy-engine.md).
6. **Fairness vs. latency.** Does aging-to-HIGH risk INTERRUPT-like queue
   jumping for batches of aged BACKGROUND work? May need a per-window promotion
   cap.

## Acceptance checklist

Restating and mapping issue #46's acceptance criteria:

- [ ] **≥3 priority tiers.** Four tiers defined — INTERRUPT / HIGH / NORMAL /
      BACKGROUND — exceeding the three-tier minimum.
      → [Priority levels](#priority-levels)
- [ ] **INTERRUPT preempts in-flight NORMAL/BACKGROUND.** Preemption authority is
      exclusive to INTERRUPT; preempted jobs are cancelled and requeued at their
      level head, not dropped.
      → [Preemption rules](#preemption-rules)
- [ ] **OS classifier tags incoming calls, permission dialogs, and system alerts
      as INTERRUPT.** Source-based table maps these sources to INTERRUPT by
      default; content rules may not downgrade them below their pinned floor, and
      cannot be spoofed upward from a non-INTERRUPT source.
      → [How priority is assigned](#how-priority-is-assigned)
- [ ] **Priority logic covered by unit tests on mixed-priority streams.** Test
      matrix specified below.
- [ ] **Spec documents the priority model.** This document.

### Test matrix (for the eventual implementation)

- Strict ordering: interleaved enqueues across all four tiers dequeue in
  priority-then-FIFO order.
- Preemption: INTERRUPT enqueued during in-flight NORMAL cancels it, runs first,
  and the NORMAL job resumes from its level head with `preempted: true`.
- No INTERRUPT-vs-INTERRUPT preemption: two INTERRUPTs run FIFO, no thrash.
- Aging: a BACKGROUND message under sustained higher-priority load is promoted
  within bounded time and eventually dispatched (no starvation).
- Aging ceiling: aging never produces an INTERRUPT.
- Shedding: BACKGROUND ring buffer drops oldest under pressure while INTERRUPT/
  HIGH are never dropped.
- Classifier: incoming-call, permission-dialog, and system-alert sources resolve
  to INTERRUPT; policy pin/cap overrides source default; content rule cannot
  upgrade a non-INTERRUPT source to INTERRUPT.

## Cross-references

- [`message-queue.md`](./message-queue.md) — base queue contract this extends.
- [`event-debounce.md`](./event-debounce.md) — upstream coalescing/rate-limiting.
- [`inference-retry.md`](./inference-retry.md) — downstream cancellation, retry,
  backoff.
- [`policy-engine.md`](./policy-engine.md) — policy hints, source trust, audit.
