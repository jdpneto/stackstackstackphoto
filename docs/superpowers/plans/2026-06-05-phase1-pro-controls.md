# Phase 1 — Pro Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manual "Pro" capture layer — override the look's **frame count**, and set manual **ISO / shutter / focus** — without touching the auto path.

**Architecture:** A `ProControls` value (all fields optional; `nil` = Auto) is merged onto the per-look `CaptureRecipe` via `recipe.applying(pro)`. The coordinator holds the live `ProControls` and applies it at shutter-press. `AVCaptureService` reads the recipe's manual exposure/focus fields and configures the device (custom exposure / locked lens position) — device-only, compile-verified. `FakeCaptureService` already honours `recipe.frameCount`, so the frame-count override is testable in the Simulator. `CaptureView` gains a collapsible Pro panel.

**Tech Stack:** Swift, AVFoundation (device), SwiftUI (app).

---

## File structure (this plan)

```
StackStackStack/StackStackStack/Capture/
  ProControls.swift     # CREATE — manual overrides value type
  CaptureService.swift  # MODIFY — CaptureRecipe manual fields + applying(_:)
  AVCaptureService.swift# MODIFY — apply manual exposure/focus from the recipe (device)
StackStackStack/StackStackStack/
  StackCaptureCoordinator.swift  # MODIFY — @Published pro; shoot applies it
StackStackStack/StackStackStack/UI/
  CaptureView.swift     # MODIFY — Pro panel (frames/ISO/shutter/focus)
StackStackStack/StackStackStackTests/
  CaptureRecipeTests.swift   # MODIFY — applying() merge
  CoordinatorTests.swift     # MODIFY — frame-count override end-to-end
```

---

## Task 1: `ProControls` + `CaptureRecipe.applying`

**Files:**
- Create: `StackStackStack/StackStackStack/Capture/ProControls.swift`
- Modify: `StackStackStack/StackStackStack/Capture/CaptureService.swift`
- Modify: `StackStackStack/StackStackStackTests/CaptureRecipeTests.swift`

- [ ] **Step 1: Failing tests** — append to `CaptureRecipeTests.swift` (inside the class):
```swift
    func testApplyingOverridesFrameCountOnly() {
        let base = CaptureRecipe.recipe(for: .noiseReduction)   // 8 frames, 0.5s
        let r = base.applying(ProControls(frameCount: 20))
        XCTAssertEqual(r.frameCount, 20)
        XCTAssertEqual(r.durationSeconds, base.durationSeconds)  // duration untouched
        XCTAssertNil(r.manualISO)
    }

    func testApplyingAutoLeavesRecipeUnchanged() {
        let base = CaptureRecipe.recipe(for: .lightTrails)
        XCTAssertEqual(base.applying(.auto), base)
    }

    func testApplyingPropagatesManualExposure() {
        let r = CaptureRecipe.recipe(for: .noiseReduction)
            .applying(ProControls(iso: 800, shutterSeconds: 0.02, focus: 0.5))
        XCTAssertEqual(r.manualISO, 800)
        XCTAssertEqual(r.manualShutterSeconds, 0.02)
        XCTAssertEqual(r.manualFocus, 0.5)
    }

    func testApplyingClampsFrameCountToAtLeastOne() {
        let r = CaptureRecipe.recipe(for: .noiseReduction).applying(ProControls(frameCount: 0))
        XCTAssertEqual(r.frameCount, 1)
    }
```

- [ ] **Step 2: Run → FAIL** (`cannot find 'ProControls'`):
`xcodebuild test -project StackStackStack/StackStackStack.xcodeproj -scheme StackStackStack -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:StackStackStackTests/CaptureRecipeTests`

- [ ] **Step 3a: Create `ProControls.swift`:**
```swift
/// Manual capture overrides (design §11, Pro mode). A `nil` field means Auto for that control.
struct ProControls: Sendable, Equatable {
    var frameCount: Int?        // override the look's burst length
    var iso: Double?            // manual sensor gain (ISO units)
    var shutterSeconds: Double? // manual exposure duration (seconds)
    var focus: Double?          // manual lens position, 0 (near) … 1 (far)

    static let auto = ProControls()
    var isAuto: Bool { self == .auto }
}
```

