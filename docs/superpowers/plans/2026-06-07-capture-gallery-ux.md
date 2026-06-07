# Capture & Gallery UX Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix portrait shots saving rotated, give the capture result preview a dismiss control, show live capture progress (photos counter + seconds countdown), and add pinch-zoom + persistent 90° rotate to the gallery viewer.

**Architecture:** A shared platform-free quarter-turn rotation helper in `StackEngineCore` underpins both the capture-orientation fix (rotate the stacked result to upright before save) and a new non-destructive `quarterTurns` adjustment (gallery rotate). The capture services gain an `onProgress` callback; the coordinator publishes capture progress; the gallery viewer wraps the image in a UIScrollView for zoom.

**Tech Stack:** Swift / SwiftUI / UIKit / AVFoundation (app); pure-Swift + simd (engine). XCTest.

**Spec:** `docs/superpowers/specs/2026-06-07-capture-gallery-ux-design.md`

---

## File Structure
- Create `Packages/StackEngineCore/Sources/StackEngineCore/ImageGeometry.swift` — quarter-turn rotation.
- Modify `Packages/StackEngineCore/Sources/StackEngineCore/ImageAdjustments.swift` — `quarterTurns` field.
- Modify `Packages/StackEngineCore/Sources/StackEngineCore/ImageEditor.swift` — apply `quarterTurns`.
- Create `StackStackStack/StackStackStack/Capture/CaptureOrientation.swift` — device-orientation → quarter-turns.
- Modify `StackStackStack/StackStackStack/StackCaptureCoordinator.swift` — orientation snapshot, `dismissResult()`, capture progress.
- Modify `StackStackStack/StackStackStack/Capture/CaptureService.swift`, `AVCaptureService.swift`, `FakeCaptureService.swift` — `onProgress`.
- Modify `StackStackStack/StackStackStack/UI/CaptureView.swift` — ✕ dismiss, progress overlay, hide sliders while capturing.
- Create `StackStackStack/StackStackStack/UI/ZoomableScrollView.swift` — pinch-zoom/pan.
- Modify `StackStackStack/StackStackStack/UI/PhotoDetailView.swift` — zoom + rotate buttons.
- Tests: engine `ImageGeometryTests.swift`, `ImageEditorTests.swift`, `ImageAdjustmentsTests.swift`; app `CaptureOrientationTests.swift`, `CoordinatorTests.swift`.

**Test/build commands** (use `iPhone 17 Pro`; `iPhone 16` is not installed):
- Engine: `cd Packages/StackEngineCore && swift test --filter <Class>`
- App test: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:StackStackStackTests/<Class>`
- App build: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build`
- (After `cd StackStackStack`, the shell cwd changes — use `git -C /Users/davidneto/photo-stack-app` for git.)

---

## Task 1: Engine — quarter-turn rotation

**Files:** Create `Packages/StackEngineCore/Sources/StackEngineCore/ImageGeometry.swift`; create `Packages/StackEngineCore/Tests/StackEngineCoreTests/ImageGeometryTests.swift`.

- [ ] **Step 1: Write the failing tests**

Create `ImageGeometryTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class ImageGeometryTests: XCTestCase {
    // 3×2 image, each pixel uniquely valued by its row-major index.
    private func sample(_ w: Int = 3, _ h: Int = 2) -> PixelImage {
        PixelImage(width: w, height: h,
                   pixels: (0..<(w*h)).map { SIMD3(Float($0), Float($0) * 2, Float($0) * 3) })
    }

    func testZeroAndFourTurnsAreIdentity() {
        let img = sample()
        XCTAssertEqual(ImageGeometry.rotated(img, quarterTurns: 0), img)
        XCTAssertEqual(ImageGeometry.rotated(img, quarterTurns: 4), img)
        XCTAssertEqual(ImageGeometry.rotated(img, quarterTurns: -4), img)
    }

    func testOneTurnSwapsDimensionsAndMapsTopLeftToTopRight() {
        let img = sample(3, 2)                 // w=3, h=2
        let r = ImageGeometry.rotated(img, quarterTurns: 1)   // 90° clockwise
        XCTAssertEqual(r.width, 2)
        XCTAssertEqual(r.height, 3)
        XCTAssertEqual(r[r.width - 1, 0], img[0, 0])   // source top-left → rotated top-right
    }

    func testTwoTurnsIs180() {
        let img = sample(3, 2)
        let r = ImageGeometry.rotated(img, quarterTurns: 2)
        XCTAssertEqual(r.width, 3); XCTAssertEqual(r.height, 2)
        XCTAssertEqual(r[img.width - 1, img.height - 1], img[0, 0])
    }

    func testNegativeWrapsToThree() {
        let img = sample()
        XCTAssertEqual(ImageGeometry.rotated(img, quarterTurns: -1),
                       ImageGeometry.rotated(img, quarterTurns: 3))
    }
}
```

