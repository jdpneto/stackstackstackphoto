# Phase 1 — Long-Exposure Family Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the three long-exposure looks — **smooth motion**, **short light trails**, and **low-light boost** — on top of the existing align-and-reduce engine, selectable from the capture screen, alongside the shipped Noise Reduction look.

**Architecture:** Per the design bible §13.3, every look shares one align step and differs only in the per-pixel temporal **reducer**: noise reduction = robust (sigma-clipped) mean, smooth motion = plain mean, light trails = per-channel lighten (max), low-light boost = robust mean × exposure gain. We add a `StackMode` enum + two new reducers to `StackEngineCore`, route the pipeline through a shared `alignedStack` helper, and let the app pick a mode from a look-picker. Capture stays the existing burst (the looks differ only in processing for this phase).

**Tech Stack:** Swift, Swift Package Manager, XCTest, simd, SwiftUI. Builds on the Phase 0 walking skeleton (`StackEngineCore` + `StackStackStack`).

---

## File structure (this plan)

```
Packages/StackEngineCore/
  Sources/StackEngineCore/
    StackMode.swift        # NEW — the look enum (noiseReduction/smoothMotion/lightTrails/lowLightBoost)
    StackReducer.swift     # MODIFY — add mean(), lighten(), boostedMean()
    Pipeline.swift         # MODIFY — extract alignedStack(); add reduceImages(mode:)/reduce(mode:)
  Tests/StackEngineCoreTests/
    StackReducerTests.swift  # MODIFY — mean/lighten/boostedMean tests
    PipelineTests.swift      # MODIFY — mode-dispatch tests
StackStackStack/StackStackStack/
  StackCaptureCoordinator.swift  # MODIFY — published `mode`; route through Pipeline.reduce(mode:)
  UI/CaptureView.swift           # MODIFY — look-picker + StackMode UI extension
StackStackStack/StackStackStackTests/
  CoordinatorTests.swift         # MODIFY — a non-default-mode shoot produces a result
```

---

## Task 1: `StackMode` enum

**Files:**
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/StackMode.swift`

> Pure enum — exercised by the reducer/pipeline tests in later tasks (no standalone test needed).

- [ ] **Step 1: Create the enum**

Create `Packages/StackEngineCore/Sources/StackEngineCore/StackMode.swift`:
```swift
/// The processing look applied to an aligned burst (design §13). Every look shares the
/// align step and differs only in the per-pixel reducer.
public enum StackMode: Sendable, Equatable, Hashable, CaseIterable {
    case noiseReduction   // robust (sigma-clipped) mean — clean detail
    case smoothMotion     // plain temporal mean — silky water / clouds
    case lightTrails      // per-channel lighten (max) — light streaks
    case lowLightBoost    // robust mean + exposure gain — brighter night shot
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd Packages/StackEngineCore && swift build`
Expected: builds with no errors.

- [ ] **Step 3: Commit**

```bash
git add Packages/StackEngineCore/Sources/StackEngineCore/StackMode.swift
git commit -m "feat(core): add StackMode look enum"
```

---

## Task 2: `StackReducer.mean` (smooth motion)

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/StackReducer.swift`
- Modify: `Packages/StackEngineCore/Tests/StackEngineCoreTests/StackReducerTests.swift`

- [ ] **Step 1: Add the failing test**

Add to `StackReducerTests.swift` (inside the class; it already has a `flat(_:)` helper that builds a 1×1 image):
```swift
    func testMeanIsPlainAverage() {
        // Plain mean keeps every sample (no clipping) — even an extreme one.
        let out = StackReducer.mean([flat(0.0), flat(0.4), flat(0.8), flat(10.0)])
        XCTAssertEqual(out[0, 0].x, (0.0 + 0.4 + 0.8 + 10.0) / 4, accuracy: 1e-5)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testMeanIsPlainAverage`
Expected: FAIL — `type 'StackReducer' has no member 'mean'`.

- [ ] **Step 3: Implement `mean`**

Add to `StackReducer.swift` (inside `enum StackReducer`):
```swift
    /// Plain per-pixel temporal mean — keeps scene motion (smooth-motion look).
    public static func mean(_ imgs: [PixelImage]) -> PixelImage {
        precondition(!imgs.isEmpty)
        let w = imgs[0].width, h = imgs[0].height
        precondition(imgs.allSatisfy { $0.width == w && $0.height == h }, "all images must be the same size")
        var out = PixelImage(width: w, height: h)
        let inv = 1 / Float(imgs.count)
        for i in 0..<(w * h) {
            var acc = SIMD3<Float>(0, 0, 0)
            for im in imgs { acc += im.pixels[i] }
            out.pixels[i] = acc * inv
        }
        return out
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter testMeanIsPlainAverage`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): plain temporal mean reducer (smooth motion)"
```

---

## Task 3: `StackReducer.lighten` (light trails)

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/StackReducer.swift`
- Modify: `Packages/StackEngineCore/Tests/StackEngineCoreTests/StackReducerTests.swift`

