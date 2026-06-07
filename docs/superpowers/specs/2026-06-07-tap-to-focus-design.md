# Tap-to-Focus (+ Long-press AE/AF Lock) — Design

**Status:** Approved design (brainstorm).
**Parent spec:** `docs/superpowers/specs/2026-06-04-stack-stack-stack-photography-design.md` (the "bible").
**Related:** `docs/superpowers/specs/2026-06-07-capture-performance-and-control-design.md` (capture controls; merged as PR #27).

## 1. Goal

Let the user tap the live preview to focus and meter exposure at that point (like the standard iOS
Camera app), with an iOS-style focus-square indicator. A long-press locks AF/AE at the point (with an
"AE/AF LOCK" banner) until the next tap. The next burst locks whatever focus/exposure the tap
converged to. iOS-only / device-only (the Simulator's `FakeCaptureService` has no camera).

## 2. Decisions (locked in brainstorm)

| Decision | Choice | Rationale |
|---|---|---|
| What a tap sets | **Focus + exposure** at the point | Matches the standard iOS Camera tap (the yellow square). |
| Manual Pro interaction | **Disabled while any manual Pro override is active** (`pro.focus`/`pro.iso`/`pro.shutterSeconds` ≠ nil) | Manual mode stays fully in control; a tap won't silently fight a manual setting. |
| Scope | **Tap-to-focus/expose + long-press AE/AF lock** (banner; tap to clear) | The requested behavior plus the standard companion gesture. |
| Coordinate mapping | **`AVCaptureVideoPreviewLayer.captureDevicePointConverted(fromLayerPoint:)`** | Correctly handles `resizeAspectFill` cropping + orientation; hand-rolled math drifts. |
| Gesture location | **UIKit recognizers in `PreviewHostView`** (has the layer) over a SwiftUI overlay | Direct layer access for precise conversion; SwiftUI would still need the layer. |
| Exposure mode on tap | **`.continuousAutoExposure` at the point** | Keeps metering the chosen subject until the next tap (matches iOS); the burst locks it at shoot time. |
| When disabled | Manual Pro active, OR shutter busy (capturing/processing) | Tapping mid-burst is pointless (already locked); manual is user's explicit choice. |

## 3. Architecture & components

Four small units, app-side only:

```
StackStackStack/StackStackStack/
  UI/CameraPreviewView.swift        # + tap & long-press recognizers, layer-point → device-point, onFocus callback
  Capture/CaptureService.swift      # + protocol method setFocusExposure(atDevicePoint:lock:) (default no-op)
  Capture/AVCaptureService.swift    # implements setFocusExposure on the device
  StackCaptureCoordinator.swift     # tapToFocusEnabled gate, focusAndExpose(...), aeAfLocked state
  UI/CaptureView.swift              # focus-square indicator overlay + AE/AF LOCK banner; wires the gesture
```

### 3.1 `CameraPreviewView` (gesture + conversion)
- `PreviewHostView` gains a `UITapGestureRecognizer` and a `UILongPressGestureRecognizer`; the tap
  `require(toFail:)` the long-press so a hold doesn't also fire a tap.
- `CameraPreviewView` gains `var enabled: Bool` and `var onFocus: (_ devicePoint: CGPoint, _ viewPoint: CGPoint, _ lock: Bool) -> Void`, held on its `Coordinator` (the gesture target).
- Handler: if `enabled` and the hosted `CALayer` casts to `AVCaptureVideoPreviewLayer`, convert the
  gesture location to a normalized device point (0…1) and call `onFocus(devicePoint, viewPoint, lock)`.
  Long-press fires once on `.began` with `lock = true`; tap fires with `lock = false`. Not an
  `AVCaptureVideoPreviewLayer` (fake/nil) → no-op.

### 3.2 `AVCaptureService.setFocusExposure(atDevicePoint:lock:)`
Runs on `sessionQueue` under `lockForConfiguration`, every step capability-guarded
(`isFocusPointOfInterestSupported`, `isExposurePointOfInterestSupported`, `isFocusModeSupported`,
`isExposureModeSupported`):
- **Tap (`lock == false`):** `focusPointOfInterest = point` + `focusMode = .autoFocus` (single sweep,
  then holds); `exposurePointOfInterest = point` + `exposureMode = .continuousAutoExposure`. This also
  clears any prior `.locked` state (from a previous burst or AE/AF lock).
- **Long-press (`lock == true`):** set the points and focus/expose, then `focusMode = .locked` +
  `exposureMode = .locked` → AF/AE held.
- Added to the `CaptureService` protocol with a **default no-op extension**, so `FakeCaptureService`
  compiles unchanged and the Simulator path is inert.

### 3.3 `StackCaptureCoordinator`
- `var tapToFocusEnabled: Bool { pro.focus == nil && pro.iso == nil && pro.shutterSeconds == nil && !isBusy }`
  — the single, unit-testable gate.
- `func focusAndExpose(atDevicePoint point: CGPoint, lock: Bool)` → forwards to
  `capture.setFocusExposure(atDevicePoint:lock:)` and sets `@Published var aeAfLocked = lock`.
- `aeAfLocked` resets to `false` when a manual Pro override is enabled or the look changes (those
  paths already clear transient state).

### 3.4 `CaptureView` (indicator + banner + wiring)
- Pass `CameraPreviewView(previewLayer:, enabled: coordinator.tapToFocusEnabled, onFocus: …)`. The
  callback calls `coordinator.focusAndExpose(atDevicePoint:lock:)` and sets a `@State`
  `focusIndicator: (point: CGPoint, locked: Bool, id: UUID)?` at `viewPoint`.
- **Focus square:** ~80 pt, amber stroke, drawn at `viewPoint`. On a tap it appears full-size, springs
  to ~0.85, holds ~0.6 s, then fades (cleared via the `id` after the animation). A long-press square
  **persists** while `aeAfLocked`.
- **Banner:** "AE/AF LOCK" near the top while `coordinator.aeAfLocked`.
- When `tapToFocusEnabled == false`, the gesture is inert and no square appears (v1 stays silent; a
  "manual" hint is a deferred nicety).

## 4. Data flow

```
user taps/long-presses preview (viewPoint)
  → PreviewHostView: layer.captureDevicePointConverted(fromLayerPoint: viewPoint) → devicePoint   [device]
  → onFocus(devicePoint, viewPoint, lock)
  → CaptureView: show focus square at viewPoint (persist if lock)
  → coordinator.focusAndExpose(atDevicePoint: devicePoint, lock:)
       → aeAfLocked = lock
       → AVCaptureService.setFocusExposure(atDevicePoint:lock:)  [device focus/exposure POI + mode]
  → (later) shutter → captureBurst → lockExposureAndFocus locks the tapped focus/exposure for the burst
```

## 5. Error handling & edge cases

- **Manual Pro active** → `tapToFocusEnabled == false`; gesture inert; `aeAfLocked` cleared.
- **Capturing / processing** (`isBusy`) → gesture inert.
- **Device lacks point-of-interest support** → `setFocusExposure` is a guarded no-op.
- **After a burst** → focus stays locked from the burst (existing behavior); a tap re-enables
  autofocus at the new point.
- **Simulator / non-AVCaptureVideoPreviewLayer** → conversion skipped; no-op.
- **Long-press vs tap** → tap requires long-press to fail (no double trigger).
- **Tap point outside valid range** → `captureDevicePointConverted` clamps; modes only set when supported.

## 6. Testing strategy

**Unit (Simulator):**
- `tapToFocusEnabled` truth table: auto + free → true; each of `pro.focus`/`pro.iso`/`pro.shutterSeconds`
  set → false; `isBusy` → false. (Main logic seam testable without a camera.)
- Coordinator `focusAndExpose(lock:)` sets `aeAfLocked` accordingly; enabling a manual override or
  changing look resets it.

**Device-only (compile-verified + manual via mobile-mcp on the iPhone):**
- Tap → preview focus/exposure visibly changes at the point; focus square animates.
- Long-press → AF/AE locks, banner shows; a subsequent tap clears it.
- A burst taken after a tap locks the tapped focus.
- Tap-to-focus inert while a manual Pro override is set and while capturing/processing.

## 7. Phased build (sequential TDD → /code-review → merge)

1. **Coordinator gate + state** — `tapToFocusEnabled`, `focusAndExpose`, `aeAfLocked`; unit tests.
2. **Capture API** — `CaptureService` protocol method + default no-op; `AVCaptureService.setFocusExposure` (device, compile-verified).
3. **Gesture + conversion** — `CameraPreviewView` recognizers + device-point conversion + `onFocus`/`enabled`.
4. **Indicator + banner + wiring** — `CaptureView` focus square, AE/AF banner, gesture wiring.

## 8. Deferred (not in v1)

- Pinch-to-zoom; exposure-bias drag (the iOS "sun" slider).
- A visible hint when tapping is disabled in manual mode.
- Persisting the focus point across app launches.
- Tap-to-focus for the Depth (focus-stacking) look, which sweeps focus by design.