- [ ] **Step 2: Run, expect FAIL** — `cd Packages/StackEngineCore && swift test --filter ImageGeometryTests` (compile error: `ImageGeometry` missing).

- [ ] **Step 3: Implement** `ImageGeometry.swift`:
```swift
import simd

/// Lossless 90°-multiple rotation of a `PixelImage` — used to bake capture orientation upright and
/// for the editor's quarter-turn rotate. Deterministic (a pure index remap); platform-free.
public enum ImageGeometry {
    /// Rotate clockwise by `quarterTurns × 90°` (normalized mod 4; negatives wrap). 0 → a copy;
    /// 1 and 3 swap width/height.
    public static func rotated(_ img: PixelImage, quarterTurns: Int) -> PixelImage {
        let k = ((quarterTurns % 4) + 4) % 4
        if k == 0 { return img }
        let w = img.width, h = img.height
        let src = img.pixels
        if k == 2 {
            var out = PixelImage(width: w, height: h)
            for y in 0..<h { for x in 0..<w { out.pixels[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x] } }
            return out
        }
        var out = PixelImage(width: h, height: w)   // 90° (k==1) or 270° (k==3): dimensions swap
        for y in 0..<h {
            for x in 0..<w {
                let nx: Int, ny: Int
                if k == 1 { nx = h - 1 - y; ny = x }           // 90° clockwise
                else      { nx = y;         ny = w - 1 - x }   // 270° clockwise (90° counter-clockwise)
                out.pixels[ny * h + nx] = src[y * w + x]       // out.width == h
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run, expect PASS** — `cd Packages/StackEngineCore && swift test --filter ImageGeometryTests`.

- [ ] **Step 5: Commit**
```bash
git -C /Users/davidneto/photo-stack-app add Packages/StackEngineCore/Sources/StackEngineCore/ImageGeometry.swift Packages/StackEngineCore/Tests/StackEngineCoreTests/ImageGeometryTests.swift
git -C /Users/davidneto/photo-stack-app commit -m "feat(engine): ImageGeometry.rotated (quarter-turn rotation)"
```

---

## Task 2: `ImageAdjustments.quarterTurns` + `ImageEditor`

**Files:** Modify `ImageAdjustments.swift`, `ImageEditor.swift`; test `Packages/StackEngineCore/Tests/StackEngineCoreTests/ImageEditorTests.swift` (add) and `.../ImageAdjustmentsTests.swift` (add, create if absent).

- [ ] **Step 1: Write the failing tests**

Add to `ImageEditorTests.swift` (a new test method):
```swift
    func testQuarterTurnRotatesViaImageGeometry() {
        let img = PixelImage(width: 3, height: 2,
                             pixels: (0..<6).map { SIMD3(Float($0), 0, 0) })
        let adj = ImageAdjustments(quarterTurns: 1)
        XCTAssertEqual(ImageEditor.apply(adj, to: img), ImageGeometry.rotated(img, quarterTurns: 1))
    }

    func testQuarterTurnMakesNonIdentity() {
        XCTAssertFalse(ImageAdjustments(quarterTurns: 1).isIdentity)
        XCTAssertTrue(ImageAdjustments(quarterTurns: 0).isIdentity)
    }
```

Create/append `ImageAdjustmentsTests.swift`:
```swift
import XCTest
@testable import StackEngineCore

final class ImageAdjustmentsTests: XCTestCase {
    func testQuarterTurnsCodableRoundTrip() throws {
        let adj = ImageAdjustments(quarterTurns: 3)
        let data = try JSONEncoder().encode(adj)
        let back = try JSONDecoder().decode(ImageAdjustments.self, from: data)
        XCTAssertEqual(back.quarterTurns, 3)
        XCTAssertEqual(back, adj)
    }

