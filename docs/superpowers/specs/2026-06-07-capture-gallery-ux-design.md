# Capture & Gallery UX Improvements — Design

**Status:** Approved design (brainstorm).
**Parent spec:** `docs/superpowers/specs/2026-06-04-stack-stack-stack-photography-design.md` (the "bible").
**Builds on:** capture-perf overhaul (PR #27) and tap-to-focus (PR #28), both merged to `main`.

## 1. Goal

Five UX fixes/features across capture, the result preview, and the gallery:
1. **Portrait orientation** — a shot taken in portrait currently saves rotated 90°; bake the correct upright orientation into the saved image.
2. **Dismiss the result preview** — the capture-screen result has no way to clear it; add one.
3. **Live capture feedback** — during a burst, show a photos-taken counter (left) and a seconds-remaining countdown (right).
4. **Gallery zoom** — pinch-to-zoom + pan in the full-screen viewer.
5. **Gallery rotate** — persistent 90° rotate (rotate-left/right), non-destructive.

iOS-only; the engine pieces stay pure-Swift, deterministic, golden-tested.

## 2. Decisions (locked in brainstorm)

| Decision | Choice | Rationale |
|---|---|---|
| Orientation fix depth | **Bake upright into the saved image** (displayed result + immutable original) | Correct end-to-end — viewer, editor, exported/shared file — not just on-screen. |
| Orientation source | **`UIDevice` orientation at shutter time** (fall back to `.portrait` on `.faceUp/.unknown`); CoreMotion gravity as a fallback if flaky | Standard; the exact orientation→turns mapping is device-verified. |
| Dismiss-bug nature | The editor sheet already dismisses; the gap is the **capture result preview has no clear control** | Confirmed by reading the code (`EditorView` calls `dismiss()` on Save/Cancel). |
| Capture-progress source | **`capturedCount` via an `onProgress` callback from `captureBurst`** (real frames; works with the Fake) + a coordinator **countdown timer** over the recipe duration | The count must reflect actual frames; the timer is the simplest accurate remaining-time. |
| Feedback format | Left = `captured/total` (e.g. `3/10`); right = seconds remaining (e.g. `5s`) | Matches the user's mock (Photos left / Time right). |
| Zoom implementation | **`UIScrollView`-backed `ZoomableScrollView`** (UIViewRepresentable) | Correct pinch/pan/double-tap/bounds; SwiftUI-only gestures are fiddly. |
| Rotate model | **Non-destructive `quarterTurns` adjustment** in `ImageAdjustments`, applied by `ImageEditor` | Persistent + reversible, reuses the existing edit/sidecar pipeline; shares the §3.1 engine helper with the orientation fix. |
| Scope | **One combined spec/PR** (engine rotation helper → the five items) | Each item is small; #1 and #5 share the rotation helper. |

## 3. Architecture & components

### 3.1 Engine — quarter-turn rotation (shared by #1 and #5)
New platform-free helper in `Packages/StackEngineCore/Sources/StackEngineCore/` (e.g. `ImageGeometry.swift`):
`static func rotated(_ img: PixelImage, quarterTurns: Int) -> PixelImage` — normalize `quarterTurns` mod 4; 0 → copy; 1/3 → swap width/height with the appropriate index remap; 2 → 180° remap. Deterministic, golden-testable. No platform deps.

### 3.2 #1 Portrait orientation (capture path)
- **App `DeviceOrientation`** helper: maps `UIDeviceOrientation` → `quarterTurns` needed to make the back-camera's native-landscape result upright (portrait, landscapeLeft, landscapeRight, portraitUpsideDown; default 0 for face-up/unknown → treat as portrait). The exact constants are validated on device.
- **`StackCaptureCoordinator`**: the capture screen enables device-orientation notifications; `shoot()` snapshots the current `quarterTurns` and passes it through `enqueueProcessing` → `makeJPEG(..., orientationQuarterTurns:)`, which calls `ImageGeometry.rotated(result, quarterTurns:)` before `OutputTransform.encodeSRGB8` + encode. Both the saved displayed JPEG and the immutable original are encoded from the rotated result, so everything downstream is upright.

### 3.3 #2 Dismiss the result preview
- `StackCaptureCoordinator.dismissResult()` sets `lastResultJPEG = nil`, `lastSavedID = nil` (→ `CaptureView`'s `onReceive` clears `lastResult`).
- `CaptureView`: an **✕** button on the result-preview block calls `coordinator.dismissResult()`, returning to the clean viewfinder.

### 3.4 #3 Live capture feedback
- `CaptureService.captureBurst` gains an optional `onProgress: (@Sendable (Int) -> Void)?` (default nil), called with the running captured-frame count. It fires on the service's internal queue (`AVCaptureService` calls it as each converted `RawSensorFrame` is appended; `FakeCaptureService` per synthesized frame), so the coordinator's closure marshals to the MainActor (`Task { @MainActor in … }`) before touching `@Published capturedCount`.
- `StackCaptureCoordinator`: `@Published private(set) var capturedCount: Int` and `@Published private(set) var captureRemainingSeconds: Int`. On `shoot()` for a burst: reset count to 0, start a countdown from `ceil(recipe.durationSeconds)` driven by a `Timer`/`Task` that ticks each second while `isCapturing`; `onProgress` updates `capturedCount`. Both reset when capture ends.
- `CaptureView`: while `isCapturing`, the left edge shows `\(capturedCount)/\(total)` and the right edge shows `\(captureRemainingSeconds)s`. For long-exposure looks these replace the Photos/Time slider readouts; for static looks (no sliders) a minimal matching overlay is shown in the same spots.

### 3.5 #4 Gallery zoom
- New `ZoomableScrollView` (UIViewRepresentable wrapping `UIScrollView` with the image in a `UIImageView`): `minimumZoomScale = 1`, `maximumZoomScale ≈ 4`, centered content, double-tap to toggle 1×↔2×, pan when zoomed.
- `PhotoDetailView` replaces `Image(uiImage:).resizable().scaledToFit()` with `ZoomableScrollView(image:)`.

### 3.6 #5 Gallery rotate (persistent)
- `ImageAdjustments` gains `quarterTurns: Int` (0–3, `Codable`, back-compat default 0 in the custom decoder), normalized into 0–3. `ImageEditor.apply` rotates by `quarterTurns` via §3.1 in its geometry pass (compose with straighten/crop sensibly: apply quarter-turn first, then straighten/crop).
- `PhotoDetailView` toolbar: **rotate-left** / **rotate-right** buttons bump `quarterTurns` (mod 4) and persist via the existing `store.applyEdit(id:adjustments:renderedJPEG:)` path (re-render off-main, then `onChanged()` refresh). Non-destructive (original untouched), persistent (sidecar + re-rendered result).

## 4. Data flow

```
# Capture (upright)
shutter → coordinator snapshots quarterTurns (UIDevice orientation)
  → captureBurst(recipe, isSteady:, onProgress: { capturedCount = $0 })   [count updates live]
  → enqueueProcessing → makeJPEG(frames, mode, orientationQuarterTurns:)
       → reduce → ImageGeometry.rotated(result, quarterTurns:) → encode → save (upright)

# Result preview dismiss
✕ → coordinator.dismissResult() → lastResultJPEG/lastSavedID = nil → clean viewfinder

# Gallery rotate (persistent)
rotate button → quarterTurns = (quarterTurns + ±1) mod 4
  → ResultRenderer.render(original, adjustments) [ImageEditor applies quarterTurns]
  → store.applyEdit(...) → viewer + gallery refresh
```

## 5. Error handling & edge cases
- **Orientation unknown / face-up** → default to portrait (0 turns mapping); never crash.
- **Static looks** (fast fixed burst): the countdown is brief (≤1 s) but harmless; counter still increments. Feedback shown for all looks.
- **`onProgress` nil** (default / callers that don't need it) → no-op.
- **Old edit sidecars** lacking `quarterTurns` → decode to 0 (existing back-compat pattern in `ImageAdjustments.init(from:)`).
- **Zoom**: a non-decoded/failed image → the existing placeholder; double-tap/pan no-op when not zoomable.
- **Rotate while another edit is in flight** → reuse the editor's existing `isSaving`-style guard / serialize through `applyEdit` (MainActor-confined writes, per `LibraryStore`).
- **`quarterTurns` + straighten + crop**: quarter-turn applied first so straighten/crop operate in the displayed orientation.

## 6. Testing strategy
**Engine (golden/unit):**
- `ImageGeometry.rotated`: 1 turn swaps W/H and maps a known pixel correctly; 2 turns = 180°; 4 turns = identity; negative/large turns normalize.
- `ImageEditor` applies `quarterTurns` (a 1-turn edit on a known image matches `rotated(...)`).
- `ImageAdjustments` `quarterTurns` Codable round-trip + back-compat (missing key → 0).

**App (Simulator/unit):**
- `dismissResult()` clears `lastResultJPEG`/`lastSavedID`.
- Capture progress: a Fake `onProgress` drives `capturedCount` to the frame count; countdown resets on end.
- `DeviceOrientation` mapping table (pure function: orientation → quarterTurns).

**Device-only (mobile-mcp):**
- Portrait and landscape shots → saved image upright (validates the #1 mapping).
- Live counter + countdown during a Smooth burst.
- Gallery pinch-zoom, pan, double-tap; rotate-left/right persists across viewer close/reopen.

## 7. Phased build (sequential TDD → /code-review → merge)
1. **Engine quarter-turn rotation** (`ImageGeometry.rotated`) — golden tests.
2. **`ImageAdjustments.quarterTurns` + `ImageEditor`** — apply + codable/back-compat tests.
3. **#1 capture orientation** — `DeviceOrientation` mapping + coordinator snapshot + `makeJPEG` rotate (mapping device-verified).
4. **#2 result-preview dismiss** — `dismissResult()` + ✕ button.
5. **#3 live capture feedback** — `onProgress` through the capture services + coordinator `capturedCount`/countdown + CaptureView readouts.
6. **#4 gallery zoom** — `ZoomableScrollView` in `PhotoDetailView`.
7. **#5 gallery rotate** — rotate buttons in `PhotoDetailView` wired to `quarterTurns` + `applyEdit`.

## 8. Deferred (not in this round)
- Auto-rotating the capture controls/UI themselves to match device orientation.
- Free (arbitrary-angle) rotation beyond the existing straighten.
- Zoom inside the editor; gallery multi-select / batch ops.
- Per-orientation EXIF tagging of the exported file (we bake pixels instead).
