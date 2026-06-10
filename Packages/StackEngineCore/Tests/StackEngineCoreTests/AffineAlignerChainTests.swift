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
