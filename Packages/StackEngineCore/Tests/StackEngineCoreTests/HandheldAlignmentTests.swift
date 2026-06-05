import XCTest
import simd
@testable import StackEngineCore

/// Handheld capture rotates the frame (not just shifts it). These tests synthesize hand-shake —
/// a small per-frame rotation + translation of a sharp static scene — and verify the similarity
/// aligner registers it, so combining the frames keeps static detail sharp (the tripod-free goal).
final class HandheldAlignmentTests: XCTestCase {
    /// A sharp, NON-periodic pattern so the aligner has unique features to lock onto (a periodic
    /// checkerboard would be ambiguous). 6 px blocks toggled by a spatial hash.
    private func staticScene(_ w: Int, _ h: Int, block: Int = 6) -> PixelImage {
        var img = PixelImage(width: w, height: h)
        for y in 0..<h {
            for x in 0..<w {
                let hash = (Int(x / block) &* 73856093) ^ (Int(y / block) &* 19349663)
                let v: Float = (hash & 1 == 0) ? 0.85 : 0.15
                img[x, y] = SIMD3(v, v, v)
            }
        }
        return img
    }

    /// Mean per-pixel squared spread across frames in the central region (away from warp borders).
    /// Low spread ⇒ the frames agree ⇒ combining them keeps that region sharp.
    private func centralSpread(_ imgs: [PixelImage], margin: Int) -> Float {
        let w = imgs[0].width, h = imgs[0].height
        var sum: Float = 0
        var n = 0
        for y in margin..<(h - margin) {
            for x in margin..<(w - margin) {
                var mean: Float = 0
                for im in imgs { mean += im[x, y].x }
                mean /= Float(imgs.count)
                for im in imgs { let d = im[x, y].x - mean; sum += d * d }
                n += 1
            }
        }
        return sum / Float(n)
    }

    func testHandShakeRotationIsAlignedOut() {
        let w = 72, h = 72
        let scene = staticScene(w, h)
        // Hand shake: small rotations (≈ ±1°) + a couple px of translation per frame.
        let shakes: [(r: Float, tx: Float, ty: Float)] = [
            (0, 0, 0), (0.018, 1, -1), (-0.020, -1, 1), (0.012, 2, 0), (-0.015, 0, 2),
        ]
        let frames = shakes.map {
            AffineAligner.warp(scene, by: .similarity(scale: 1, rotation: $0.r, tx: $0.tx, ty: $0.ty))
        }

        let aligned = Pipeline.alignedStack(frames, searchRange: 6)

        let before = centralSpread(frames, margin: 14)
        let after = centralSpread(aligned, margin: 14)
        // Registration should sharply reduce frame disagreement in the static region — translation
        // alone could not, because the shake includes rotation.
        XCTAssertLessThan(after, before * 0.5,
                          "similarity alignment should at least halve static-region spread (before \(before), after \(after))")
    }

    func testMotionMaskBlendsStaticVsMoving() {
        // Compositing logic only (no alignment): static pixels barely vary → take `base`; a pixel
        // that moves across frames → take `effect`.
        let w = 40, h = 40
        let base = staticScene(w, h, block: 4)
        var effect = base
        for i in 0..<(w * h) { effect.pixels[i] += SIMD3(0.3, 0.3, 0.3) }   // a distinct "effect" image
        var frames = Array(repeating: base, count: 4)
        for k in 0..<4 { frames[k][6 + k, 6] = SIMD3(1, 1, 1) }              // a bright spot moving along y=6
        let mask = MotionComposite.motionMask(frames, lo: 0.05, hi: 0.15, smoothRadius: 0)
        let out = MotionComposite.blend(staticBase: base, effect: effect, mask: mask)
        XCTAssertEqual(out[20, 30].x, base[20, 30].x, accuracy: 0.01, "static pixel → base")
        XCTAssertGreaterThan(out[8, 6].x, base[8, 6].x + 0.1, "a pixel the spot passed through → toward effect")
    }

    func testLightTrailsStreaksMotionAndKeepsStaticSharp() {
        // End-to-end light-trails: a bright object sweeps the top of an otherwise-static scene. The
        // path should streak; the static region should stay sharp (the mask keeps it as the mean, not
        // a noisy max). No shake/noise here so alignment is trivially exact — the motion-mask logic
        // itself is covered by `testMotionMaskBlendsStaticVsMoving`.
        let w = 80, h = 80
        let scene = staticScene(w, h)
        var frames: [PixelImage] = []
        for k in 0..<6 {
            var f = scene
            let cx = 6 + k * 11
            for dy in 0..<6 {
                for dx in 0..<6 {
                    let x = cx + dx, y = 5 + dy
                    if x >= 0 && x < w && y >= 0 && y < h { f[x, y] = SIMD3(1, 1, 1) }
                }
            }
            frames.append(f)
        }

        let result = Pipeline.reduceImages(frames, mode: .lightTrails)
        // (a) Static region (bottom): kept sharp/clean — equal to the scene (mask ≈ 0 → mean).
        var staticDiff: Float = 0
        var nStatic = 0
        for y in 30..<76 { for x in 6..<74 { staticDiff += abs(result[x, y].x - scene[x, y].x); nStatic += 1 } }
        XCTAssertLessThan(staticDiff / Float(nStatic), 0.03, "static region should stay sharp (≈ scene)")
        // (b) The object's path streaks bright (mask ≈ 1 → lighten).
        var pathBright: Float = 0
        var nPath = 0
        for y in 5..<11 { for x in 6..<80 { pathBright += result[x, y].x; nPath += 1 } }
        XCTAssertGreaterThan(pathBright / Float(nPath), 0.45, "the moving object's path should streak bright")
    }

    func testStationaryFramesAreUnchangedEnough() {
        // No shake ⇒ already-aligned frames must stay sharp (no regression / over-warping).
        let w = 72, h = 72
        let scene = staticScene(w, h)
        let frames = Array(repeating: scene, count: 4)
        let aligned = Pipeline.alignedStack(frames, searchRange: 6)
        let result = StackReducer.mean(aligned)
        // The combined result should be ~as sharp as a single frame (identity alignment, no blur).
        let single = Luma.sharpness(scene)
        let combined = Luma.sharpness(result)
        XCTAssertGreaterThan(combined, single * 0.9,
                             "stationary frames should not be softened by alignment (single \(single), combined \(combined))")
    }
}
