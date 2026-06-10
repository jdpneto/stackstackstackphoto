# Depth of Field Handheld (Chain Alignment + End-to-End Ship) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the "Depth" (all-in-focus) look end-to-end: blur-robust chain alignment in the engine, a step-paced `lensPosition` focus sweep on device, and the Depth chip + Near/Far Pro controls in the UI — handheld, verified on a physical iPhone.

**Architecture:** The engine gains `Transform2D.composed/inverse` and `AffineAligner.alignChain` (similarity links estimated between *adjacent* brackets — where blur is nearly identical and SSD is valid — validated against physics bounds, composed to the sharpest reference; out-of-bounds links degrade to translation-only). `FocusStacker` switches to chain alignment on by default. The app gains `StackMode.depthOfField`, a `FocusSweep` capture-recipe descriptor, a fake-bracket simulator path, coordinator routing to `FocusStacker`, and the Depth UI. Spec: `docs/superpowers/specs/2026-06-10-depth-of-field-handheld-design.md`.

**Tech Stack:** Pure-Swift SwiftPM engine (`Packages/StackEngineCore`, no platform frameworks), iOS app (SwiftUI + AVFoundation), XCTest.

**Branch:** `feat/phase2-dof-handheld` (already created, spec committed).

**Commands you'll use repeatedly:**

```bash
# Engine tests (fast, no simulator):
cd /Users/davidneto/photo-stack-app/Packages/StackEngineCore && swift test --filter <TestClass>
# App unit tests (simulator; adjust simulator name to one in `xcrun simctl list devices`):
cd /Users/davidneto/photo-stack-app/StackStackStack && xcodebuild test -scheme StackStackStack \
  -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:StackStackStackTests/<TestClass> -quiet
```

**Conventions:** TDD every task (write failing test → run → implement → run → commit). The engine must stay deterministic and platform-free. `StackMode` raw values are persisted storage keys. Match the existing comment density and style — comments explain *why*, reference spec sections.

---

## File map (what's touched, one responsibility each)

| File | Change |
|---|---|
| `Packages/StackEngineCore/Sources/StackEngineCore/Transform2D.swift` | + `composed(with:)`, `inverse` |
| `Packages/StackEngineCore/Sources/StackEngineCore/AffineAligner.swift` | + `ChainBounds`, `alignChain`, private `boundedLink`/`reduceForEstimate` |
| `Packages/StackEngineCore/Sources/StackEngineCore/FocusStacker.swift` | translation-only opt-in alignment → chain alignment (default on); header rewrite |
| `Packages/StackEngineCore/Sources/StackEngineCore/DepthConfig.swift` | `alignFrames` default `true`; delete full-res `.pro` preset |
| `Packages/StackEngineCore/Sources/StackEngineCore/StackMode.swift` | + `case depthOfField` |
| `Packages/StackEngineCore/Sources/StackEngineCore/Pipeline.swift` | exhaustive-switch guard for `.depthOfField` |
| `Packages/StackEngineCore/Tests/.../Transform2DTests.swift` | + compose/inverse tests |
| `Packages/StackEngineCore/Tests/.../AffineAlignerChainTests.swift` | **new** — chain recovery + bounds fallback |
| `Packages/StackEngineCore/Tests/.../FocusStackerTests.swift` | pin blend-only tests `alignFrames: false`; + default-align + drifting-bracket tests |
| `Packages/StackEngineCore/Tests/.../StackModeTests.swift` | **new** — raw-value stability |
| `StackStackStack/StackStackStack/Capture/CaptureService.swift` | + `FocusSweep`, depth recipe, `applying` merge, `supportsDepthOfField` protocol req + default, `usesSteadinessGate` |
| `StackStackStack/StackStackStack/Capture/ProControls.swift` | + `focusSweepNear`/`focusSweepFar` |
| `StackStackStack/StackStackStack/Capture/FakeCaptureService.swift` | + focus-bracket synthesis for sweep recipes |
| `StackStackStack/StackStackStack/Capture/AVCaptureService.swift` | + per-frame lens stepping, `supportsDepthOfField`, sweep state |
| `StackStackStack/StackStackStack/StackCaptureCoordinator.swift` | + depth routing → `FocusStacker`, `supportsDepth`, steadiness gate for depth |
| `StackStackStack/StackStackStack/UI/CaptureView.swift` | + "Depth" label, Near/Far Pro controls, steadiness overlay condition, chip gating |
| `StackStackStack/StackStackStackTests/CaptureRecipeTests.swift` | + sweep tests |
| `StackStackStack/StackStackStackTests/FakeCaptureServiceTests.swift` | + bracket test |
| `StackStackStack/StackStackStackTests/CoordinatorTests.swift` | + depth routing tests |
| `StackStackStack/StackStackStackUITests/StackFlowUITests.swift` | + Depth flow test |

---

