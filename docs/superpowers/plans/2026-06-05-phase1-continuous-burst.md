# Phase 1 — Continuous-Burst Capture Recipe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the long-exposure looks genuine by capturing a per-look **burst recipe** — a longer, multi-frame continuous burst for smooth motion / light trails (vs a short burst for noise reduction) — and simulate scene motion in the fake capture so the looks are visibly distinct in the Simulator.

**Architecture:** Replace the single-case `CaptureMode` with a `CaptureRecipe` (frame count + duration window) chosen per `StackMode` (design §10.4). The `CaptureService` protocol takes a recipe; `FakeCaptureService` synthesises a moving bright object across the burst (so max-blend "trails" ≠ mean "smooth"); `AVCaptureService` captures `frameCount` frames toward the duration window (genuine duration/video-rate pacing is device tuning, flagged). The engine is unchanged — its reducers already consume whatever frames they get.

**Tech Stack:** Swift, SwiftUI, AVFoundation, XCTest. Builds on Phases 0–1.

**Deferred (noted):** a capture progress ring / frame counter UI, and true wall-clock duration pacing + video-rate frame capture on device (verify on hardware).

---

## File structure (this plan)

```
StackStackStack/StackStackStack/
  Capture/CaptureService.swift        # MODIFY — replace CaptureMode with CaptureRecipe + recipe(for:)
  Capture/FakeCaptureService.swift    # MODIFY — recipe-driven, simulates a moving bright object
  Capture/AVCaptureService.swift      # MODIFY — captureBurst(recipe:) ; frameCount toward duration
  StackCaptureCoordinator.swift       # MODIFY — capture with the per-mode recipe
StackStackStack/StackStackStackTests/
  CaptureRecipeTests.swift            # NEW — per-mode recipe mapping
  FakeCaptureServiceTests.swift       # MODIFY — recipe-driven frame count + motion makes looks differ
  CoordinatorTests.swift              # MODIFY — shoot() (no frameCount arg)
```

---

## Task 1: `CaptureRecipe` + per-mode mapping

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/CaptureService.swift`
- Create: `StackStackStack/StackStackStackTests/CaptureRecipeTests.swift`

- [ ] **Step 1: Write the failing test**

Create `StackStackStackTests/CaptureRecipeTests.swift`:
```swift
import XCTest
import StackEngineCore
@testable import StackStackStack

