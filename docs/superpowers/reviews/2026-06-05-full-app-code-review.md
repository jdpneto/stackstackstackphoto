# Full Application Code Review — Stack Stack Stack Photography

**Date:** 2026-06-05
**Scope:** Entire codebase — `StackEngineCore` (engine) + `StackStackStack` (iOS app).
**Method:** Five parallel reviewers (engine math, editor/output, capture+concurrency, persistence+IO+UI, security/privacy+architecture), recall-biased. Severities below are adjusted for actual call-site context (e.g. the default noise recipe captures 8 frames; `LibraryStore.adjustments` already uses `try?`).

> **Note on the device path:** the app has only ever been **compile-verified**, never run on hardware. Every CRITICAL/HIGH item tagged *(device-only)* is unreachable in the Simulator (which uses `FakeCaptureService`) and must be fixed/validated before the first real-device capture.

---

## Severity summary

| # | Severity | Area | Issue | Device-only |
|---|---|---|---|---|
| 1 | CRITICAL | Capture | RAW not enabled during session configuration → burst produces no RAW frames | yes |
| 2 | CRITICAL | Capture | `RawFrameConverter` assumes UInt16/single-plane/packed rows → OOB / corrupt on real RAW buffers | yes |
| 3 | HIGH | Engine | NaN/Inf (e.g. `whiteLevel==blackLevel`) propagates to `UInt8(NaN)` → hard trap at encode | partly |
| 4 | HIGH | Concurrency | `lockExposureAndFocus` reads `device` + configures it **off** `sessionQueue` → data race (breaks the `@unchecked Sendable` invariant) | yes |
| 5 | HIGH | Concurrency | Burst completion driven off `didFinishProcessingPhoto`, no timeout → a dropped callback hangs `shoot()` forever | yes |
| 6 | HIGH | Concurrency | Stale `asyncAfter`/delegate callbacks from an abandoned burst mutate the next burst's counters (no generation token) | yes |
| 7 | HIGH | Persistence | `index.json` written non-atomically → interrupted write corrupts the whole library (silently presents empty, next save drops all records) | no |
| 8 | HIGH | Persistence | `save()` not atomic across its writes → orphaned files / torn index on mid-write failure | no |
| 9 | MED-HIGH | IO | `ImageEncoder.encode` trusts caller `width*height` with no `rgba8.count` check → OOB read if contract ever violated | no |
| 10 | MED-HIGH | Persistence | `applyEdit` rewrites the JPEG but never updates the index → Gallery cell won't reload after an edit | no |
| 11 | MEDIUM | Engine | `linearizeSample` clamps to [0,1] **before** white balance → colored (magenta) clipped highlights | partly |
| 12 | MEDIUM | Engine | Noise-reduction `sigmaClippedMean` does no rejection when N≤5 (only reachable if Pro sets frame count ≤5; default recipe is 8) | no |
| 13 | MEDIUM | Editor | Tone-curve weights clamp `tone` to [0,1] but apply to unclamped `p` → undefined highlight push for super-white (p>1) pixels | no |
| 14 | MEDIUM | Privacy | Saved JPEGs + index written to Documents with **default** data protection (no `NSFileProtectionComplete`), not backup-excluded | no |
| 15 | MEDIUM | UI | View builders/inits do **synchronous disk reads** on the main thread (editor original, `adjustments`, gallery `loadAll`) | no |
| 16 | MEDIUM | Persistence | Unbounded library growth: no delete API, no orphan reconciliation, whole index decoded per Gallery appearance | no |
| 17 | MEDIUM | Capture | Long manual shutter makes burst interval exceed the window → overlapping in-flight RAW captures | yes |
| 18 | LOW | various | Force-unwrapped sRGB colorspace in `ImageEncoder`; preview stale-state race; `crop` upper-bound clamp; `maxAbsDiff` geometry check; unused `NSPhotoLibraryAddUsageDescription`; round-trip ±1 LSB; test gaps (corrupt input, metadata stripping) | no |

---

## What's solid (verified, not assumed)

