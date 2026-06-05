import XCTest
import simd
@testable import StackEngineCore

final class AffineAlignerTests: XCTestCase {
    /// A deterministic bumpy texture: rich structure at many radii so scale is observable.
    func texture(_ w: Int, _ h: Int) -> PixelImage {
        var img = PixelImage(width: w, height: h)
        for y in 0..<h { for x in 0..<w {
            let v = 0.5 + 0.4 * sin(0.6 * Float(x)) * sin(0.5 * Float(y))
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
}