- [ ] **Step 1: Add the failing test**

Add to `StackReducerTests.swift`:
```swift
    func testLightenTakesPerChannelMax() {
        let a = PixelImage(width: 1, height: 1, pixels: [SIMD3<Float>(0.2, 0.8, 0.1)])
        let b = PixelImage(width: 1, height: 1, pixels: [SIMD3<Float>(0.7, 0.3, 0.5)])
        let out = StackReducer.lighten([a, b])
        XCTAssertEqual(out[0, 0].x, 0.7, accuracy: 1e-6)
        XCTAssertEqual(out[0, 0].y, 0.8, accuracy: 1e-6)
        XCTAssertEqual(out[0, 0].z, 0.5, accuracy: 1e-6)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testLightenTakesPerChannelMax`
Expected: FAIL — `type 'StackReducer' has no member 'lighten'`.

- [ ] **Step 3: Implement `lighten`**

Add to `StackReducer.swift`:
```swift
    /// Per-channel lighten (max) across frames — light streaks accumulate (light-trails look).
    public static func lighten(_ imgs: [PixelImage]) -> PixelImage {
        precondition(!imgs.isEmpty)
        let w = imgs[0].width, h = imgs[0].height
        precondition(imgs.allSatisfy { $0.width == w && $0.height == h }, "all images must be the same size")
        var out = PixelImage(width: w, height: h)
        for i in 0..<(w * h) {
            var m = imgs[0].pixels[i]
            for k in 1..<imgs.count { m = simd_max(m, imgs[k].pixels[i]) }
            out.pixels[i] = m
        }
        return out
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter testLightenTakesPerChannelMax`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): per-channel lighten reducer (light trails)"
```

---

## Task 4: `StackReducer.boostedMean` (low-light boost)

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/StackReducer.swift`
- Modify: `Packages/StackEngineCore/Tests/StackEngineCoreTests/StackReducerTests.swift`

- [ ] **Step 1: Add the failing test**

Add to `StackReducerTests.swift`:
```swift
    func testBoostedMeanAppliesGain() {
        // Robust mean of five 0.3s is 0.3; gain 2.0 → 0.6 (linear; output clamps later).
        let imgs = [flat(0.3), flat(0.3), flat(0.3), flat(0.3), flat(0.3)]
        XCTAssertEqual(StackReducer.boostedMean(imgs, gain: 2.0)[0, 0].x, 0.6, accuracy: 1e-5)
        // gain 1.0 is identical to the robust mean.
        XCTAssertEqual(StackReducer.boostedMean(imgs, gain: 1.0)[0, 0].x, 0.3, accuracy: 1e-5)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testBoostedMeanAppliesGain`
Expected: FAIL — `type 'StackReducer' has no member 'boostedMean'`.

- [ ] **Step 3: Implement `boostedMean`**

Add to `StackReducer.swift`:
```swift
    /// Robust (sigma-clipped) mean with an exposure gain — low-light-boost look. Output may
    /// exceed 1.0 (the output transform clamps). gain > 1 brightens; gain == 1 == noise reduction.
    public static func boostedMean(_ imgs: [PixelImage], gain: Float) -> PixelImage {
        let base = sigmaClippedMean(imgs)
        if gain == 1 { return base }
        var out = base
        for i in 0..<out.pixels.count { out.pixels[i] = base.pixels[i] * gain }
        return out
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter testBoostedMeanAppliesGain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): robust-mean-with-gain reducer (low-light boost)"
```

---