    func testQuarterTurnsBackCompatDefaultsToZero() throws {
        // A sidecar written before quarterTurns existed has no such key.
        let json = #"{"exposureEV":0,"contrast":0,"temperature":0,"tint":0,"shadows":0,"highlights":0,"straightenDegrees":0,"cropAspect":"original"}"#
        let back = try JSONDecoder().decode(ImageAdjustments.self, from: Data(json.utf8))
        XCTAssertEqual(back.quarterTurns, 0)
    }
}
```

- [ ] **Step 2: Run, expect FAIL** — `cd Packages/StackEngineCore && swift test --filter ImageAdjustmentsTests` (compile error: no `quarterTurns`).

- [ ] **Step 3: Add `quarterTurns` to `ImageAdjustments`.** In `ImageAdjustments.swift`:

Add the stored property after `cropAspect`:
```swift
    public var quarterTurns: Int       // 90°×k clockwise rotation, 0…3 (gallery rotate)
```
Add the parameter to the memberwise `init` (after `cropAspect: CropAspect = .original`), normalizing into 0…3:
```swift
    public init(exposureEV: Float = 0, contrast: Float = 0, temperature: Float = 0, tint: Float = 0,
                shadows: Float = 0, highlights: Float = 0, straightenDegrees: Float = 0,
                cropAspect: CropAspect = .original, quarterTurns: Int = 0) {
        self.exposureEV = exposureEV
        self.contrast = contrast
        self.temperature = temperature
        self.tint = tint
        self.shadows = shadows
        self.highlights = highlights
        self.straightenDegrees = straightenDegrees
        self.cropAspect = cropAspect
        self.quarterTurns = ((quarterTurns % 4) + 4) % 4
    }
```
In the custom `init(from decoder:)`, add a back-compat decode (after the `cropAspect` line):
```swift
        let rawTurns = try c.decodeIfPresent(Int.self, forKey: .quarterTurns) ?? 0
        quarterTurns = ((rawTurns % 4) + 4) % 4
```
(`CodingKeys` is auto-synthesized from the stored properties, so `.quarterTurns` exists; `encode(to:)` is auto-synthesized and includes it.)

- [ ] **Step 4: Apply it in `ImageEditor`.** In `ImageEditor.apply`, add the quarter-turn as the first geometry step:
```swift
        if adj.isIdentity { return img }
        var out = img
        if adj.quarterTurns != 0 { out = ImageGeometry.rotated(out, quarterTurns: adj.quarterTurns) }
        if adj.straightenDegrees != 0 { out = straighten(out, degrees: adj.straightenDegrees) }
        if adj.cropAspect.ratio != nil { out = crop(out, aspect: adj.cropAspect) }
        return adj.hasTonalAdjustments ? tonal(adj, out) : out
```

- [ ] **Step 5: Run, expect PASS** — `cd Packages/StackEngineCore && swift test --filter ImageAdjustmentsTests --filter ImageEditorTests` (run each filter; both green), then the full suite `swift test`.

- [ ] **Step 6: Commit**
```bash
git -C /Users/davidneto/photo-stack-app add Packages/StackEngineCore/Sources/StackEngineCore/ImageAdjustments.swift Packages/StackEngineCore/Sources/StackEngineCore/ImageEditor.swift Packages/StackEngineCore/Tests/StackEngineCoreTests/ImageEditorTests.swift Packages/StackEngineCore/Tests/StackEngineCoreTests/ImageAdjustmentsTests.swift
git -C /Users/davidneto/photo-stack-app commit -m "feat(engine): non-destructive quarterTurns adjustment"
```

---

## Task 3: #1 Capture orientation — save upright

**Files:** Create `StackStackStack/StackStackStack/Capture/CaptureOrientation.swift`; modify `StackCaptureCoordinator.swift`, `CaptureView.swift`; test `StackStackStack/StackStackStackTests/CaptureOrientationTests.swift`.

- [ ] **Step 1: Write the failing test** — `CaptureOrientationTests.swift`:
```swift
import XCTest
import UIKit
@testable import StackStackStack

final class CaptureOrientationTests: XCTestCase {
    func testMapping() {
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .portrait), 1)
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .portraitUpsideDown), 3)
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .landscapeLeft), 2)
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .landscapeRight), 0)
        // Indeterminate orientations default to portrait (the common hold).
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .faceUp), 1)
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .unknown), 1)
    }
}
```

- [ ] **Step 2: Run, expect FAIL** — `cd StackStackStack && xcodebuild test … -only-testing:StackStackStackTests/CaptureOrientationTests` (`CaptureOrientation` missing).

- [ ] **Step 3: Create `CaptureOrientation.swift`:**
```swift
import UIKit

