# Tap-to-Focus (+ Long-press AE/AF Lock) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tap the live preview to focus + meter exposure at that point (standard iOS behavior) with an iOS-style focus-square indicator; long-press locks AF/AE with an "AE/AF LOCK" banner. Disabled while a manual Pro override is active or the shutter is busy.

**Architecture:** A UIKit tap/long-press recognizer on the preview host converts the touch to a normalized device point via `AVCaptureVideoPreviewLayer.captureDevicePointConverted(fromLayerPoint:)`; the coordinator gates the gesture (auto mode + free) and forwards the point to `AVCaptureService`, which sets the device's focus/exposure point-of-interest; `CaptureView` draws the focus square + lock banner. Device-only (the Simulator fake no-ops).

**Tech Stack:** Swift / SwiftUI / UIKit / AVFoundation (app). XCTest (`xcodebuild test`).

**Spec:** `docs/superpowers/specs/2026-06-07-tap-to-focus-design.md`

---

## File Structure

- Modify `StackStackStack/StackStackStack/Capture/CaptureService.swift` — protocol method `setFocusExposure(atDevicePoint:lock:)` + default no-op extension.
- Modify `StackStackStack/StackStackStack/Capture/AVCaptureService.swift` — device implementation.
- Modify `StackStackStack/StackStackStack/StackCaptureCoordinator.swift` — `tapToFocusEnabled`, `focusAndExpose(...)`, `aeAfLocked` + resets.
- Modify `StackStackStack/StackStackStack/UI/CameraPreviewView.swift` — gesture recognizers + device-point conversion + `enabled`/`onFocus`.
- Modify `StackStackStack/StackStackStack/UI/CaptureView.swift` — focus-square overlay, AE/AF banner, gesture wiring.
- Test `StackStackStack/StackStackStackTests/CoordinatorTests.swift` — `tapToFocusEnabled` table + `aeAfLocked` behavior.

**Test/build commands** (use `iPhone 17 Pro`; `iPhone 16` is not installed):
- Test: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:StackStackStackTests/<Class>`
- Build: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build`

> **Ordering note:** the Capture API (Task 1) comes before the coordinator (Task 2) because `focusAndExpose` calls the new protocol method. (The spec lists the coordinator first; this order avoids a forward reference.)

---

## Task 1: Capture API — `setFocusExposure(atDevicePoint:lock:)`

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/CaptureService.swift`
- Modify: `StackStackStack/StackStackStack/Capture/AVCaptureService.swift`

(Device-only; verified by a clean build. The Simulator uses the default no-op.)

- [ ] **Step 1: Add the protocol requirement + default no-op**

In `CaptureService.swift`, add the method to the `protocol CaptureService { … }` (alongside `captureBurst`/`startPreview`):

```swift
    /// Tap-to-focus: focus + meter exposure at a normalized device point of interest (0…1, from
    /// `AVCaptureVideoPreviewLayer.captureDevicePointConverted`). `lock` (long-press) holds AF/AE.
    /// Device-only; the default below makes it a no-op for callers without a camera. (design tap-to-focus §3.2)
    func setFocusExposure(atDevicePoint point: CGPoint, lock: Bool)