## Task 5: Pipeline `alignedStack` + mode dispatch

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/Pipeline.swift`
- Modify: `Packages/StackEngineCore/Tests/StackEngineCoreTests/PipelineTests.swift`

This extracts the shared align step into `alignedStack` (DRY) and adds `reduceImages(_:mode:searchRange:)` and `reduce(_:mode:searchRange:)`. The existing `noiseReductionImages` is rewritten to use `alignedStack`, preserving its `kappa` parameter so the golden test is unchanged.

- [ ] **Step 1: Add the failing test**

Add to `PipelineTests.swift`:
```swift
    func testReduceImagesDispatchesByMode() {
        // Two aligned frames (no shift) with distinct values; check each mode's reducer is used.
        let a = PixelImage(width: 2, height: 1, pixels: [SIMD3<Float>(0.2, 0.2, 0.2), SIMD3<Float>(0.2, 0.2, 0.2)])
        let b = PixelImage(width: 2, height: 1, pixels: [SIMD3<Float>(0.8, 0.8, 0.8), SIMD3<Float>(0.8, 0.8, 0.8)])
        // smoothMotion → mean = 0.5
        XCTAssertEqual(Pipeline.reduceImages([a, b], mode: .smoothMotion, searchRange: 0)[0, 0].x, 0.5, accuracy: 1e-5)
        // lightTrails → max = 0.8
        XCTAssertEqual(Pipeline.reduceImages([a, b], mode: .lightTrails, searchRange: 0)[0, 0].x, 0.8, accuracy: 1e-5)
        // lowLightBoost → robust mean (0.5) × 2.0 = 1.0
        XCTAssertEqual(Pipeline.reduceImages([a, b], mode: .lowLightBoost, searchRange: 0)[0, 0].x, 1.0, accuracy: 1e-5)
    }
```
*(searchRange: 0 means "no shift search" so the test is purely about the reducer, not alignment.)*

- [ ] **Step 2: Run it to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testReduceImagesDispatchesByMode`
Expected: FAIL — `type 'Pipeline' has no member 'reduceImages'`.

- [ ] **Step 3: Rewrite `Pipeline.swift`**

Replace the body of `enum Pipeline` so it reads exactly:
```swift
import simd

public enum Pipeline {
    /// Align a burst to its sharpest frame (shared by every look). Luminance is computed once
    /// per frame and reused for reference selection AND per-frame alignment.
    static func alignedStack(_ imgs: [PixelImage], searchRange: Int) -> [PixelImage] {
        precondition(!imgs.isEmpty)
        if imgs.count == 1 { return imgs }
        let w = imgs[0].width, h = imgs[0].height
        let lumas = imgs.map { Luma.luminance($0) }
        let refIdx = ReferenceSelection.sharpestIndex(lumas: lumas, width: w, height: h)
        var aligned = [PixelImage]()
        aligned.reserveCapacity(imgs.count)
        for (i, im) in imgs.enumerated() {
            if i == refIdx { aligned.append(im); continue }
            let t = Alignment.estimateTranslation(referenceLuma: lumas[refIdx], movingLuma: lumas[i],
                                                  width: w, height: h, searchRange: searchRange)
            aligned.append(Alignment.warp(im, by: t))
        }
        return aligned
    }

    /// Align then apply the look's reducer.
    public static func reduceImages(_ imgs: [PixelImage], mode: StackMode, searchRange: Int = 8) -> PixelImage {
        let aligned = alignedStack(imgs, searchRange: searchRange)
        switch mode {
        case .noiseReduction: return StackReducer.sigmaClippedMean(aligned)
        case .smoothMotion:   return StackReducer.mean(aligned)
        case .lightTrails:    return StackReducer.lighten(aligned)
        case .lowLightBoost:  return StackReducer.boostedMean(aligned, gain: 2.0)
        }
    }

    /// End-to-end from raw frames: develop each → align → reduce for the chosen look.
    public static func reduce(_ frames: [RawSensorFrame], mode: StackMode, searchRange: Int = 8) -> PixelImage {
        reduceImages(frames.map { ColorPipeline.process($0) }, mode: mode, searchRange: searchRange)
    }

    // MARK: - Noise-reduction-specific entry points (kept for the golden harness/tests)

    /// Noise reduction with an explicit kappa (used by the golden convergence test).
    public static func noiseReductionImages(_ imgs: [PixelImage],
                                            searchRange: Int = 8,
                                            kappa: Float = 2.0) -> PixelImage {
        StackReducer.sigmaClippedMean(alignedStack(imgs, searchRange: searchRange), kappa: kappa)
    }

    public static func noiseReduction(_ frames: [RawSensorFrame],
                                      searchRange: Int = 8,
                                      kappa: Float = 2.0) -> PixelImage {
        noiseReductionImages(frames.map { ColorPipeline.process($0) }, searchRange: searchRange, kappa: kappa)
    }

    public static func noiseReductionEncoded(_ frames: [RawSensorFrame]) -> (image: PixelImage, rgba8: [UInt8]) {
        let result = noiseReduction(frames)
        return (result, OutputTransform.encodeSRGB8(result))
    }
}
```