- [ ] **Step 3b: Replace `CaptureService.swift`'s `CaptureRecipe` struct** (keep the `protocol CaptureService` below it unchanged):
```swift
import StackEngineCore

/// How a burst is captured for a given look (design §10.4), plus optional manual Pro overrides.
/// Long-exposure looks capture more frames over a longer window; static looks use a short fast burst.
struct CaptureRecipe: Sendable, Equatable {
    var frameCount: Int
    var durationSeconds: Double
    var manualISO: Float?            // nil = auto/locked exposure gain (device path)
    var manualShutterSeconds: Double? // nil = auto/locked exposure duration (device path)
    var manualFocus: Float?          // nil = auto/locked focus; else lens position 0…1 (device path)

    init(frameCount: Int, durationSeconds: Double,
         manualISO: Float? = nil, manualShutterSeconds: Double? = nil, manualFocus: Float? = nil) {
        precondition(frameCount > 0, "frameCount must be > 0")
        self.frameCount = frameCount
        self.durationSeconds = durationSeconds
        self.manualISO = manualISO
        self.manualShutterSeconds = manualShutterSeconds
        self.manualFocus = manualFocus
    }

    /// Per-look capture policy. Frame counts trade noise/motion-sampling against memory + time;
    /// durations are the wall-clock window the device burst is paced over. Tunable; the unit test
    /// only pins the relative ordering.
    static func recipe(for mode: StackMode) -> CaptureRecipe {
        switch mode {
        case .noiseReduction: return CaptureRecipe(frameCount: 8,  durationSeconds: 0.5)
        case .lowLightBoost:  return CaptureRecipe(frameCount: 12, durationSeconds: 1.0)
        case .smoothMotion:   return CaptureRecipe(frameCount: 30, durationSeconds: 2.0)
        case .lightTrails:    return CaptureRecipe(frameCount: 30, durationSeconds: 3.0)
        }
    }

    /// Merge manual Pro overrides onto a per-look recipe. Auto (nil) fields leave the look default;
    /// the frame-count override is clamped to ≥ 1 so the recipe stays valid.
    func applying(_ pro: ProControls) -> CaptureRecipe {
        CaptureRecipe(frameCount: max(1, pro.frameCount ?? frameCount),
                      durationSeconds: durationSeconds,
                      manualISO: pro.iso.map(Float.init) ?? manualISO,
                      manualShutterSeconds: pro.shutterSeconds ?? manualShutterSeconds,
                      manualFocus: pro.focus.map(Float.init) ?? manualFocus)
    }
}
```

- [ ] **Step 4: Run → PASS** (same command as Step 2).
- [ ] **Step 5: Commit** — `git add StackStackStack && git commit -m "feat(app): ProControls + CaptureRecipe.applying (manual overrides)"`

---

## Task 2: Coordinator applies Pro controls

**Files:**
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Modify: `StackStackStack/StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: Failing test** — append to `CoordinatorTests.swift` (mirror the existing temp-store setup used by the other coordinator tests; if they use a helper, reuse it):
```swift
    func testProFrameCountOverrideChangesCapturedFrames() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("pro-\(UUID().uuidString)", isDirectory: true)
        let store = LibraryStore(rootDirectory: tmp)
        let coord = StackCaptureCoordinator(capture: FakeCaptureService(width: 16, height: 16), store: store)
        coord.pro = ProControls(frameCount: 5)      // override the look default (Detail = 8)
        await coord.shoot()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 5)
    }
```
*(CoordinatorTests is `@MainActor`; if it is not, wrap the coordinator access in `await MainActor.run { … }`. Match the existing tests in the file.)*

- [ ] **Step 2: Run → FAIL** (`value of type 'StackCaptureCoordinator' has no member 'pro'`).

- [ ] **Step 3: Edit `StackCaptureCoordinator.swift`** — add the published property after `@Published var mode`:
```swift
    /// Manual Pro overrides (frame count / ISO / shutter / focus). Auto by default.
    @Published var pro: ProControls = .auto
