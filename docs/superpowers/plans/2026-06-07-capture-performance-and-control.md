# Capture Performance & Control Overhaul — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make capture fast and memory-bounded — cap RAW at 12 MP, stream the long-exposure stack so peak memory is ~1–2 frames regardless of count, make processing cancellable, give the long-exposure looks user-controllable burst length/duration via edge sliders, and add a CoreMotion steadiness guide that gates the long-exposure burst.

**Architecture:** Two paths. **Static looks** (Detail/Night) keep the existing in-memory pipeline + fixed fast burst. **Long-exposure looks** (Smooth/Trails) get a new streaming reducer in `StackEngineCore`, user `BurstSettings`, and the steadiness guide/gating. Shared across both: explicit 12 MP cap, a cancellation token threaded into the off-actor stacking work, and a Cancel button. The engine stays pure-Swift, deterministic, golden-tested; device/UI concerns stay in the app.

**Tech Stack:** Swift / SwiftUI / AVFoundation / CoreMotion (app); pure-Swift + simd (engine). XCTest (`swift test` for engine, `xcodebuild test` for app).

**Spec:** `docs/superpowers/specs/2026-06-07-capture-performance-and-control-design.md`

---

## File Structure

**Engine (`Packages/StackEngineCore/Sources/StackEngineCore/`):**
- Modify `StackMode.swift` — add `isLongExposure`.
- Modify `Pipeline.swift` — add `streamingReduce(count:mode:shouldCancel:aligned:)` (internal core) and `reduceStreaming(_:mode:…)` (public develop+align+fold wrapper).
- Test `Packages/StackEngineCore/Tests/StackEngineCoreTests/PipelineStreamingTests.swift` (new).

**App (`StackStackStack/StackStackStack/`):**
- Modify `Capture/AVCaptureService.swift` — 12 MP `maxPhotoDimensions`; steadiness gating.
- Modify `Capture/FakeCaptureService.swift` — match the new protocol signature.
- Modify `Capture/CaptureService.swift` — `CaptureRecipe.applying` frame cap; protocol `isSteady` param + extension default.
- Create `Capture/BurstSettings.swift` — user burst length/duration (clamped).
- Create `Capture/CancellationToken.swift` — thread-safe one-way cancel flag.
- Create `Capture/MotionSteadiness.swift` — CoreMotion attitude → offset/steady; `SteadinessMath` pure helper.
- Modify `StackCaptureCoordinator.swift` — routing, cancellation, `burst`, steadiness lifecycle.
- Modify `UI/CaptureView.swift` — Cancel button, edge sliders, steadiness overlay.
- Modify `StackStackStackApp.swift` — pass `steadiness` into `CaptureView`.
- Tests: `StackStackStackTests/CaptureRecipeTests.swift`, `CoordinatorTests.swift` (extend); `BurstSettingsTests.swift`, `SteadinessMathTests.swift` (new).

**Test destinations:**
- Engine: `cd Packages/StackEngineCore && swift test --filter <Class>`
- App: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/<Class>`
- App build: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' build`

---

## Phase 1 — Capture safety: 12 MP cap, frame cap, diagnostics off

### Task 1: Hard-cap burst frames at 20 in `CaptureRecipe.applying`

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/CaptureService.swift`
- Test: `StackStackStack/StackStackStackTests/CaptureRecipeTests.swift`

- [ ] **Step 1: Write the failing test**

Add to `CaptureRecipeTests`:

```swift
func testProFrameCountIsCappedAt20() {
    let recipe = CaptureRecipe(frameCount: 8, durationSeconds: 0.5)
        .applying(ProControls(frameCount: 40))
    XCTAssertEqual(recipe.frameCount, 20, "burst frame count must be hard-capped at 20")
}