### Task 1: `Transform2D.composed(with:)` + `inverse`

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/Transform2D.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/Transform2DTests.swift`

- [ ] **Step 1: Write the failing tests** — append inside the existing `Transform2DTests` class:

```swift
    func testComposedAppliesRightHandSideFirst() {
        let scale = Transform2D.similarity(scale: 2, rotation: 0, tx: 0, ty: 0)
        let shift = Transform2D.similarity(scale: 1, rotation: 0, tx: 3, ty: -1)
        // scale ∘ shift: p → scale(shift(p)) = 2·(p + (3,−1))
        let p = scale.composed(with: shift).apply(1, 1)
        XCTAssertEqual(p.x, 8, accuracy: 1e-5)
        XCTAssertEqual(p.y, 0, accuracy: 1e-5)
    }

    func testComposedWithIdentityIsUnchanged() {
        let t = Transform2D.similarity(scale: 1.04, rotation: 0.02, tx: 2, ty: -1)
        XCTAssertEqual(t.composed(with: .identity), t)
        XCTAssertEqual(Transform2D.identity.composed(with: t), t)
    }

    func testInverseRoundTripsToIdentity() {
        let t = Transform2D.similarity(scale: 1.04, rotation: 0.02, tx: 2, ty: -1)
        let id = t.composed(with: t.inverse)
        XCTAssertEqual(id.a, 1, accuracy: 1e-5)
        XCTAssertEqual(id.b, 0, accuracy: 1e-5)
        XCTAssertEqual(id.c, 0, accuracy: 1e-5)
        XCTAssertEqual(id.d, 1, accuracy: 1e-5)
        XCTAssertEqual(id.tx, 0, accuracy: 1e-4)
        XCTAssertEqual(id.ty, 0, accuracy: 1e-4)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `cd /Users/davidneto/photo-stack-app/Packages/StackEngineCore && swift test --filter Transform2DTests`
Expected: compile error — `composed(with:)`/`inverse` undefined.

- [ ] **Step 3: Implement** — append to the `Transform2D` struct body in `Transform2D.swift`:

```swift
    /// The map that applies `other` FIRST, then `self`: result.apply(p) == self.apply(other.apply(p)).
    /// Used to chain per-pair focus-sweep links into a frame's warp-to-reference (spec §4.2).
    public func composed(with other: Transform2D) -> Transform2D {
        Transform2D(a: a * other.a + b * other.c,
                    b: a * other.b + b * other.d,
                    c: c * other.a + d * other.c,
                    d: c * other.b + d * other.d,
                    tx: a * other.tx + b * other.ty + tx,
                    ty: c * other.tx + d * other.ty + ty)
    }

    /// The inverse map. Similarity/affine registration transforms are invertible; a degenerate
    /// (near-zero determinant) matrix would mean the estimator already failed, so trap loudly.
    public var inverse: Transform2D {
        let det = a * d - b * c
        precondition(abs(det) > 1e-12, "non-invertible transform")
        let ia = d / det, ib = -b / det, ic = -c / det, id = a / det
        return Transform2D(a: ia, b: ib, c: ic, d: id,
                           tx: -(ia * tx + ib * ty), ty: -(ic * tx + id * ty))
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `swift test --filter Transform2DTests`
Expected: PASS (all, including pre-existing).

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): Transform2D.composed(with:) + inverse for chain alignment"
```

---

### Task 2: `ChainBounds` + `AffineAligner.alignChain` (happy path)

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/AffineAligner.swift`
- Create: `Packages/StackEngineCore/Tests/StackEngineCoreTests/AffineAlignerChainTests.swift`

- [ ] **Step 1: Write the failing test** — create `AffineAlignerChainTests.swift`:

```swift
import XCTest
import simd
@testable import StackEngineCore

/// Synthetic focus brackets WITH handheld drift, shared by the chain-aligner and FocusStacker
/// tests (file-scope so both files in this test module can use them — don't instantiate a test
/// case to borrow a fixture). Bracket k is "in focus" (full texture amplitude) only in vertical
/// band k and "defocused" (damped amplitude) elsewhere, then warped by the CUMULATIVE drift D_k
/// (breathing scale + jitter). Low pixel frequency keeps bilinear resampling accurate (same
/// rationale as AffineAlignerTests.texture). Returns the frames and each frame's cumulative
/// drift D_k (D_0 = identity).
func chainBracketFrames(count: Int, w: Int, h: Int,
                        stepScale: Float, stepRot: Float, stepTx: Float, stepTy: Float)
    -> (frames: [PixelImage], drifts: [Transform2D]) {
    var drift = Transform2D.identity
    var frames: [PixelImage] = []
    var drifts: [Transform2D] = []
    for k in 0..<count {
        frames.append(k == 0 ? chainBracketContent(band: k, of: count, w: w, h: h)
                             : AffineAligner.warp(chainBracketContent(band: k, of: count, w: w, h: h), by: drift))
        drifts.append(drift)
        drift = Transform2D.similarity(scale: stepScale, rotation: stepRot, tx: stepTx, ty: stepTy)
            .composed(with: drift)
    }
    return (frames, drifts)
}

/// The un-drifted content of bracket k: shared ramp + texture, amplitude full in band k only.
func chainBracketContent(band: Int, of count: Int, w: Int, h: Int) -> PixelImage {
    var img = PixelImage(width: w, height: h)
    let bandW = w / count
    for y in 0..<h {
        for x in 0..<w {
            let fx = Float(x) / Float(w - 1), fy = Float(y) / Float(h - 1)
            let amp: Float = (x / bandW) == band ? 0.25 : 0.05    // sharp in band k, defocused elsewhere
            let v = 0.15 + 0.5 * fx * fy + amp * sin(20 * fx) * sin(16 * fy)
            img[x, y] = SIMD3<Float>(v, v, v)
        }
    }
    return img
}

final class AffineAlignerChainTests: XCTestCase {
    func params(_ t: Transform2D) -> (scale: Float, rot: Float, tx: Float, ty: Float) {
        ((t.a * t.a + t.c * t.c).squareRoot(), atan2(t.c, t.a), t.tx, t.ty)
    }

    func testChainRecoversCompoundDriftAcrossBlurVaryingBrackets() {
        // 4 brackets, per-step drift well inside ChainBounds (scale +1.2%, ~1px shift).
        let (frames, drifts) = chainBracketFrames(count: 4, w: 96, h: 64,
                                             stepScale: 1.012, stepRot: 0.004, stepTx: 1.0, stepTy: -0.5)
        let transforms = AffineAligner.alignChain(frames, referenceIndex: 0)
        XCTAssertEqual(transforms.count, 4)
        XCTAssertEqual(transforms[0], .identity)
        for k in 1..<4 {
            // frame_k = warp(content_k, D_k) ⇒ the warp-to-reference is D_k⁻¹.
            let want = params(drifts[k].inverse)
            let got = params(transforms[k])
            XCTAssertEqual(got.scale, want.scale, accuracy: 0.02, "frame \(k) scale")
            XCTAssertEqual(got.rot, want.rot, accuracy: 0.02, "frame \(k) rotation")
            XCTAssertEqual(got.tx, want.tx, accuracy: 1.5, "frame \(k) tx")
            XCTAssertEqual(got.ty, want.ty, accuracy: 1.5, "frame \(k) ty")
        }
        // And the warp actually registers: the last (most-drifted) frame, warped back, matches its
        // un-drifted content in the interior.
        let aligned = AffineAligner.warp(frames[3], by: transforms[3])
        let target = chainBracketContent(band: 3, of: 4, w: 96, h: 64)
        var maxd: Float = 0
        for y in 16..<48 { for x in 24..<72 { maxd = max(maxd, abs(aligned[x, y].x - target[x, y].x)) } }
        XCTAssertLessThan(maxd, 0.08, "chain-aligned frame must match its un-drifted content")
    }

    func testReferenceInTheMiddleAlignsBothDirections() {
        let (frames, drifts) = chainBracketFrames(count: 3, w: 96, h: 64,
                                             stepScale: 1.01, stepRot: 0, stepTx: 1.0, stepTy: 0)
        let transforms = AffineAligner.alignChain(frames, referenceIndex: 1)
        XCTAssertEqual(transforms[1], .identity)
        // transforms[k] maps reference(frame-1) coords → frame-k coords: D_k ∘ D_1⁻¹.
        for k in [0, 2] {
            let want = params(drifts[k].composed(with: drifts[1].inverse).inverse)
            let got = params(transforms[k])
            XCTAssertEqual(got.scale, want.scale, accuracy: 0.02, "frame \(k) scale")
            XCTAssertEqual(got.tx, want.tx, accuracy: 1.5, "frame \(k) tx")
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `swift test --filter AffineAlignerChainTests`
Expected: compile error — `alignChain` undefined.

- [ ] **Step 3: Implement** — append to `AffineAligner.swift` (inside the `AffineAligner` enum), plus the `ChainBounds` struct at file scope below the enum:

```swift
    // MARK: - Chain alignment for focus sweeps (spec 2026-06-10 §4.2)

    /// Chain-align a focus sweep: estimate a similarity link between each ADJACENT pair — whose
    /// blur is nearly identical, so the SSD cost is valid there, unlike a sharp-vs-defocused
    /// direct-to-reference fit (the documented spurious-warp failure) — validate each link against
    /// `bounds`, and compose links outward from `referenceIndex`.
    ///
    /// Returns one transform per frame mapping reference coords → that frame's coords (identity at
    /// the reference); `warp(frames[i], by: result[i])` aligns frame i. Frames MUST be in sweep
    /// (focus) order — adjacency is what makes the links well-conditioned.
    public static func alignChain(_ frames: [PixelImage], referenceIndex: Int,
                                  bounds: ChainBounds = .default) -> [Transform2D] {
        precondition(frames.indices.contains(referenceIndex), "referenceIndex out of range")
        var transforms = [Transform2D](repeating: .identity, count: frames.count)
        // Up the sweep: link maps frame[i-1] coords → frame[i] coords.
        for i in (referenceIndex + 1)..<frames.count {
            let link = boundedLink(reference: frames[i - 1], moving: frames[i], bounds: bounds)
            transforms[i] = link.composed(with: transforms[i - 1])
        }
        // Down the sweep: roles swapped so the link maps frame[i+1] coords → frame[i] coords.
        for i in stride(from: referenceIndex - 1, through: 0, by: -1) {
            let link = boundedLink(reference: frames[i + 1], moving: frames[i], bounds: bounds)
            transforms[i] = link.composed(with: transforms[i + 1])
        }
        return transforms
    }

    /// One chain link: estimate the moving→reference similarity on a reduced copy (cheap; matches
    /// the Pipeline's estimate-small/scale-translation-up pattern), then accept it only if it is
    /// physically plausible for one focus step. An implausible fit is a blur difference posing as
    /// warp — re-estimate translation-only, which cannot smear detail.
    private static func boundedLink(reference ref: PixelImage, moving mov: PixelImage,
                                    bounds: ChainBounds) -> Transform2D {
        let (refSmall, factor) = reduceForEstimate(ref)
        let (movSmall, _) = reduceForEstimate(mov)
        let t = estimate(reference: refSmall, moving: movSmall)
        let scale = (t.a * t.a + t.c * t.c).squareRoot()
        let rotation = atan2(t.c, t.a)
        let translation = (t.tx * t.tx + t.ty * t.ty).squareRoot()
        let longEdge = Float(max(refSmall.width, refSmall.height))
        if abs(scale - 1) <= bounds.maxScaleDelta,
           abs(rotation) <= bounds.maxRotationRadians,
           translation <= bounds.maxTranslationFraction * longEdge {
            return Transform2D(a: t.a, b: t.b, c: t.c, d: t.d, tx: t.tx * factor, ty: t.ty * factor)
        }
        let shift = Alignment.estimateTranslation(reference: refSmall, moving: movSmall, searchRange: 8)
        return .similarity(scale: 1, rotation: 0,
                           tx: Float(shift.dx) * factor, ty: Float(shift.dy) * factor)
    }

    /// Halve until the long edge is within `maxEdge`; returns the reduced image and the factor to
    /// scale a reduced-space translation back to input pixels (powers of 2 — exact).
    private static func reduceForEstimate(_ img: PixelImage, maxEdge: Int = 512) -> (PixelImage, Float) {
        var out = img
        var factor: Float = 1
        while max(out.width, out.height) > maxEdge {
            out = ImagePyramid.reduce(out)
            factor *= 2
        }
        return (out, factor)
    }
```

And at file scope (below the `AffineAligner` enum's closing brace):

```swift
/// Per-link plausibility bounds for `AffineAligner.alignChain`. Focus breathing between ADJACENT
/// brackets is a small, monotonic magnification change, and the steadiness gate bounds handheld
/// per-step motion — so a link estimate outside these is a spurious fit (a blur difference being
/// "explained" by warp) and must not be trusted with scale/rotation. (spec 2026-06-10 §4.2)
public struct ChainBounds: Sendable, Equatable {
    /// Max |scale − 1| per step.
    public var maxScaleDelta: Float
    /// Max |rotation| per step (radians).
    public var maxRotationRadians: Float
    /// Max translation magnitude per step, as a fraction of the long edge.
    public var maxTranslationFraction: Float

    public init(maxScaleDelta: Float = 0.02,
                maxRotationRadians: Float = Float.pi / 180,
                maxTranslationFraction: Float = 0.015) {
        self.maxScaleDelta = maxScaleDelta
        self.maxRotationRadians = maxRotationRadians
        self.maxTranslationFraction = maxTranslationFraction
    }

    public static let `default` = ChainBounds()
}
```

- [ ] **Step 4: Run to verify pass**

Run: `swift test --filter AffineAlignerChainTests`
Expected: PASS (2 tests). If the parameter tolerances fail marginally, inspect actual vs expected — widen only the *translation* tolerances (estimation is sub-pixel-honest but the fixture's double resampling costs a little), never the scale/rotation ones past 0.02.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): AffineAligner.alignChain — adjacent-pair similarity links composed to the reference"
```

---

### Task 3: `alignChain` bounds fallback (translation-only on implausible links)

**Files:**
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/AffineAlignerChainTests.swift`

- [ ] **Step 1: Write the failing test** — append to `AffineAlignerChainTests`:

```swift
    func testImplausibleLinkFallsBackToTranslationOnly() {
        // A 4°-per-step rotation is far outside what a steadiness-gated focus sweep can produce.
        // The chain must refuse the similarity fit and degrade that link to translation-only.
        let (frames, _) = chainBracketFrames(count: 2, w: 96, h: 64,
                                        stepScale: 1.0, stepRot: 0.07, stepTx: 0, stepTy: 0)
        let p = params(AffineAligner.alignChain(frames, referenceIndex: 0)[1])
        XCTAssertEqual(p.scale, 1, accuracy: 1e-4, "fallback link must carry no scale")
        XCTAssertEqual(p.rot, 0, accuracy: 1e-4, "fallback link must carry no rotation")
    }

    func testWideBoundsAcceptTheSameLink() {
        // Same frames, but with bounds wide enough for 4°: the similarity fit is accepted —
        // proving the bounds (not the estimator) gated the previous test.
        let (frames, drifts) = chainBracketFrames(count: 2, w: 96, h: 64,
                                             stepScale: 1.0, stepRot: 0.07, stepTx: 0, stepTy: 0)
        let wide = ChainBounds(maxScaleDelta: 0.5, maxRotationRadians: 0.2, maxTranslationFraction: 0.2)
        let p = params(AffineAligner.alignChain(frames, referenceIndex: 0, bounds: wide)[1])
        XCTAssertEqual(p.rot, params(drifts[1].inverse).rot, accuracy: 0.02,
                       "with wide bounds the rotation is recovered")
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `swift test --filter AffineAlignerChainTests`
Expected: `testImplausibleLinkFallsBackToTranslationOnly` may already PASS if Task 2's implementation is correct — that's fine (the implementation landed with the bounds in place). If both pass, this step verifies the behavior is pinned; continue. If `testWideBoundsAcceptTheSameLink` fails, check the bounds comparison logic in `boundedLink`.

- [ ] **Step 3: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "test(core): pin alignChain bounds fallback (implausible link → translation-only)"
```

---

### Task 4: `FocusStacker` chain alignment on by default + `DepthConfig` rework

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/FocusStacker.swift`
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/DepthConfig.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/FocusStackerTests.swift`

- [ ] **Step 1: Update existing tests + write the failing tests.** In `FocusStackerTests.swift`:

(a) Pin the pure-blend tests to `alignFrames: false` (they construct co-registered brackets; the default is about to flip). Change these two config arguments only:

In `testAllInFocusBeatsAnySingleFrame`:
```swift
        let out = try! XCTUnwrap(FocusStacker.allInFocus(frames, config: DepthConfig(workingResolution: nil, maxFrames: 12, alignFrames: false)))
```
In `testWorkingResolutionDownscales`:
```swift
        let out = try! XCTUnwrap(FocusStacker.allInFocus([img, img], config: DepthConfig(workingResolution: 20, maxFrames: 12, alignFrames: false)))
```

(b) Update the comment on `testAlignPathRunsAndStaysSharp` (behavior unchanged — it passes `alignFrames: true` explicitly and co-registered brackets chain to ~identity):
```swift
    func testAlignPathRunsAndStaysSharp() {
        // With alignment ON, co-registered brackets chain to ~identity links and still produce a
        // sharper-than-single composite (exercises the alignChain code path).
```

(c) Append the new tests:

```swift
    func testDefaultConfigAlignsFrames() {
        // Chain alignment is the default — the handheld promise (spec §4.4). `alignFrames: false`
        // remains available for the device alignment-off comparison.
        XCTAssertTrue(DepthConfig.auto.alignFrames)
        XCTAssertTrue(DepthConfig(workingResolution: nil, maxFrames: 5).alignFrames)
    }

    func testThereIsNoFullResProPreset() {
        // 48 MP full-res runs hit the ~3 GB jetsam limit — the managed preset is the only one.
        // (Compile-time check by absence: DepthConfig.pro must not exist. This test documents it.)
        XCTAssertEqual(DepthConfig.auto.workingResolution, 1500)
        XCTAssertEqual(DepthConfig.auto.maxFrames, 10)
    }

    /// Drifting, blur-varying brackets (the real handheld scenario): the default config must
    /// chain-align them and still produce an everywhere-sharper composite.
    func testAllInFocusOnDriftingBracketsBeatsEveryInput() {
        let (frames, _) = chainBracketFrames(count: 3, w: 96, h: 64,
                                             stepScale: 1.01, stepRot: 0.004, stepTx: 1.0, stepTy: -0.5)
        let out = try! XCTUnwrap(FocusStacker.allInFocus(frames, config: DepthConfig(workingResolution: nil, maxFrames: 12)))
        let total = SharpnessMap.compute(out).reduce(0, +)
        for f in frames {
            XCTAssertGreaterThan(total, SharpnessMap.compute(f).reduce(0, +) * 1.2,
                                 "aligned composite must out-sharpen every single drifted bracket")
        }
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `swift test --filter FocusStackerTests`
Expected: `testDefaultConfigAlignsFrames` FAILS (default is `false`); `testThereIsNoFullResProPreset` FAILS only if `.auto` changed — it shouldn't; `testAllInFocusOnDriftingBracketsBeatsEveryInput` FAILS or under-sharpens (no alignment by default yet).

- [ ] **Step 3: Implement.**

`DepthConfig.swift` — replace the `alignFrames` doc + default and delete `.pro`:

```swift
/// Operating point for Depth-of-Field focus stacking (spec 2026-06-10 §4.4, tiered).
public struct DepthConfig: Sendable, Equatable {
    /// Max long-edge in pixels for the working resolution; `nil` = full resolution (no downscale).
    public var workingResolution: Int?
    /// Cap on the number of focus brackets actually stacked (memory/time bound).
    public var maxFrames: Int
    /// Chain-align the brackets to the sharpest reference before stacking (default ON — the
    /// handheld promise). Adjacent-pair similarity links sidestep the sharp-vs-defocused SSD trap
    /// that made direct-to-reference alignment smear detail; `false` remains for tripod use and the
    /// device alignment-off comparison. (spec 2026-06-10 §2, §4.2)
    public var alignFrames: Bool

    public init(workingResolution: Int?, maxFrames: Int, alignFrames: Bool = true) {
        precondition(maxFrames > 0, "maxFrames must be > 0")
        precondition(workingResolution == nil || workingResolution! >= 1, "workingResolution must be >= 1 or nil")
        self.workingResolution = workingResolution
        self.maxFrames = maxFrames
        self.alignFrames = alignFrames
    }

    /// The managed operating point: ~1500 px long edge, ~10 brackets. DoF is the one mode that
    /// holds ALL frames + their pyramids + weight masks simultaneously — ~700 MB peak at 1500 px
    /// for 10 brackets vs ~1.8 GB at 2400 px, which flirts with the ~3 GB jetsam limit. There is
    /// deliberately NO full-resolution preset (a 48 MP run hit that limit). (spec 2026-06-10 §3)
    public static let auto = DepthConfig(workingResolution: 1500, maxFrames: 10)
}
```

`FocusStacker.swift` — replace the header comment and the alignment block:

```swift
import simd

/// End-to-end focus stacking: develop → downscale to the working resolution → chain-align the
/// brackets to the sharpest reference → per-pixel sharpness → selection weights → multiband blend
/// → all-in-focus image (design §13.2, spec 2026-06-10). Returns nil for an empty input.
///
/// Alignment is CHAIN alignment (`AffineAligner.alignChain`, default on): a direct similarity fit
/// of a sharp frame against a defocused one lets the optimizer "explain" blur differences with a
/// spurious warp that smears detail — the failure that originally shelved this look. Adjacent
/// brackets share nearly identical blur, so estimating each link between neighbours and composing
/// to the reference keeps every fit well-conditioned; implausible links degrade to translation-only.
public enum FocusStacker {
    /// All-in-focus composite from already-developed linear frames (all the same dimensions),
    /// in SWEEP ORDER (chain alignment depends on adjacency in focus).
    public static func allInFocus(_ images: [PixelImage], config: DepthConfig) -> PixelImage? {
        guard !images.isEmpty else { return nil }
        let frames = images.prefix(config.maxFrames).map { downscale($0, maxEdge: config.workingResolution) }
        guard frames.count >= 2 else { return frames.first }
        // All brackets must share dimensions for sharpness/selection/blend; reject (nil) rather than trap.
        guard frames.allSatisfy({ $0.width == frames[0].width && $0.height == frames[0].height }) else { return nil }

        let refIdx = ReferenceSelection.sharpestIndex(frames)
        let reference = frames[refIdx]
        let refLuma = Luma.luminance(reference)

        let aligned: [PixelImage]
        if config.alignFrames {
            let transforms = AffineAligner.alignChain(frames, referenceIndex: refIdx)
            aligned = zip(frames, transforms).map { f, t in t == .identity ? f : AffineAligner.warp(f, by: t) }
        } else {
            aligned = frames
        }

        let sharp = aligned.map { SharpnessMap.compute($0) }
        let weights = SelectionMap.weights(sharpness: sharp, guide: refLuma,
                                           width: reference.width, height: reference.height)
        return LaplacianPyramidBlend.blend(images: aligned, weights: weights)
    }
```
(The `allInFocus(rawFrames:)` overload and private `downscale` stay unchanged.)

- [ ] **Step 4: Run the full engine suite** (DepthConfig/FocusStacker are used elsewhere)

Run: `swift test`
Expected: PASS. If anything still references `DepthConfig.pro`, the compile fails — check with `grep -rn "DepthConfig.pro\|\.pro\b" Packages/StackEngineCore/Sources Packages/StackEngineCore/Tests` and fix the call sites (there should be none in `main`).

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): FocusStacker chain alignment on by default; DepthConfig managed-only (no full-res preset)"
```

---

### Task 5: `StackMode.depthOfField` + `Pipeline` exhaustive-switch guard

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/StackMode.swift`
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/Pipeline.swift:45-57` (the `reduceImages` switch)
- Create: `Packages/StackEngineCore/Tests/StackEngineCoreTests/StackModeTests.swift`

- [ ] **Step 1: Write the failing test** — create `StackModeTests.swift`:

```swift
import XCTest
@testable import StackEngineCore

final class StackModeTests: XCTestCase {
    func testRawValuesAreStableStorageKeys() {
        // Raw values are persisted library keys — pin every one (renames silently break libraries).
        XCTAssertEqual(StackMode.noiseReduction.rawValue, "noiseReduction")
        XCTAssertEqual(StackMode.smoothMotion.rawValue, "smoothMotion")
        XCTAssertEqual(StackMode.lightTrails.rawValue, "lightTrails")
        XCTAssertEqual(StackMode.lowLightBoost.rawValue, "lowLightBoost")
        XCTAssertEqual(StackMode.depthOfField.rawValue, "depthOfField")
        XCTAssertEqual(StackMode.allCases.count, 5)
    }

    func testDepthOfFieldIsNotLongExposure() {
        // Depth is a static fast-ish sweep (frame-count sliders, no duration window).
        XCTAssertFalse(StackMode.depthOfField.isLongExposure)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `swift test --filter StackModeTests`
Expected: compile error — `depthOfField` undefined.

- [ ] **Step 3: Implement.**

`StackMode.swift` — add the case and extend the switch:

```swift
public enum StackMode: String, Sendable, Equatable, Hashable, CaseIterable {
    case noiseReduction   // robust (sigma-clipped) mean — clean detail
    case smoothMotion     // plain temporal mean — silky water / clouds
    case lightTrails      // per-channel lighten (max) — light streaks
    case lowLightBoost    // robust mean + exposure gain — brighter night shot
    case depthOfField     // all-in-focus focus sweep — stacked by FocusStacker, not Pipeline.reduce

    /// The looks that capture a continuous burst over a window and use the streaming reducer
    /// (vs. the static fast-burst looks). (design 2026-06-07 §3)
    public var isLongExposure: Bool {
        switch self {
        case .smoothMotion, .lightTrails: return true
        case .noiseReduction, .lowLightBoost, .depthOfField: return false
        }
    }
}
```

`Pipeline.swift` — add to the `reduceImages` switch (after `case .lowLightBoost:`):

```swift
        case .depthOfField:
            preconditionFailure("Depth of Field is stacked by FocusStacker.allInFocus, not Pipeline.reduce — fix the caller's routing")
```

- [ ] **Step 4: Run the full engine suite**

Run: `swift test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): StackMode.depthOfField (stable storage key; routed to FocusStacker)"
```

---

### Task 6: `FocusSweep` recipe + `ProControls` Near/Far + steadiness-gate policy

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/CaptureService.swift`
- Modify: `StackStackStack/StackStackStack/Capture/ProControls.swift`
- Test: `StackStackStack/StackStackStackTests/CaptureRecipeTests.swift`

- [ ] **Step 1: Write the failing tests** — append to `CaptureRecipeTests`:

```swift
    // MARK: - Depth focus sweep (spec 2026-06-10 §5.1)

    func testDepthRecipeHasFullRangeSweepMatchingFrameCount() throws {
        let r = CaptureRecipe.recipe(for: .depthOfField)
        let sweep = try XCTUnwrap(r.focusSweep)
        XCTAssertEqual(sweep.steps, r.frameCount, "one bracket per sweep step")
        XCTAssertEqual(sweep.near, 0)
        XCTAssertEqual(sweep.far, 1)
        XCTAssertEqual(sweep.positions.count, r.frameCount)
        XCTAssertEqual(sweep.positions.first, 0)
        XCTAssertEqual(sweep.positions.last, 1)
    }

    func testSweepPositionsAreMonotonicNearToFar() {
        let positions = CaptureRecipe.FocusSweep(near: 0.2, far: 0.8, steps: 5).positions
        XCTAssertEqual(positions.count, 5)
        for i in 1..<positions.count {
            XCTAssertGreaterThan(positions[i], positions[i - 1], "sweep order is what the chain aligner relies on")
        }
    }

    func testSweepNormalizesAReversedRange() {
        let s = CaptureRecipe.FocusSweep(near: 0.9, far: 0.1, steps: 5)
        XCTAssertEqual(s.near, 0.1, accuracy: 1e-6)
        XCTAssertEqual(s.far, 0.9, accuracy: 1e-6)
    }

    func testApplyingMergesSweepRangeAndKeepsStepsEqualToFrames() throws {
        let r = CaptureRecipe.recipe(for: .depthOfField)
            .applying(ProControls(frameCount: 6, focusSweepNear: 0.2, focusSweepFar: 0.8))
        let sweep = try XCTUnwrap(r.focusSweep)
        XCTAssertEqual(r.frameCount, 6)
        XCTAssertEqual(sweep.steps, 6)
        XCTAssertEqual(sweep.near, 0.2, accuracy: 1e-6)
        XCTAssertEqual(sweep.far, 0.8, accuracy: 1e-6)
    }

    func testManualFocusIsIgnoredForSweepRecipes() {
        // The sweep owns lens position; a lingering Pro single-focus value must not leak in.
        let r = CaptureRecipe.recipe(for: .depthOfField).applying(ProControls(focus: 0.5))
        XCTAssertNil(r.manualFocus)
    }

    func testNonDepthRecipesHaveNoSweep() {
        XCTAssertNil(CaptureRecipe.recipe(for: .noiseReduction).focusSweep)
        XCTAssertNil(CaptureRecipe.recipe(for: .lightTrails).focusSweep)
    }

    func testSteadinessGatePolicy() {
        // Long-exposure looks gate (existing) and Depth gates too — per-step handheld motion must
        // stay inside the chain aligner's bounds (spec 2026-06-10 §5.3).
        XCTAssertTrue(StackMode.smoothMotion.usesSteadinessGate)
        XCTAssertTrue(StackMode.lightTrails.usesSteadinessGate)
        XCTAssertTrue(StackMode.depthOfField.usesSteadinessGate)
        XCTAssertFalse(StackMode.noiseReduction.usesSteadinessGate)
        XCTAssertFalse(StackMode.lowLightBoost.usesSteadinessGate)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `cd /Users/davidneto/photo-stack-app/StackStackStack && xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests/CaptureRecipeTests -quiet`
Expected: compile error — `focusSweep`/`FocusSweep`/`focusSweepNear`/`usesSteadinessGate` undefined.

- [ ] **Step 3: Implement.**

`ProControls.swift` — add the sweep fields (note: they deliberately do NOT affect `hasManualFocusOrExposure` — tap-to-focus still meters exposure during Depth; the sweep owns the lens):

```swift
/// Manual capture overrides (design §11, Pro mode). A `nil` field means Auto for that control.
struct ProControls: Sendable, Equatable {
    var frameCount: Int?        // override the look's burst length
    var iso: Double?            // manual sensor gain (ISO units)
    var shutterSeconds: Double? // manual exposure duration (seconds)
    var focus: Double?          // manual lens position, 0 (near) … 1 (far)
    var focusSweepNear: Double? // Depth: sweep start lens position (0…1); nil = full range
    var focusSweepFar: Double?  // Depth: sweep end lens position (0…1); nil = full range

    static let auto = ProControls()

    /// True if any focus/exposure override is set (frame count doesn't affect AF/AE). Tap-to-focus
    /// requires full-auto AF/AE, so it's gated off when this is true. The Depth sweep range is NOT
    /// included: the sweep owns lens position at capture regardless, and a tap should still meter
    /// exposure. (spec 2026-06-10 §6)
    var hasManualFocusOrExposure: Bool { focus != nil || iso != nil || shutterSeconds != nil }
}
```

`CaptureService.swift` — inside `CaptureRecipe`, add the `FocusSweep` type, the stored property, the new recipe case, and the `applying` merge. Full updated struct portions:

```swift
struct CaptureRecipe: Sendable, Equatable {
    /// A Depth focus sweep: M evenly-spaced lens positions from `near` to `far` (normalized
    /// `lensPosition` space: 0 = closest, 1 = infinity). Frames are captured IN SWEEP ORDER —
    /// the chain aligner depends on adjacency in focus. (spec 2026-06-10 §5.1)
    struct FocusSweep: Sendable, Equatable {
        let near: Float
        let far: Float
        let steps: Int

        /// Normalizes a reversed range and clamps to 0…1 so a wild UI value can't escape.
        init(near: Float, far: Float, steps: Int) {
            let lo = min(max(min(near, far), 0), 1)
            let hi = min(max(max(near, far), 0), 1)
            self.near = lo
            self.far = hi
            self.steps = max(steps, 1)
        }

        /// The per-frame lens positions, near → far inclusive.
        var positions: [Float] {
            guard steps > 1 else { return [(near + far) / 2] }
            return (0..<steps).map { near + (far - near) * Float($0) / Float(steps - 1) }
        }
    }

    var frameCount: Int
    var durationSeconds: Double
    var manualISO: Float?             // nil = auto/locked exposure gain (device path)
    var manualShutterSeconds: Double? // nil = auto/locked exposure duration (device path)
    var manualFocus: Float?           // nil = auto/locked focus; else lens position 0…1 (device path)
    var focusSweep: FocusSweep?       // non-nil = Depth: step the lens per frame (device path)

    /// Hard ceiling on burst length. The on-device develop+stack memory/time envelope is sized for
    /// this; beyond it the app risks the ~3 GB jetsam kill. (design 2026-06-07 §2)
    static let maxBurstFrames = 20

    init(frameCount: Int, durationSeconds: Double,
         manualISO: Float? = nil, manualShutterSeconds: Double? = nil, manualFocus: Float? = nil,
         focusSweep: FocusSweep? = nil) {
        precondition(frameCount > 0, "frameCount must be > 0")
        self.frameCount = frameCount
        self.durationSeconds = durationSeconds
        self.manualISO = manualISO
        self.manualShutterSeconds = manualShutterSeconds
        self.manualFocus = manualFocus
        self.focusSweep = focusSweep
    }
```

Add the depth case to `recipe(for:)` (after `.lightTrails`):

```swift
        // Depth: a near→far focus sweep, one RAW bracket per lens position; exposure/WB locked.
        // Step-paced (focus settle per frame), so duration here only sets the pacing floor.
        case .depthOfField:
            return CaptureRecipe(frameCount: 10, durationSeconds: 1.0,
                                 focusSweep: FocusSweep(near: 0, far: 1, steps: 10))
```

Replace `applying(_:)`:

```swift
    /// Merge manual Pro overrides onto a per-look recipe. Auto (nil) fields leave the look default;
    /// the frame-count override is clamped to ≥ 1 so the recipe stays valid. For a sweep recipe the
    /// sweep absorbs the Near/Far overrides and tracks the final frame count (steps == frames), and
    /// the single manual-focus override is dropped — the sweep owns lens position.
    func applying(_ pro: ProControls) -> CaptureRecipe {
        let count = min(Self.maxBurstFrames, max(1, pro.frameCount ?? frameCount))
        let sweep = focusSweep.map { s in
            FocusSweep(near: pro.focusSweepNear.map(Float.init) ?? s.near,
                       far: pro.focusSweepFar.map(Float.init) ?? s.far,
                       steps: count)
        }
        return CaptureRecipe(frameCount: count,
                             durationSeconds: durationSeconds,
                             manualISO: pro.iso.map(Float.init) ?? manualISO,
                             manualShutterSeconds: pro.shutterSeconds ?? manualShutterSeconds,
                             manualFocus: sweep != nil ? nil : (pro.focus.map(Float.init) ?? manualFocus),
                             focusSweep: sweep)
    }
```

Add the steadiness policy extension at the bottom of `CaptureService.swift`:

```swift
extension StackMode {
    /// Looks whose capture quality depends on holding a pose: the long-exposure window looks, and
    /// the Depth focus sweep (per-step handheld motion must stay inside the chain aligner's
    /// `ChainBounds`). Drives the capture gate and the steadiness overlay. (spec 2026-06-10 §5.3)
    var usesSteadinessGate: Bool { isLongExposure || self == .depthOfField }
}
```

- [ ] **Step 4: Run to verify pass**

Run: same `xcodebuild test … -only-testing:StackStackStackTests/CaptureRecipeTests -quiet`
Expected: PASS (all, including pre-existing recipe tests).

- [ ] **Step 5: Commit**

```bash
git add StackStackStack
git commit -m "feat(capture): FocusSweep recipe descriptor + Pro Near/Far overrides + Depth steadiness-gate policy"
```

---

### Task 7: `FakeCaptureService` synthetic focus brackets

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/FakeCaptureService.swift`
- Test: `StackStackStack/StackStackStackTests/FakeCaptureServiceTests.swift`

- [ ] **Step 1: Write the failing test** — append to the existing `FakeCaptureServiceTests` class:

```swift
    func testFocusSweepRecipeProducesDistinctOrderedBrackets() async throws {
        let svc = FakeCaptureService(width: 32, height: 16)
        let recipe = CaptureRecipe.recipe(for: .depthOfField).applying(ProControls(frameCount: 4))
        let frames = try await svc.captureBurst(recipe: recipe)
        XCTAssertEqual(frames.count, 4)
        // Each bracket is sharp in a different band → mosaics must differ pairwise.
        for i in 0..<frames.count {
            for j in (i + 1)..<frames.count {
                XCTAssertNotEqual(frames[i].mosaic, frames[j].mosaic,
                                  "bracket \(i) and \(j) must differ (different sharp band)")
            }
        }
    }

    func testFocusSweepReportsProgressPerBracket() async throws {
        let svc = FakeCaptureService(width: 32, height: 16)
        let recipe = CaptureRecipe.recipe(for: .depthOfField).applying(ProControls(frameCount: 3))
        let counter = ProgressCounter()
        _ = try await svc.captureBurst(recipe: recipe, isSteady: { true },
                                       onProgress: { n in Task { await counter.record(n) } })
        // Give the recording tasks a beat to land.
        try await Task.sleep(nanoseconds: 50_000_000)
        let seen = await counter.values
        XCTAssertEqual(seen, [1, 2, 3])
    }
```

And add this helper actor at file scope (outside the test class) if the file doesn't already have one:

```swift
private actor ProgressCounter {
    private(set) var values: [Int] = []
    func record(_ n: Int) { values.append(n) }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `xcodebuild test … -only-testing:StackStackStackTests/FakeCaptureServiceTests -quiet`
Expected: `testFocusSweepRecipeProducesDistinctOrderedBrackets` FAILS — the fake ignores `focusSweep` and returns moving-dot frames whose backgrounds happen to share most values (the pairwise mosaic comparison may even pass by luck of the `k * 17` noise; if both tests pass before implementing, strengthen nothing — proceed, the implementation is still required for the *bracket* content the coordinator test in Task 8 relies on).

- [ ] **Step 3: Implement** — in `FakeCaptureService.swift`, branch at the top of `captureBurst` and add the bracket factory:

```swift
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool,
                      onProgress: (@Sendable (Int) -> Void)?) async throws -> [RawSensorFrame] {
        await Task.yield()   // model a non-instant capture so the shutter's re-entrancy guard applies
        if let sweep = recipe.focusSweep {
            return focusBrackets(steps: sweep.positions.count, onProgress: onProgress)
        }
        let n = max(recipe.frameCount, 1)
        // …existing moving-object body unchanged…
```

Append the private factory below `captureBurst`:

```swift
    /// Focus-bracket fake (spec 2026-06-10 §5.5): frame k carries high-amplitude checker texture
    /// only in vertical band k and a dim texture elsewhere (synthetic defocus), plus a small
    /// per-frame horizontal drift so the chain aligner has real work. Drift is translation-only —
    /// scaling a Bayer mosaic would corrupt the CFA pattern; the engine's unit tests cover scale.
    /// No single frame is sharp in every band; the stacked result must be.
    private func focusBrackets(steps: Int, onProgress: (@Sendable (Int) -> Void)?) -> [RawSensorFrame] {
        (0..<steps).map { k in
            var mosaic = [UInt16](repeating: 0, count: width * height)
            let band = max(width / steps, 1)
            let drift = k                       // px of horizontal drift per frame (handheld jitter)
            for y in 0..<height {
                for x in 0..<width {
                    let sx = x + drift          // shift the PATTERN, not the band, so bands stay put
                    let inBand = min(x / band, steps - 1) == k
                    let amp = inBand ? 350 : 40
                    let checker = ((sx / 2) + (y / 2)) % 2 == 0 ? amp : -amp
                    mosaic[y * width + x] = UInt16(max(64, 500 + checker))
                }
            }
            let frame = RawSensorFrame(width: width, height: height, mosaic: mosaic,
                                       blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
                                       wbGains: SIMD3<Float>(1, 1, 1))
            onProgress?(k + 1)
            return frame
        }
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `xcodebuild test … -only-testing:StackStackStackTests/FakeCaptureServiceTests -quiet`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add StackStackStack
git commit -m "feat(capture): FakeCaptureService synthesizes drifting focus brackets for sweep recipes"
```

---

### Task 8: Coordinator — route Depth to `FocusStacker`, `supportsDepth`, steadiness gate

**Files:**
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Modify: `StackStackStack/StackStackStack/Capture/CaptureService.swift` (protocol requirement)
- Test: `StackStackStack/StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: Write the failing tests** — append to `CoordinatorTests`:

```swift
    @MainActor
    func testDepthShootRoutesToFocusStackerAndSaves() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .depthOfField
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertNil(coord.lastError)
        XCTAssertNotNil(coord.lastResultJPEG)
        let record = try XCTUnwrap(store.loadAll().first)
        XCTAssertEqual(record.mode, "depthOfField")
        XCTAssertEqual(record.frameCount, 10, "Depth default is a 10-bracket sweep")
    }

    @MainActor
    func testDepthHonoursProFrameCount() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .depthOfField
        coord.pro = ProControls(frameCount: 4)
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 4)
    }

    @MainActor
    func testSupportsDepthIsTrueWithTheFake() async {
        let (coord, _) = makeCoordinator()
        _ = await coord.startPreview()
        XCTAssertTrue(coord.supportsDepth, "the fake always supports a focus sweep")
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `xcodebuild test … -only-testing:StackStackStackTests/CoordinatorTests -quiet`
Expected: compile error — `supportsDepth` undefined (and `.depthOfField` shoot would trip `Pipeline.reduceImages`' precondition if routing were missing).

- [ ] **Step 3: Implement.**

`CaptureService.swift` — add the capability to the protocol (below `setFocusExposure`) and a default:

```swift
    /// Whether the device can step lens position for a Depth focus sweep (manual-focus hardware).
    /// Drives Depth-chip gating in the UI. (spec 2026-06-10 §5.4)
    var supportsDepthOfField: Bool { get }
```

and in the `extension CaptureService` block:

```swift
    var supportsDepthOfField: Bool { true }   // overridden by the device service after configuring
```

`StackCaptureCoordinator.swift`:

(a) Add the published capability (next to the other `@Published` properties):

```swift
    /// Whether the camera can run a Depth focus sweep (manual-focus hardware). Optimistic `true`
    /// until the preview configures the session; the UI disables the Depth chip when false.
    @Published private(set) var supportsDepth = true
```

(b) Update `startPreview()` to refresh it:

```swift
    /// Start the live preview and return its layer (nil if unavailable, e.g. the Simulator fake).
    /// Also the earliest point the device capability probe is meaningful (session configured).
    func startPreview() async -> CALayer? {
        let layer = await capture.startPreview()
        supportsDepth = capture.supportsDepthOfField
        return layer
    }
```

(c) In `shoot()`, replace the two `mode.isLongExposure` steadiness references with the policy property:

```swift
        let gating: @Sendable () -> Bool
        if mode.usesSteadinessGate {
            steadiness.start()
            gating = { [steadiness] in steadiness.isSteady }
        } else {
            gating = { true }
        }
        defer {
            if mode.usesSteadinessGate { steadiness.stop() }
```

(d) Add the depth branch to `makeJPEG` (between the long-exposure and static branches):

```swift
            } else if mode == .depthOfField {
                // Depth: develop all brackets at the managed depth resolution, then focus-stack.
                // maxFrames follows the actual capture (the recipe already capped it). (spec §6)
                let developed = Pipeline.developedFrames(frames, binnedDevelop: true,
                                                         workingResolution: depthWorkingResolution)
                if shouldCancel() { throw CancellationError() }
                guard let stacked = FocusStacker.allInFocus(
                    developed,
                    config: DepthConfig(workingResolution: depthWorkingResolution,
                                        maxFrames: max(frames.count, 1)))
                else { throw ProcessingError.focusStackFailed }
                result = stacked
            } else {
```

(e) Add the constant next to `managedWorkingResolution` and the error type at file scope (bottom of the file):

```swift
    /// Depth working resolution (long-edge px). Lower than the 2400 the other looks use: DoF holds
    /// ALL brackets + Laplacian pyramids + weight masks at once (~700 MB at 1500 px for 10 brackets
    /// vs ~1.8 GB at 2400 px, which flirts with the ~3 GB jetsam limit). (spec 2026-06-10 §4.4)
    nonisolated private static let depthWorkingResolution = 1500
```

```swift
/// Background-processing failures surfaced to the capture screen's status label.
enum ProcessingError: LocalizedError {
    case focusStackFailed
    var errorDescription: String? {
        switch self {
        case .focusStackFailed: return "Couldn't combine the focus brackets. Please try again."
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `xcodebuild test … -only-testing:StackStackStackTests/CoordinatorTests -quiet`
Expected: PASS (all, including pre-existing).

- [ ] **Step 5: Commit**

```bash
git add StackStackStack
git commit -m "feat(app): route Depth to FocusStacker; supportsDepth capability; steadiness gate for the sweep"
```

---

### Task 9: `AVCaptureService` — step-paced lens sweep + capability probe (device path)

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/AVCaptureService.swift`

Device-only behavior — compile-verified now, exercised on hardware in Task 12. No simulator test can drive `AVCaptureDevice`; correctness rails are: the sweep reuses the existing sequential one-in-flight state machine untouched, and every new step is capability-guarded with a fire-anyway fallback (a failed focus step degrades to "capture at current focus", never a stall).

- [ ] **Step 1: Add sweep state.** In the "Touched only on stateQueue" block (below `onProgress`):

```swift
    private var sweepPositions: [Float] = []     // Depth: per-frame lens positions; empty = no sweep
```

In `captureBurst`'s `stateQueue.async` initialization block (next to `self.onProgress = onProgress`):

```swift
                self.sweepPositions = recipe.focusSweep?.positions ?? []
```

And in `finishLocked()` (with the other releases):

```swift
        sweepPositions = []
```

- [ ] **Step 2: Add the capability flag.** In the "Touched only on stateQueue" block:

```swift
    private var manualLensSupported = true       // probed at configure; optimistic until then
```

At the end of `ensureConfigured()`'s success path (after `self.configured = true`):

```swift
                    let lensSupported = dev.isLockingFocusWithCustomLensPositionSupported
                    self.stateQueue.async { self.manualLensSupported = lensSupported }
```

And the protocol property (anywhere in the class body):

```swift
    /// Manual-focus capability, probed when the session configures (spec 2026-06-10 §5.4).
    var supportsDepthOfField: Bool { stateQueue.sync { manualLensSupported } }
```

- [ ] **Step 3: Step the lens per frame.** In `startNextFrameLocked(gen:)`, capture the frame index before building settings (after the `self.gateAttempts = 0` line):

```swift
        let frameIndex = self.totalFrames - self.remaining
        let sweepPosition: Float? =
            (frameIndex >= 0 && frameIndex < self.sweepPositions.count) ? self.sweepPositions[frameIndex] : nil
```

Then change the `sessionQueue.async` block to route through the focus step:

```swift
        self.sessionQueue.async {
            // Don't fire for a superseded burst or a stopped session — fail this frame so the burst
            // advances instead of stalling until the watchdog.
            let active = self.stateQueue.sync { self.generation == gen }
            guard active, self.session.isRunning else {
                self.stateQueue.async { self.advanceLocked(completedID: id) }
                return
            }
            if let dims = self.cappedPhotoDimensions { settings.maxPhotoDimensions = dims }
            self.stepSweepFocusThenFire(position: sweepPosition) {
                self.output.capturePhoto(with: settings, delegate: self)
            }
        }
```

Add the helper (near `lockExposureAndFocus`):

```swift
    /// Depth sweep: step the lens to this frame's position and WAIT for the lens to settle before
    /// firing — capturing mid-travel would blur the bracket. No sweep position, no manual-lens
    /// support, or a config-lock failure all degrade to firing at the current focus (a worse
    /// bracket beats a stalled burst; the watchdog never has to save us). Runs on `sessionQueue`.
    /// (spec 2026-06-10 §5.2; same settle pattern as lockExposureAndFocus's manual-focus path)
    private func stepSweepFocusThenFire(position: Float?, fire: @escaping () -> Void) {
        guard let position, let dev = self.device,
              dev.isLockingFocusWithCustomLensPositionSupported else { fire(); return }
        do { try dev.lockForConfiguration() } catch { fire(); return }
        dev.setFocusModeLocked(lensPosition: min(max(position, 0), 1)) { _ in
            self.sessionQueue.async { fire() }
        }
        dev.unlockForConfiguration()
    }
```

- [ ] **Step 4: Compile-verify (simulator build) + run the app unit tests**

```bash
cd /Users/davidneto/photo-stack-app/StackStackStack && xcodebuild -scheme StackStackStack \
  -project StackStackStack.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16' build -quiet
xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj \
  -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:StackStackStackTests -quiet
```
Expected: build succeeds; all unit tests PASS.

- [ ] **Step 5: Commit**

```bash
git add StackStackStack
git commit -m "feat(capture): AVCaptureService lens-stepped focus sweep + manual-focus capability probe"
```

---

### Task 10: Capture UI — Depth chip, Near/Far Pro controls, steadiness overlay, gating

**Files:**
- Modify: `StackStackStack/StackStackStack/UI/CaptureView.swift`
- Test: `StackStackStack/StackStackStackUITests/StackFlowUITests.swift`

- [ ] **Step 1: Write the failing UI test** — append to `StackFlowUITests` (mirror the file's existing launch/wait helpers if it has them; the plain form below is correct regardless):

```swift
    func testDepthLookProducesASavedStack() throws {
        let app = XCUIApplication()
        app.launch()
        app.buttons["look-depthOfField"].tap()
        app.buttons["shutter"].tap()
        // Capture (10 fake brackets) + background focus stack — generous timeout for CI simulators.
        XCTAssertTrue(app.staticTexts["Saved ✓"].waitForExistence(timeout: 60),
                      "Depth shoot must produce a saved stack")
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `xcodebuild test … -only-testing:StackStackStackUITests/StackFlowUITests/testDepthLookProducesASavedStack -quiet`
Expected: FAIL — `look-depthOfField` exists (the picker iterates `StackMode.allCases`, so the chip appears as soon as the case exists) **but its label is the raw case name**: `shortLabel`'s switch won't compile without the new case — so actually expect a BUILD FAILURE first ("switch must be exhaustive" in `CaptureView.swift`). That compile error is this step's "red".

- [ ] **Step 3: Implement.** In `CaptureView.swift`:

(a) `shortLabel` extension — add the case:

```swift
        case .depthOfField:   return "Depth"
```

(b) `lookPicker` — gate the Depth chip on capability (replace the `.disabled(coordinator.isBusy)` line on the chip button):

```swift
                .disabled(coordinator.isBusy || (m == .depthOfField && !coordinator.supportsDepth))
```

and add a one-line explanation under the `HStack` (inside `lookPicker`'s enclosing layout — wrap the existing `HStack` in a `VStack(spacing: 4)` and append):

```swift
            if !coordinator.supportsDepth {
                Text("Depth needs manual-focus hardware this camera doesn't have")
                    .font(.caption2).foregroundColor(.white.opacity(0.7))
            }
```

(c) `steadinessOverlay` — show during Depth sweeps too:

```swift
        if coordinator.isCapturing && coordinator.mode.usesSteadinessGate {
```

(d) `proPanel` — for Depth, swap the single Focus control for Near/Far (replace the existing `optControl("Focus", …)` call):

```swift
                    if coordinator.mode == .depthOfField {
                        // The sweep owns lens position; Near/Far set its range (spec 2026-06-10 §6).
                        optControl("Near", unit: "",
                                   binding: $coordinator.pro.focusSweepNear, range: 0...1, step: 0.01,
                                   defaultValue: 0) { String(format: "%.2f", $0) }
                        optControl("Far", unit: "",
                                   binding: $coordinator.pro.focusSweepFar, range: 0...1, step: 0.01,
                                   defaultValue: 1) { String(format: "%.2f", $0) }
                    } else {
                        optControl("Focus", unit: "",
                                   binding: $coordinator.pro.focus, range: 0...1, step: 0.01, defaultValue: 0.5) {
                                       String(format: "%.2f", $0) }
                    }
```

- [ ] **Step 4: Run to verify pass**

```bash
xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:StackStackStackUITests/StackFlowUITests/testDepthLookProducesASavedStack -quiet
```
Expected: PASS (the simulator fake feeds 10 drifting brackets through the full chain-align + focus-stack path).

- [ ] **Step 5: Commit**

```bash
git add StackStackStack
git commit -m "feat(ui): Depth look chip (capability-gated) + Near/Far sweep Pro controls + sweep steadiness overlay"
```

---

### Task 11: Full-suite verification + loose-end sweep

- [ ] **Step 1: Engine suite**

Run: `cd /Users/davidneto/photo-stack-app/Packages/StackEngineCore && swift test`
Expected: PASS, zero failures.

- [ ] **Step 2: App unit + UI suites**

```bash
cd /Users/davidneto/photo-stack-app/StackStackStack
xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:StackStackStackTests -quiet
xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:StackStackStackUITests/StackFlowUITests -quiet
```
Expected: PASS. (The CLAUDE.md rule applies: any failing test found here gets fixed, whoever broke it.)

- [ ] **Step 3: Loose ends**

```bash
grep -rn "DepthConfig.pro" /Users/davidneto/photo-stack-app --include="*.swift"   # expect: no hits
grep -rn "alignFrames: false" /Users/davidneto/photo-stack-app/Packages --include="*.swift"  # expect: only deliberate test pins
```

- [ ] **Step 4: Commit anything outstanding**

```bash
git status --short   # should be clean; commit any stragglers with an appropriate message
```

---

### Task 12: Device verification + review + PR (manual gate — involve the user)

The acceptance bar (spec §8): **handheld** focus sweep of a close-subject + far-background scene on the physical iPhone → visibly all-in-focus, no smearing/ghosting, no jetsam. Use the mobile-mcp workflow (memory: `mobile-mcp-device-setup`).

- [ ] **Step 1: Build to the device** (or ask the user to run it from Xcode), select **Depth**, shoot the test scene handheld. Verify in the gallery: subject AND background sharp, no doubled edges.
- [ ] **Step 2: Alignment-off comparison.** Temporarily set `dumpFramesForDiagnostics = true` in `StackCaptureCoordinator.swift`, reshoot, pull `Documents/diag` frames, and run them offline through `FocusStacker.allInFocus` with `alignFrames: false` vs default (the `_DebugRealFrames` harness pattern in the engine tests shows the load/save plumbing). Confirm the chain visibly earns its keep, then **revert the flag**.
- [ ] **Step 3: Watch memory.** Shoot a 20-bracket Pro sweep; confirm no jetsam (Xcode memory gauge < ~1.5 GB peak).
- [ ] **Step 4: Run `/code-review`** (CLAUDE.md: ALWAYS before merging PRs) and address findings.
- [ ] **Step 5: Open the PR** (branch `feat/phase2-dof-handheld` → `main`) with a summary referencing the spec, and device-verification evidence (screenshots).

---

## Self-review notes (done at plan-writing time)

- **Spec coverage:** §4.1→Task 1, §4.2→Tasks 2–3, §4.3/§4.4→Task 4, §4.5→Tasks 2–4 tests, §5.1→Task 6, §5.2→Task 9, §5.3→Tasks 6+8 (+overlay in 10), §5.4→Tasks 8–10, §5.5→Task 7, §6→Tasks 5, 8, 10, §7 error rows→Tasks 2 (bounds fallback), 4 (<2 frames, pre-existing), 8 (`focusStackFailed`), 9 (fire-anyway fallback), 10 (chip gating), §8→Tasks 2–4/7–8/10–12, §9→Task 12.
- **Known judgment calls:** `Pipeline.reduceImages` traps on `.depthOfField` (misrouting is a programmer error, covered by coordinator tests); the fake's drift is translation-only (Bayer-space scaling would corrupt the CFA — engine tests cover scale); `ProgressCounter` actor may already exist in `FakeCaptureServiceTests` — reuse it if so.