- **No network egress, no analytics/crash SDKs, no sensitive logging.** Exhaustive search found zero `URLSession`/sockets and zero Firebase/Sentry/etc. Matches the on-device-only requirement.
- **Camera permission fails closed** — every `authorizationStatus` case is handled; nothing proceeds unauthorized; session config runs off-main and only after auth.
- **`Info.plist` correct** — `NSCameraUsageDescription` present + specific; no over-broad entitlements/background modes.
- **No path traversal** — all file names derive from `UUID()` + fixed suffixes; no untrusted string reaches the filesystem.
- **No EXIF/GPS leakage by construction** — JPEGs are built from raw RGBA via `CGContext`→`CGImageDestination` with no metadata dict; the pipeline never holds location data.
- **`ResultRenderer` dimension contract is correct** — it encodes at `adjusted.width/height` (the earlier crop-OOB fix holds).
- **Straighten auto-zoom math is correct**, including non-square frames (the re-review fix holds).
- **Tonal pipeline** operates entirely in linear light, correct op ordering, NaN-free for in-range inputs.
- **Engine module boundary is clean** — `StackEngineCore` is pure/platform-agnostic with the bulk of the tests; the app target owns AVFoundation/ImageIO/storage/SwiftUI; the `CaptureService`/`FakeCaptureService` seam keeps the coordinator testable.
- **Continuation + DispatchGroup discipline** is correct for a *well-behaved* single burst (resumed exactly once; settle group balanced).

---

## Detailed findings