/// Maps the physical device orientation at shutter time to the clockwise quarter-turns needed to make
/// the back camera's native-landscape stacked result upright. The exact constants are validated on a
/// physical device (the back wide-camera buffer is landscape-native); see the plan's device-verify step.
enum CaptureOrientation {
    static func quarterTurns(for orientation: UIDeviceOrientation) -> Int {
        switch orientation {
        case .portrait:           return 1
        case .portraitUpsideDown: return 3
        case .landscapeLeft:      return 2
        case .landscapeRight:     return 0
        default:                  return 1   // faceUp/faceDown/unknown → assume portrait
        }
    }
}
```

- [ ] **Step 4: Snapshot orientation + thread it through the coordinator.** In `StackCaptureCoordinator.swift`, add `import UIKit` at the top (alongside the other imports). In `shoot()`, snapshot the orientation right after capturing `mode` and rotate the recipe computation to a local (so the total is available later in Task 5 too):
```swift
        let mode = self.mode                 // capture the selected look/overrides at shutter-press time
        let orientationTurns = CaptureOrientation.quarterTurns(for: UIDevice.current.orientation)
```
Change the `enqueueProcessing(frames: frames, mode: mode)` call to:
```swift
        enqueueProcessing(frames: frames, mode: mode, orientationQuarterTurns: orientationTurns)
```
Change `enqueueProcessing`'s signature and its `makeJPEG` call:
```swift
    private func enqueueProcessing(frames: [RawSensorFrame], mode: StackMode, orientationQuarterTurns: Int) {
```
and inside, the `makeJPEG` call becomes:
```swift
                let jpeg = try await Self.makeJPEG(from: frames, mode: mode,
                                                   orientationQuarterTurns: orientationQuarterTurns,
                                                   shouldCancel: { token.isCancelled })
```
Change `makeJPEG` to accept and apply the rotation:
```swift
    nonisolated private static func makeJPEG(from frames: [RawSensorFrame], mode: StackMode,
                                             orientationQuarterTurns: Int,
                                             shouldCancel: @escaping @Sendable () -> Bool) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            let result: PixelImage
            if mode.isLongExposure {
                result = try Pipeline.reduceStreaming(frames, mode: mode,
                                                      workingResolution: managedWorkingResolution,
                                                      binnedDevelop: true, shouldCancel: shouldCancel)
            } else {
                let developed = Pipeline.developedFrames(frames, binnedDevelop: true,
                                                         workingResolution: managedWorkingResolution)
                if shouldCancel() { throw CancellationError() }
                if dumpFramesForDiagnostics { dumpDevelopedFrames(developed) }
                result = Pipeline.reduceImages(developed, mode: mode)
            }
            let oriented = ImageGeometry.rotated(result, quarterTurns: orientationQuarterTurns)   // bake upright
            let rgba = OutputTransform.encodeSRGB8(oriented)
            return try ImageEncoder.encode(rgba8: rgba, width: oriented.width, height: oriented.height,
                                           format: .jpeg, quality: 0.95)
        }.value
    }
```

- [ ] **Step 5: Generate device-orientation notifications while the capture screen is up.** In `CaptureView.swift`, add to the `body`'s `.task` modifier area an `.onAppear`/`.onDisappear` pair (place after the existing `.task { … }` line):
```swift
        .onAppear { UIDevice.current.beginGeneratingDeviceOrientationNotifications() }
        .onDisappear { UIDevice.current.endGeneratingDeviceOrientationNotifications() }
```
(`UIDevice.current.orientation` is only valid while generation is on.)

- [ ] **Step 6: Run tests + build** — `cd StackStackStack && xcodebuild test … -only-testing:StackStackStackTests/CaptureOrientationTests` (PASS), then `… -only-testing:StackStackStackTests/CoordinatorTests` (existing tests still PASS — Simulator orientation maps to a value but the Fake result rotates losslessly), then a full `build`.

- [ ] **Step 7: Commit**
```bash
git -C /Users/davidneto/photo-stack-app add StackStackStack/StackStackStack/Capture/CaptureOrientation.swift StackStackStack/StackStackStack/StackCaptureCoordinator.swift StackStackStack/StackStackStack/UI/CaptureView.swift StackStackStack/StackStackStackTests/CaptureOrientationTests.swift
git -C /Users/davidneto/photo-stack-app commit -m "feat(capture): bake device orientation into the saved result (portrait fix)"
```

---

## Task 4: #2 Dismiss the capture result preview

**Files:** Modify `StackCaptureCoordinator.swift`, `CaptureView.swift`; test `CoordinatorTests.swift`.

- [ ] **Step 1: Write the failing test** — add to `CoordinatorTests`:
```swift
    @MainActor
    func testDismissResultClearsPreview() async throws {
        let (coord, _) = makeCoordinator()
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertNotNil(coord.lastResultJPEG)
        XCTAssertNotNil(coord.lastSavedID)
        coord.dismissResult()
        XCTAssertNil(coord.lastResultJPEG, "dismiss clears the result preview")
        XCTAssertNil(coord.lastSavedID)
    }
