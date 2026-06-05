# Phase 2 DoF — Phase 4: FocusStacker + DepthConfig Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire the focus-stacking stages end-to-end into a single `FocusStacker.allInFocus`, parameterized by a `DepthConfig` (working resolution + max frames), and upgrade `AffineAligner.estimate` to a **coarse-to-fine pyramid** search so registration is robust on real (high-frequency) frames — the prerequisite the Phase-1 aligner review flagged.

**Architecture:** `DepthConfig` is a small value type. `AffineAligner.estimate` becomes coarse-to-fine over a Gaussian luma pyramid (reuses `ImagePyramid`), refining the same Hooke–Jeeves search per level (scale/rotation are resolution-invariant; translation doubles per finer level). `FocusStacker` develops → downscales to the working resolution → aligns each frame to the sharpest reference → computes sharpness → selection weights → multiband blend. Spec: `docs/superpowers/specs/2026-06-05-phase2-depth-of-field-design.md` §3.6, §5.

**Tech Stack:** Swift, `simd`. Pure-CPU, golden-tested. Builds on `AffineAligner`, `SharpnessMap`, `SelectionMap`, `LaplacianPyramidBlend`, `ImagePyramid`, `ColorPipeline`, `ReferenceSelection`, `Luma`.

---

## File structure (this plan)

```
Packages/StackEngineCore/Sources/StackEngineCore/
  DepthConfig.swift     # CREATE — operating point (working resolution + max frames)
  AffineAligner.swift   # MODIFY — coarse-to-fine pyramid estimate (refactor `estimate`, add `refine`)
  FocusStacker.swift    # CREATE — end-to-end all-in-focus orchestration
Packages/StackEngineCore/Tests/StackEngineCoreTests/
  FocusStackerTests.swift  # CREATE
  (AffineAlignerTests.swift — existing; must stay green as a regression guard for the refactor)
```

Existing API: `ColorPipeline.process(_:) -> PixelImage`, `ReferenceSelection.sharpestIndex(_:) -> Int`, `AffineAligner.align(reference:moving:)`, `SharpnessMap.compute(_:)`, `SelectionMap.weights(sharpness:guide:width:height:)`, `LaplacianPyramidBlend.blend(images:weights:)`, `ImagePyramid.gaussian(_:minSize:)`/`reduce(_:)`, `Luma.luminance(_:)`, `Alignment.estimateTranslation(referenceLuma:movingLuma:width:height:searchRange:)`.

---

## Task 1: `DepthConfig`

