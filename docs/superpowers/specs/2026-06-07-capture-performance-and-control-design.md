# Capture Performance & Control Overhaul — Design

**Status:** Approved design (brainstorm).
**Parent spec:** `docs/superpowers/specs/2026-06-04-stack-stack-stack-photography-design.md` (the "bible").
**Related plans:** `docs/superpowers/plans/2026-06-05-phase1-continuous-burst.md`, `docs/superpowers/plans/2026-06-05-phase1-long-exposure.md`, `docs/superpowers/plans/2026-06-05-phase1-pro-controls.md`.

## 1. Goal

Make capture fast, bounded in memory, and controllable. Today, long-exposure shots can hold every
developed + aligned frame in memory at once (peaking ~3 GB → the OS jetsam-kills the app) and take
minutes to process with no way to stop, and the burst length/timing are fixed per look. This work:

1. **Caps capture at 12 MP explicitly** (both paths).
2. **Makes the long-exposure burst user-controllable** — Photos (2–20, default 10) and Duration (1–60 s) as edge sliders.
3. **Streams the long-exposure stack** so peak memory is bounded (~1–2 frames) regardless of frame count.
4. **Makes processing cancellable** — a Cancel button stops and discards the in-flight stack, freeing the UI immediately.
5. **Adds a handheld-steadiness guide that gates the long-exposure burst** so frames aren't shot while the phone is badly off-pose.

iOS first; the engine changes stay pure-Swift, deterministic, and golden-tested (Android-portable).

## 2. Decisions (locked in brainstorm)

| Decision | Choice | Rationale |
|---|---|---|
| Path partition | Two paths: **static looks** (Detail/Night) keep the existing in-memory pipeline + fixed fast burst; **long-exposure looks** (Smooth/Trails) get the new streaming stack + user burst controls + steadiness guide/gating | Confines the risky engine rework + new UI to exactly the looks that need them; small static bursts already work and stay untouched. |
| Memory strategy | **Streaming/incremental stack** for long-exposure looks (fold one frame at a time, discard it) | Peak memory bound by ~1–2 frames + accumulators instead of all-N; kills the ~3 GB jetsam crash. |
| Alignment anchor (streaming) | **First frame** is the anchor | Streaming can't hold all frames to pick the *sharpest*; for handheld long exposures the anchor choice matters far less than for noise reduction, and the steadiness gate keeps all frames near one pose. Static looks keep sharpest-anchor selection. |
| Burst controls | **Long-exposure looks only**: Photos (2–20, default 10) + Duration (1–60 s) as **vertical edge sliders** with a live numeric readout | Matches the user's mental model; the controls exist only where variable burst length/timing is meaningful. |
| Frame cap | **Global hard cap of 20** (Pro frames override max drops 40 → 20) | A single concrete bound that prevents the worst memory blowups even before streaming. |
| Cancellation | **Cancel discards** the in-flight stack (no save, no error); shutter stays locked during processing (no shoot-while-processing) | Capturing during all-core processing crashes the still pipeline (FigCapture -12773); cancel is the escape hatch instead of waiting out a long stack. |
| Steadiness source | **CoreMotion `deviceMotion` attitude** (not raw integrated gyro) | Attitude/gravity is drift-free; raw-gyro integration drifts over a 60 s window. |
| Steadiness behavior | **Gate the burst**: don't fire a frame (incl. the first) while off-pose; reschedule until steady or a per-frame gate timeout | Protects long exposures from frames shot mid-shake; the duration window gives room to wait. |
| 12 MP cap | Set `settings.maxPhotoDimensions` (clamped to the device max), ProRAW stays off, prioritization stays `.speed` | Makes the ~12 MP binned-Bayer readout explicit instead of relying on the implicit default. |

## 3. The two-path partition

| | **Static looks** — Detail (`.noiseReduction`), Night (`.lowLightBoost`) | **Long-exposure looks** — Smooth (`.smoothMotion`), Trails (`.lightTrails`) |
|---|---|---|
| Burst | Fixed fast burst, unchanged (8 / 12 frames) | **User Photos (2–20, default 10) + Duration (1–60 s)** |
| Stacking | Existing in-memory `Pipeline.reduceImages` (sigma-clip / boosted mean), sharpest anchor | **New streaming stack**, first-frame anchor |
| Steadiness guide + gating | Off | **On** |

Applies to **both** paths: the explicit 12 MP cap (§4), the Cancel button + cancellation plumbing
(§7), and turning the diagnostic frame-dump off (§7).