```

- [ ] **Step 2: Run, expect FAIL** — `… -only-testing:StackStackStackTests/CoordinatorTests/testDismissResultClearsPreview` (`dismissResult` missing).

- [ ] **Step 3: Add `dismissResult()`** in `StackCaptureCoordinator.swift` (e.g. after `shoot()`):
```swift
    /// Clear the on-screen result preview, returning the capture screen to the live viewfinder.
    func dismissResult() {
        lastResultJPEG = nil
        lastSavedID = nil
    }
```

- [ ] **Step 4: Add the ✕ control** in `CaptureView.swift`. Replace the result-preview block:
```swift
                if let img = lastResult {
                    VStack {
                        Image(uiImage: img).resizable().scaledToFit()
                        if coordinator.lastSavedID != nil {
                            Button("Edit") { openEditor() }
                                .buttonStyle(.bordered).tint(.white)
                        }
                    }.padding()
                } else {
                    Text(coordinator.mode.shortLabel).foregroundColor(.white).font(.title3)
                }
```
with (an HStack of Edit + Done under the image):
```swift
                if let img = lastResult {
                    VStack {
                        Image(uiImage: img).resizable().scaledToFit()
                        HStack {
                            if coordinator.lastSavedID != nil {
                                Button("Edit") { openEditor() }.buttonStyle(.bordered).tint(.white)
                            }
                            Button("Done") { coordinator.dismissResult() }
                                .buttonStyle(.bordered).tint(.white)
                                .accessibilityIdentifier("dismiss-result")
                        }
                    }.padding()
                } else {
                    Text(coordinator.mode.shortLabel).foregroundColor(.white).font(.title3)
                }
```

- [ ] **Step 5: Run + build** — `… -only-testing:StackStackStackTests/CoordinatorTests` (PASS) then `build` (SUCCEEDED).

- [ ] **Step 6: Commit**
```bash
git -C /Users/davidneto/photo-stack-app add StackStackStack/StackStackStack/StackCaptureCoordinator.swift StackStackStack/StackStackStack/UI/CaptureView.swift StackStackStack/StackStackStackTests/CoordinatorTests.swift
git -C /Users/davidneto/photo-stack-app commit -m "feat(ui): Done button to dismiss the capture result preview"
```

---

## Task 5: #3 Live capture feedback (counter + countdown)

**Files:** Modify `CaptureService.swift`, `AVCaptureService.swift`, `FakeCaptureService.swift`, `StackCaptureCoordinator.swift`, `CaptureView.swift`; test `CoordinatorTests.swift`.

- [ ] **Step 1: Write the failing test** — add to `CoordinatorTests`:
```swift
    @MainActor
    func testCapturePublishesProgress() async throws {
        let (coord, _) = makeCoordinator()
        coord.mode = .noiseReduction          // Detail: fixed 8-frame burst
        await coord.shoot()                   // Fake fires onProgress per synthesized frame
        XCTAssertEqual(coord.captureTotal, 8, "total reflects the recipe frame count")
        XCTAssertEqual(coord.capturedCount, 8, "counter reaches the captured frame count")
        await coord.awaitProcessing()
    }
```

- [ ] **Step 2: Run, expect FAIL** — `… /testCapturePublishesProgress` (`captureTotal`/`capturedCount` missing).

- [ ] **Step 3: Add `onProgress` to the capture protocol + services.**

In `CaptureService.swift`, change the protocol requirement and add a convenience overload. Replace the `captureBurst(recipe:isSteady:)` requirement with:
```swift
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool,
                      onProgress: (@Sendable (Int) -> Void)?) async throws -> [RawSensorFrame]
