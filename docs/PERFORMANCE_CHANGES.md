# Colorful Lighting — Performance Engineering Notes

A technical breakdown of the optimization work done on the colored-light engine and its
Sodium integration. Covers commits `a15a4b3` → `3d128ca` (mod version `2.6.3hf`,
MC 1.20.1 / Forge 47).

The engine computes per-block RGB light on a background thread and feeds it into the
renderer's mesh build. There are two performance-critical surfaces:

1. **The propagator thread** (`CL-LightPropagator`) — floods colored light through newly
   loaded chunks and reacts to block updates.
2. **The sampling path** — every Sodium chunk-build worker reads the engine ~10 times per
   block face while meshing terrain. This is the hottest path in the whole mod.

Everything below is aimed at one of those two.

---

## 1. Chunk scheduling: from O(n²) drain to a cached ordering

### The problem

Chunks awaiting light propagation were held in a `ConcurrentLinkedQueue`. Two costs
compounded:

- **Removal was O(n).** `ConcurrentLinkedQueue.remove` walks the list. It ran once per
  propagated chunk.
- **Prioritization rescanned the whole collection.** To pick the chunk nearest the player
  (frustum-visible first), `getNearestWaitingChunk` iterated *every* waiting chunk, testing
  all nine neighbours for availability and allocating an `AABB` per chunk for a frustum
  test — **once for every chunk propagated**.

That is O(n) work per chunk and **O(n²) to drain the queue**. Profiling a Nether dimension
change put the two scans at **~20% of the propagator thread**.

### The fix (`a15a4b3`)

- **Queues → `ConcurrentHashMap.newKeySet()`.** Membership and removal become O(1).
  Ordering no longer comes from the collection.
- **A cached `ChunkOrder`.** The visible-first, nearest-first ordering is computed *once*
  into an `ArrayList` and consumed with a cursor. It is rebuilt only when:
  - the player crosses a chunk boundary (`rebuiltCenter` changes), or
  - a short TTL expires (`CHUNK_ORDER_TTL_NANOS = 100 ms`) so camera rotation still
    re-prioritizes.

  Priority is advisory, so a slightly stale order costs nothing but order. Crucially it is
  **not** rebuilt on cursor exhaustion — when every remaining chunk is still loading, that
  would rebuild on every poll and reinstate the O(n)-per-poll cost.

```
Before:  pick nearest = scan all N waiting chunks (9 neighbour checks + 1 AABB alloc each)
         → run once per propagated chunk → O(N²) per drain
After:   sort once into ChunkOrder, walk with a cursor → O(N log N) once, O(1) per chunk
```

---

## 2. Eliminating chunks that can *never* propagate

### The problem

`ViewArea` is a **square**, but since MC 1.18 the server only sends chunks inside a **disc**
(`ChunkMap.isChunkInRange`). Propagating a chunk requires all eight of its neighbours to be
loaded. Chunks near the square's corners — and their inward neighbours — can therefore
**never** have a full neighbourhood, so they sat in the queue for the entire session.

They kept the queue permanently non-empty and were re-sorted by every `ChunkOrder` rebuild.

> **Measured at render distance 24: 336 of the 2209 queued chunks were permanently
> unpropagatable** (~15% dead weight).

### The fix (`a9ba10a`)

- **`canEverPropagate(...)`** checks all nine neighbours against `ChunkMap.isChunkInRange`
  at queue time. Corner chunks are never enqueued. It uses the client's effective render
  distance and is conservative when the server's view distance is larger — the sliver it
  skips is culled by euclidean distance anyway, so nothing visible is lost.
- **A `queuedChunks` dedup set** replaces the old "skip anything in the previous inner area"
  rule, which could never re-queue a chunk that was skipped because its neighbours hadn't
  arrived yet.

### Adaptive backoff for the residual stall

Even after this, the queue never truly empties (the disc/square mismatch leaves a thin
residue). Rather than wake the propagator every 5 ms to walk the order and find nothing,
the idle branch backs off exponentially:

- `MIN_BLOCKED_SLEEP_MILLIS = 5 ms` → doubles → `MAX_BLOCKED_SLEEP_MILLIS = 20 ms`.
- **Block updates never back off.** A placed torch must light up immediately, so any pending
  block update resets the sleep to the minimum. 20 ms is under half a tick and removes ~¾ of
  the wasted wakeups.