```

Add an extension default after the protocol:

```swift
extension CaptureService {
    func setFocusExposure(atDevicePoint point: CGPoint, lock: Bool) { }   // no-op unless a device overrides it
}
```

(`CGPoint` is available via the existing `import QuartzCore`. If the compiler complains, add `import CoreGraphics`.)

- [ ] **Step 2: Implement it on the device**

In `AVCaptureService.swift`, add this method to the class (e.g. after `lockExposureAndFocus`):

```swift
    /// Focus + meter exposure at `point` (normalized device coords). Tap → one-shot autofocus with
    /// continuous exposure metering at the point; long-press (`lock`) → one-shot AF + AE that hold
    /// (AE/AF lock). Runs on `sessionQueue`; every step capability-guarded. (design tap-to-focus §3.2)
    func setFocusExposure(atDevicePoint point: CGPoint, lock: Bool) {
        sessionQueue.async {
            guard let dev = self.device else { return }
            do { try dev.lockForConfiguration() } catch { return }
            if dev.isFocusPointOfInterestSupported {
                dev.focusPointOfInterest = point
                if dev.isFocusModeSupported(.autoFocus) { dev.focusMode = .autoFocus }   // one sweep, then holds
            }
            if dev.isExposurePointOfInterestSupported {
                dev.exposurePointOfInterest = point
                // Tap: keep metering the subject. Lock: meter once and hold.
                let mode: AVCaptureDevice.ExposureMode = lock ? .autoExpose : .continuousAutoExposure
                if dev.isExposureModeSupported(mode) { dev.exposureMode = mode }
                else if dev.isExposureModeSupported(.continuousAutoExposure) { dev.exposureMode = .continuousAutoExposure }
            }
            dev.unlockForConfiguration()
        }
    }
```

- [ ] **Step 3: Build to verify it compiles**

Run: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build`
Expected: BUILD SUCCEEDED (FakeCaptureService compiles unchanged via the default no-op).

- [ ] **Step 4: Commit**

```bash
git add StackStackStack/StackStackStack/Capture/CaptureService.swift StackStackStack/StackStackStack/Capture/AVCaptureService.swift
git commit -m "feat(capture): setFocusExposure(atDevicePoint:lock:) device API"
```

---

## Task 2: Coordinator gate + state