**Files:** Create `DepthConfig.swift` (no separate test file — it's exercised by FocusStackerTests).

- [ ] **Step 1: Create `DepthConfig.swift`:**
```swift
/// Operating point for Depth-of-Field focus stacking (design §5, tiered).
public struct DepthConfig: Sendable, Equatable {
    /// Max long-edge in pixels for the working resolution; `nil` = full resolution (no downscale).
    public var workingResolution: Int?
    /// Cap on the number of focus brackets actually stacked (memory/time bound).
    public var maxFrames: Int

    public init(workingResolution: Int?, maxFrames: Int) {
        precondition(maxFrames > 0, "maxFrames must be > 0")
        self.workingResolution = workingResolution
        self.maxFrames = maxFrames
    }

    /// Auto (managed): snappy, screen/share-quality — ~1500 px long edge, ~10 brackets.
    public static let auto = DepthConfig(workingResolution: 1500, maxFrames: 10)
    /// Pro (max quality): full sensor resolution, more brackets (slow on CPU until Metal).
    public static let pro = DepthConfig(workingResolution: nil, maxFrames: 24)
}
```

- [ ] **Step 2: Build check** — `cd Packages/StackEngineCore && swift build` → succeeds.
- [ ] **Step 3: Commit** — `git add Packages/StackEngineCore && git commit -m "feat(core): DepthConfig (DoF operating point)"`

---

## Task 2: coarse-to-fine pyramid in `AffineAligner.estimate`

**Files:** Modify `AffineAligner.swift`. The existing `AffineAlignerTests.swift` is the regression guard.

- [ ] **Step 1: Add a high-frequency robustness test** — append to `AffineAlignerTests.swift` (inside the class):
```swift
    func testEstimateRecoversSimilarityOnHighFrequencyImage() {
        // A higher-frequency texture would trap a single-resolution search in a local minimum; the
        // coarse-to-fine pyramid should still recover the transform (align → reference).
        let w = 64, h = 64
        var ref = PixelImage(width: w, height: h)
        for y in 0..<h { for x in 0..<w {
            let v = 0.5 + 0.45 * sin(0.9 * Float(x)) * sin(0.8 * Float(y))   // near-Nyquist detail
            ref[x, y] = SIMD3<Float>(v, v, v)
        }}
        let known = Transform2D.similarity(scale: 1.03, rotation: 0.015, tx: 2, ty: -1)
        let mov = AffineAligner.warp(ref, by: known)
        let aligned = AffineAligner.align(reference: ref, moving: mov)
        var maxd: Float = 0
        for y in 16..<48 { for x in 16..<48 { maxd = max(maxd, abs(aligned[x, y].x - ref[x, y].x)) } }
        XCTAssertLessThan(maxd, 0.08, "coarse-to-fine alignment should register a high-frequency frame")
    }
```

- [ ] **Step 2: Run → likely FAIL** (the single-resolution search lodges in a local minimum on near-Nyquist content): `cd Packages/StackEngineCore && swift test --filter testEstimateRecoversSimilarityOnHighFrequencyImage`. (If it happens to pass, that's fine — proceed; the refactor still improves robustness.)

- [ ] **Step 3: Replace the `estimate` function in `AffineAligner.swift`** (keep `warp`, `align`, `ssdWarped`, `sampleRGB`, `sampleLuma` unchanged) with the coarse-to-fine driver + a `refine` helper:
```swift
    /// Estimate the similarity transform that best aligns `moving` to `reference`, minimising luma
    /// SSD. COARSE-TO-FINE over a Gaussian luma pyramid: the coarsest level is smooth (no aliasing →
    /// a global basin), then each finer level refines. scale/rotation are resolution-invariant;
    /// translation doubles per finer level. Robust on real high-frequency frames.
    public static func estimate(reference ref: PixelImage, moving mov: PixelImage,
                                translationSearch: Int = 8) -> Transform2D {
        precondition(ref.width == mov.width && ref.height == mov.height)
        let refPyr = ImagePyramid.gaussian(ref, minSize: 24)
        let movPyr = ImagePyramid.gaussian(mov, minSize: 24)
        let levels = refPyr.count
        var s: Float = 1, r: Float = 0, tx: Float = 0, ty: Float = 0
        for lvl in stride(from: levels - 1, through: 0, by: -1) {   // coarsest → finest
            let rL = Luma.luminance(refPyr[lvl]), mL = Luma.luminance(movPyr[lvl])
            let lw = refPyr[lvl].width, lh = refPyr[lvl].height
            (s, r, tx, ty) = refine(rL, mL, width: lw, height: lh, s: s, r: r, tx: tx, ty: ty,
                                    translationInit: lvl == levels - 1 ? translationSearch : 0)
            if lvl > 0 { tx *= 2; ty *= 2 }   // propagate translation to the next finer level
        }
        return .similarity(scale: s, rotation: r, tx: tx, ty: ty)
    }

    /// One pyramid level of the deterministic Hooke–Jeeves search over scale / rotation / sub-pixel
    /// translation, starting from `(s,r,tx,ty)`. `translationInit > 0` seeds translation by an integer
    /// SSD search (only needed at the coarsest level). Scale is clamped to a sane range.
    private static func refine(_ refL: [Float], _ movL: [Float], width w: Int, height h: Int,
                               s s0: Float, r r0: Float, tx tx0: Float, ty ty0: Float,
                               translationInit: Int) -> (Float, Float, Float, Float) {
        var s = s0, r = r0, tx = tx0, ty = ty0
        if translationInit > 0 {
            let t0 = Alignment.estimateTranslation(referenceLuma: refL, movingLuma: movL,
                                                   width: w, height: h, searchRange: translationInit)
            tx = Float(t0.dx); ty = Float(t0.dy)
        }
        func cost(_ s: Float, _ r: Float, _ tx: Float, _ ty: Float) -> Float {
            ssdWarped(movL, refL, width: w, height: h,
                      by: .similarity(scale: s, rotation: r, tx: tx, ty: ty))
        }
        var best = cost(s, r, tx, ty)
        var stepS: Float = 0.05, stepR: Float = 0.04, stepT: Float = 1.0
        let minScale: Float = 0.5, maxScale: Float = 2.0
        var guardCount = 0
        while stepT > 0.01 && guardCount < 1000 {
            guardCount += 1
            var improved = false
            let trials: [(Float, Float, Float, Float)] = [
                ( stepS, 0, 0, 0), (-stepS, 0, 0, 0),
                (0,  stepR, 0, 0), (0, -stepR, 0, 0),
                (0, 0,  stepT, 0), (0, 0, -stepT, 0),
                (0, 0, 0,  stepT), (0, 0, 0, -stepT),
            ]
            for (dS, dR, dTx, dTy) in trials {
                let ns = s + dS
                if ns < minScale || ns > maxScale { continue }
                let c = cost(ns, r + dR, tx + dTx, ty + dTy)
                if c < best - 1e-9 { best = c; s = ns; r += dR; tx += dTx; ty += dTy; improved = true }
            }
            if !improved { stepS *= 0.5; stepR *= 0.5; stepT *= 0.5 }
        }
        return (s, r, tx, ty)
    }
```

- [ ] **Step 4: Run the new test + the existing AffineAligner suite + full suite** — `swift test --filter AffineAlignerTests` → ALL PASS (the existing `testEstimateRecoversSimilarity`, `testEstimateOnIdenticalFramesIsNearIdentity`, `testAlignReducesPureScaleBreathing`, `testEstimateKeepsScaleSaneAndFinite`, `testStraighten*` must remain green, plus the new high-frequency test). Then `swift test` → all green. **If a pre-existing AffineAligner test regresses, STOP and report the exact numbers** — do not weaken it.

- [ ] **Step 5: Commit** — `git add Packages/StackEngineCore && git commit -m "feat(core): AffineAligner coarse-to-fine pyramid estimate (robust on real frames)"`

---

## Task 3: `FocusStacker`

**Files:** Create `FocusStacker.swift`, `FocusStackerTests.swift`.

- [ ] **Step 1: Failing tests** — `FocusStackerTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class FocusStackerTests: XCTestCase {
    // A frame sharp (checker) in one vertical third of `count`, flat elsewhere.
    func bracket(third: Int, of count: Int, w: Int, h: Int) -> PixelImage {
        var img = PixelImage(width: w, height: h, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        let band = w / count
        for y in 0..<h { for x in 0..<w where (x / band) == third {
            let v: Float = ((x + y) % 2 == 0) ? 0.85 : 0.15
            img[x, y] = SIMD3<Float>(v, v, v)
        }}
        return img
    }

    func testAllInFocusBeatsAnySingleFrame() {
        let w = 36, h = 18
        let frames = (0..<3).map { bracket(third: $0, of: 3, w: w, h: h) }
        let out = try! XCTUnwrap(FocusStacker.allInFocus(frames, config: DepthConfig(workingResolution: nil, maxFrames: 12)))
        let total = SharpnessMap.compute(out).reduce(0, +)
        for f in frames {
            XCTAssertGreaterThan(total, SharpnessMap.compute(f).reduce(0, +) * 1.5)   // detail in ALL thirds
        }
    }

    func testEmptyReturnsNilAndSingleFrameReturnsItself() {
        XCTAssertNil(FocusStacker.allInFocus([], config: .auto))
        let img = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        XCTAssertEqual(FocusStacker.allInFocus([img], config: DepthConfig(workingResolution: nil, maxFrames: 12))?.width, 8)
    }

    func testWorkingResolutionDownscales() {
        let img = PixelImage(width: 64, height: 64, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        let out = try! XCTUnwrap(FocusStacker.allInFocus([img, img], config: DepthConfig(workingResolution: 20, maxFrames: 12)))
        XCTAssertLessThanOrEqual(max(out.width, out.height), 20)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`cannot find 'FocusStacker'`): `swift test --filter FocusStackerTests`

- [ ] **Step 3: Create `FocusStacker.swift`:**
```swift
import simd

/// End-to-end focus stacking: develop → downscale to the working resolution → align each frame to
/// the sharpest reference → per-pixel sharpness → selection weights → multiband blend → all-in-focus
/// image (design §13.2). Returns nil for an empty input.
public enum FocusStacker {
    /// All-in-focus composite from already-developed linear frames (all the same dimensions).
    public static func allInFocus(_ images: [PixelImage], config: DepthConfig) -> PixelImage? {
        guard !images.isEmpty else { return nil }
        let frames = images.prefix(config.maxFrames).map { downscale($0, maxEdge: config.workingResolution) }
        guard frames.count >= 2 else { return frames.first }

        let refIdx = ReferenceSelection.sharpestIndex(frames)
        let reference = frames[refIdx]
        let aligned = frames.enumerated().map { i, f in
            i == refIdx ? f : AffineAligner.align(reference: reference, moving: f)
        }
        let sharp = aligned.map { SharpnessMap.compute($0) }
        let weights = SelectionMap.weights(sharpness: sharp, guide: Luma.luminance(reference),
                                           width: reference.width, height: reference.height)
        return LaplacianPyramidBlend.blend(images: aligned, weights: weights)
    }

    /// All-in-focus composite from raw focus-bracketed frames (develops each first).
    public static func allInFocus(rawFrames: [RawSensorFrame], config: DepthConfig) -> PixelImage? {
        allInFocus(rawFrames.map { ColorPipeline.process($0) }, config: config)
    }

    /// Halve (Gaussian reduce) until the long edge is within `maxEdge` (nil = no downscale).
    private static func downscale(_ img: PixelImage, maxEdge: Int?) -> PixelImage {
        guard let maxEdge else { return img }
        var out = img
        while max(out.width, out.height) > maxEdge { out = ImagePyramid.reduce(out) }
        return out
    }
}
```

- [ ] **Step 4: Run the file + full suite** — `swift test --filter FocusStackerTests` → PASS; `swift test` → all green.
- [ ] **Step 5: Commit** — `git add Packages/StackEngineCore && git commit -m "feat(core): FocusStacker (end-to-end all-in-focus orchestration)"`

---

## Self-review

**1. Spec coverage (§3.6, §5):** end-to-end develop→align→sharpness→select→blend → `FocusStacker`; tiered working-resolution + frame cap → `DepthConfig` + `downscale`; robust scale-aware alignment on real frames → coarse-to-fine `estimate`. The core DoF algorithm is now usable as one call; Phase 5 (capture sweep + fake) and Phase 6 (the "Depth" look + gating) wire it into the app.

**2. Placeholder scan:** every step has complete code; no TBD.

**3. Type consistency:** `DepthConfig(workingResolution:maxFrames:)` / `.auto` / `.pro`; `FocusStacker.allInFocus(_:config:)` and `allInFocus(rawFrames:config:)`; `AffineAligner.estimate(reference:moving:translationSearch:)` (signature unchanged) + private `refine`. Reuses the real signatures of `ColorPipeline.process`, `ReferenceSelection.sharpestIndex`, `SelectionMap.weights`, `LaplacianPyramidBlend.blend`, `ImagePyramid.gaussian/reduce`.

---

## Definition of done

- `cd Packages/StackEngineCore && swift test` → all green (77 prior + new), **existing AffineAligner tests still pass** after the coarse-to-fine refactor.
- `FocusStacker.allInFocus` on synthetic focus brackets is sharper than any single frame; respects the frame cap + working resolution; nil on empty, single-frame passthrough.
- DoF engine usable end-to-end; ready for Phase 5 (capture sweep + focus-bracket fake) and Phase 6 (the "Depth" look).