- [ ] **Step 4: Run the new test AND the full suite**

Run: `cd Packages/StackEngineCore && swift test --filter testReduceImagesDispatchesByMode`
Expected: PASS.
Run: `cd Packages/StackEngineCore && swift test`
Expected: ALL pass (the existing golden/raw-path tests still use `noiseReduction*` and must stay green).

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): shared alignedStack + reduce(mode:) pipeline dispatch"
```

---

## Task 6: Coordinator selects a mode

**Files:**
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Modify: `StackStackStack/StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: Add the failing test**

Add to `CoordinatorTests.swift`:
```swift
    @MainActor
    func testSmoothMotionShootProducesAResult() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: FakeCaptureService(width: 16, height: 16), store: store)
        coord.mode = .smoothMotion
        await coord.shoot(frameCount: 6)
        if case .done = coord.state {} else { XCTFail("expected .done, got \(coord.state)") }
        XCTAssertEqual(try store.loadAll().count, 1)
    }
```
*(Requires `import StackEngineCore` at the top of `CoordinatorTests.swift` to name `StackMode` — add it if not present.)*

- [ ] **Step 2: Run it to verify it fails**

Build the test target (⌘U) or run that test.
Expected: FAIL — `value of type 'StackCaptureCoordinator' has no member 'mode'`.

- [ ] **Step 3: Add the published mode and route through `reduce(mode:)`**

In `StackCaptureCoordinator.swift`:
- Add `import StackEngineCore` if not present (it is).
- Add a published, settable mode property after `lastResultJPEG`:
```swift
    /// The currently selected look. Settable from the capture UI.
    @Published var mode: StackMode = .noiseReduction
```
- In `shoot(frameCount:)`, capture the mode and pass it into the off-main worker. Replace the `state = .processing` / `makeJPEG` lines with:
```swift
            state = .processing
            let mode = self.mode
            let jpeg = try await Self.makeJPEG(from: frames, mode: mode)
            lastResultJPEG = jpeg
            let saved = try store.save(resultJPEG: jpeg, mode: "\(mode)", frameCount: frames.count)
```
- Replace `makeJPEG(from:)` with the mode-aware version:
```swift
    /// CPU-heavy develop → align → reduce → encode, run off the MainActor.
    nonisolated private static func makeJPEG(from frames: [RawSensorFrame], mode: StackMode) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            let result = Pipeline.reduce(frames, mode: mode)
            let rgba = OutputTransform.encodeSRGB8(result)
            return try ImageEncoder.encode(rgba8: rgba, width: result.width, height: result.height,
                                           format: .jpeg, quality: 0.95)
        }.value
    }
```

- [ ] **Step 4: Run it to verify it passes**

⌘U (or run that test).
Expected: PASS (and the existing `testShootProducesADoneStateAndSavesAFile` still passes — default mode is `.noiseReduction`).

- [ ] **Step 5: Commit**

```bash
git add StackStackStack/StackStackStack/StackCaptureCoordinator.swift StackStackStack/StackStackStackTests/CoordinatorTests.swift
git commit -m "feat(app): coordinator selects a StackMode and routes through reduce(mode:)"
```

---

## Task 7: Look-picker in the capture screen

**Files:**
- Modify: `StackStackStack/StackStackStack/UI/CaptureView.swift`

> UI change — verified by build + Simulator run (the existing UI test still taps the shutter with the default look).

- [ ] **Step 1: Add the StackMode UI labels + the picker, and show the selected look**