**Files:**
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Test: `StackStackStack/StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: Write the failing tests**

In `CoordinatorTests.swift`, ensure `import CoreGraphics` is present at the top (add it if missing — needed for `CGPoint`). Add:

```swift
    @MainActor
    func testTapToFocusEnabledInAutoModeAndFree() {
        let (coord, _) = makeCoordinator()
        XCTAssertTrue(coord.tapToFocusEnabled)
    }

    @MainActor
    func testTapToFocusDisabledWithEachManualOverride() {
        let (coord, _) = makeCoordinator()
        coord.pro = ProControls(focus: 0.5)
        XCTAssertFalse(coord.tapToFocusEnabled, "manual focus disables tap-to-focus")
        coord.pro = ProControls(iso: 800)
        XCTAssertFalse(coord.tapToFocusEnabled, "manual ISO disables tap-to-focus")
        coord.pro = ProControls(shutterSeconds: 0.01)
        XCTAssertFalse(coord.tapToFocusEnabled, "manual shutter disables tap-to-focus")
        coord.pro = .auto
        XCTAssertTrue(coord.tapToFocusEnabled, "back to auto re-enables")
    }

    @MainActor
    func testFocusAndExposeTogglesAeAfLock() {
        let (coord, _) = makeCoordinator()
        coord.focusAndExpose(atDevicePoint: CGPoint(x: 0.5, y: 0.5), lock: true)
        XCTAssertTrue(coord.aeAfLocked)
        coord.focusAndExpose(atDevicePoint: CGPoint(x: 0.3, y: 0.3), lock: false)
        XCTAssertFalse(coord.aeAfLocked, "a normal tap clears the lock")
    }

    @MainActor
    func testEnablingManualClearsAeAfLock() {
        let (coord, _) = makeCoordinator()
        coord.focusAndExpose(atDevicePoint: CGPoint(x: 0.5, y: 0.5), lock: true)
        coord.pro = ProControls(iso: 800)
        XCTAssertFalse(coord.aeAfLocked, "entering manual drops the AE/AF lock")
    }

    @MainActor
    func testChangingLookClearsAeAfLock() {
        let (coord, _) = makeCoordinator()
        coord.focusAndExpose(atDevicePoint: CGPoint(x: 0.5, y: 0.5), lock: true)
        coord.mode = .smoothMotion
        XCTAssertFalse(coord.aeAfLocked, "switching looks drops the AE/AF lock")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:StackStackStackTests/CoordinatorTests/testTapToFocusEnabledInAutoModeAndFree`
Expected: FAIL — `tapToFocusEnabled` / `focusAndExpose` / `aeAfLocked` don't exist.

- [ ] **Step 3: Add the state, gate, and method**

In `StackCaptureCoordinator.swift`:

Add a published property after `@Published private(set) var lastError: String?`:

```swift
    /// True while AF/AE are locked via a long-press on the preview (drives the "AE/AF LOCK" banner).
    @Published private(set) var aeAfLocked = false
```

Add `aeAfLocked = false` to the `mode` didSet:

```swift
    @Published var mode: StackMode = .noiseReduction {
        didSet { if mode != oldValue { lastResultJPEG = nil; lastSavedID = nil; lastError = nil; aeAfLocked = false } }
    }
```

Add a didSet to `pro` (it currently has none) so entering manual drops the lock:

```swift
    /// Manual Pro overrides (frame count / ISO / shutter / focus). Auto by default.
    @Published var pro: ProControls = .auto {
        // Tapping is disabled in manual mode (see `tapToFocusEnabled`); drop any AE/AF lock so the
        // banner doesn't linger.
        didSet { if pro.focus != nil || pro.iso != nil || pro.shutterSeconds != nil { aeAfLocked = false } }
    }
```

Add the gate + method (e.g. after `startPreview()`):

```swift
    /// Tap-to-focus is available only in full-auto exposure/focus (no manual Pro override) and while
    /// the shutter is free. (design tap-to-focus §3.3)
    var tapToFocusEnabled: Bool {
        pro.focus == nil && pro.iso == nil && pro.shutterSeconds == nil && !isBusy
    }

    /// Focus + meter exposure at a normalized device point; `lock` (long-press) holds AF/AE and shows
    /// the banner. (design tap-to-focus §3.3)
    func focusAndExpose(atDevicePoint point: CGPoint, lock: Bool) {
        capture.setFocusExposure(atDevicePoint: point, lock: lock)
        aeAfLocked = lock
    }
```

(Add `import CoreGraphics` at the top of `StackCaptureCoordinator.swift` if `CGPoint` isn't resolved — it currently imports `QuartzCore`, which re-exports CoreGraphics, so it should already resolve.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:StackStackStackTests/CoordinatorTests`
Expected: PASS (the 5 new tests + all existing coordinator tests).

- [ ] **Step 5: Commit**

```bash
git add StackStackStack/StackStackStack/StackCaptureCoordinator.swift StackStackStack/StackStackStackTests/CoordinatorTests.swift
git commit -m "feat(capture): tapToFocusEnabled gate + focusAndExpose + aeAfLocked state"
```

---

## Task 3: Gesture + coordinate conversion — `CameraPreviewView`

**Files:**
- Modify: `StackStackStack/StackStackStack/UI/CameraPreviewView.swift`

(Conversion is device-only; verified by a clean build. `PreviewHostView` is unchanged.)

- [ ] **Step 1: Add gesture recognizers, conversion, and callbacks**

Replace the `CameraPreviewView` struct (keep `PreviewHostView` below it as-is) with:

```swift
import SwiftUI
import UIKit
import AVFoundation

/// Hosts the camera preview `CALayer` (from `AVCaptureService`) behind the capture controls, and
/// forwards taps / long-presses on the preview as normalized device focus points. A nil layer (or a
/// non-`AVCaptureVideoPreviewLayer`, e.g. the Simulator) leaves it transparent and inert.
struct CameraPreviewView: UIViewRepresentable {
    let previewLayer: CALayer?
    /// When false, taps/long-presses are ignored (e.g. manual Pro mode, or shutter busy).
    var enabled: Bool = false
    /// (normalized device point 0…1, tap location in view coords, lock=true for long-press)
    var onFocus: ((_ devicePoint: CGPoint, _ viewPoint: CGPoint, _ lock: Bool) -> Void)? = nil

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> PreviewHostView {
        let view = PreviewHostView()
        let tap = UITapGestureRecognizer(target: context.coordinator,
                                         action: #selector(Coordinator.handleTap(_:)))
        let long = UILongPressGestureRecognizer(target: context.coordinator,
                                                action: #selector(Coordinator.handleLongPress(_:)))
        tap.require(toFail: long)   // a hold fires the long-press, not also a tap
        view.addGestureRecognizer(tap)
        view.addGestureRecognizer(long)
        context.coordinator.host = view
        return view
    }

    func updateUIView(_ uiView: PreviewHostView, context: Context) {
        uiView.previewLayer = previewLayer
        context.coordinator.enabled = enabled
        context.coordinator.onFocus = onFocus
    }

    final class Coordinator: NSObject {
        weak var host: PreviewHostView?
        var enabled = false
        var onFocus: ((CGPoint, CGPoint, Bool) -> Void)?

        @objc func handleTap(_ g: UITapGestureRecognizer) { fire(g, lock: false) }
        @objc func handleLongPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began else { return }   // fire once when the hold is recognized
            fire(g, lock: true)
        }

        private func fire(_ g: UIGestureRecognizer, lock: Bool) {
            guard enabled, let host,
                  let layer = host.previewLayer as? AVCaptureVideoPreviewLayer else { return }
            let viewPoint = g.location(in: host)   // host bounds == layer frame, so this is the layer point
            let devicePoint = layer.captureDevicePointConverted(fromLayerPoint: viewPoint)
            onFocus?(devicePoint, viewPoint, lock)
        }
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build`
Expected: BUILD SUCCEEDED. (Note: `CaptureView` still constructs `CameraPreviewView(previewLayer:)` — the new params have defaults, so it compiles before Task 4 wires them.)

- [ ] **Step 3: Commit**

```bash
git add StackStackStack/StackStackStack/UI/CameraPreviewView.swift
git commit -m "feat(ui): tap/long-press recognizers + device-point conversion on the preview"
```

---

## Task 4: Focus indicator + AE/AF banner + wiring — `CaptureView`

**Files:**
- Modify: `StackStackStack/StackStackStack/UI/CaptureView.swift`

(UI; verified by build + on-device.)

- [ ] **Step 1: Add indicator state + helper**

In `CaptureView.swift`, add a state struct and properties after `@State private var previewLayer: CALayer?` (line 12):

```swift
    @State private var focusIndicator: FocusIndicator?
    @State private var focusSquareScale: CGFloat = 1.0

    private struct FocusIndicator: Identifiable, Equatable {
        let id = UUID()
        let point: CGPoint
        let locked: Bool
    }
```

Add a helper method (e.g. near `openEditor()`):

```swift
    /// Show the focus square at the tap location: spring in, then auto-dismiss after ~1s unless it's a
    /// lock (the lock square persists while `aeAfLocked`).
    private func showFocusIndicator(at point: CGPoint, locked: Bool) {
        let indicator = FocusIndicator(point: point, locked: locked)
        focusIndicator = indicator
        focusSquareScale = 1.3
        withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) { focusSquareScale = 1.0 }
        guard !locked else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            if focusIndicator?.id == indicator.id {
                withAnimation(.easeOut(duration: 0.3)) { focusIndicator = nil }
            }
        }
    }
```

- [ ] **Step 2: Add the overlay views**

Add to the struct (e.g. after `steadinessOverlay` / `burstSliders`):

```swift
    /// iOS-style focus square at the last tap. Transient for a tap, persistent while AE/AF locked.
    @ViewBuilder private var focusIndicatorOverlay: some View {
        if let fi = focusIndicator {
            Rectangle()
                .stroke(Color.yellow, lineWidth: 1.5)
                .frame(width: 80, height: 80)
                .scaleEffect(focusSquareScale)
                .position(fi.point)
                .allowsHitTesting(false)
                .accessibilityIdentifier("focus-indicator")
        }
    }

    @ViewBuilder private var aeAfBanner: some View {
        if coordinator.aeAfLocked {
            Text("AE/AF LOCK")
                .font(.caption).bold().foregroundColor(.black)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(Color.yellow).clipShape(RoundedRectangle(cornerRadius: 4))
                .padding(.top, 12)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .allowsHitTesting(false)
                .accessibilityIdentifier("ae-af-lock-banner")
        }
    }
```

- [ ] **Step 3: Wire the gesture into the preview and add the overlays**

In `body`, replace the `CameraPreviewView(...)` line and the overlay block:

```swift
            CameraPreviewView(previewLayer: previewLayer).ignoresSafeArea()   // live viewfinder (nil → black)
            burstSliders
            steadinessOverlay
```
with:

```swift
            CameraPreviewView(
                previewLayer: previewLayer,
                enabled: coordinator.tapToFocusEnabled,
                onFocus: { devicePoint, viewPoint, lock in
                    coordinator.focusAndExpose(atDevicePoint: devicePoint, lock: lock)
                    showFocusIndicator(at: viewPoint, locked: lock)
                }
            ).ignoresSafeArea()   // live viewfinder (nil → black)
            burstSliders
            steadinessOverlay
            focusIndicatorOverlay
            aeAfBanner
```

- [ ] **Step 4: Clear the lock square when the lock is released**

Add this modifier to the `ZStack` in `body`, right after the existing `.onReceive(coordinator.$lastResultJPEG) { … }` block:

```swift
        // When the AE/AF lock is released (by a tap, a manual override, or a look change), drop the
        // persistent lock square.
        .onReceive(coordinator.$aeAfLocked) { locked in
            if !locked, focusIndicator?.locked == true { focusIndicator = nil }
        }
```

- [ ] **Step 5: Build to verify it compiles**

Run: `cd StackStackStack && xcodebuild -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 6: Run the full app unit suite (no regressions)**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:StackStackStackTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add StackStackStack/StackStackStack/UI/CaptureView.swift
git commit -m "feat(ui): focus-square indicator + AE/AF lock banner; wire tap-to-focus"
```

---

## Task 5: Verification & PR

- [ ] **Step 1: Full app unit suite**

Run: `cd StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:StackStackStackTests`
Expected: PASS.

- [ ] **Step 2: Run the code-review skill (required before merge)**

Per project CLAUDE.md: invoke the code-review skill on the branch diff and address findings.

- [ ] **Step 3: Device verification (manual, on the iPhone via mobile-mcp)**

Build+install a Release build (`generic/platform=iOS`) and confirm on a physical device: a tap focuses/exposes at the point with the focus square; a long-press locks AF/AE and shows the banner; a tap clears it; tap-to-focus is inert in manual Pro mode and while capturing/processing; a burst after a tap locks the tapped focus.

- [ ] **Step 4: Open the PR**

```bash
git push -u origin feat/tap-to-focus
gh pr create --title "Tap-to-focus (+ long-press AE/AF lock)" --body "$(cat <<'EOF'
Implements docs/superpowers/specs/2026-06-07-tap-to-focus-design.md.

- Tap the preview to focus + meter exposure at the point (standard iOS), with an iOS-style focus square.
- Long-press locks AF/AE with an "AE/AF LOCK" banner; a tap clears it.
- Disabled while a manual Pro override is active or the shutter is busy.
- Device point via AVCaptureVideoPreviewLayer.captureDevicePointConverted; AVCaptureService sets the focus/exposure point-of-interest. Simulator no-ops.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Notes on deviations from the spec (deliberate)

- **AE/AF lock uses one-shot `.autoFocus` + `.autoExpose` that hold** (rather than an explicit `.locked` transition). This avoids a converge-then-lock race (setting `.locked` immediately would freeze the lens before the focus sweep runs) with no KVO machinery; for a still subject it's behaviorally a lock, and the burst's `lockExposureAndFocus` hard-locks at shoot time. A true `.locked`-after-convergence (KVO on `isAdjusting…`) is a possible follow-up if drift is observed on-device.
- **Task order puts the Capture API before the coordinator** (spec §3 lists the coordinator first) so `focusAndExpose` can call the new protocol method without a forward reference.