final class CaptureRecipeTests: XCTestCase {
    func testLongExposureLooksCaptureMoreFramesThanNoiseReduction() {
        let nr = CaptureRecipe.recipe(for: .noiseReduction)
        let smooth = CaptureRecipe.recipe(for: .smoothMotion)
        let trails = CaptureRecipe.recipe(for: .lightTrails)
        XCTAssertGreaterThan(smooth.frameCount, nr.frameCount)
        XCTAssertGreaterThan(trails.frameCount, nr.frameCount)
        XCTAssertGreaterThan(trails.durationSeconds, nr.durationSeconds)
        XCTAssertGreaterThan(nr.frameCount, 0)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

⌘U. Expected: FAIL — `cannot find 'CaptureRecipe' in scope`.

- [ ] **Step 3: Replace `CaptureMode` with `CaptureRecipe`**

Replace the entire contents of `StackStackStack/StackStackStack/Capture/CaptureService.swift` with:
```swift
import StackEngineCore

/// How a burst is captured for a given look (design §10.4). Long-exposure looks capture more
/// frames over a longer window (a continuous burst); the static looks use a short fast burst.
struct CaptureRecipe: Sendable, Equatable {
    var frameCount: Int
    var durationSeconds: Double

    static func recipe(for mode: StackMode) -> CaptureRecipe {
        switch mode {
        case .noiseReduction: return CaptureRecipe(frameCount: 8,  durationSeconds: 0.5)
        case .lowLightBoost:  return CaptureRecipe(frameCount: 12, durationSeconds: 1.0)
        case .smoothMotion:   return CaptureRecipe(frameCount: 30, durationSeconds: 2.0)
        case .lightTrails:    return CaptureRecipe(frameCount: 30, durationSeconds: 3.0)
        }
    }
}

protocol CaptureService {
    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame]
}
```

- [ ] **Step 4: Run to verify it passes (the package compiles for the test target)**

⌘U the `CaptureRecipeTests` test. Expected: PASS. (Other targets/files won't compile yet — that's Task 2.)

- [ ] **Step 5: Commit**

```bash
git add StackStackStack/StackStackStack/Capture/CaptureService.swift StackStackStack/StackStackStackTests/CaptureRecipeTests.swift
git commit -m "feat(app): CaptureRecipe per StackMode (replaces single-case CaptureMode)"
```

---

## Task 2: Recipe-driven capture across fake, AVFoundation, coordinator

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/FakeCaptureService.swift`
- Modify: `StackStackStack/StackStackStack/Capture/AVCaptureService.swift`
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Modify: `StackStackStack/StackStackStackTests/FakeCaptureServiceTests.swift`
- Modify: `StackStackStack/StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: Update the failing fake-capture test**

Replace the body of `FakeCaptureServiceTests.swift`'s test with:
```swift
import XCTest
import StackEngineCore
@testable import StackStackStack

final class FakeCaptureServiceTests: XCTestCase {
    func testFakeReturnsRecipeFrameCount() async throws {
        let svc = FakeCaptureService(width: 8, height: 8)
        let frames = try await svc.captureBurst(recipe: CaptureRecipe(frameCount: 5, durationSeconds: 1))
        XCTAssertEqual(frames.count, 5)
        XCTAssertEqual(frames[0].width, 8)
        let img = ColorPipeline.process(frames[0])
        XCTAssertEqual(img.pixels.count, 64)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

⌘U. Expected: FAIL — `captureBurst(recipe:)` not found (the fake still has the old signature).

- [ ] **Step 3: Rewrite `FakeCaptureService` with simulated motion**

Replace the contents of `Capture/FakeCaptureService.swift` with:
```swift
import StackEngineCore
import simd

/// Deterministic in-memory capture that SIMULATES scene motion across the burst, so the
/// long-exposure looks are visibly distinct in the Simulator: a bright object sweeps across
/// the frame, so max-blend "light trails" leaves a bright streak while mean "smooth motion"
/// averages it into a faint blur.
struct FakeCaptureService: CaptureService {
    let width: Int
    let height: Int

    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
        let n = max(recipe.frameCount, 1)
        return (0..<n).map { k in
            var mosaic = [UInt16](repeating: 0, count: width * height)
            // Dim, slightly noisy static background.
            for y in 0..<height { for x in 0..<width {
                mosaic[y * width + x] = UInt16(200 + (k * 17 + x * 3 + y * 5) % 11)
            }}
            // A bright object sweeping left→right over the burst.
            let cx = Int((Float(k) / Float(max(n - 1, 1))) * Float(width - 1))
            let cy = height / 2
            for dy in -2...2 { for dx in -2...2 {
                let x = cx + dx, y = cy + dy
                if x >= 0, x < width, y >= 0, y < height { mosaic[y * width + x] = 1000 }
            }}
            return RawSensorFrame(width: width, height: height, mosaic: mosaic,
                                  blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
                                  wbGains: SIMD3<Float>(1, 1, 1))
        }
    }
}
```

- [ ] **Step 4: Update `AVCaptureService` to the recipe signature**

In `Capture/AVCaptureService.swift`, change the capture entry point signature and use `recipe.frameCount`. Replace:
```swift
    func captureBurst(mode: CaptureMode, frameCount: Int) async throws -> [RawSensorFrame] {
        guard !output.availableRawPhotoPixelFormatTypes.isEmpty else { throw CaptureError.noRawFormat }
        try lockExposureAndFocus()
```
with:
```swift
    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
        try await ensureAuthorized()
        try await ensureConfigured()
        guard !output.availableRawPhotoPixelFormatTypes.isEmpty else { throw CaptureError.noRawFormat }
        try lockExposureAndFocus()
        let frameCount = recipe.frameCount   // genuine wall-clock duration pacing + video-rate capture is device tuning (verify on hardware)
```
(Leave the rest of the function body unchanged — it already submits `frameCount` captures.)

> NOTE: if `captureBurst` already calls `ensureAuthorized()`/`ensureConfigured()` at its top, do not duplicate them — keep one of each. The only required change is the signature and deriving `frameCount` from `recipe`.

- [ ] **Step 5: Update the coordinator to capture with the per-mode recipe**

In `StackCaptureCoordinator.swift`, change `shoot` to take no frame count and use the recipe. Replace:
```swift
    func shoot(frameCount: Int = 8) async {
        guard !isBusy else { return }   // reject a second shoot while one is already running
        let mode = self.mode            // capture the selected look at shutter-press time (before any await)
        do {
            state = .capturing
            let frames = try await capture.captureBurst(mode: .noiseReduction, frameCount: frameCount)
```
with:
```swift
    func shoot() async {
        guard !isBusy else { return }   // reject a second shoot while one is already running
        let mode = self.mode            // capture the selected look at shutter-press time (before any await)
        do {
            state = .capturing
            let frames = try await capture.captureBurst(recipe: .recipe(for: mode))
```

- [ ] **Step 6: Update `CoordinatorTests` call sites**

In `CoordinatorTests.swift`, change every `await coord.shoot(frameCount: N)` to `await coord.shoot()`. (Three call sites: `testShootProducesADoneStateAndSavesAFile`, `testSmoothMotionShootProducesAResult`, `testConcurrentShootsAreRejected`.)

- [ ] **Step 7: Build + run the app unit tests**

Run: `xcodebuild test -project StackStackStack/StackStackStack.xcodeproj -scheme StackStackStack -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:StackStackStackTests`
Expected: TEST SUCCEEDED (CaptureRecipe, fake, coordinator, library, encoder, result-renderer all green). `CaptureMode` no longer referenced anywhere.

- [ ] **Step 8: Commit**

```bash
git add StackStackStack
git commit -m "feat(app): recipe-driven capture (motion-simulating fake, AV signature, coordinator)"
```

---

## Task 3: Prove the looks actually differ on a moving scene

**Files:**
- Modify: `StackStackStack/StackStackStackTests/FakeCaptureServiceTests.swift`

- [ ] **Step 1: Add the failing test**

Add to `FakeCaptureServiceTests.swift`:
```swift
    func testMotionMakesLightTrailsBrighterThanSmoothMotion() async throws {
        let svc = FakeCaptureService(width: 32, height: 32)
        let frames = try await svc.captureBurst(recipe: CaptureRecipe(frameCount: 16, durationSeconds: 1))
        let trails = Pipeline.reduce(frames, mode: .lightTrails)    // per-channel max → bright streak
        let smooth = Pipeline.reduce(frames, mode: .smoothMotion)   // mean → faint blur

        // Across the centre row the moving object leaves a brighter max-streak than the mean.
        let y = 16
        var trailsMax: Float = 0, smoothMax: Float = 0
        for x in 0..<32 {
            trailsMax = max(trailsMax, trails[x, y].x)
            smoothMax = max(smoothMax, smooth[x, y].x)
        }
        XCTAssertGreaterThan(trailsMax, smoothMax, "light trails must keep the moving highlight brighter than smooth motion")
    }
```

- [ ] **Step 2: Run to verify it fails / passes**

⌘U `testMotionMakesLightTrailsBrighterThanSmoothMotion`.
Expected: PASS (the motion fake from Task 2 makes the looks differ; if it FAILS, the fake isn't moving the object — fix the fake, don't weaken the assertion).

- [ ] **Step 3: Simulator check (visual)**

Run the app in the Simulator. Pick **Trails**, shoot → a bright horizontal streak across the centre. Pick **Smooth**, shoot → a faint blurred band. Pick **Detail**, shoot → the moving object is mostly averaged away. The three looks are now visibly different on the same (simulated-motion) scene.

- [ ] **Step 4: Commit**

```bash
git add StackStackStack/StackStackStackTests/FakeCaptureServiceTests.swift
git commit -m "test(app): motion fake makes light trails brighter than smooth motion"
```

---

## Self-review

**1. Spec coverage (§10.4 continuous burst):** per-look recipe with more frames over a longer window for long-exposure looks → Task 1 (`CaptureRecipe.recipe(for:)`); recipe threaded through capture → Task 2; the looks are genuinely different on a moving scene → Task 3. **Deferred and noted:** capture progress UI, and true wall-clock duration pacing + video-rate capture on device.

**2. Placeholder scan:** every step has complete code; no TBD/TODO.

**3. Type consistency:** `CaptureRecipe(frameCount:durationSeconds:)` / `.recipe(for:)`; `CaptureService.captureBurst(recipe:)`; `FakeCaptureService`/`AVCaptureService` conform; `StackCaptureCoordinator.shoot()` (no args) calls `capture.captureBurst(recipe: .recipe(for: mode))`. `CaptureMode` is fully removed (grep `CaptureMode` → no hits).

---

## Definition of done

- App unit tests green (recipe mapping, recipe-driven fake, looks-differ, coordinator/library/encoder/renderer).
- `cd Packages/StackEngineCore && swift test` → still all green (engine untouched).
- Simulator: Trails / Smooth / Detail produce visibly different results on the simulated-motion scene.
- `grep -r CaptureMode StackStackStack` → no results.
