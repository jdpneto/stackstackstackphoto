# Capture Safeguards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thermal/battery/storage capture policy + the non-RAW "Standard quality" HEIC capture fallback. Spec: `docs/superpowers/specs/2026-06-11-capture-safeguards-design.md`.

**Architecture:** `CaptureEnvironment` (injectable closures) feeds shutter-press policy in the coordinator; `CapturedBurst` (.raw/.developed) lets `AVCaptureService` fall back to HEIC capture on non-RAW hardware, with `.developed` routed through the existing images pipeline. Branch `feat/capture-safeguards`. Sim via `xcrun simctl list devices available | grep -i iphone | head -3`; preflight/Busy with zero failing cases = infra, retry. Stage exact paths; CLAUDE.md untracked.

---

### Task 1: `CaptureEnvironment` + coordinator policy

**Files:**
- Create: `StackStackStack/StackStackStack/Capture/CaptureEnvironment.swift`
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`, `UI/CaptureView.swift` (statusLabel/environment note line)
- Test: `StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: failing tests** (READ CoordinatorTests; `makeCoordinator` exists):
```swift
    @MainActor
    func testCriticalThermalBlocksTheShot() async throws {
        let (coord, store) = makeCoordinator()
        coord.environment = CaptureEnvironment(thermalState: { .critical }, batteryLevel: { 1 },
                                               batteryCharging: { false }, freeDiskBytes: { .max })
        await coord.shoot()
        XCTAssertEqual(coord.lastError, "Too hot — let the phone cool down.")
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().count, 0)
    }

    @MainActor
    func testSeriousThermalHalvesTheBurst() async throws {
        let (coord, store) = makeCoordinator()
        coord.environment = CaptureEnvironment(thermalState: { .serious }, batteryLevel: { 1 },
                                               batteryCharging: { false }, freeDiskBytes: { .max })
        coord.mode = .noiseReduction                      // base recipe = 8 frames
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 4, "serious thermal halves the burst")
        XCTAssertNotNil(coord.environmentNote)
    }

    @MainActor
    func testLowStorageBlocksTheShot() async throws {
        let (coord, store) = makeCoordinator()
        coord.environment = CaptureEnvironment(thermalState: { .nominal }, batteryLevel: { 1 },
                                               batteryCharging: { false }, freeDiskBytes: { 50_000_000 })
        await coord.shoot()
        XCTAssertEqual(coord.lastError, "Not enough storage to capture.")
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().count, 0)
    }

    @MainActor
    func testLowBatteryWarnsButShoots() async throws {
        let (coord, store) = makeCoordinator()
        coord.environment = CaptureEnvironment(thermalState: { .nominal }, batteryLevel: { 0.05 },
                                               batteryCharging: { false }, freeDiskBytes: { .max })
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().count, 1, "low battery never blocks")
        XCTAssertEqual(coord.environmentNote, "Low battery")
    }
```

- [ ] **Step 2:** verify compile failure.

- [ ] **Step 3: implement.**

`Capture/CaptureEnvironment.swift` (new):
```swift
import Foundation
import UIKit

/// System conditions consulted at shutter press (spec 2026-06-11 §2). Closures so tests inject
/// states the simulator can't produce (thermal, battery, full disk).
struct CaptureEnvironment {
    var thermalState: () -> ProcessInfo.ThermalState
    var batteryLevel: () -> Float          // 0…1; -1 = unknown (simulator)
    var batteryCharging: () -> Bool
    var freeDiskBytes: () -> Int64

    /// Real system probes. Battery monitoring is enabled lazily on first read.
    @MainActor
    static func live() -> CaptureEnvironment {
        CaptureEnvironment(
            thermalState: { ProcessInfo.processInfo.thermalState },
            batteryLevel: {
                UIDevice.current.isBatteryMonitoringEnabled = true
                return UIDevice.current.batteryLevel
            },
            batteryCharging: {
                UIDevice.current.isBatteryMonitoringEnabled = true
                return UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
            },
            freeDiskBytes: {
                // A failed probe must never wrongly block the shutter — report "plenty".
                let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let cap = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                    .volumeAvailableCapacityForImportantUsage
                return cap ?? .max
            })
    }

    /// Free space below which capture is blocked (a 20-frame stack + result can need ~150 MB).
    static let minimumFreeBytes: Int64 = 200_000_000
    /// Battery fraction below which the UI warns (capture is never blocked on battery).
    static let lowBatteryThreshold: Float = 0.10
}
```
Coordinator: `var environment: CaptureEnvironment` (init default `.live()` — note coordinator is @MainActor so calling the @MainActor factory in the initializer default is fine; if the compiler objects, assign in init body), `@Published private(set) var environmentNote: String?`. At the TOP of `shoot()` (before isBusy guard? AFTER the isBusy guard, before clearing lastError):
```swift
        // Environment policy (spec 2026-06-11 §2): hard blocks first, then advisory notes.
        let thermal = environment.thermalState()
        if thermal == .critical { lastError = "Too hot — let the phone cool down."; return }
        if environment.freeDiskBytes() < CaptureEnvironment.minimumFreeBytes {
            lastError = "Not enough storage to capture."; return
        }
        let battery = environment.batteryLevel()
        if battery >= 0 && battery < CaptureEnvironment.lowBatteryThreshold && !environment.batteryCharging() {
            environmentNote = "Low battery"
        } else if thermal == .serious {
            environmentNote = "Device is warm — shorter bursts"
        } else {
            environmentNote = nil
        }
```
(NOTE: blocks set lastError and return BEFORE `lastError = nil` clearing — order carefully: do the policy check first, then the existing `lastError = nil` only on the non-blocked path... read the current shoot() ordering: it clears lastError early; restructure so the guards run after the isBusy guard and BEFORE the clears, returning early.) `makeRecipe` halving: add at the end, before returning, for ALL paths: `if environment.thermalState() == .serious { recipe.frameCount = max(2, recipe.frameCount / 2); if let s = recipe.focusSweep { recipe.focusSweep = .init(near: s.near, far: s.far, steps: recipe.frameCount) } }` — implement by making makeRecipe build `var recipe` and apply the clamp (sweep steps follow frameCount per the established invariant).
`CaptureView.statusLabel`: when `Ready` and `environmentNote != nil`, show `Text("Ready · \(note)")` (and keep the note visible alongside Saved ✓ analogously — lean: only on the Ready state).

