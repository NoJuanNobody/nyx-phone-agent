# Event Debounce & Deduplication

Spec for suppressing redundant OS observations before model dispatch.
Resolves issue **#45** ("Add debounce / deduplication logic for redundant OS
observations before model dispatch").

> **Status:** specification only. No runtime/language is committed for this repo
> yet (see [CLAUDE.md](../../CLAUDE.md)); this document defines responsibility,
> contracts, tunables, and an acceptance checklist. All code/JSON below is
> **illustrative**, not normative.

---

## 1. Purpose & scope

The Perception / Screen-Reading subsystem emits a stream of **OS observations** —
each a snapshot of phone state (screen pixels, accessibility tree, active app,
notifications, focused element). The on-device LLM is expensive (battery,
latency, thermal), so every observation that reaches inference must justify its
cost.

In practice the observation stream is highly redundant:

- **Repeated identical state** — a loading spinner re-emits the "same" screen
  many times per second; a static screen the user is reading emits nothing new.
- **Rapid transitions that settle** — opening an app produces a burst of
  intermediate frames (splash → skeleton → loaded) where only the settled frame
  matters.
- **Chatty notification updates** — a download/progress notification updates
  "12% → 13% → 14% …", or a chat app coalesces "3 new messages" into "5 new
  messages" within a second.

**This stage's job:** sit between the perception producers and the model
dispatch path and drop observations that would not change what the model would
decide, while still forwarding the *settled, novel* observation promptly.

**In scope**

- Debounce (time-window coalescing) of rapid bursts of observations.
- Deduplication of content-identical or semantically-equivalent observations.
- A deduplication metric counter.
- A pure, model-free decision function that is independently unit-testable.

**Out of scope**

- Deciding *which* model to call or building the prompt (downstream).
- Priority ordering of surviving observations — that is the priority layer
  (`queue-priority.md`).
- Queue transport / backpressure mechanics — see `message-queue.md`.
- Long-term semantic memory of past screens (a separate concern).

---

## 2. Where it runs

Debounce/dedup is a **filter stage** that runs *after* observations are produced
and *before* they are committed to the model-dispatch queue. It is intentionally
placed **upstream of the priority layer** so the priority layer never wastes
slots ranking duplicates.

```
 Perception producers                        Debounce / Dedup
 (screen capture,           raw obs           (THIS SPEC)            survivors
  a11y-tree diff,        ───────────────►  ┌──────────────────┐  ───────────────►
  notification listener)                   │ debounce window  │
                                           │ + dedup cache    │
                                           └──────────────────┘
                                                    │ dropped (counted)
                                                    ▼
                                              metrics counter

  survivors ──►  message queue  ──►  priority layer  ──►  model dispatch
                (message-queue.md)   (queue-priority.md)
```

Cross-links:

- Queue transport / enqueue contract: [`message-queue.md`](./message-queue.md)
- Priority ordering of survivors: [`queue-priority.md`](./queue-priority.md)

Rationale for ordering: dedup is cheap and produces a smaller, cleaner set; doing
it first means the queue holds fewer items and the priority layer ranks only
observations that are genuinely novel. The filter MUST be **non-blocking** for
the perception thread — it makes a synchronous keep/drop decision per
observation and never calls the model.

---

## 3. Strategies

The stage combines two orthogonal mechanisms. Both run on the same observation;
an observation is dispatched only if it survives both.

### 3.1 Debounce (time-window coalescing)

Collapse a *burst* of observations that share a **coalescing key** (typically
the source surface, e.g. `app + screen` or a notification key) into a single
dispatch.

| Variant | Behavior | When to use |
|---|---|---|
| **Trailing edge** (default) | Hold observations for `debounce_ms`; on each new same-key observation, reset the timer; dispatch the **last** one when the window goes quiet. | UI that settles (app launch, progress bars). Captures the *final* state. |
| **Leading edge** | Dispatch the **first** observation immediately, then suppress same-key observations for `debounce_ms`. | Latency-sensitive surfaces where the first signal matters (incoming call banner). |
| **Leading + trailing** | Dispatch first immediately *and* the settled last one if it differs. | Surfaces where both onset and final state are meaningful. |

Debounce is about **timing** (when to flush). It does not by itself know whether
two observations are equal — that is what dedup decides.

### 3.2 Deduplication

Decide whether two observations are "the same" for dispatch purposes.