```
In the `extension CaptureService`, keep the existing `captureBurst(recipe:)` and add an `isSteady`-only overload so existing call sites compile:
```swift
extension CaptureService {
    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
        try await captureBurst(recipe: recipe, isSteady: { true }, onProgress: nil)
    }
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool) async throws -> [RawSensorFrame] {
        try await captureBurst(recipe: recipe, isSteady: isSteady, onProgress: nil)
    }
}
```

In `FakeCaptureService.swift`, change the signature and fire `onProgress` per frame:
```swift
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool,
                      onProgress: (@Sendable (Int) -> Void)?) async throws -> [RawSensorFrame] {
        await Task.yield()
        let n = max(recipe.frameCount, 1)
        return (0..<n).map { k in
            // … existing mosaic-building body unchanged …
            let frame = RawSensorFrame(width: width, height: height, mosaic: mosaic,
                                       blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
                                       wbGains: SIMD3<Float>(1, 1, 1))
            onProgress?(k + 1)
            return frame
        }
    }
```
(Keep the existing mosaic-building lines; just capture the frame in a `let`, call `onProgress?(k + 1)`, and `return frame`.)

In `AVCaptureService.swift`: change the `captureBurst` signature to add `onProgress`, store it on `stateQueue` alongside the other burst state, and fire it when a frame is appended.
- Signature: `func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool, onProgress: (@Sendable (Int) -> Void)?) async throws -> [RawSensorFrame] {`
- Add a stored property near `isSteadyCheck`: `private var onProgress: (@Sendable (Int) -> Void)?`
- In the `stateQueue.async` setup block (where `isSteadyCheck = isSteady` is set), add: `self.onProgress = onProgress`
- In `photoOutput(_:didFinishProcessingPhoto:…)`, in the inner `stateQueue.async` block, right after `if let frame { self.pending.append(frame) }`, add: `if frame != nil { self.onProgress?(self.pending.count) }`

- [ ] **Step 4: Publish progress on the coordinator.** In `StackCaptureCoordinator.swift`:

Add published state (after `aeAfLocked`):
```swift
    /// Live capture progress (drives the on-screen counter + countdown during the burst).
    @Published private(set) var capturedCount = 0
    @Published private(set) var captureTotal = 0
    @Published private(set) var captureRemainingSeconds = 0
    private var countdownTask: Task<Void, Never>?
```
Rework the capture section of `shoot()`. Replace from `isCapturing = true` through the `do { … } catch { … }` capture call with:
```swift
        let recipe = makeRecipe(for: mode)
        isCapturing = true
        capturedCount = 0
        captureTotal = recipe.frameCount
        captureRemainingSeconds = Int(ceil(recipe.durationSeconds))
        startCaptureCountdown()
        let gating: @Sendable () -> Bool
        if mode.isLongExposure {
            steadiness.start()
            gating = { [steadiness] in steadiness.isSteady }
        } else {
            gating = { true }
        }
        defer {
            if mode.isLongExposure { steadiness.stop() }
            countdownTask?.cancel(); countdownTask = nil
        }
        let progress: @Sendable (Int) -> Void = { [weak self] n in
            Task { @MainActor in self?.capturedCount = n }
        }
        let frames: [RawSensorFrame]
        do {
            frames = try await capture.captureBurst(recipe: recipe, isSteady: gating, onProgress: progress)
        } catch {
            lastError = error.localizedDescription
            isCapturing = false
            return
        }
        isCapturing = false
```
(`makeRecipe` is now called once here; remove the `makeRecipe(for: mode)` argument from the old `captureBurst` line since `recipe` is reused.) Add the countdown helper (e.g. after `dismissResult()`):
```swift
    /// One-second ticks decrementing `captureRemainingSeconds` to 0 while the burst runs.
    private func startCaptureCountdown() {
        countdownTask?.cancel()
        countdownTask = Task { [weak self] in
            while !Task.isCancelled, let s = self?.captureRemainingSeconds, s > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { break }
                self?.captureRemainingSeconds = max(0, (self?.captureRemainingSeconds ?? 1) - 1)
            }
        }
    }