- [ ] **Step 4:** CoordinatorTests + full unit bundle green (existing tests must pass — default env in `makeCoordinator` is `.live()` which in the SIMULATOR returns nominal/-1/plenty → no behavior change; verify batteryLevel -1 path doesn't warn).

- [ ] **Step 5: commit** `git add StackStackStack && git commit -m "feat(capture): environment policy — thermal block/halve, storage pre-flight, battery warning"`

---

### Task 2: `CapturedBurst` + non-RAW HEIC fallback

**Files:**
- Modify: `Capture/CaptureService.swift` (protocol + enum), `Capture/AVCaptureService.swift`, `Capture/FakeCaptureService.swift`, `StackCaptureCoordinator.swift`, `UI/CaptureView.swift` (Standard-quality tag)
- Test: `StackStackStackTests/CoordinatorTests.swift`, `FakeCaptureServiceTests.swift`

- [ ] **Step 1: failing tests:**
`CoordinatorTests` — a minimal developed-burst fake at file scope:
```swift
/// Fallback-path fake: returns already-developed frames, as a non-RAW device's HEIC path would.
private struct DevelopedFake: CaptureService {
    let width: Int, height: Int, count: Int
    func startPreview() async -> CALayer? { nil }
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool,
                      onProgress: (@Sendable (Int) -> Void)?) async throws -> CapturedBurst {
        await Task.yield()
        let n = min(recipe.frameCount, count)
        let imgs = (0..<n).map { k -> PixelImage in
            var img = PixelImage(width: width, height: height, fill: SIMD3<Float>(0.4, 0.4, 0.4))
            img[k % width, 0] = SIMD3<Float>(0.9, 0.9, 0.9)   // per-frame variation
            onProgress?(k + 1)
            return img
        }
        return .developed(imgs)
    }
    var supportsRAWCapture: Bool { false }
}
```
and tests:
```swift
    @MainActor
    func testDevelopedBurstSavesForStaticAndLongExposure() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: DevelopedFake(width: 16, height: 16, count: 20), store: store)
        for mode in [StackMode.noiseReduction, .smoothMotion] {
            coord.mode = mode
            await coord.shoot()
            await coord.awaitProcessing()
            XCTAssertNil(coord.lastError, "\(mode)")
        }
        XCTAssertEqual(try store.loadAll().count, 2)
        // Blendable look on the fallback path still stores a reference.
        let smooth = try XCTUnwrap(store.loadAll().first { $0.mode == "smoothMotion" })
        XCTAssertNotNil(store.referenceData(for: smooth.id))
    }

    @MainActor
    func testDevelopedBurstDepthSaves() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: DevelopedFake(width: 24, height: 16, count: 10), store: store)
        coord.mode = .depthOfField
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertNil(coord.lastError)
        XCTAssertEqual(try store.loadAll().first?.mode, "depthOfField")
    }
```
`FakeCaptureServiceTests`: existing tests destructure the burst — update call sites for the new return type (`guard case .raw(let frames) = burst`).

- [ ] **Step 2:** verify compile failure (CapturedBurst undefined; protocol return changed).

- [ ] **Step 3: implement.**

`CaptureService.swift`: 
```swift
/// What a burst produced: Bayer RAW frames (the quality path) or already-developed images (the
/// non-RAW "Standard quality" fallback, decoded at working resolution; spec 2026-06-11 §3).
enum CapturedBurst: Sendable {
    case raw([RawSensorFrame])
    case developed([PixelImage])

    var count: Int {
        switch self {
        case .raw(let f): return f.count
        case .developed(let i): return i.count
        }
    }
    var isEmpty: Bool { count == 0 }
}
```
Protocol `captureBurst` returns `CapturedBurst`; update the convenience overloads in the extension. `FakeCaptureService` wraps its existing returns in `.raw(...)`.

`AVCaptureService.swift` — the fallback branch replaces the `noRawFormat` throw:
- Burst-state init: instead of `guard let rawType ... else { throw }`, compute `let bayerType = rawTypes.first(where: { RawFrameConverter.isSupportedBayerFormat($0) })`; store `self.rawType = bayerType ?? 0` and a new stateQueue var `private var fallbackHEIC = false` = `bayerType == nil`. `pending` becomes storage for BOTH: add `private var pendingDeveloped: [PixelImage] = []`.
- Settings construction in `startNextFrameLocked`: `let settings = fallbackHEIC ? AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.hevc]) : AVCapturePhotoSettings(rawPixelFormatType: self.rawType)` (keep `.speed` prioritization + dims cap for both).
- Delegate conversion on `processingQueue`: when `fallbackHEIC`, `photo.fileDataRepresentation()` → `ImageDecoder.rgba8(from:maxPixel: Self.fallbackDecodeLongEdge /* 2400 */)` → `OutputTransform.decodeSRGB8(rgba, width: w, height: h)` (returns linear PixelImage — check the exact signature in OutputTransform; it exists, used by ResultRenderer) → append `pendingDeveloped`; progress uses `pendingDeveloped.count`.
- `finishLocked`: resume with `.raw(pending)` or `.developed(pendingDeveloped)`; empty → `noFramesProduced` as today; clear both arrays.
- NOTE: `supportsRAWCapture` probe already exists and reports false in this scenario.

`StackCaptureCoordinator.swift`: `shoot()` holds `let burst = try await capture.captureBurst(...)`; `guard !burst.isEmpty`; `enqueueProcessing(burst:...)`; `makeResult` switches:
```swift
            switch burst {
            case .raw(let frames):
                // …existing three-way mode branch, unchanged…
            case .developed(let images):
                // Fallback: already-developed working-res frames → images pipeline for every look.
                if mode == .depthOfField {
                    guard let stacked = FocusStacker.allInFocus(images, config: DepthConfig(workingResolution: depthWorkingResolution, maxFrames: max(images.count, 1)))
                    else { throw ProcessingError.focusStackFailed }
                    (resultImage, referenceImage) = (stacked, nil)
                } else {
                    let pair = Pipeline.reduceImagesWithReference(images, mode: mode, workingResolution: managedWorkingResolution)
                    (resultImage, referenceImage) = (pair.result, mode.supportsBlendReference ? pair.reference : nil)
                }
            }
```
(Adapt to makeResult's actual local structure — it currently produces `result` + `referencePixels` then orientation-bakes + encodes; restructure minimally so both cases feed the same tail. READ the function first. frameCount for store.save uses `burst.count`.)
`CaptureView`: small "Standard quality" caption near the look picker when `!coordinator.supportsRAW` (mirrors the Depth-unsupported caption pattern).

- [ ] **Step 4:** full unit bundle + StackFlowUITests green.

- [ ] **Step 5: commit** `git add StackStackStack && git commit -m "feat(capture): non-RAW HEIC fallback — CapturedBurst, working-res decode, images-pipeline routing, Standard quality tag"`

---

### Task 3: docs + suites (then controller runs /code-review + merges)

- [ ] Delta: TL;DR #4 → done-with-scope note (metering guidance explicitly remains; reference the spec); §16/§17 table rows updated (thermal/battery/storage/no-RAW rows → done; metering row stays Missing).
- [ ] Bible §17 table: append a one-line status note blockquote after the table ("Implemented 2026-06-11: thermal throttle/abort, storage pre-flight, low-battery warning, no-RAW standard-quality fallback — see capture-safeguards spec. Metering guidance still open.").
- [ ] All three suites green; commit docs.

## Self-review notes
- Spec coverage: §2→T1; §3→T2; §4 rows→T1 (probe-fail = plenty; battery -1 no warn) + T2 (decode-fail frame skip = existing conversion-skip path); §5→T1/T2 tests; metering deferral→T3 docs.
- Consistency: `CapturedBurst` Sendable (PixelImage/RawSensorFrame are Sendable — verify PixelImage is; engine types are Sendable); `environment` injectable var mirrors existing seams.