### Engine (math / color)
- **NaN→encode trap (#3, HIGH).** `RawSensorFrame.linearizeSample` computes `(v−black)/(white−black)`; if `white==black` this is `0/0 = NaN`, and Swift's `min/max` clamp does **not** strip NaN, so it flows to `OutputTransform.encodeSRGB8` where `UInt8(NaN)` traps. *Fix:* guard the denominator (`max(white−black, ε)`) **and** make the sRGB clamp finite-safe (`x.isFinite ? clamp : 0`).
- **Highlight clip order (#11, MEDIUM).** `linearizeSample` clamps to [0,1] *before* `ColorPipeline` applies per-channel WB gain, so a clipped highlight × a >1 gain goes off-neutral (magenta skies/speculars). *Fix:* clamp only the low bound in the linear stage; defer the high clamp to output.
- **Small-N sigma clip (#12, MEDIUM).** At κ=2 a single outlier's max z-score is √(N−1), so N≤5 does no rejection. The default `.noiseReduction` recipe is **8 frames** (safe), so this only bites when Pro lowers the count below 6. *Fix:* couple κ to N (κ≈1.5 for N≤5) or expose κ on the main path.
- LOW: `Metrics.maxAbsDiff` checks pixel count not geometry; empty-burst hits a `precondition` rather than a recoverable error.

### Editor / output
- **`crop` upper-bound (LOW, defensive).** The branch condition makes `cw≤w`/`ch≤h` in practice, but a defensive `min(w, …)`/`min(h, …)` removes any Float-rounding doubt cheaply.
- **Tone-curve for p>1 (#13, MEDIUM).** Exposure/contrast routinely push linear values >1; the shadow/highlight weights saturate at `tone=1`, so super-white pixels get a constant additive highlight push. *Fix:* compute the weight from a normalized tone, or pin LDR behavior + a p>1 test.
- **Malformed sidecar:** a wrong-typed or unknown-`cropAspect` value throws in `init(from:)`, but `LibraryStore.adjustments` wraps the decode in `try?` → degrades to `.identity` (silent loss of the edit, **not** a crash). *Fix (LOW):* make `CropAspect` decode tolerant (unknown→`.original`).
- LOW: `decode→encode` may drift ±1 LSB on a few byte values (no exact-round-trip test).

### Capture / concurrency (device-only unless noted)
- **RAW not configured (#1, CRITICAL).** `ensureConfigured` never enables RAW delivery (no ProRAW enable / format validation) before reading `availableRawPhotoPixelFormatTypes`, so on real hardware the burst likely yields no Bayer frames → `noFramesProduced`. *Fix:* enable RAW during configuration and verify the chosen type is a real Bayer RAW + `photo.pixelBuffer` non-nil.
- **`RawFrameConverter` format assumptions (#2, CRITICAL).** Hardcoded `assumingMemoryBound(to: UInt16)`, single-plane, packed-row assumptions; no pixel-format / `rowBytes` check. On a planar or non-16-bit buffer this reads past the buffer (EXC_BAD_ACCESS). *Fix:* validate `CVPixelBufferGetPixelFormatType` against expected Bayer types, guard `rowBytes`, use plane-aware accessors, read bit depth from the format.
- **Off-queue device config (#4, HIGH).** `lockExposureAndFocus`'s continuation body runs on the cooperative pool, reading `self.device` (written on `sessionQueue`) and calling `lockForConfiguration`/`setExposureModeCustom` off-queue, concurrently with `capturePhoto` on `sessionQueue` — a real race the `@unchecked Sendable` annotation hides. *Fix:* wrap the body in `sessionQueue.async`.
- **No completion watchdog (#5, HIGH).** `remaining` only decrements in `didFinishProcessingPhoto`; a dropped/failed callback (interruption, background, thermal) leaves it >0 forever → `shoot()` hangs in `.capturing`, shutter stuck disabled. *Fix:* drive completion off `didFinishCaptureFor` (one per request even on error) + a timeout that resumes with an error.
- **Cross-burst stragglers (#6, HIGH).** All N `capturePhoto` calls are scheduled up-front via `asyncAfter`; a stale block/callback from an abandoned burst mutates the next burst's counters. *Fix:* tag each burst with a generation token; ignore stragglers.
- **Long-shutter pacing (#17, MEDIUM).** `interval = max(window/(N−1), manualShutter)` makes a 1 s shutter × 30 frames span ~30 s with overlapping in-flight RAW captures. *Fix:* cap total burst time / reduce N / ensure one capture in flight.
- LOW: DNG black level read only from element [0] (per-channel black collapses → slight shadow tint).

### Persistence / IO / UI
- **Non-atomic index write (#7, HIGH).** `persist` does `data.write(to:)` with no `.atomic`; an interrupted write corrupts `index.json`. `loadAll` then throws → callers `try?` it to `[]` → library looks empty → the next `save` overwrites with one record, orphaning everything. *Fix:* `.atomic` writes; rebuild-from-disk recovery on decode failure.
- **Non-atomic `save()` (#8, HIGH).** Multiple sequential writes with no crash-consistency or single-writer guarantee. *Fix:* write files first, swap the index atomically last; route index mutation through one serial owner.
- **`applyEdit` doesn't update the index (#10, MED-HIGH).** Edited JPEG bytes change but the record/URL don't, so `ThumbnailCell.task(id: url)` won't reload an already-rendered Gallery cell. *Fix:* bump an `updatedAt` on the record and re-persist (or key the cell on `(url, updatedAt)`).
- **`ImageEncoder` buffer guard (#9, MED-HIGH).** No `rgba8.count == width*height*4` check; currently safe because `ResultRenderer` passes matching dims, but unenforced. *Fix:* guard + throw `contextFailed`.
- **Main-thread disk reads (#15, MEDIUM).** Editor original-data read, `adjustments(for:)`, and Gallery `loadAll` run synchronously in view builders/inits. *Fix:* load via `.task`, pass results in.
- **Unbounded growth (#16, MEDIUM).** No delete/reconcile/cap. *Fix:* delete API + orphan reconcile.
- LOW: force-unwrapped `CGColorSpace(name: .sRGB)!` in `ImageEncoder` (decoder handles it via `guard`); preview stale-state race (add a generation token); `renderTask` not cancelled on disappear.

### Security / privacy
- **Data protection (#14, MEDIUM).** Images + index in Documents use the **default** protection class and aren't backup-excluded. *Fix:* write with `.completeFileProtection` (or `.completeUnlessOpen`); consider Application Support + exclude-from-backup.
- **Unused permission (LOW).** `NSPhotoLibraryAddUsageDescription` is declared but no Photos-save code exists. *Fix:* remove until a save-to-Photos feature lands.
- **Test gaps (LOW).** No tests for corrupt-index tolerance, malformed-JPEG → `ResultRenderer` returns nil (not trap), or the EXIF/GPS-stripping privacy property.

---

## Recommended fix order

**A. Before any real-device run (device-only criticals):** #1 RAW config, #2 `RawFrameConverter` format validation, #4 sessionQueue confinement, #5 watchdog timeout, #6 burst generation token, #17 long-shutter pacing.

**B. Cheap correctness/robustness now (Simulator-testable):** #3 NaN-safe clamp, #9 encoder buffer guard, #7/#8 atomic library writes, #10 `applyEdit` index update.

**C. Quality + privacy:** #11 highlight-clip order, #14 data protection, #15 off-main disk IO, #13 tone-curve >1, #12 adaptive κ, #16 retention/delete.

**D. Low / cleanup:** force-unwrap, preview token, crop clamp, unused permission, round-trip + corrupt-input + metadata-stripping tests.