```
and in `shoot()`, capture + apply it (replace the two relevant lines):
```swift
        let mode = self.mode            // capture the selected look at shutter-press time (before any await)
        let pro = self.pro              // …and the manual overrides
        do {
            state = .capturing
            let frames = try await capture.captureBurst(recipe: .recipe(for: mode).applying(pro))
```

- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `git add StackStackStack && git commit -m "feat(app): coordinator applies ProControls at capture"`

---

## Task 3: `AVCaptureService` manual exposure / focus (device)

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/AVCaptureService.swift`

> Device-only (the Simulator uses the fake). Verified by compile + build; runtime behaviour requires hardware.

- [ ] **Step 1: Thread the recipe into the lock step.** In `captureBurst(recipe:)`, change `try lockExposureAndFocus()` to `try lockExposureAndFocus(recipe: recipe)`.

- [ ] **Step 2: Replace `lockExposureAndFocus()`** with:
```swift
    /// Lock exposure/focus/WB for a stable burst. Honour any manual Pro overrides on the recipe;
    /// otherwise lock to the metered auto values. Manual values are clamped to the device's range.
    private func lockExposureAndFocus(recipe: CaptureRecipe) throws {
        guard let dev = device else { throw CaptureError.noDevice }
        try dev.lockForConfiguration()
        defer { dev.unlockForConfiguration() }

        // Manual exposure (ISO and/or shutter) → custom exposure; else lock the auto value.
        if recipe.manualISO != nil || recipe.manualShutterSeconds != nil {
            let fmt = dev.activeFormat
            let duration: CMTime = recipe.manualShutterSeconds
                .map { CMTime(seconds: $0, preferredTimescale: 1_000_000) }
                .map { min(max($0, fmt.minExposureDuration), fmt.maxExposureDuration) }
                ?? AVCaptureDevice.currentExposureDuration
            let iso: Float = recipe.manualISO
                .map { min(max($0, fmt.minISO), fmt.maxISO) }
                ?? AVCaptureDevice.currentISO
            if dev.isExposureModeSupported(.custom) {
                dev.setExposureModeCustom(duration: duration, iso: iso, completionHandler: nil)
            }
        } else if dev.isExposureModeSupported(.locked) {
            dev.exposureMode = .locked
        }

        // Manual focus → locked lens position; else lock focus.
        if let focus = recipe.manualFocus, dev.isLockingFocusWithCustomLensPositionSupported {
            dev.setFocusModeLocked(lensPosition: min(max(focus, 0), 1), completionHandler: nil)
        } else if dev.isFocusModeSupported(.locked) {
            dev.focusMode = .locked
        }

        if dev.isWhiteBalanceModeSupported(.locked) { dev.whiteBalanceMode = .locked }
    }
```
*(`CMTime` / `AVCaptureDevice.currentExposureDuration` / `.currentISO` come with the existing `import AVFoundation`. The original explicit `dev.unlockForConfiguration()` is now the `defer`.)*

- [ ] **Step 3: Build** (device-targeted compile is covered by the Simulator build):
`xcodebuild build -project StackStackStack/StackStackStack.xcodeproj -scheme StackStackStack -destination 'platform=iOS Simulator,name=iPhone 17'` → BUILD SUCCEEDED.

- [ ] **Step 4: Commit** — `git add StackStackStack && git commit -m "feat(app): AVCaptureService honours manual ISO/shutter/focus (device)"`

---

## Task 4: Pro panel in `CaptureView`

**Files:**
- Modify: `StackStackStack/StackStackStack/UI/CaptureView.swift`

> Verified by build + the existing capture UI tests (which must still pass — the Pro panel is collapsed by default).

- [ ] **Step 1: Add state + panel.** Add `@State private var showPro = false` next to the other `@State`s. Insert `proPanel` between `lookPicker` and `statusLabel` in the `body` VStack:
```swift
                lookPicker
                proPanel
                statusLabel
```

- [ ] **Step 2: Add the panel + a reusable optional-slider helper** (place above the closing brace of `CaptureView`):
```swift
    private var proPanel: some View {
        VStack(spacing: 8) {
            Button(showPro ? "Pro ▴" : "Pro ▾") { showPro.toggle() }
                .font(.caption).foregroundColor(.white)
                .accessibilityIdentifier("pro-toggle")
                .disabled(coordinator.isBusy)
            if showPro {
                VStack(spacing: 10) {
                    optControl("Frames", unit: "",
                               binding: Binding(get: { coordinator.pro.frameCount.map(Double.init) },
                                                set: { coordinator.pro.frameCount = $0.map { Int($0.rounded()) } }),
                               range: 2...40, step: 1, defaultValue: 12) { "\(Int($0))" }
                    optControl("ISO", unit: "",
                               binding: $coordinator.pro.iso, range: 50...3200, step: 10, defaultValue: 400) { "\(Int($0))" }
                    optControl("Shutter", unit: "s",
                               binding: $coordinator.pro.shutterSeconds, range: 0.001...1, step: 0.001, defaultValue: 0.02) {
                                   String(format: "1/%.0f", 1 / max($0, 0.0001)) }
                    optControl("Focus", unit: "",
                               binding: $coordinator.pro.focus, range: 0...1, step: 0.01, defaultValue: 0.5) {
                                   String(format: "%.2f", $0) }
                }
                .padding(.horizontal, 24)
            }
        }
        .padding(.bottom, 4)
    }

    /// A labelled control that is Auto when off and a value slider when on.
    private func optControl(_ label: String, unit: String, binding: Binding<Double?>,
                            range: ClosedRange<Double>, step: Double, defaultValue: Double,
                            format: @escaping (Double) -> String) -> some View {
        VStack(spacing: 2) {
            Toggle(isOn: Binding(get: { binding.wrappedValue != nil },
                                 set: { binding.wrappedValue = $0 ? defaultValue : nil })) {
                Text(binding.wrappedValue.map { "\(label): \(format($0))\(unit)" } ?? "\(label): Auto")
                    .font(.caption2).foregroundColor(.white)
            }
            .tint(.white)
            if let v = binding.wrappedValue {
                Slider(value: Binding(get: { v }, set: { binding.wrappedValue = $0 }), in: range, step: step)
                    .tint(.white)
            }
        }
        .disabled(coordinator.isBusy)
    }
```

- [ ] **Step 3: Build + Simulator run.** `xcodebuild build … -destination 'platform=iOS Simulator,name=iPhone 17'` → BUILD SUCCEEDED. In the Simulator: tap **Pro ▾** → toggle **Frames** to e.g. 5 → shoot → the saved stack used 5 frames (visible faster capture); collapse restores Auto.

- [ ] **Step 4: Run app unit + UI tests:**
`xcodebuild test … -only-testing:StackStackStackTests -only-testing:StackStackStackUITests/StackFlowUITests` → TEST SUCCEEDED (existing flows unaffected; Pro panel collapsed by default).

- [ ] **Step 5: Commit** — `git add StackStackStack && git commit -m "feat(app): Pro panel (frames / ISO / shutter / focus)"`

---

## Self-review

**1. Spec coverage:** frame-count override (`ProControls.frameCount` → `applying` → recipe → fake/device); manual ISO/shutter/focus (`ProControls` → recipe manual fields → `AVCaptureService` custom exposure + locked lens); blend = the existing look picker (already shipped). Pro panel exposes all four manual controls + Auto.

**2. Placeholder scan:** every step has complete code; no TBD.

**3. Type consistency:** `ProControls(frameCount:iso:shutterSeconds:focus:)` (Int?/Double?/Double?/Double?), `.auto`, `.isAuto`; `CaptureRecipe(frameCount:durationSeconds:manualISO:manualShutterSeconds:manualFocus:)` (Float?/Double?/Float?), `.applying(_:)`; coordinator `pro`; `lockExposureAndFocus(recipe:)`. `applying` bridges `ProControls.iso/focus` (Double?) → recipe `Float?` via `.map(Float.init)`.

---

## Definition of done

- App unit tests green (recipe `applying` merge + frame-count override end-to-end via the fake); existing UI tests green; clean build.
- Device manual exposure/focus path compiles (runtime verification needs hardware — flagged).
- Simulator: Pro panel toggles, frame-count override changes the captured burst length.