---

## 3. The propagation loop: budget instead of per-chunk sleep

### The problem

The old loop did one propagation step, then `Thread.sleep(1)`, every iteration. Profiling
showed **~16% of the propagator thread sitting inside `Thread.sleep` while work was
queued.**

### The fix (`a15a4b3`) — time-budgeted passes + proportional throttle

The loop now propagates for a **budget** (`speed.budgetNanos()`) before considering a pause,
and stops early the moment a pass does no work (queue is blocked on chunk loads):

```java
long deadline = System.nanoTime() + speed.budgetNanos();
do {
    progressedThisPass  = propagateLight() | propagateDarkness();
    ...
} while (running && progressedThisPass && hasWork && System.nanoTime() < deadline);
```

`propagateLight()` / `propagateDarkness()` were changed to **return a boolean** — `true`
only when they actually moved light — so the loop can distinguish "throttled" from "blocked."

### Why the pause is proportional, not fixed (`lightUpdateSpeed` config)

Every finished chunk makes the renderer rebuild and re-upload that chunk's mesh. Filling
light in faster concentrates those uploads, which is what stutters on slower machines. So
the engine pauses between passes — but the pause is a **multiple of the work just done**,
not a fixed millisecond count.

The reason: a pass can't stop mid-chunk, and **one Nether chunk dwarfs any sane fixed
budget.** A fixed 8 ms pause after a 15 ms chunk barely throttles anything — measurements
showed a "gentle" fixed pause moving the duty cycle only from 67% → 60%. Pausing for
`work × pauseFactor` yields a duty cycle of `1 / (1 + pauseFactor)` regardless of how
expensive a chunk turns out to be.

| Setting    | `budgetMillis` | `pauseFactor` | Duty cycle |
|------------|---------------:|--------------:|-----------:|
| `FASTEST`  | 8              | 0.0 (yields)  | 100%       |
| `FAST`     | 8              | 0.25          | ~80%       |
| `BALANCED` | 8              | 1.0           | ~50%       |
| `GENTLE`   | 4              | 3.0           | ~25%       |

The config is re-read every pass, so it takes effect without a restart. `FASTEST` yields
instead of sleeping (no OS scheduler round-trip).

---

## 4. Lock-free sampling — the hottest path

### The problem

`sampleLightColor` took a global `storageLock` (a plain monitor) on **every sample**. With
~10 samples per block face across 10 Sodium chunk-build workers, that monitor **serialized
all ten workers** behind a single lock. It also:

- did two `ConcurrentHashMap` lookups (light + darkness) per sample, and
- allocated `ColorRGB4` objects for the result and both operands.

### The fix (`a9ba10a`, extended in `3d128ca`)

**Drop the lock on the read path entirely.** The storages are concurrent maps of
volatile-published sections. Readers race with the propagator *by design*: the worst case is
one frame of slightly-wrong color on a block that is already queued for a re-mesh.

- **`ColoredLightSection.data` is now `volatile byte[]`.** The array reference is safely
  published; the byte writes inside it are intentionally racy. `set()` re-publishes with a
  release store (`this.data = d`) so a reader that sees the new reference also sees the
  bytes.
- **`structureVersion` (`AtomicInteger`)** is bumped whenever sections are added/removed
  from either storage. It lets readers cache a section safely and discard the cache the
  instant the structure changes.

**Per-thread section memo (`SectionCursor`).** Sodium walks a chunk build in section order,
and nearly all of a face's ~10 samples land in the *same* section. A `ThreadLocal`
`SectionCursor` caches the last section's light + darkness handles keyed by
`(sectionPos, structureVersion)`:

> This turns **two concurrent-map lookups per sample** into **two per section**.

**Allocation-free packed sampling.** `sampleLightColorPacked` returns
`light − darkness` as a packed 12-bit int (`r<<8 | g<<4 | b`) — no `ColorRGB4` objects, with
early-outs for the common all-black / darkness-free / fully-absorbed cases:

```java
if (light == 0 || light == darkness) return 0;
if (darkness == 0) return light;
```

`ColoredLightSection.getPacked()` reads directly out of the 12-bits-per-block bit layout
into an int, so the whole sample path is allocation-free end to end.

