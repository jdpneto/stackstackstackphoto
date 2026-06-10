import XCTest
import simd
@testable import StackEngineCore

/// Synthetic focus brackets WITH handheld drift, shared by the chain-aligner and FocusStacker
/// tests (file-scope so both files in this test module can use them — don't instantiate a test
/// case to borrow a fixture). Bracket k is "in focus" (full texture amplitude) only in vertical
/// band k and "defocused" (damped amplitude) elsewhere, then warped by the CUMULATIVE drift D_k
/// (breathing scale + jitter). Low pixel frequency keeps bilinear resampling accurate (same
/// rationale as AffineAlignerTests.texture).
///
/// `steps` contains one per-step transform; `frames.count == steps.count + 1`. The cumulative
/// drift is `drifts[0] = .identity`, `drifts[k] = steps[k-1].composed(with: drifts[k-1])`.
/// Using VARIED (non-constant) steps makes the composition non-commutative, so tests catch a
/// transposed `link.composed(with: transforms[i-1])` vs `transforms[i-1].composed(with: link)`.
func chainBracketFrames(w: Int, h: Int, steps: [Transform2D])
    -> (frames: [PixelImage], drifts: [Transform2D]) {
    let count = steps.count + 1
    var drifts: [Transform2D] = [.identity]
    for step in steps {
        drifts.append(step.composed(with: drifts.last!))
    }
    var frames: [PixelImage] = []
    for k in 0..<count {
        let content = chainBracketContent(band: k, of: count, w: w, h: h)
        frames.append(k == 0 ? content : AffineAligner.warp(content, by: drifts[k]))
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
        // 4 brackets: varied per-step drift (alternating rotation sign, varied tx/ty) — all
        // inside ChainBounds: |scale−1| ≤ 0.012, |rot| ≤ 0.004, |t| ≤ ~1.1 px.
        // Steps are NON-CONSTANT and VARIED so composition is non-commutative in general.
        let steps: [Transform2D] = [
            .similarity(scale: 1.012, rotation:  0.004, tx:  1.0, ty: -0.5),
            .similarity(scale: 1.008, rotation: -0.003, tx:  0.7, ty:  0.8),
            .similarity(scale: 1.010, rotation:  0.002, tx: -0.6, ty: -0.9),
        ]
        let (frames, drifts) = chainBracketFrames(w: 96, h: 64, steps: steps)
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
        // 3 brackets with varied steps so composition is non-commutative.
        let steps: [Transform2D] = [
            .similarity(scale: 1.010, rotation:  0.003, tx:  1.0, ty: -0.4),
            .similarity(scale: 1.008, rotation: -0.002, tx:  0.8, ty:  0.5),
        ]
        let (frames, drifts) = chainBracketFrames(w: 96, h: 64, steps: steps)
        let transforms = AffineAligner.alignChain(frames, referenceIndex: 1)
        XCTAssertEqual(transforms[1], .identity)
        // transforms[k] maps reference(frame-1) coords → frame-k coords.
        // Derivation: aligned_k[p] = frame_k[T(p)] must reproduce the reference geometry, i.e.
        // D_k·T(p) = D_1·p  ⇒  T = D_k⁻¹ ∘ D_1.
        for k in [0, 2] {
            let want = params(drifts[k].inverse.composed(with: drifts[1]))
            let got = params(transforms[k])
            XCTAssertEqual(got.scale, want.scale, accuracy: 0.02, "frame \(k) scale")
            XCTAssertEqual(got.tx, want.tx, accuracy: 1.5, "frame \(k) tx")
            XCTAssertEqual(got.ty, want.ty, accuracy: 1.5, "frame \(k) ty")
        }
    }

    /// Directly verifies that `accumulateLinks` uses `link.composed(with: prev)` (not reversed)
    /// by injecting exact algebraically-constructed links — bypassing the estimator entirely so
    /// composition-order errors are never masked by estimation noise.
    ///
    /// WHY the integration tests above cannot catch this:
    ///   For within-ChainBounds drifts (|rot| ≤ 0.0175 rad, |tx| ≤ 1.44 px), the Lie commutator
    ///   [link₂, link₁] is O(|rot|·|tx|) ≈ 0.003–0.05 px — sub-pixel and undetectable at 1.5 px
    ///   tolerance. The integration tests verify ESTIMATION QUALITY; this test isolates the
    ///   COMPOSITION-ORDER algebra in `accumulateLinks` via exact inputs.
    ///
    /// Mutation sensitivity: for scale+translation links with (scale−1)·tx terms:
    ///   link2.composed(with: link1).tx = scale2 · tx1 + tx2
    ///   link1.composed(with: link2).tx = scale1 · tx2 + tx1   [wrong order]
    ///   difference = |(scale2−1)·tx1 − (scale1−1)·tx2|
    ///
    /// With link1=(scale=1.5, tx=3) and link2=(scale=0.7, tx=−4):
    ///   diff = |(0.7−1)·3 − (1.5−1)·(−4)| = |−0.9 + 2.0| = 1.1 px >> 1e-4 tolerance.
    func testAccumulateLinksOrderIsLinkComposedWithPrev() {
        // Pure scale+translation links (rotation=0). These do NOT commute when scales differ:
        //   L2 ∘ L1 tx = scale2*tx1 + tx2, L1 ∘ L2 tx = scale1*tx2 + tx1  (generally unequal).
        let link1 = Transform2D.similarity(scale: 1.5, rotation: 0, tx:  3.0, ty: 0)
        let link2 = Transform2D.similarity(scale: 0.7, rotation: 0, tx: -4.0, ty: 0)
        let link3 = Transform2D.similarity(scale: 1.2, rotation: 0, tx:  2.0, ty: 0)

        // Correct accumulated transforms (link.composed(with: prev)):
        //   t1 = link1; t2 = link2 ∘ link1; t3 = link3 ∘ link2 ∘ link1
        let t1_want = link1
        let t2_want = link2.composed(with: link1)
        let t3_want = link3.composed(with: t2_want)

        // Wrong accumulated transforms (prev.composed(with: link)):
        //   t1 = link1 (same for first step: id.composed(with: link1) = link1)
        //   t2 = link1 ∘ link2; t3 = (link1 ∘ link2) ∘ link3
        let t2_wrong = link1.composed(with: link2)
        _ = t2_wrong.composed(with: link3)   // t3_wrong: not asserted, but kept to document the wrong chain

        // Verify the fixture is non-degenerate: the two orderings differ by > 1 px.
        XCTAssertGreaterThan(abs(t2_want.tx - t2_wrong.tx), 1.0,
            "t2_want and t2_wrong must differ by > 1 px — fixture is degenerate")

        // Run accumulateLinks with exact links and ref=0.
        let links: [Transform2D] = [.identity, link1, link2, link3]
        let result = AffineAligner.accumulateLinks(links, referenceIndex: 0)

        XCTAssertEqual(result[0], .identity)
        // t1: both orderings agree on the first step (trivially identity.composed(with:) = itself).
        XCTAssertEqual(result[1].tx, t1_want.tx, accuracy: 1e-4, "t1 tx")

        // t2: correct = link2 ∘ link1; wrong = link1 ∘ link2 → diff = 1.1 px.
        XCTAssertEqual(result[2].tx, t2_want.tx, accuracy: 1e-4,
            "t2 tx must be link2∘link1 not link1∘link2 (catches transposed loop)")
        XCTAssertNotEqual(result[2].tx, t2_wrong.tx, "t2 correct and wrong must be distinguishable")

        // t3: accumulated error grows further.
        XCTAssertEqual(result[3].tx, t3_want.tx, accuracy: 1e-4,
            "t3 tx must follow correct composition chain")
    }

    // MARK: - Part B: down-sweep composition order

    /// Mirrors `testAccumulateLinksOrderIsLinkComposedWithPrev` for the DOWN-SWEEP path
    /// (referenceIndex at the end, i.e. referenceIndex = links.count - 1).
    ///
    /// Down-sweep code: `transforms[i] = links[i].composed(with: transforms[i+1])`.
    ///
    /// Links array: [link3, link1, link2, .identity], referenceIndex = 3.
    /// Correct accumulated transforms:
    ///   transforms[3] = .identity   (reference)
    ///   transforms[2] = link2.composed(with: .identity)           = link2
    ///   transforms[1] = link1.composed(with: transforms[2])       = link1 ∘ link2
    ///   transforms[0] = link3.composed(with: transforms[1])       = link3 ∘ link1 ∘ link2
    ///
    /// Wrong (transposed: transforms[i+1].composed(with: links[i])):
    ///   transforms[2] = .identity.composed(with: link2)           = link2  (trivially same)
    ///   transforms[1] = transforms[2].composed(with: link1)       = link2 ∘ link1  [WRONG]
    ///   transforms[0] = transforms[1].composed(with: link3)       = ...            [WRONG]
    ///
    /// Mutation sensitivity at transforms[1]:
    ///   correct  link1∘link2 tx = scale1*tx2 + tx1 = 1.5*(-4) + 3 = -3.0
    ///   wrong    link2∘link1 tx = scale2*tx1 + tx2 = 0.7*3 + (-4) = -1.9
    ///   diff = 1.1 px >> 1e-4 tolerance.
    func testAccumulateLinksDownSweepOrderIsLinkComposedWithNext() {
        let link1 = Transform2D.similarity(scale: 1.5, rotation: 0, tx:  3.0, ty: 0)
        let link2 = Transform2D.similarity(scale: 0.7, rotation: 0, tx: -4.0, ty: 0)
        let link3 = Transform2D.similarity(scale: 1.2, rotation: 0, tx:  2.0, ty: 0)

        // links[3] = .identity is at the reference; links[2,1,0] are non-trivial.
        // For the down-sweep, links[i] maps frame[i+1] coords → frame[i] coords.
        let links: [Transform2D] = [link3, link1, link2, .identity]
        let referenceIndex = links.count - 1   // = 3

        // Correct accumulated transforms:
        //   t[3] = .identity
        //   t[2] = link2 ∘ .identity = link2
        //   t[1] = link1 ∘ t[2]      = link1 ∘ link2
        //   t[0] = link3 ∘ t[1]      = link3 ∘ link1 ∘ link2
        let t2_want = link2                                  // link2 ∘ identity
        let t1_want = link1.composed(with: link2)           // link1 ∘ link2
        let t0_want = link3.composed(with: t1_want)         // link3 ∘ link1 ∘ link2

        // Wrong (transposed: transforms[i+1].composed(with: links[i])):
        //   t[2] = .identity ∘ link2          = link2  (trivially same as correct)
        //   t[1] = link2 ∘ link1              [WRONG — reversed]
        //   t[0] = (link2 ∘ link1) ∘ link3   [WRONG — accumulated reversed]
        let t1_wrong = link2.composed(with: link1)

        // Verify the fixture is non-degenerate: the two orderings of t[1] differ by > 1 px.
        XCTAssertGreaterThan(abs(t1_want.tx - t1_wrong.tx), 1.0,
            "t1_want and t1_wrong must differ by > 1 px — fixture is degenerate")

        let result = AffineAligner.accumulateLinks(links, referenceIndex: referenceIndex)

        XCTAssertEqual(result[referenceIndex], .identity)

        // t[2]: both orderings trivially agree (link ∘ identity = identity ∘ link = link).
        XCTAssertEqual(result[2].tx, t2_want.tx, accuracy: 1e-4, "t[2] tx")
        XCTAssertEqual(result[2].a,  t2_want.a,  accuracy: 1e-4, "t[2] scale")

        // t[1]: correct = link1 ∘ link2; wrong = link2 ∘ link1 → diff = 1.1 px.
        XCTAssertEqual(result[1].tx, t1_want.tx, accuracy: 1e-4,
            "t[1] tx must be link1∘link2 not link2∘link1 (catches transposed down-sweep loop)")
        XCTAssertNotEqual(result[1].tx, t1_wrong.tx,
            "t[1] correct and wrong must be distinguishable")

        // t[0]: accumulated error grows further.
        XCTAssertEqual(result[0].tx, t0_want.tx, accuracy: 1e-4,
            "t[0] tx must follow correct down-sweep composition chain")
    }

    // MARK: - Part A: bounds-fallback tests

    /// A 4°-per-step rotation (0.07 rad) is far outside the 1° default bound.
    /// `boundedLink` must reject the similarity fit and fall back to translation-only,
    /// so the resulting transform has scale = 1, rotation = 0.
    func testImplausibleLinkFallsBackToTranslationOnly() {
        let (frames, _) = chainBracketFrames(w: 96, h: 64,
                                             steps: [.similarity(scale: 1.0, rotation: 0.07, tx: 0, ty: 0)])
        let p = params(AffineAligner.alignChain(frames, referenceIndex: 0)[1])
        XCTAssertEqual(p.scale, 1, accuracy: 1e-4, "fallback link must carry no scale")
        XCTAssertEqual(p.rot, 0,   accuracy: 1e-4, "fallback link must carry no rotation")
    }

    /// Same frames as `testImplausibleLinkFallsBackToTranslationOnly`, but with bounds wide
    /// enough to accept a 4° rotation. The similarity fit is now accepted, proving it was the
    /// BOUNDS (not the estimator) that gated the previous test.
    func testWideBoundsAcceptTheSameLink() {
        let (frames, drifts) = chainBracketFrames(w: 96, h: 64,
                                                  steps: [.similarity(scale: 1.0, rotation: 0.07, tx: 0, ty: 0)])
        // maxRotationRadians: 0.2 rad (~11.5°) — wide enough to accept the 4° (0.07 rad) rotation.
        // maxTranslationFraction / maxScaleDelta are also widened to avoid spurious rejection.
        let wide = ChainBounds(maxScaleDelta: 0.5, maxRotationRadians: 0.2,
                               maxTranslationFraction: 0.2, robustClip: nil)
        let p = params(AffineAligner.alignChain(frames, referenceIndex: 0, bounds: wide)[1])
        // drifts[1] = steps[0], so its inverse has rotation = -0.07 rad.
        let wantRot = params(drifts[1].inverse).rot   // ≈ -0.07
        XCTAssertEqual(p.rot, wantRot, accuracy: 0.02,
                       "with wide bounds the rotation must be recovered (estimator accepted by wide ChainBounds)")
    }
}