| Strategy | Key | Notes |
|---|---|---|
| **Content hash** | Stable hash of canonicalized observation payload (a11y tree text + structural shape; screenshot via perceptual/aHash, *not* a raw byte hash). | Exact-duplicate detection. Perceptual hash tolerates 1-px / anti-alias jitter; raw pixel hash would never match. |
| **Semantic key** | Tuple of meaningful fields, e.g. `(app, screen_id, focused_element, salient_text)`. | Treats observations as equal if they'd lead to the same decision even when pixels differ slightly. |
| **Last-seen cache + TTL** | Per coalescing key, remember the last dispatched dedup-key with a timestamp; drop matches inside `dedup_ttl_ms`. | Bounds memory; lets a state re-appear after the TTL (so a genuinely re-shown screen is eventually re-dispatched). |

**Combined decision (illustrative):**

```text
keep(obs):
  ckey  = coalescing_key(obs)            # e.g. app+screen, or notification key
  dkey  = dedup_key(obs)                 # content-hash and/or semantic tuple
  prev  = cache.get(ckey)

  if prev and prev.dkey == dkey and (now - prev.ts) < dedup_ttl_ms:
      metrics.deduplicated += 1
      return DROP                        # exact/near duplicate within TTL

  # not a duplicate → run debounce timing
  schedule_or_reset_debounce(ckey, obs)  # trailing edge: hold for debounce_ms
  return HELD                            # dispatched when window goes quiet
```

The keep/drop function is a **pure function** of `(obs, cache state, clock)`.
The clock is injected, so tests advance a fake clock and assert keep/drop/flush
without any model or device — satisfying issue #45's testability criterion.

---

## 4. Worked example — rapid notification updates

A download notification updates its progress percentage rapidly, then the
download completes. Without dedup/debounce each update is a separate model call.

Coalescing key = the notification key `notif:dl-42`.
Strategy = **trailing-edge debounce**, `debounce_ms = 500`, plus dedup so the
identical "complete" state isn't re-sent.

```
 time (ms) →   0    120   240   360   480   600   720   ...   1100
 producer:     │     │     │     │     │     │     │            │
 obs:         12%   14%   37%   58%   83%  100%  100%(dup)     100%(dup)
               │     │     │     │     │     │     │            │
 debounce      └──── timer keeps resetting on each new obs ────┘
 window:       (each obs within 500ms of the previous resets the trailing timer)

 dedup:                                       ▲              ▲
                                              └ same dkey ───┴ DROPPED (counted)
                                                as 100% obs

 quiet for 500ms after the last *novel* obs (100% @600) →
 FLUSH at 1100ms ─────────────────────────────────────────────► dispatch "100%"

 Result:  7 raw observations  →  1 model dispatch  ("download complete, 100%")
          dedup counter += 2   (the two redundant 100% updates)
```

Key points illustrated:

- The intermediate percentages never reach the model; only the **settled** state
  does (trailing edge).
- Once the value stops changing, dedup drops the repeats and increments the
  counter (acceptance criterion: a counter tracks deduplicated observations).
- A latency-sensitive surface (incoming call) would instead use **leading edge**
  so the first banner dispatches at t=0 rather than waiting 500ms.

---

## 5. Dedup key schema (illustrative)

> **Illustrative only** — field names and hash choices are examples, not a
> frozen contract. The normative requirement is: a stable, model-free key
> derived from canonicalized observation content.

```jsonc
// ILLUSTRATIVE — not a normative schema
{
  "coalescing_key": "notif:dl-42",        // groups a burst; resets debounce timer
  "dedup_key": {
    "app": "com.android.chrome",
    "screen_id": "download_manager",
    "focused_element": null,
    "salient_text_hash": "sha256:9f2c…",  // hash of canonicalized a11y text
    "screen_phash": "ahash:0x1f3c8a…",    // perceptual hash, tolerant to jitter
    "semantic_tuple": ["download", "complete", "100%"]
  },
  "observed_at": "2026-06-25T12:00:00.600Z",
  "ttl_ms": 2000                          // dedup_ttl_ms for this key
}
```

Canonicalization rules (so equal states produce equal keys):

- Strip volatile fields before hashing: timestamps, monotonic counters, cursor
  blink state, battery/clock chrome, scroll pixel offset (bucketed instead).
- Normalize whitespace and ordering in extracted a11y text.
- Use a **perceptual** image hash (aHash/pHash) with a Hamming-distance
  threshold, never a raw byte/MD5 of the framebuffer.

---

## 6. Tunable parameters & defaults