## 4. 12 MP capture cap (both paths)

In `AVCaptureService`, when building each `AVCapturePhotoSettings` in `startNextFrameLocked`:

```swift
settings.maxPhotoDimensions = CMVideoDimensions(width: 4032, height: 3024)
```

- Clamp to the device's `activeFormat.supportedMaxPhotoDimensions` so a device whose max wide-angle
  readout differs doesn't raise an exception; pick the largest supported dimension that does not
  exceed 4032×3024 (fall back to the format max if all options are smaller).
- Keep `photoQualityPrioritization = .speed` (we develop the RAW ourselves) and ProRAW off
  (`isAppleProRAWEnabled = false`, already set) — together these keep the classic ~12 MP binned
  Bayer readout, not the 48 MP quad-Bayer path.

## 5. Configurable burst (long-exposure looks only)

### 5.1 Model
- New `BurstSettings` value (app side): `photoCount: Int` (clamped 2…20), `durationSeconds: Double`
  (clamped 1…60). Held on `StackCaptureCoordinator` (`@Published`), per the long-exposure looks.
- For Smooth/Trails the coordinator builds the `CaptureRecipe` from `BurstSettings` instead of the
  hardcoded per-look `frameCount`/`durationSeconds`. Defaults seed `photoCount = 10`,
  `durationSeconds = 2` (Smooth) / `3` (Trails).
- Static looks keep `CaptureRecipe.recipe(for:)` unchanged.
- The Pro "Frames" override is **replaced** by the edge sliders for long-exposure looks; for static
  looks the Pro frames override remains but its max drops **40 → 20** (the global cap, §2).
- Pacing reuses the existing model: `pacing = max(durationSeconds / (photoCount − 1), 0.05)`.

### 5.2 UI (`CaptureView`)
- Two **vertical sliders pinned to the left and right edges** of the viewfinder, shown **only when a
  long-exposure look is selected**:
  - **Left = Photos** (range 2…20, integer steps), live integer readout that updates while dragging.
  - **Right = Duration** (range 1…60 s), live readout (e.g. `18s`) that updates while dragging.
- Hidden for static looks. Both are disabled while `isBusy` (consistent with the other controls).
- Layout sketch:

```
┌─────────────────────────────┐
│ Photos                  Dur. │
│  ▲                        ▲  │
│ ┌┴┐  live: 10       18s   ┌┴┐ │   numeric readout updates while dragging
│ │●│                      │●│ │
│ └┬┘    (viewfinder)      └┬┘ │
│  ▼                        ▼  │
│        [ look picker ]       │
│         (  shutter  )        │
└─────────────────────────────┘
```

## 6. Streaming stack engine (long-exposure looks)

New engine entry point in `StackEngineCore/Pipeline.swift` alongside `reduceImages`, processing one
frame at a time and discarding it instead of materializing all developed + all aligned frames.

### 6.1 Algorithm
```
reference = develop+downscale(frame[0])        // kept for the whole run; the alignment anchor
init accumulators sized to the reference
fold reference into accumulators
for i in 1 ..< n:
    if shouldCancel() { throw CancellationError() }
    dev   = develop+downscale(frame[i])         // ~one frame's memory
    moved = AffineAligner.warp(dev, to: reference)   // estimate on the 720 px copy, warp at working res
    fold moved into accumulators
    release dev, moved, and raw frame[i]
finalize accumulators → PixelImage
```

- **Develop** uses the binned half-res develop (`ColorPipeline.processBinned`) then downscales to the
  managed working resolution — same as today's `developedFrames`, but per frame instead of all-at-once.
- **Alignment** reuses `AffineAligner`: estimate the similarity transform on the 720 px (`alignmentEstimateEdge`)
  copies of `reference` and `dev`, scale the translation by `factor`, warp `dev` at working resolution.
  Anchor is the **first** frame (not the sharpest).
- Raw buffers are freed as consumed (process raws in order, dropping each after develop), so raw
  memory shrinks across the run rather than being held in full.

### 6.2 Per-pixel accumulators (confirmed feasible from the existing reducers)
- **Smooth (`.smoothMotion`)** = streaming `mean`: running `sum: [SIMD3<Float>]` + integer `count`;
  finalize `sum / count`. (Equivalent to `StackReducer.mean`.)