In `CaptureView.swift`:
- Add `import StackEngineCore` at the top (to name `StackMode`).
- Add this extension at the bottom of the file:
```swift
extension StackMode {
    /// Short label shown in the capture-screen look-picker.
    var shortLabel: String {
        switch self {
        case .noiseReduction: return "Detail"
        case .smoothMotion:   return "Smooth"
        case .lightTrails:    return "Trails"
        case .lowLightBoost:  return "Night"
        }
    }
}
```
- Replace the placeholder `Text("Detail (Noise Reduction)")` branch's `else` content so the centre shows the selected look name:
```swift
                } else {
                    Text(coordinator.mode.shortLabel).foregroundColor(.white).font(.title3)
                }
```
- Insert the look-picker just above `statusLabel` in the `VStack`:
```swift
                lookPicker
                statusLabel
```
- Add the `lookPicker` computed view (e.g. after `statusLabel`):
```swift
    private var lookPicker: some View {
        HStack(spacing: 8) {
            ForEach(StackMode.allCases, id: \.self) { m in
                Button { coordinator.mode = m } label: {
                    Text(m.shortLabel)
                        .font(.caption)
                        .fontWeight(coordinator.mode == m ? .bold : .regular)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(coordinator.mode == m ? Color.white : Color.white.opacity(0.18))
                        .foregroundColor(coordinator.mode == m ? .black : .white)
                        .clipShape(Capsule())
                }
                .accessibilityIdentifier("look-\(m)")
                .disabled(isBusy)
            }
        }
        .padding(.bottom, 8)
    }
```

- [ ] **Step 2: Build and run in the Simulator**

Run: `xcodebuild -project StackStackStack/StackStackStack.xcodeproj -scheme StackStackStack -destination 'generic/platform=iOS Simulator' build`
Expected: `BUILD SUCCEEDED`.
Then run in the Simulator: the four look chips (Detail / Smooth / Trails / Night) appear above the status; tapping one selects it (bold + filled), and the centre label updates. Tap a look, then the shutter → a stacked result for that look appears.

- [ ] **Step 3: Run the app unit + UI tests**

Run: `xcodebuild test -project StackStackStack/StackStackStack.xcodeproj -scheme StackStackStack -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:StackStackStackTests -only-testing:StackStackStackUITests/StackFlowUITests`
Expected: TEST SUCCEEDED (default-look shutter flow still works; the new coordinator test passes).

- [ ] **Step 4: Commit**

```bash
git add StackStackStack/StackStackStack/UI/CaptureView.swift
git commit -m "feat(app): look-picker to choose Detail/Smooth/Trails/Night"
```

---

## Self-review

**1. Spec coverage (design §13.3 long-exposure family):**
- Smooth motion = temporal mean → Task 2 (`mean`).
- Short light trails = lighten/max → Task 3 (`lighten`).
- Low-light boost = robust accumulation + gain → Task 4 (`boostedMean`).
- One align step, mode-selected reducer → Task 5 (`alignedStack` + `reduceImages`/`reduce(mode:)`).
- App selects the look → Tasks 6–7 (coordinator `mode` + look-picker).
- **Intentionally deferred** (future plan, per roadmap §19): the *continuous-burst / duration* capture recipe (this phase reduces the same burst), Pro manual controls, and the editor.

**2. Placeholder scan:** no TBD/TODO/"handle errors"; every code step is complete.

**3. Type consistency:** `StackMode` (cases `noiseReduction`/`smoothMotion`/`lightTrails`/`lowLightBoost`, `CaseIterable`, `Hashable`) is used identically in `StackReducer` callers, `Pipeline.reduceImages`/`reduce`, the coordinator's `mode`, and `CaptureView`'s picker + `shortLabel`. `StackReducer.mean`/`lighten`/`boostedMean(_:gain:)`, `Pipeline.reduceImages(_:mode:searchRange:)`/`reduce(_:mode:searchRange:)`/`alignedStack(_:searchRange:)`, and the preserved `noiseReductionImages(_:searchRange:kappa:)` all match across tasks.

---

## Definition of done

- `cd Packages/StackEngineCore && swift test` → all green (existing + new reducer/mode tests).
- App unit + UI tests green.
- Simulator: four look chips selectable; shooting each produces a distinct stacked result (Smooth = blended, Trails = brightest-wins, Night = brighter, Detail = clean).