func testProFrameCountFloorIsRespected() {
    let recipe = CaptureRecipe(frameCount: 8, durationSeconds: 0.5)
        .applying(ProControls(frameCount: 0))
    XCTAssertEqual(recipe.frameCount, 1, "frame count must stay >= 1")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/CaptureRecipeTests/testProFrameCountIsCappedAt20`
Expected: FAIL — result is 40, not 20.

- [ ] **Step 3: Add the cap**

In `CaptureService.swift`, add a constant to `CaptureRecipe` (near the top of the struct, after the stored properties) and clamp in `applying`:

```swift
    /// Hard ceiling on burst length. The on-device develop+stack memory/time envelope is sized for
    /// this; beyond it the app risks the ~3 GB jetsam kill. (design 2026-06-07 §2)
    static let maxBurstFrames = 20
```

Change the `frameCount:` argument in `applying(_:)` from:

```swift
        CaptureRecipe(frameCount: max(1, pro.frameCount ?? frameCount),
```
to:
```swift
        CaptureRecipe(frameCount: min(Self.maxBurstFrames, max(1, pro.frameCount ?? frameCount)),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/CaptureRecipeTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add StackStackStack/StackStackStack/Capture/CaptureService.swift StackStackStack/StackStackStackTests/CaptureRecipeTests.swift
git commit -m "feat(capture): hard-cap burst frames at 20"
```

### Task 2: Set explicit 12 MP `maxPhotoDimensions`; turn diagnostics off

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/AVCaptureService.swift`
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Modify: `StackStackStack/StackStackStack/UI/CaptureView.swift`

(No unit test — `AVCaptureService` is device-only/compile-verified; the simulator uses `FakeCaptureService`. Verification is a clean build.)

- [ ] **Step 1: Cache the capped photo dimensions at configure time**

In `AVCaptureService.swift`, add a stored property next to the other sessionQueue-confined state (after `private var configured = false`):

```swift
    // Computed once at configure (sessionQueue), read when building each capture's settings. The
    // largest supported photo size not exceeding ~12 MP (4032x3024) — keeps RAW at the binned
    // readout, never the 48 MP path, even if a device's default differs. (design 2026-06-07 §4)
    private var cappedPhotoDimensions: CMVideoDimensions?
```

- [ ] **Step 2: Populate it inside `ensureConfigured`**

In `ensureConfigured`, immediately after `self.output.maxPhotoQualityPrioritization = .speed`, insert:

```swift
                    // Cap photo dimensions to ~12 MP. Pick the largest supported size within the
                    // target; if every supported size is larger, fall back to the smallest (closest
                    // to 12 MP) so we never silently capture a 48 MP RAW.
                    let target = CMVideoDimensions(width: 4032, height: 3024)
                    let supported = dev.activeFormat.supportedMaxPhotoDimensions
                    let area: (CMVideoDimensions) -> Int = { Int($0.width) * Int($0.height) }
                    self.cappedPhotoDimensions =
                        supported.filter { $0.width <= target.width && $0.height <= target.height }
                                 .max(by: { area($0) < area($1) })
                        ?? supported.min(by: { area($0) < area($1) })
                        ?? target
```

- [ ] **Step 3: Apply it to each capture's settings**

In `startNextFrameLocked`, immediately after `settings.photoQualityPrioritization = .speed`, insert:

```swift
        if let dims = self.cappedPhotoDimensions { settings.maxPhotoDimensions = dims }
```

(`cappedPhotoDimensions` is write-once on `sessionQueue` during `ensureConfigured`, which `captureBurst` awaits before any `startNextFrameLocked`; reading it here mirrors the existing cross-queue `output.availableRawPhotoPixelFormatTypes` read at the top of `captureBurst`.)

- [ ] **Step 4: Turn the diagnostic frame-dump off**

In `StackCaptureCoordinator.swift`, change:

```swift
    nonisolated private static let dumpFramesForDiagnostics = true
```
to:
```swift
    nonisolated private static let dumpFramesForDiagnostics = false
```

- [ ] **Step 5: Build to verify it compiles**

Run: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 6: Commit**

```bash
git add StackStackStack/StackStackStack/Capture/AVCaptureService.swift StackStackStack/StackStackStack/StackCaptureCoordinator.swift
git commit -m "feat(capture): explicit 12MP maxPhotoDimensions; diagnostics off"
```

---

## Phase 2 — Streaming stack engine (long-exposure looks)

### Task 3: Add `StackMode.isLongExposure`

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/StackMode.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/PipelineStreamingTests.swift` (new)

- [ ] **Step 1: Write the failing test**

Create `PipelineStreamingTests.swift`:

```swift
import XCTest
import simd
@testable import StackEngineCore

final class PipelineStreamingTests: XCTestCase {
    func testIsLongExposureClassification() {
        XCTAssertTrue(StackMode.smoothMotion.isLongExposure)
        XCTAssertTrue(StackMode.lightTrails.isLongExposure)
        XCTAssertFalse(StackMode.noiseReduction.isLongExposure)
        XCTAssertFalse(StackMode.lowLightBoost.isLongExposure)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter PipelineStreamingTests/testIsLongExposureClassification`
Expected: FAIL — `isLongExposure` does not exist.

- [ ] **Step 3: Add the property**

In `StackMode.swift`, inside the enum after the cases, add:

```swift
    /// The looks that capture a continuous burst over a window and use the streaming reducer
    /// (vs. the static fast-burst looks). (design 2026-06-07 §3)
    public var isLongExposure: Bool { self == .smoothMotion || self == .lightTrails }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter PipelineStreamingTests/testIsLongExposureClassification`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore/Sources/StackEngineCore/StackMode.swift Packages/StackEngineCore/Tests/StackEngineCoreTests/PipelineStreamingTests.swift
git commit -m "feat(engine): StackMode.isLongExposure"
```

### Task 4: Streaming accumulator core `streamingReduce`

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/Pipeline.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/PipelineStreamingTests.swift`

- [ ] **Step 1: Write the failing parity + cancellation tests**

Add to `PipelineStreamingTests`:

```swift
    /// N developed frames: static gray background + a single bright pixel that moves across frames.
    /// No global motion, so feeding them as already-aligned isolates accumulation from alignment.
    private func movingSpotFrames(_ n: Int, w: Int = 8, h: Int = 8) -> [PixelImage] {
        (0..<n).map { k in
            var px = [SIMD3<Float>](repeating: SIMD3(0.2, 0.2, 0.2), count: w * h)
            let x = (k * (w - 1)) / max(n - 1, 1)
            px[(h / 2) * w + x] = SIMD3(1, 1, 1)
            return PixelImage(width: w, height: h, pixels: px)
        }
    }

    func testStreamingSmoothMatchesBatchMean() throws {
        let imgs = movingSpotFrames(6)
        let streamed = try Pipeline.streamingReduce(count: imgs.count, mode: .smoothMotion) { imgs[$0] }
        let batch = StackReducer.mean(imgs)
        XCTAssertEqual(streamed.pixels.count, batch.pixels.count)
        for i in 0..<batch.pixels.count {
            XCTAssertEqual(streamed.pixels[i].x, batch.pixels[i].x, accuracy: 1e-5)
            XCTAssertEqual(streamed.pixels[i].y, batch.pixels[i].y, accuracy: 1e-5)
            XCTAssertEqual(streamed.pixels[i].z, batch.pixels[i].z, accuracy: 1e-5)
        }
    }

    func testStreamingTrailsMatchesBatchComposite() throws {
        let imgs = movingSpotFrames(6)
        let streamed = try Pipeline.streamingReduce(count: imgs.count, mode: .lightTrails) { imgs[$0] }
        let base = StackReducer.mean(imgs)
        let streaks = StackReducer.lighten(imgs)
        let mask = MotionComposite.motionMask(imgs, lo: Pipeline.trailsMotionLo,
                                              hi: Pipeline.trailsMotionHi, smoothRadius: 2)
        let batch = MotionComposite.blend(staticBase: base, effect: streaks, mask: mask)
        for i in 0..<batch.pixels.count {
            XCTAssertEqual(streamed.pixels[i].x, batch.pixels[i].x, accuracy: 1e-5)
            XCTAssertEqual(streamed.pixels[i].y, batch.pixels[i].y, accuracy: 1e-5)
            XCTAssertEqual(streamed.pixels[i].z, batch.pixels[i].z, accuracy: 1e-5)
        }
    }

    func testStreamingCancellationThrows() {
        let imgs = movingSpotFrames(10)
        var calls = 0
        XCTAssertThrowsError(
            try Pipeline.streamingReduce(count: imgs.count, mode: .smoothMotion,
                                         shouldCancel: { calls += 1; return calls > 2 }) { imgs[$0] }
        ) { error in
            XCTAssertTrue(error is CancellationError, "expected CancellationError, got \(error)")
        }
    }

    func testStreamingSingleFrameReturnsThatFrame() throws {
        let imgs = movingSpotFrames(1)
        let streamed = try Pipeline.streamingReduce(count: 1, mode: .smoothMotion) { imgs[$0] }
        for i in 0..<imgs[0].pixels.count {
            XCTAssertEqual(streamed.pixels[i].x, imgs[0].pixels[i].x, accuracy: 1e-6)
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Packages/StackEngineCore && swift test --filter PipelineStreamingTests/testStreamingSmoothMatchesBatchMean`
Expected: FAIL — `streamingReduce` does not exist (compile error).

- [ ] **Step 3: Implement `streamingReduce`**

In `Pipeline.swift`, add inside `enum Pipeline` (after `reduceImages`):

```swift
    /// Streaming/incremental reducer for the long-exposure looks: folds one frame at a time into
    /// per-pixel accumulators and releases it, so peak memory is bounded by ~1-2 frames + a fixed
    /// set of accumulator buffers regardless of frame count (vs. holding all developed + all aligned
    /// frames). `aligned(i)` returns frame i ALREADY aligned to the anchor (frame 0); it is called in
    /// order and the result folded immediately, so only one frame is live at a time. `shouldCancel`
    /// is checked between frames; a true return throws `CancellationError` (no result). Only
    /// smoothMotion / lightTrails are supported. (design 2026-06-07 §6)
    static func streamingReduce(count n: Int, mode: StackMode,
                                shouldCancel: () -> Bool = { false },
                                aligned: (Int) -> PixelImage) throws -> PixelImage {
        precondition(n >= 1, "need at least one frame")
        precondition(mode == .smoothMotion || mode == .lightTrails,
                     "streamingReduce supports the long-exposure looks only")
        let first = aligned(0)
        let w = first.width, h = first.height
        let wantTrails = (mode == .lightTrails)

        var sum = [SIMD3<Float>](repeating: .zero, count: w * h)        // running sum  → mean / base
        var maxRGB = wantTrails ? first.pixels : [SIMD3<Float>]()        // running max  → lighten / streaks
        var lumaMin = wantTrails ? [Float](repeating: .greatestFiniteMagnitude, count: w * h) : []
        var lumaMax = wantTrails ? [Float](repeating: -.greatestFiniteMagnitude, count: w * h) : []
        var count = 0

        func fold(_ img: PixelImage) {
            precondition(img.width == w && img.height == h, "all frames must be the same size")
            for i in 0..<(w * h) {
                let p = img.pixels[i]
                sum[i] += p
                if wantTrails {
                    maxRGB[i] = simd_max(maxRGB[i], p)
                    let l = 0.2126 * p.x + 0.7152 * p.y + 0.0722 * p.z
                    lumaMin[i] = Swift.min(lumaMin[i], l)
                    lumaMax[i] = Swift.max(lumaMax[i], l)
                }
            }
            count += 1
        }

        fold(first)                                   // the anchor folds as itself
        for i in 1..<n {
            if shouldCancel() { throw CancellationError() }
            fold(aligned(i))
        }

        let inv = 1 / Float(count)
        var base = PixelImage(width: w, height: h)
        for i in 0..<(w * h) { base.pixels[i] = sum[i] * inv }          // mean
        if !wantTrails { return base }                                  // smoothMotion = mean

        // lightTrails: streaks (running max), motion mask from the temporal luma range, composite.
        let streaks = PixelImage(width: w, height: h, pixels: maxRGB)
        let invSpan = 1 / Swift.max(trailsMotionHi - trailsMotionLo, 1e-6)
        var mask = [Float](repeating: 0, count: w * h)
        for i in 0..<(w * h) {
            let c = Swift.min(Swift.max((lumaMax[i] - lumaMin[i] - trailsMotionLo) * invSpan, 0), 1)
            mask[i] = c * c * (3 - 2 * c)                               // smoothstep
        }
        mask = BoxFilter.mean(mask, width: w, height: h, radius: 2)     // same smoothRadius as motionMask
        return MotionComposite.blend(staticBase: base, effect: streaks, mask: mask)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd Packages/StackEngineCore && swift test --filter PipelineStreamingTests`
Expected: PASS (all five tests).

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore/Sources/StackEngineCore/Pipeline.swift Packages/StackEngineCore/Tests/StackEngineCoreTests/PipelineStreamingTests.swift
git commit -m "feat(engine): streaming accumulator core (mean / motion-composite) with cancellation"
```

### Task 5: Public `reduceStreaming` (develop + align + fold)

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/Pipeline.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/PipelineStreamingTests.swift`

- [ ] **Step 1: Write the failing test**

Add to `PipelineStreamingTests` (a synthetic raw-frame factory + a smoke test that it develops, aligns, and stacks without holding all frames):

```swift
    private func grayRaw(_ value: UInt16, w: Int = 64, h: Int = 64) -> RawSensorFrame {
        RawSensorFrame(width: w, height: h, mosaic: [UInt16](repeating: value, count: w * h),
                       blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
                       wbGains: SIMD3<Float>(1, 1, 1))
    }

    func testReduceStreamingFromRawFramesProducesResult() throws {
        let frames = (0..<5).map { grayRaw(UInt16(300 + $0 * 10)) }
        let result = try Pipeline.reduceStreaming(frames, mode: .smoothMotion, workingResolution: 32)
        XCTAssertGreaterThan(result.width, 0)
        XCTAssertGreaterThan(result.height, 0)
        XCTAssertEqual(result.pixels.count, result.width * result.height)
    }

    func testReduceStreamingHonorsCancellation() {
        let frames = (0..<8).map { grayRaw(UInt16(300 + $0 * 10)) }
        var calls = 0
        XCTAssertThrowsError(
            try Pipeline.reduceStreaming(frames, mode: .lightTrails, workingResolution: 32,
                                         shouldCancel: { calls += 1; return calls > 1 })
        ) { error in
            XCTAssertTrue(error is CancellationError)
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Packages/StackEngineCore && swift test --filter PipelineStreamingTests/testReduceStreamingFromRawFramesProducesResult`
Expected: FAIL — `reduceStreaming` does not exist.

- [ ] **Step 3: Implement `reduceStreaming`**

In `Pipeline.swift`, add (after `streamingReduce`):

```swift
    /// End-to-end streaming stack for the long-exposure looks. Develops each raw frame on demand,
    /// downscales to `workingResolution`, aligns it to the FIRST frame (the anchor — streaming can't
    /// hold all frames to pick the sharpest, and the steadiness gate keeps the burst near one pose),
    /// and folds it via `streamingReduce`. Only one developed/aligned frame is live at a time.
    /// (design 2026-06-07 §6)
    public static func reduceStreaming(_ frames: [RawSensorFrame], mode: StackMode,
                                       searchRange: Int = 8, workingResolution: Int? = nil,
                                       binnedDevelop: Bool = true,
                                       shouldCancel: () -> Bool = { false }) throws -> PixelImage {
        precondition(!frames.isEmpty, "need at least one frame")
        func develop(_ i: Int) -> PixelImage {
            let d = binnedDevelop ? ColorPipeline.processBinned(frames[i]) : ColorPipeline.process(frames[i])
            guard let maxEdge = workingResolution else { return d }
            return downscaleOne(d, maxEdge: maxEdge)
        }
        let reference = develop(0)                                   // anchor, kept for the whole run
        let refSmall = downscaleOne(reference, maxEdge: alignmentEstimateEdge)
        let factor = Float(reference.width) / Float(refSmall.width)
        return try streamingReduce(count: frames.count, mode: mode, shouldCancel: shouldCancel) { i in
            if i == 0 { return reference }
            let moving = develop(i)
            let movSmall = downscaleOne(moving, maxEdge: alignmentEstimateEdge)
            let ts = AffineAligner.estimate(reference: refSmall, moving: movSmall,
                                            translationSearch: searchRange, robustClip: alignmentRobustClip)
            let t = Transform2D(a: ts.a, b: ts.b, c: ts.c, d: ts.d, tx: ts.tx * factor, ty: ts.ty * factor)
            return AffineAligner.warp(moving, by: t)
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd Packages/StackEngineCore && swift test --filter PipelineStreamingTests`
Expected: PASS (all tests).

- [ ] **Step 5: Run the full engine suite (no regressions)**

Run: `cd Packages/StackEngineCore && swift test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add Packages/StackEngineCore/Sources/StackEngineCore/Pipeline.swift Packages/StackEngineCore/Tests/StackEngineCoreTests/PipelineStreamingTests.swift
git commit -m "feat(engine): reduceStreaming end-to-end (develop+align+fold, first-frame anchor)"
```

---

## Phase 3 — Coordinator routing, cancellation, Cancel button

### Task 6: Cancellation token

**Files:**
- Create: `StackStackStack/StackStackStack/Capture/CancellationToken.swift`

(No standalone test — exercised via the coordinator test in Task 7.)

- [ ] **Step 1: Create the token**

```swift
import Foundation

/// A thread-safe one-way cancellation flag. `Task.detached` does NOT inherit Swift-concurrency
/// cancellation, so the coordinator uses this to signal the off-actor stacking work to stop.
final class CancellationToken: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 3: Commit**

```bash
git add StackStackStack/StackStackStack/Capture/CancellationToken.swift
git commit -m "feat(capture): thread-safe CancellationToken"
```

### Task 7: Route long-exposure to streaming; thread cancellation; `cancelProcessing`

**Files:**
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Test: `StackStackStack/StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: Write the failing test**

Add to `CoordinatorTests`:

```swift
    @MainActor
    func testCancelDiscardsQueuedStackAndFreesUI() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .smoothMotion
        await coord.shoot()              // capture done on MainActor; processing Task scheduled, not yet run
        coord.cancelProcessing()         // runs synchronously before the processing Task can take the actor
        await coord.awaitProcessing()    // now it runs, sees the token cancelled, bails before saving
        XCTAssertFalse(coord.isBusy, "shutter must be free after cancel")
        XCTAssertNil(coord.lastSavedID)
        XCTAssertNil(coord.lastError, "cancel is not an error")
        XCTAssertEqual(try store.loadAll().count, 0, "a cancelled stack must not be saved")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/CoordinatorTests/testCancelDiscardsQueuedStackAndFreesUI`
Expected: FAIL — `cancelProcessing` does not exist.

- [ ] **Step 3: Add the token list + `cancelProcessing`**

In `StackCaptureCoordinator.swift`, add a stored property after `private var processingTail: Task<Void, Never>?`:

```swift
    /// Live cancellation tokens for queued/in-flight stacks; cancelled together by `cancelProcessing`.
    private var activeTokens: [CancellationToken] = []
```

Add this method (e.g. after `awaitProcessing`):

```swift
    /// Cancel every queued/in-flight background stack. Each job discards its partial work without
    /// saving (no error surfaced), freeing the shutter immediately. (design 2026-06-07 §7)
    func cancelProcessing() {
        for token in activeTokens { token.cancel() }
        activeTokens.removeAll()
        processingTail?.cancel()
    }
```

- [ ] **Step 4: Thread the token through `enqueueProcessing`**

Replace the body of `enqueueProcessing` with:

```swift
    private func enqueueProcessing(frames: [RawSensorFrame], mode: StackMode) {
        processingCount += 1
        let token = CancellationToken()
        activeTokens.append(token)
        let previous = processingTail
        processingTail = Task { [weak self] in
            await previous?.value                                   // serialize behind earlier jobs
            guard let self else { return }
            defer {
                self.processingCount -= 1
                self.activeTokens.removeAll { $0 === token }
            }
            if token.isCancelled { return }                        // cancelled while queued
            do {
                let jpeg = try await Self.makeJPEG(from: frames, mode: mode,
                                                   shouldCancel: { token.isCancelled })
                if token.isCancelled { return }                    // cancelled during processing → discard
                let saved = try self.store.save(resultJPEG: jpeg, mode: mode.rawValue, frameCount: frames.count)
                self.lastResultJPEG = jpeg
                self.lastSavedID = saved.id
            } catch is CancellationError {
                return                                             // discarded mid-stack — not an error
            } catch {
                self.lastError = error.localizedDescription
            }
        }
    }
```

- [ ] **Step 5: Route looks and accept `shouldCancel` in `makeJPEG`**

Replace `makeJPEG` with:

```swift
    nonisolated private static func makeJPEG(from frames: [RawSensorFrame], mode: StackMode,
                                             shouldCancel: @escaping @Sendable () -> Bool) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            let result: PixelImage
            if mode.isLongExposure {
                // Streaming: one developed+aligned frame in flight at a time; cancellable between frames.
                result = try Pipeline.reduceStreaming(frames, mode: mode,
                                                      workingResolution: managedWorkingResolution,
                                                      binnedDevelop: true, shouldCancel: shouldCancel)
            } else {
                let developed = Pipeline.developedFrames(frames, binnedDevelop: true,
                                                         workingResolution: managedWorkingResolution)
                if dumpFramesForDiagnostics { dumpDevelopedFrames(developed) }
                result = Pipeline.reduceImages(developed, mode: mode, workingResolution: managedWorkingResolution)
            }
            let rgba = OutputTransform.encodeSRGB8(result)
            return try ImageEncoder.encode(rgba8: rgba, width: result.width, height: result.height,
                                           format: .jpeg, quality: 0.95)
        }.value
    }
```

- [ ] **Step 6: Run the coordinator tests to verify they pass**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/CoordinatorTests`
Expected: PASS (new test + all existing coordinator tests).

- [ ] **Step 7: Commit**

```bash
git add StackStackStack/StackStackStack/StackCaptureCoordinator.swift StackStackStack/StackStackStackTests/CoordinatorTests.swift
git commit -m "feat(capture): route long-exposure to streaming; cancellable processing"
```

### Task 8: Cancel button in the capture UI

**Files:**
- Modify: `StackStackStack/StackStackStack/UI/CaptureView.swift`

(UI change — verified by build; behavior covered by Task 7's coordinator test.)

- [ ] **Step 1: Add the Cancel button**

In `CaptureView.swift`, change the `VStack` block that lays out the bottom controls. After `statusLabel`, insert a Cancel button shown only while processing:

```swift
                statusLabel
                if coordinator.processingCount > 0 {
                    Button("Cancel") { coordinator.cancelProcessing() }
                        .buttonStyle(.bordered).tint(.white)
                        .accessibilityIdentifier("cancel-processing")
                }
                shutterButton.padding(.bottom, 40)
```

- [ ] **Step 2: Build to verify it compiles**

Run: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 3: Commit**

```bash
git add StackStackStack/StackStackStack/UI/CaptureView.swift
git commit -m "feat(ui): Cancel button while a stack is processing"
```

---

## Phase 4 — Configurable burst + edge sliders (long-exposure looks)

### Task 9: `BurstSettings` value (clamped)

**Files:**
- Create: `StackStackStack/StackStackStack/Capture/BurstSettings.swift`
- Test: `StackStackStack/StackStackStackTests/BurstSettingsTests.swift` (new)

- [ ] **Step 1: Write the failing test**

Create `BurstSettingsTests.swift`:

```swift
import XCTest
@testable import StackStackStack

final class BurstSettingsTests: XCTestCase {
    func testPhotoCountIsClampedToTwoThroughTwenty() {
        XCTAssertEqual(BurstSettings(photoCount: 99, durationSeconds: 5).photoCount, 20)
        XCTAssertEqual(BurstSettings(photoCount: 0, durationSeconds: 5).photoCount, 2)
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 5).photoCount, 10)
    }

    func testDurationIsClampedToOneThroughSixty() {
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 999).durationSeconds, 60, accuracy: 1e-9)
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 0).durationSeconds, 1, accuracy: 1e-9)
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 18).durationSeconds, 18, accuracy: 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/BurstSettingsTests`
Expected: FAIL — `BurstSettings` does not exist.

- [ ] **Step 3: Create `BurstSettings`**

```swift
/// User-controllable burst length + window for the long-exposure looks (Smooth/Trails). Photo count
/// is hard-capped at 20 and duration at 1...60s — the envelope the on-device streaming stack is
/// sized for. The init clamps, so out-of-range values from sliders can never escape. (design 2026-06-07 §5)
struct BurstSettings: Equatable, Sendable {
    let photoCount: Int
    let durationSeconds: Double

    init(photoCount: Int, durationSeconds: Double) {
        self.photoCount = min(max(photoCount, 2), 20)
        self.durationSeconds = min(max(durationSeconds, 1), 60)
    }

    /// Default seed for the long-exposure looks.
    static let `default` = BurstSettings(photoCount: 10, durationSeconds: 2)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/BurstSettingsTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add StackStackStack/StackStackStack/Capture/BurstSettings.swift StackStackStack/StackStackStackTests/BurstSettingsTests.swift
git commit -m "feat(capture): BurstSettings (clamped photo count + duration)"
```

### Task 10: Coordinator builds the recipe from `BurstSettings`

**Files:**
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Test: `StackStackStack/StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: Write the failing tests**

Add to `CoordinatorTests`:

```swift
    @MainActor
    func testLongExposureUsesBurstSettingsFrameCount() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .smoothMotion
        coord.burst = BurstSettings(photoCount: 7, durationSeconds: 4)
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 7)
    }

    @MainActor
    func testStaticLookIgnoresBurstSettings() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .noiseReduction                       // Detail: fixed 8-frame burst
        coord.burst = BurstSettings(photoCount: 3, durationSeconds: 4)
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 8)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/CoordinatorTests/testLongExposureUsesBurstSettingsFrameCount`
Expected: FAIL — `burst` does not exist.

- [ ] **Step 3: Add `burst` and a recipe builder**

In `StackCaptureCoordinator.swift`, add a published property after `@Published var pro: ProControls = .auto`:

```swift
    /// User burst length/window for the long-exposure looks (ignored by the static looks).
    @Published var burst: BurstSettings = .default
```

Add a private helper (e.g. after `shoot()`):

```swift
    /// Build the capture recipe for `mode`. Long-exposure looks take their length/window from
    /// `burst` (the edge sliders) plus any manual Pro exposure overrides; static looks use the fixed
    /// per-look recipe with the full Pro overrides. (design 2026-06-07 §5)
    private func makeRecipe(for mode: StackMode) -> CaptureRecipe {
        if mode.isLongExposure {
            return CaptureRecipe(frameCount: burst.photoCount,
                                 durationSeconds: burst.durationSeconds,
                                 manualISO: pro.iso.map(Float.init),
                                 manualShutterSeconds: pro.shutterSeconds,
                                 manualFocus: pro.focus.map(Float.init))
        }
        return CaptureRecipe.recipe(for: mode).applying(pro)
    }
```

In `shoot()`, replace:

```swift
            frames = try await capture.captureBurst(recipe: .recipe(for: mode).applying(pro))
```
with:
```swift
            frames = try await capture.captureBurst(recipe: makeRecipe(for: mode))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/CoordinatorTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add StackStackStack/StackStackStack/StackCaptureCoordinator.swift StackStackStack/StackStackStackTests/CoordinatorTests.swift
git commit -m "feat(capture): build long-exposure recipe from BurstSettings"
```

### Task 11: Edge sliders in the capture UI

**Files:**
- Modify: `StackStackStack/StackStackStack/UI/CaptureView.swift`

(UI change — verified by build; values covered by Task 10.)

- [ ] **Step 1: Add the slider views**

In `CaptureView.swift`, add these helpers to the struct (e.g. after `proPanel`):

```swift
    /// Vertical Photos/Duration sliders pinned to the left/right edges, shown only for the
    /// long-exposure looks. Each shows its value live as you drag. (design 2026-06-07 §5)
    @ViewBuilder private var burstSliders: some View {
        if coordinator.mode.isLongExposure {
            HStack {
                verticalBurstControl(
                    title: "Photos",
                    readout: "\(coordinator.burst.photoCount)",
                    value: Binding(
                        get: { Double(coordinator.burst.photoCount) },
                        set: { coordinator.burst = BurstSettings(photoCount: Int($0.rounded()),
                                                                 durationSeconds: coordinator.burst.durationSeconds) }),
                    range: 2...20, step: 1)
                Spacer()
                verticalBurstControl(
                    title: "Time",
                    readout: "\(Int(coordinator.burst.durationSeconds))s",
                    value: Binding(
                        get: { coordinator.burst.durationSeconds },
                        set: { coordinator.burst = BurstSettings(photoCount: coordinator.burst.photoCount,
                                                                 durationSeconds: $0) }),
                    range: 1...60, step: 1)
            }
            .padding(.horizontal, 6)
            .disabled(coordinator.isBusy)
        }
    }

    private func verticalBurstControl(title: String, readout: String, value: Binding<Double>,
                                      range: ClosedRange<Double>, step: Double) -> some View {
        VStack(spacing: 6) {
            Text(title).font(.caption2).foregroundColor(.white)
            Text(readout).font(.caption).bold().foregroundColor(.white)
                .accessibilityIdentifier("burst-\(title.lowercased())-value")
            Slider(value: value, in: range, step: step)
                .rotationEffect(.degrees(-90))
                .frame(width: 180)            // length of the slider track (becomes vertical extent)
                .frame(width: 44, height: 180) // constrain the rotated footprint so layout reserves the right box
                .tint(.white)
                .accessibilityIdentifier("burst-\(title.lowercased())-slider")
        }
    }
```

- [ ] **Step 2: Show the sliders over the viewfinder**

In `body`, inside the `ZStack`, add `burstSliders` immediately after the `CameraPreviewView(...)` line so it floats over the viewfinder (the inner `HStack` + `Spacer` pins the two controls to the screen edges, vertically centered):

```swift
            CameraPreviewView(previewLayer: previewLayer).ignoresSafeArea()   // live viewfinder (nil → black)
            burstSliders
```

- [ ] **Step 3: Cap and hide the Pro "Frames" control**

The edge sliders own frame count for the long-exposure looks, so hide the Pro "Frames" control there; for the static looks keep it but drop its max 40 → 20 (matching the burst cap). In `proPanel`, replace the `optControl("Frames", …)` block:

```swift
                    optControl("Frames", unit: "",
                               binding: Binding(get: { coordinator.pro.frameCount.map(Double.init) },
                                                set: { coordinator.pro.frameCount = $0.map { Int($0.rounded()) } }),
                               range: 2...40, step: 1,
                               // Default to the current look's burst length so enabling the control
                               // doesn't silently change it; the user adjusts from there.
                               defaultValue: Double(CaptureRecipe.recipe(for: coordinator.mode).frameCount)) { "\(Int($0))" }
```
with:
```swift
                    if !coordinator.mode.isLongExposure {
                        optControl("Frames", unit: "",
                                   binding: Binding(get: { coordinator.pro.frameCount.map(Double.init) },
                                                    set: { coordinator.pro.frameCount = $0.map { Int($0.rounded()) } }),
                                   range: 2...20, step: 1,
                                   // Default to the current look's burst length so enabling the control
                                   // doesn't silently change it; the user adjusts from there.
                                   defaultValue: Double(CaptureRecipe.recipe(for: coordinator.mode).frameCount)) { "\(Int($0))" }
                    }
```

- [ ] **Step 4: Build to verify it compiles**

Run: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 5: Commit**

```bash
git add StackStackStack/StackStackStack/UI/CaptureView.swift
git commit -m "feat(ui): vertical Photos/Duration edge sliders; cap/hide Pro Frames"
```

---

## Phase 5 — Steadiness guide + burst gating (long-exposure looks)

### Task 12: `SteadinessMath` pure helper + `MotionSteadiness`

**Files:**
- Create: `StackStackStack/StackStackStack/Capture/MotionSteadiness.swift`
- Test: `StackStackStack/StackStackStackTests/SteadinessMathTests.swift` (new)

- [ ] **Step 1: Write the failing test**

Create `SteadinessMathTests.swift`:

```swift
import XCTest
import CoreGraphics
@testable import StackStackStack

final class SteadinessMathTests: XCTestCase {
    func testCenteredIsSteadyWithZeroOffset() {
        let r = SteadinessMath.evaluate(deltaPitch: 0, deltaRoll: 0, tolerance: 0.05, fullScale: 0.12)
        XCTAssertTrue(r.steady)
        XCTAssertEqual(r.offset.x, 0, accuracy: 1e-9)
        XCTAssertEqual(r.offset.y, 0, accuracy: 1e-9)
    }

    func testWithinToleranceIsSteady() {
        let r = SteadinessMath.evaluate(deltaPitch: 0.04, deltaRoll: 0.0, tolerance: 0.05, fullScale: 0.12)
        XCTAssertTrue(r.steady)
    }

    func testLargeDeviationIsNotSteadyAndOffsetClampsToUnit() {
        let r = SteadinessMath.evaluate(deltaPitch: 0.5, deltaRoll: 0.0, tolerance: 0.05, fullScale: 0.12)
        XCTAssertFalse(r.steady)
        XCTAssertEqual(r.offset.y, 1.0, accuracy: 1e-9, "offset clamps to +1 at full scale")
    }

    func testNegativeRollMapsToNegativeX() {
        let r = SteadinessMath.evaluate(deltaPitch: 0.0, deltaRoll: -0.12, tolerance: 0.05, fullScale: 0.12)
        XCTAssertEqual(r.offset.x, -1.0, accuracy: 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/SteadinessMathTests`
Expected: FAIL — `SteadinessMath` does not exist.

- [ ] **Step 3: Create `MotionSteadiness.swift` (math + CoreMotion wrapper)**

```swift
import CoreMotion
import CoreGraphics
import Foundation

/// Pure steadiness math (testable without CoreMotion): map an attitude deviation from the reference
/// pose to a normalized on-screen offset and a steady/unsteady verdict. (design 2026-06-07 §8)
enum SteadinessMath {
    static func evaluate(deltaPitch: Double, deltaRoll: Double,
                         tolerance: Double, fullScale: Double) -> (offset: CGPoint, steady: Bool) {
        let mag = (deltaPitch * deltaPitch + deltaRoll * deltaRoll).squareRoot()
        let nx = max(min(deltaRoll / fullScale, 1), -1)
        let ny = max(min(deltaPitch / fullScale, 1), -1)
        return (CGPoint(x: nx, y: ny), mag <= tolerance)
    }
}

/// Tracks handheld steadiness during a long-exposure burst. On `start()` it snapshots the reference
/// attitude (the "glued" big circle); each update yields a normalized `offset` (for the moving small
/// circle) and a thread-safe `isSteady` flag the capture gate reads. Device-only: with no device
/// motion (Simulator), `isSteady` stays true so capture is never blocked. (design 2026-06-07 §8)
final class MotionSteadiness: ObservableObject, @unchecked Sendable {
    @Published private(set) var offset: CGPoint = .zero       // updated on the main queue (for the UI)
    @Published private(set) var isWithinTolerance = true

    private let manager = CMMotionManager()
    private let queue = OperationQueue()
    private var reference: CMAttitude?                        // touched only on `queue`
    private let lock = NSLock()
    private var steadyFlag = true
    private let toleranceRadians = 0.05                       // ~2.9° = "steady"
    private let fullScaleRadians = 0.12                       // offset reaches the ring edge at ~6.9°

    /// Thread-safe; read from the capture's state queue.
    var isSteady: Bool { lock.lock(); defer { lock.unlock() }; return steadyFlag }

    func start() {
        setSteady(true)
        offset = .zero
        isWithinTolerance = true
        guard manager.isDeviceMotionAvailable else { return }   // Simulator / no sensor → always steady
        queue.maxConcurrentOperationCount = 1                    // serial delivery → `reference` is race-free
        reference = nil
        manager.deviceMotionUpdateInterval = 1.0 / 60.0
        manager.startDeviceMotionUpdates(to: queue) { [weak self] motion, _ in
            guard let self, let m = motion else { return }
            if self.reference == nil { self.reference = m.attitude.copy() as? CMAttitude }
            guard let ref = self.reference, let a = m.attitude.copy() as? CMAttitude else { return }
            a.multiply(byInverseOf: ref)                        // attitude relative to the reference pose
            let result = SteadinessMath.evaluate(deltaPitch: a.pitch, deltaRoll: a.roll,
                                                 tolerance: self.toleranceRadians,
                                                 fullScale: self.fullScaleRadians)
            self.setSteady(result.steady)
            DispatchQueue.main.async {
                self.offset = result.offset
                self.isWithinTolerance = result.steady
            }
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
        setSteady(true)
    }

    private func setSteady(_ v: Bool) { lock.lock(); steadyFlag = v; lock.unlock() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/SteadinessMathTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add StackStackStack/StackStackStack/Capture/MotionSteadiness.swift StackStackStack/StackStackStackTests/SteadinessMathTests.swift
git commit -m "feat(capture): MotionSteadiness (CoreMotion attitude) + SteadinessMath"
```

### Task 13: Add `isSteady` gating to the capture protocol + services

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/CaptureService.swift`
- Modify: `StackStackStack/StackStackStack/Capture/FakeCaptureService.swift`
- Modify: `StackStackStack/StackStackStack/Capture/AVCaptureService.swift`

(Protocol/device change — verified by build + the existing `FakeCaptureServiceTests`/`CoordinatorTests` which call the convenience overload; live gating is device-verified.)

- [ ] **Step 1: Add the protocol requirement + back-compat default**

In `CaptureService.swift`, change the protocol and add an extension:

```swift
protocol CaptureService {
    /// `isSteady` is consulted before each frame; when it returns false the burst waits rather than
    /// capturing (steadiness gating, long-exposure looks). Callers that don't gate use the overload
    /// below. (design 2026-06-07 §8)
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool) async throws -> [RawSensorFrame]
    /// Start the live preview session and return a layer showing it (nil if unavailable, e.g. the
    /// Simulator fake). Idempotent — safe to call each time the capture screen appears.
    func startPreview() async -> CALayer?
}

extension CaptureService {
    /// Ungated capture (static looks, tests): always "steady".
    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
        try await captureBurst(recipe: recipe, isSteady: { true })
    }
}
```

- [ ] **Step 2: Update `FakeCaptureService` (ignores gating)**

In `FakeCaptureService.swift`, change the method signature from:

```swift
    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
```
to:
```swift
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool) async throws -> [RawSensorFrame] {
```
(The body is unchanged — the fake has no motion to gate on.)

- [ ] **Step 3: Update `AVCaptureService` signature + burst gate state**

In `AVCaptureService.swift`, change the signature from:

```swift
    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
```
to:
```swift
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool) async throws -> [RawSensorFrame] {
```

Add gate state next to the other stateQueue-confined vars (after `private var perFrameTimeout = 0.0`):

```swift
    private var totalFrames = 0                  // frames requested this burst (to detect the first frame)
    private var isSteadyCheck: () -> Bool = { true }   // steadiness gate; { true } = ungated
    private var gateAttempts = 0                 // consecutive off-pose rechecks for the current frame
    private let gateRecheckInterval = 0.1        // seconds between steadiness rechecks
    private let maxStartGateAttempts = 50        // ~5s: wait for the first frame to be steady, then fire anyway
    private let maxFrameGateAttempts = 30        // ~3s: if a later frame can't get steady, end the burst
```

In the `stateQueue.async` block inside `captureBurst`, set the new state alongside the existing assignments (after `self.remaining = frameCount`):

```swift
                self.totalFrames = frameCount
                self.isSteadyCheck = isSteady
                self.gateAttempts = 0
```

- [ ] **Step 4: Apply the gate in `startNextFrameLocked`**

In `startNextFrameLocked`, immediately after the two existing `guard` lines (`guard self.generation == gen …` and `guard self.remaining > 0 …`), insert the gate:

```swift
        // Steadiness gate (long-exposure looks): don't consume a frame while off-pose.
        if !self.isSteadyCheck() {
            self.gateAttempts += 1
            let isFirst = (self.remaining == self.totalFrames)
            let maxAttempts = isFirst ? self.maxStartGateAttempts : self.maxFrameGateAttempts
            if self.gateAttempts <= maxAttempts {
                self.stateQueue.asyncAfter(deadline: .now() + self.gateRecheckInterval) {
                    self.startNextFrameLocked(gen: gen)        // recheck; don't advance
                }
                return
            }
            if !isFirst {
                // Later frame never steadied within the window → stop requesting frames, stack what we have.
                self.remaining = 0
                self.maybeFinishLocked()
                return
            }
            // First frame timed out → fall through and capture anyway so the shot always yields ≥1 frame.
        }
        self.gateAttempts = 0   // reset for the next frame
```

- [ ] **Step 5: Build to verify it compiles**

Run: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 6: Run the full app unit suite (no regressions)**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests`
Expected: PASS (the fake/coordinator tests use the ungated overload, so they're unaffected).

- [ ] **Step 7: Commit**

```bash
git add StackStackStack/StackStackStack/Capture/CaptureService.swift StackStackStack/StackStackStack/Capture/FakeCaptureService.swift StackStackStack/StackStackStack/Capture/AVCaptureService.swift
git commit -m "feat(capture): steadiness gating in the burst state machine"
```

### Task 14: Wire steadiness into the coordinator + overlay UI

**Files:**
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Modify: `StackStackStack/StackStackStack/UI/CaptureView.swift`
- Modify: `StackStackStack/StackStackStack/StackStackStackApp.swift`

(Integration — verified by build; the steadiness math is unit-tested in Task 12, the gate machine in Task 13.)

- [ ] **Step 1: Own a `MotionSteadiness` on the coordinator and drive its lifecycle**

In `StackCaptureCoordinator.swift`, add a property after `@Published var burst: BurstSettings = .default`:

```swift
    /// Handheld-steadiness tracker for the long-exposure looks; observed by the capture overlay and
    /// read by the capture gate. (design 2026-06-07 §8)
    let steadiness = MotionSteadiness()
```

In `shoot()`, replace the capture block. Change from:

```swift
        isCapturing = true
        let frames: [RawSensorFrame]
        do {
            frames = try await capture.captureBurst(recipe: makeRecipe(for: mode))
        } catch {
            lastError = error.localizedDescription
            isCapturing = false
            return
        }
        isCapturing = false                  // arms-up done — re-enable the shutter immediately
```
to:
```swift
        isCapturing = true
        let gating: @Sendable () -> Bool
        if mode.isLongExposure {
            steadiness.start()
            gating = { [steadiness] in steadiness.isSteady }
        } else {
            gating = { true }
        }
        let frames: [RawSensorFrame]
        do {
            frames = try await capture.captureBurst(recipe: makeRecipe(for: mode), isSteady: gating)
        } catch {
            if mode.isLongExposure { steadiness.stop() }
            lastError = error.localizedDescription
            isCapturing = false
            return
        }
        if mode.isLongExposure { steadiness.stop() }
        isCapturing = false                  // arms-up done — re-enable the shutter immediately
```

- [ ] **Step 2: Add the overlay to `CaptureView`**

In `CaptureView.swift`, add a stored property at the top of the struct (after `@ObservedObject var coordinator: StackCaptureCoordinator`):

```swift
    @ObservedObject var steadiness: MotionSteadiness
```

Add the overlay view (e.g. after `burstSliders`):

```swift
    /// Two-circle steadiness guide: a fixed big ring + a small circle that drifts with device tilt;
    /// green inside tolerance, red outside. Shown only while a long-exposure burst is capturing.
    @ViewBuilder private var steadinessOverlay: some View {
        if coordinator.isCapturing && coordinator.mode.isLongExposure {
            GeometryReader { geo in
                let big: CGFloat = 120, small: CGFloat = 36
                let maxShift = (big - small) / 2
                let cx = geo.size.width / 2, cy = geo.size.height / 2
                ZStack {
                    Circle().stroke(Color.white.opacity(0.7), lineWidth: 3)
                        .frame(width: big, height: big).position(x: cx, y: cy)
                    Circle().fill(steadiness.isWithinTolerance ? Color.green : Color.red)
                        .frame(width: small, height: small)
                        .position(x: cx + steadiness.offset.x * maxShift,
                                  y: cy + steadiness.offset.y * maxShift)
                }
            }
            .allowsHitTesting(false)
        }
    }
```

Show it in `body`, in the `ZStack`, right after `burstSliders`:

```swift
            burstSliders
            steadinessOverlay
```

- [ ] **Step 3: Pass the steadiness object in at the call site**

In `StackStackStackApp.swift`, change:

```swift
                NavigationStack { CaptureView(coordinator: coordinator) }
```
to:
```swift
                NavigationStack { CaptureView(coordinator: coordinator, steadiness: coordinator.steadiness) }
```

- [ ] **Step 4: Build to verify it compiles**

Run: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 5: Run the full app unit suite (no regressions)**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add StackStackStack/StackStackStack/StackCaptureCoordinator.swift StackStackStack/StackStackStack/UI/CaptureView.swift StackStackStack/StackStackStack/StackStackStackApp.swift
git commit -m "feat(ui): steadiness overlay + coordinator wiring for long-exposure looks"
```

---

## Phase 6 — Final verification & PR

### Task 15: Full suites, code review, PR

- [ ] **Step 1: Run the full engine suite**

Run: `cd Packages/StackEngineCore && swift test`
Expected: PASS.

- [ ] **Step 2: Run the full app unit suite**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests`
Expected: PASS.

- [ ] **Step 3: Run the code-review skill (required before merge)**

Per project CLAUDE.md: invoke the code-review skill on the branch diff and address findings before opening/merging the PR.

- [ ] **Step 4: Open the PR**

```bash
git push -u origin feat/capture-perf-control
gh pr create --title "Capture performance & control overhaul" --body "$(cat <<'EOF'
Implements docs/superpowers/specs/2026-06-07-capture-performance-and-control-design.md.

- Explicit 12 MP `maxPhotoDimensions` cap; ProRAW stays off; diagnostics dump off.
- Hard cap of 20 burst frames.
- Streaming/incremental stack for the long-exposure looks (Smooth/Trails): peak memory bounded by ~1-2 frames regardless of count (fixes the ~3 GB jetsam crash), first-frame alignment anchor.
- Cancellable processing: a Cancel button discards the in-flight stack and frees the UI.
- User-controllable long-exposure burst: Photos (2-20) + Duration (1-60s) vertical edge sliders.
- CoreMotion steadiness guide (fixed ring + drifting circle) that gates the long-exposure burst.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 5: Device verification (manual, on hardware)**

The simulator uses `FakeCaptureService` (no camera, no CoreMotion). On a physical device verify: 12 MP RAW capture; a 20-photo / long-duration Smooth/Trails shot completes without the memory crash; Cancel frees the UI mid-stack; the steadiness ring appears and gates the burst when tilted.

---

## Notes on deviations from the spec (deliberate)

- **Single `BurstSettings` default (10 photos / 2 s)** shared by both long-exposure looks, rather than per-look defaults (Smooth 2 s / Trails 3 s) — one user-controlled setting matches the "for as long as the user wants" model and avoids extra per-look state. (Spec §5.1 listed per-look seeds as a minor detail.)
- **Routing to streaming lives in Phase 3** (with cancellation) rather than Phase 2, because both touch `makeJPEG`'s signature; Phase 2 keeps the engine change pure and independently testable. Coverage is unchanged.
- **First-frame gate timeout fires the frame anyway** (rather than skipping) so a shot always yields ≥ 1 frame; later frames that never steady end the burst with the frames gathered so far (spec §10's "≥ 1 frame, ends with frames gathered").