```

- [ ] **Step 5: Show progress in `CaptureView`.** Add an overlay shown while capturing, and hide the burst sliders during capture.

Change the `burstSliders` guard so the sliders hide during the burst (the progress overlay takes their spot). Change:
```swift
        if coordinator.mode.isLongExposure {
```
(at the top of `burstSliders`) to:
```swift
        if coordinator.mode.isLongExposure && !coordinator.isCapturing {
```
Add a progress overlay view (e.g. after `burstSliders`):
```swift
    /// During a burst: photos-taken counter (left) and seconds-remaining countdown (right).
    @ViewBuilder private var captureProgressOverlay: some View {
        if coordinator.isCapturing {
            HStack(alignment: .top) {
                progressLabel("Photos", "\(coordinator.capturedCount)/\(coordinator.captureTotal)")
                    .accessibilityIdentifier("capture-photo-count")
                Spacer()
                progressLabel("Time", "\(coordinator.captureRemainingSeconds)s")
                    .accessibilityIdentifier("capture-time-remaining")
            }
            .padding(.horizontal, 16).padding(.top, 60)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .allowsHitTesting(false)
        }
    }

    private func progressLabel(_ title: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(title).font(.caption2).foregroundColor(.white.opacity(0.8))
            Text(value).font(.headline).bold().foregroundColor(.white)
        }
    }
```
Add it to the `ZStack` (after `burstSliders`):
```swift
            burstSliders
            captureProgressOverlay
```

- [ ] **Step 6: Run tests + build** — `… -only-testing:StackStackStackTests/CoordinatorTests` (PASS, incl. the new progress test + existing) and `… -only-testing:StackStackStackTests/FakeCaptureServiceTests` (the Fake signature change must keep these green) then `build`.

- [ ] **Step 7: Commit**
```bash
git -C /Users/davidneto/photo-stack-app add StackStackStack/StackStackStack/Capture/CaptureService.swift StackStackStack/StackStackStack/Capture/AVCaptureService.swift StackStackStack/StackStackStack/Capture/FakeCaptureService.swift StackStackStack/StackStackStack/StackCaptureCoordinator.swift StackStackStack/StackStackStack/UI/CaptureView.swift StackStackStack/StackStackStackTests/CoordinatorTests.swift
git -C /Users/davidneto/photo-stack-app commit -m "feat(capture): live capture progress (photos counter + seconds countdown)"
```

---

## Task 6: #4 Gallery zoom

**Files:** Create `StackStackStack/StackStackStack/UI/ZoomableScrollView.swift`; modify `PhotoDetailView.swift`. (Build-verified; device-verified for the gestures.)

- [ ] **Step 1: Create `ZoomableScrollView.swift`:**
```swift
import SwiftUI
import UIKit

/// Pinch-to-zoom + pan + double-tap-to-zoom for a single image, backed by `UIScrollView` (correct
/// zoom bounds / pan / inertia that hand-rolled SwiftUI gestures don't give).
struct ZoomableScrollView: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.delegate = context.coordinator
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 4
        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false
        scroll.backgroundColor = .black
        let iv = context.coordinator.imageView
        iv.contentMode = .scaleAspectFit
        iv.image = image
        scroll.addSubview(iv)
        let doubleTap = UITapGestureRecognizer(target: context.coordinator,
                                               action: #selector(Coordinator.handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(doubleTap)
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        context.coordinator.imageView.image = image
        // Reset to fit on (re)appear/size change.
        context.coordinator.imageView.frame = CGRect(origin: .zero, size: scroll.bounds.size)
        scroll.contentSize = scroll.bounds.size
        scroll.zoomScale = 1
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        let imageView = UIImageView()
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
        @objc func handleDoubleTap(_ g: UITapGestureRecognizer) {
            guard let scroll = g.view as? UIScrollView else { return }
            if scroll.zoomScale > scroll.minimumZoomScale {
                scroll.setZoomScale(scroll.minimumZoomScale, animated: true)
            } else {
                let p = g.location(in: imageView)
                scroll.zoom(to: CGRect(x: p.x - 50, y: p.y - 50, width: 100, height: 100), animated: true)
            }
        }
    }
}
```

- [ ] **Step 2: Use it in `PhotoDetailView`.** Replace:
```swift
                if let image {
                    Image(uiImage: image).resizable().scaledToFit()
                } else if loaded {
```
with:
```swift
                if let image {
                    ZoomableScrollView(image: image).ignoresSafeArea()
                } else if loaded {
```

- [ ] **Step 3: Build** — `cd StackStackStack && xcodebuild … build` → SUCCEEDED.

- [ ] **Step 4: Commit**
```bash
git -C /Users/davidneto/photo-stack-app add StackStackStack/StackStackStack/UI/ZoomableScrollView.swift StackStackStack/StackStackStack/UI/PhotoDetailView.swift
git -C /Users/davidneto/photo-stack-app commit -m "feat(ui): pinch-to-zoom + pan in the gallery viewer"
```

---

## Task 7: #5 Gallery rotate (persistent 90°)

**Files:** Modify `PhotoDetailView.swift`. (Build-verified; the rotation math is covered by Tasks 1–2.)

- [ ] **Step 1: Add rotate state + a persist helper** to `PhotoDetailView`. Add a property:
```swift
    @State private var rotating = false
```
Add a method (near `delete()`):
```swift
    /// Persist a 90° rotation as a non-destructive `quarterTurns` adjustment: render the original
    /// through the updated adjustments off-main, then save via the same path as the editor.
    private func rotate(by delta: Int) {
        guard !rotating else { return }
        rotating = true
        let id = record.id, lib = store
        Task {
            let result: (ImageAdjustments, Data)? = await Task.detached(priority: .userInitiated) {
                guard let original = lib.originalData(for: id) else { return nil }
                var adj = lib.adjustments(for: id)
                adj.quarterTurns = ((adj.quarterTurns + delta) % 4 + 4) % 4
                guard let rendered = ResultRenderer.render(originalJPEG: original, adjustments: adj, quality: 0.95)
                else { return nil }
                return (adj, rendered)
            }.value
            rotating = false
            guard let (adj, rendered) = result else { return }
            do {
                try lib.applyEdit(id: id, adjustments: adj, renderedJPEG: rendered)
                image = UIImage(data: rendered)   // reflect in the viewer
                onChanged()                        // refresh the gallery
            } catch { /* leave the current image; a transient render/save failure is non-fatal here */ }
        }
    }
```

- [ ] **Step 2: Add rotate buttons** to the toolbar. In the `ToolbarItemGroup(placement: .navigationBarTrailing)`, add two buttons before the Share button:
```swift
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button { rotate(by: -1) } label: { Image(systemName: "rotate.left") }
                        .accessibilityLabel("Rotate left").disabled(rotating)
                    Button { rotate(by: 1) } label: { Image(systemName: "rotate.right") }
                        .accessibilityLabel("Rotate right").disabled(rotating)
                    Button { sharing = true } label: { Image(systemName: "square.and.arrow.up") }
                        .accessibilityLabel("Share")
                    Button { openEditor() } label: { Image(systemName: "slider.horizontal.3") }
                        .accessibilityLabel("Edit")
                    Button(role: .destructive) { confirmingDelete = true } label: { Image(systemName: "trash") }
                        .accessibilityLabel("Delete")
                }
```

- [ ] **Step 3: Build** — `cd StackStackStack && xcodebuild … build` → SUCCEEDED.

- [ ] **Step 4: Commit**
```bash
git -C /Users/davidneto/photo-stack-app add StackStackStack/StackStackStack/UI/PhotoDetailView.swift
git -C /Users/davidneto/photo-stack-app commit -m "feat(ui): persistent 90° rotate in the gallery viewer"
```

---

## Task 8: Verification & PR

- [ ] **Step 1: Full engine suite** — `cd Packages/StackEngineCore && swift test` → 0 failures.
- [ ] **Step 2: Full app suite** — `cd StackStackStack && xcodebuild test … -only-testing:StackStackStackTests` → PASS.
- [ ] **Step 3: Code-review skill** (required per CLAUDE.md) on the branch diff; address findings.
- [ ] **Step 4: Open the PR**
```bash
git -C /Users/davidneto/photo-stack-app push -u origin feat/capture-gallery-ux
gh pr create --base main --head feat/capture-gallery-ux --title "Capture & gallery UX improvements" --body "$(cat <<'EOF'
Implements docs/superpowers/specs/2026-06-07-capture-gallery-ux-design.md.

- Portrait shots save upright (device orientation baked into the result).
- Done button to dismiss the capture result preview.
- Live capture feedback: photos-taken counter (left) + seconds countdown (right).
- Gallery pinch-to-zoom + pan + double-tap.
- Persistent 90° rotate in the gallery (non-destructive quarterTurns adjustment).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
- [ ] **Step 5: Device verification (mobile-mcp)** — portrait & landscape shots save upright (confirm/adjust the `CaptureOrientation` constants); Done clears the preview; live counter + countdown during a Smooth burst; pinch-zoom/pan/double-tap; rotate-left/right persists across viewer close/reopen.

---

## Notes
- **`CaptureOrientation` constants are a best-guess pending device verification** (Task 8 Step 5). The back-camera buffer is landscape-native; if portrait still looks rotated on device, adjust the four `switch` return values (the mapping function is the single place to change, and its unit test pins whatever mapping we land on).
- The orientation fix (#1) and the gallery rotate (#5) both go through `ImageGeometry.rotated`, so they stay consistent.