| Parameter | Default | Meaning |
|---|---|---|
| `debounce_ms` | **500** | Trailing-edge quiet period before a held burst flushes (matches issue #45's 500 ms window). |
| `debounce_edge` | `trailing` | `leading` \| `trailing` \| `both`. |
| `dedup_ttl_ms` | **2000** | How long a last-seen dedup key suppresses re-dispatch. |
| `phash_hamming_max` | **4** | Max Hamming distance for two perceptual hashes to count as duplicate. |
| `coalescing_key_fn` | `app+screen` | How a burst is grouped; notifications group by notification key. |
| `max_hold_ms` | **2000** | Hard cap: a continuously-changing surface still flushes at least this often, so trailing-edge never starves dispatch. |
| `cache_max_entries` | **256** | LRU bound on the last-seen cache (memory safety). |

Per-surface overrides are expected (e.g. incoming-call surface →
`debounce_edge=leading`, `debounce_ms=0`; progress notifications →
`debounce_ms=750`).

---

## 7. False-suppression risks & mitigations

Dropping observations risks hiding state the agent *should* react to.

- **Settling-mask:** trailing edge delays dispatch by up to `debounce_ms`.
  *Mitigation:* small default (500 ms); `leading` edge for latency-critical
  surfaces.
- **Starvation on always-changing screens** (video, animation): trailing timer
  could reset forever. *Mitigation:* `max_hold_ms` forces a periodic flush.
- **Perceptual-hash collision:** two *meaningfully different* screens hash close
  (e.g. two similar list rows). *Mitigation:* combine phash with the semantic
  tuple/a11y-text hash; a match requires agreement on both.
- **Over-aggressive TTL:** a screen genuinely re-shown within `dedup_ttl_ms`
  (user navigates away and back) is wrongly dropped. *Mitigation:* keep TTL
  short; reset cache entry on an intervening *different* observation for the same
  key.
- **Volatile-field leakage:** forgetting to strip a clock/counter makes every
  observation "novel," defeating dedup. *Mitigation:* explicit canonicalization
  allowlist; a test that feeds two frames differing only in chrome and asserts a
  drop.

A deliberate bias: when uncertain, **prefer forwarding** (a redundant model call
is cheaper than a missed actionable event). Defaults are tuned so suppression
only happens on high-confidence duplicates.

---

## 8. Observability

- `observations_deduplicated_total` — counter; incremented on every DROP
  (required by issue #45).
- `observations_debounced_total` — counter; observations absorbed into a
  trailing window that did not become the flushed one.
- `observations_dispatched_total` — counter; survivors forwarded to the queue.
- `debounce_flush_latency_ms` — histogram; time from first held obs to flush.

Suppression ratio = `deduplicated + debounced` / total observations is the
headline efficiency metric.

---

## 9. Open questions

1. **Coalescing-key granularity** — is `app+screen` the right default, or should
   it be `app+screen+focused_element`? Too coarse merges unrelated changes; too
   fine defeats coalescing.
2. **Perceptual vs. a11y-only hashing** — on accessibility-rich apps the a11y
   tree may be sufficient and far cheaper than image hashing. Should screenshot
   hashing be opt-in per surface?
3. **Adaptive windows** — should `debounce_ms` adapt to recent dispatch rate /
   thermal state rather than being a fixed constant?
4. **Cross-key dedup** — should an identical state that appears under two
   different coalescing keys be deduplicated, or kept separate?
5. **Interaction with priority** — when a high-priority observation arrives
   mid-window, should debounce flush immediately (bypass trailing edge)? Likely
   yes; needs the priority layer's signal — see `queue-priority.md`.
6. **Placement of the metric** — exposed via the same telemetry channel as the
   Policy Engine audit log, or a separate metrics sink?

---

## 10. Acceptance checklist

Restating issue #45's acceptance criteria:

- [ ] Observations are **hashed** before enqueuing — perceptual hash of the
      screen plus accessibility-tree diff (see §3.2, §5).
- [ ] **Duplicate observations within a configurable time window** (default
      **500 ms**, `debounce_ms`) are dropped (see §3.1, §6).
- [ ] A **counter metric** (`observations_deduplicated_total`) tracks how many
      observations were deduplicated (see §8).
- [ ] Deduplication logic is **independently unit-testable without a live model
      call** — the keep/drop function is pure over `(obs, cache, injected clock)`
      (see §3.2).

Additional spec-level criteria:

- [ ] Runs upstream of the priority layer and downstream of perception
      producers (see §2).
- [ ] Cross-links to [`message-queue.md`](./message-queue.md) and
      [`queue-priority.md`](./queue-priority.md).
- [ ] All tunables have documented defaults and per-surface override points
      (see §6).
- [ ] False-suppression risks enumerated with mitigations (see §7).