### One ThreadLocal lookup per face, not per sample (`3d128ca`)

`acquireCursor()` exposes the `SectionCursor` so a caller taking a run of nearby samples
pays **one** `ThreadLocal.get()` instead of one per sample. The Sodium mixin grabs it once
at the top of `computeCornerLight` and threads it through all ~10 lookups.

---

## 5. Sodium mixin: killing per-corner allocation

`SodiumAoFaceDataMixin` blends light across the four corners of every quad. It originally
called `SodiumPackedLightData.unpackData(...)`, which **allocates an object per corner**, on
one of the hottest loops in terrain meshing.

### The fix (`00d6083`)

- **Static field accessors** — `unpackRed/Green/Blue/SkyLight/Alpha(int)` — read a channel
  straight out of the packed int with no allocation.
- The four-corner blend now works entirely in primitive locals.
- The "dimmest lit corner" fallback (used so an unlit corner doesn't drag the blend toward
  black) was a nullable `SodiumPackedLightData minData` object; it's now three `int`s
  (`minR/minG/minB`), reproducing the old `minData == null` behaviour by staying zero.

Net: the per-quad blend and the final `bl/gl/bll/sl` unpack loop went from **~5 object
allocations per quad to zero.**

---

## 6. Dirty-section bookkeeping (`3d128ca`)

Sections whose color changed must be marked dirty so the renderer re-meshes them. Flying
through fresh terrain makes this thousands of iterations per frame.

- **`LongOpenHashSet` (fastutil) instead of `HashSet<Long>`.** The set fills and drains fast
  enough that **boxing a `Long` per section showed up on the render thread.** The drain uses
  `toLongArray()` rather than copying into a new `HashSet`.
- **Hoisted per-loop overhead out of the section loop.** `ModList.isLoaded("flywheel")`
  hashes a string every call, and the Sodium-renderer `instanceof` check was inside the
  loop. Both are now resolved **once** before the loop, not once per dirty section.
- **Deduped section marking (`markReady`).** Propagation relaxes the same block many times,
  so a block reaches the ready batch repeatedly. Only its *first* arrival marks the
  surrounding sections (`SectionPos.aroundAndAtBlockPos`); every later arrival would just
  recompute marks the batch already holds.
- **Decoupled marking from `storageLock`.** Dirty-section marks are now computed on the
  propagator thread when a batch is *published* (`publishDirtySections`), after the storage
  writes they refer to — not on the render thread when the batch is applied. Ordering
  guarantees that the renderer never rebuilds a section before its colors have landed.

---

## Summary of hot-path wins

| Area | Before | After |
|------|--------|-------|
| Chunk pick | O(N) scan per chunk → O(N²) drain, ~20% of thread | Cached `ChunkOrder`, O(1) per chunk |
| Dead chunks | 336 / 2209 (RD24) permanently queued | Never enqueued (`canEverPropagate`) |
| Propagator idle | `sleep(1)` per step, ~16% in `Thread.sleep` | Time-budgeted passes + adaptive backoff |
| Throttling | Fixed pause (67%→60% at best) | Proportional pause, exact duty cycle |
| Sample lock | Global monitor per sample, 10 workers serialized | Lock-free; volatile publish + version cache |
| Sample lookups | 2 concurrent-map lookups per sample | 2 per **section** (ThreadLocal cursor) |
| Sample alloc | `ColorRGB4` per sample + operands | Zero (packed-int path) |
| Sodium blend | ~5 object allocs per quad | Zero (field accessors) |
| Dirty sections | `HashSet<Long>` boxing, per-loop `isLoaded`/`instanceof` | `LongOpenHashSet`, hoisted checks, deduped marks |

### Correctness notes

The sampling path is deliberately racy: readers may observe a half-applied color write. This
is safe because (a) the affected section is always marked dirty and re-meshed, and (b) the
worst-case visible artifact is one frame of slightly-off color on one block. Trading that
for lock-free reads is what unblocked all ten Sodium chunk-build workers.

The tunable knob is `lightUpdateSpeed` in the config; the log line
`Colored light engine reset (lightUpdateSpeed=...)` reports the value actually in force
(Forge silently corrects invalid values, so the file on disk isn't authoritative).
