import XCTest
import simd
@testable import StackEngineCore

final class AffineAlignerTests: XCTestCase {
    /// A deterministic, SMOOTH, non-periodic fixture: an asymmetric product ramp (unique global
    /// structure → unimodal SSD, so translation init is reliable and scale is observable) plus a
    /// gentle low-frequency undulation. Low pixel-frequency keeps bilinear resampling accurate, so
    /// the warp→align→compare round-trip isn't confounded by interpolation aliasing. (A high-freq
    /// periodic texture would alias under bilinear and create spurious SSD minima — bad for a
    /// registration fixture; real frames are likewise blurred via the luma pyramid before matching.)
    func texture(_ w: Int, _ h: Int) -> PixelImage {
        var img = PixelImage(width: w, height: h)
        for y in 0..<h { for x in 0..<w {
            let fx = Float(x) / Float(w - 1), fy = Float(y) / Float(h - 1)
            let v = 0.15 + 0.5 * fx * fy + 0.2 * sin(2.5 * fx) * sin(2.0 * fy)
            img[x, y] = SIMD3<Float>(v, v, v)
        }}
        return img
    }

    func testWarpByIdentityReturnsSameImage() {
        let img = texture(24, 24)
        let out = AffineAligner.warp(img, by: .identity)
        XCTAssertLessThan(Metrics.maxAbsDiff(out, img), 1e-5)
    }

    func testWarpByPureTranslationShiftsContent() {
        let img = texture(24, 24)
        // similarity(scale 1, rot 0, tx 2, ty 0): out[x,y] samples img at (x+2, y) → content shifts left by 2.
        let out = AffineAligner.warp(img, by: .similarity(scale: 1, rotation: 0, tx: 2, ty: 0))
        var maxd: Float = 0
        for y in 4..<20 { for x in 4..<20 {
            maxd = max(maxd, abs(out[x, y].x - img[x + 2, y].x))
        }}
        XCTAssertLessThan(maxd, 1e-4)
    }

    func testEstimateRecoversSimilarity() {
        let ref = texture(48, 48)
        // Focus-breathing: moving is ref scaled up ~4% + rotated ~1.1° + shifted. mov[p] = ref[known(p)].
        let known = Transform2D.similarity(scale: 1.04, rotation: 0.02, tx: 2, ty: -1)
        let mov = AffineAligner.warp(ref, by: known)
        // estimate finds T minimising SSD(warp(mov, T), ref); aligning mov by it recovers ref.
        let est = AffineAligner.estimate(reference: ref, moving: mov)
        let aligned = AffineAligner.warp(mov, by: est)
        var maxd: Float = 0
        for y in 10..<38 { for x in 10..<38 {
            maxd = max(maxd, abs(aligned[x, y].x - ref[x, y].x))
        }}
        XCTAssertLessThan(maxd, 0.05, "aligned interior should match the reference")
    }

    func testEstimateOnIdenticalFramesIsNearIdentity() {
        let ref = texture(32, 32)
        let est = AffineAligner.estimate(reference: ref, moving: ref)
        let aligned = AffineAligner.warp(ref, by: est)
        XCTAssertLessThan(Metrics.maxAbsDiff(aligned, ref), 1e-3)
    }
}