- **Trails (`.lightTrails`)** = streaming motion composite, maintaining simultaneously:
  - `sum` + `count` → the clean **base** (`mean`),
  - per-channel running `max: [SIMD3<Float>]` → the **streaks** (`lighten`),
  - per-pixel running luma `min`/`max` → the motion mask's **temporal luma range**.
  - Finalize: build the motion mask from the range (`(max−min)` → smoothstep with `trailsMotionLo/Hi`,
    then the existing box blur at `smoothRadius`), then `MotionComposite.blend(base, streaks, mask)`.
    The box blur runs once at finalize (a spatial op on the final range/mask buffer).

The static-look reducers (`sigmaClippedMean`, `boostedMean`) are **not** streamed (sigma-clipping is
inherently two-pass; static bursts are small, so memory isn't a concern there).

### 6.3 Memory
For a 20-frame Smooth/Trails shot at the managed working resolution (~2016×1512 binned, ~37 MB as
`SIMD3<Float>`): peak drops from ~2 GB (20 developed + 20 aligned in flight) to **under ~700 MB**
(reference + one in-flight developed + one in-flight aligned + a small fixed set of accumulator
buffers), shrinking further as raw buffers are released. Bounded by frame *size*, not frame *count*.

### 6.4 Engine purity
The cancellation hook is a plain `shouldCancel: () -> Bool` closure parameter (default `{ false }`),
checked **between frames**. No platform frameworks enter the engine; the closure is trivially
golden-testable (a counter-based closure).

## 7. Cancellation, Cancel button, and coordinator (both paths)

### 7.1 Engine
`reduceStreaming` (and any long-running entry point) takes `shouldCancel`; on a true return between
frames it throws `CancellationError()` (Swift stdlib) — no partial result is produced.

### 7.2 Coordinator (`StackCaptureCoordinator`)
- The heavy work runs in `Task.detached`, which does **not** inherit Swift-concurrency cancellation,
  so use an explicit per-job **cancellation token** (a small thread-safe box with an atomic/locked
  `Bool`). Pass `{ token.isCancelled }` into the engine as `shouldCancel`.
- `cancelProcessing()` (MainActor): set the token (and cancel the task handle). The job catches
  `CancellationError`, **discards all partial work — no `store.save`, no `lastError` message** — and
  settles `processingCount` so `isBusy` clears and the shutter re-enables immediately.
- Shutter stays locked while `processingCount > 0` (unchanged): no shoot-while-processing.
- **Turn off `dumpFramesForDiagnostics`** (set the DEBUG flag to `false`) — it re-encodes every
  developed frame to JPEG and writes to disk on every shot; pure overhead in normal use.

### 7.3 UI (`CaptureView`)
- A **Cancel** control in the status area, visible only while `processingCount > 0`, wired to
  `coordinator.cancelProcessing()`. After cancel the status returns to "Ready" (no error shown).

## 8. Steadiness guide + burst gating (long-exposure looks)

### 8.1 Motion (`MotionSteadiness`, app side)
- Uses `CMMotionManager.deviceMotion` (attitude/gravity). On burst start it snapshots the **reference
  attitude** (the "glued" big circle). Per update it computes the angular delta (pitch/roll) from the
  reference, mapped to a 2-D `offset` and an `isSteady` flag (offset within an angular tolerance).
- The latest sample is published to the UI on the MainActor **and** exposed as a thread-safe snapshot
  the capture's `stateQueue` can read for gating (atomic/locked).

### 8.2 UI overlay (`CaptureView`)
- Shown during long-exposure capture: a fixed large ring centered on screen + a smaller filled circle
  that moves with device tilt (the `offset`). Tint conveys state (e.g. green when inside / red when
  outside) so the user self-corrects.

### 8.3 Gating (`AVCaptureService.startNextFrameLocked`)
- Before firing each frame (including the **first** — "refuse to start until centered"), consult an
  `isSteady` check (provided by the coordinator, reading the motion snapshot). If unsteady,
  **reschedule the check on a short delay rather than consuming a frame** (reuse the existing
  `stateQueue.asyncAfter` pattern), up to a **per-frame gate timeout** so a hopelessly shaky shot
  still terminates and the burst ends with the frames gathered so far.
- Gating applies to the long-exposure looks only; static fast bursts fire as today.
- The `isSteady` gate is wired into `captureBurst` as an optional closure on the recipe/call so the
  capture state machine stays self-contained and the Fake/Simulator path (no CoreMotion) defaults to
  "always steady".

## 9. Data flow (long-exposure look)

```
shutter (Smooth/Trails)
  → build CaptureRecipe from BurstSettings (photoCount ≤ 20, duration 1–60 s)
  → MotionSteadiness.start() → snapshot reference attitude
  → captureBurst: for each frame, gate on isSteady (reschedule if off-pose; per-frame timeout) → RAW frame
  → enqueueProcessing (serial, off MainActor, with a cancellation token)
      → reduceStreaming(shouldCancel: { token.isCancelled }):
           reference = develop+downscale(frame[0])
           for each later raw: develop+downscale → align→reference → fold into accumulators → release
           finalize → PixelImage
      → OutputTransform.encodeSRGB8 → ImageEncoder JPEG → LibraryStore.save
  (Cancel any time during processing → token set → CancellationError → discard, free UI)
```

## 10. Error handling & edge cases

- **Cancel mid-stack** → `CancellationError` caught; no save, no error message; `processingCount`
  settles; shutter re-enables.
- **Steadiness gate timeout** (user never holds still) → that frame is skipped; the burst still
  terminates and stacks whatever was captured (≥ 1 frame).
- **No CoreMotion** (Simulator/Fake, or motion unavailable) → gate defaults to steady; the overlay is
  hidden or static; capture proceeds.
- **Single captured frame** in the streaming path → return the reference (no folding).
- **Device max dimension ≠ 4032×3024** → clamp `maxPhotoDimensions` to the supported max (§4).
- **Burst values out of range** → clamped to 2…20 / 1…60 at the `BurstSettings` boundary.
- **Static looks** are unaffected by §5/§6/§8 and keep their current behavior.

## 11. Testing strategy

**Engine (golden/unit, deterministic, CPU):**
- **Streaming ≈ batch parity:** `reduceStreaming` for `.smoothMotion` and `.lightTrails` produces a
  result equal (within float tolerance) to a **batch reference that uses the same first-frame anchor**
  (align all frames to frame 0, then apply the same reducer) — the key correctness guarantee. Note
  this is *not* production `reduceImages`, which picks the sharpest anchor; the test uses a helper (or
  pre-aligned frames) so the only variable under test is streaming-vs-batch accumulation, not the
  anchor choice.
- **Cancellation:** a `shouldCancel` closure that returns true after *k* frames → `reduceStreaming`
  throws `CancellationError` and produces no result.
- **Accumulator math:** streaming mean / lighten / luma-range against direct computation on a small
  synthetic burst.

**App (Simulator / unit):**
- `BurstSettings` clamping (2…20, 1…60) and recipe construction per look.
- Coordinator: `cancelProcessing()` discards without saving and clears `isBusy`; `processingCount`
  bookkeeping; static vs long-exposure recipe routing.
- `MotionSteadiness` math: attitude delta → offset and `isSteady` threshold (pure unit test with
  injected attitude samples).
- UI: long-exposure look shows the two edge sliders + steadiness overlay; static look hides them;
  Cancel appears only while processing.

**Device-only (compile-verified / flagged):** real `maxPhotoDimensions` capture at 12 MP; CoreMotion
attitude + live gating timing; capture during real handheld motion.

## 12. Phased build (sequential TDD → /code-review → merge cycles)

1. **12 MP cap + frame-cap + diag-off** — `maxPhotoDimensions` (clamped), Pro frames max 40→20,
   `dumpFramesForDiagnostics = false`. Smallest, immediate safety win.
2. **Streaming stack engine** — `reduceStreaming` with first-frame anchor + streaming mean / motion
   composite + `shouldCancel`; golden parity + cancellation tests. Route Smooth/Trails to it.
3. **Cancellation plumbing + Cancel button** — cancellation token in the coordinator,
   `cancelProcessing()`, Cancel UI; discard-without-save tests.
4. **Configurable burst + edge sliders** — `BurstSettings`, recipe construction, the two vertical
   sliders with live readouts (long-exposure looks only).
5. **Steadiness guide + gating** — `MotionSteadiness` (CoreMotion), the overlay, and the
   `isSteady` gate in `AVCaptureService` (Fake defaults to steady).

## 13. Deferred (noted, not in this round)

- **Streaming for the static looks** (sigma-clip is two-pass; small bursts don't need it).
- **Sharpness-based reference** for the streaming path (first-frame anchor is the v1 choice).
- **Shoot-while-processing** (kept off due to FigCapture -12773; cancel is the escape hatch).
- **Full-resolution Pro output tier** and Metal acceleration.
- **Steadiness guide/gating for static looks** (scoped to long-exposure where it matters).
