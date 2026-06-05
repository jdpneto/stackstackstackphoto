import XCTest
import simd
@testable import StackEngineCore

final class ImageEditorTests: XCTestCase {
    private func solid(_ c: SIMD3<Float>) -> PixelImage {
        PixelImage(width: 1, height: 1, pixels: [c])
    }

    func testIdentityIsNoOp() {
        let img = solid(SIMD3<Float>(0.2, 0.3, 0.4))
        XCTAssertEqual(ImageEditor.apply(.identity, to: img).pixels[0], img.pixels[0])
    }

    func testExposureDoublesAtPlusOneEV() {
        let out = ImageEditor.apply(ImageAdjustments(exposureEV: 1), to: solid(SIMD3<Float>(0.2, 0.2, 0.2)))
        XCTAssertEqual(out.pixels[0].x, 0.4, accuracy: 1e-4)   // ×2
    }

    func testWhiteBalanceWarmsRedCoolsBlue() {
        let out = ImageEditor.apply(ImageAdjustments(temperature: 1), to: solid(SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertEqual(out.pixels[0].x, 0.5 * 1.3, accuracy: 1e-4)   // R ×1.3
        XCTAssertEqual(out.pixels[0].z, 0.5 * 0.7, accuracy: 1e-4)   // B ×0.7
    }

    func testContrastPushesAwayFromPivot() {
        // value above pivot (0.18) gets brighter with +contrast
        let out = ImageEditor.apply(ImageAdjustments(contrast: 0.5), to: solid(SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertEqual(out.pixels[0].x, (0.5 - 0.18) * 1.5 + 0.18, accuracy: 1e-4)
    }

    func testNegativesAreClampedToZero() {
        // a strong cool WB on a tiny blue can't go below 0
        let out = ImageEditor.apply(ImageAdjustments(exposureEV: -10), to: solid(SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertGreaterThanOrEqual(out.pixels[0].x, 0)
    }

    func testWhiteBalanceTintMagentaReducesGreen() {
        // tint = magenta(+)/green(-): +tint must REDUCE green.
        let out = ImageEditor.apply(ImageAdjustments(tint: 1), to: solid(SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertEqual(out.pixels[0].y, 0.5 * 0.7, accuracy: 1e-4)   // G × (1 - 0.3)
    }

    func testContrastBelowPivotIsClamped() {
        // p=0.05 is below the 0.18 pivot; +contrast pushes it negative → clamp holds it at 0.
        let out = ImageEditor.apply(ImageAdjustments(contrast: 0.5), to: solid(SIMD3<Float>(0.05, 0.05, 0.05)))
        XCTAssertEqual(out.pixels[0].x, 0, accuracy: 1e-6)
    }

    func testCombinedAdjustmentsApplyInOrder() {
        // exposure ×2 → WB(temp=1) → contrast 0.5, hand-computed from p=0.2.
        let out = ImageEditor.apply(ImageAdjustments(exposureEV: 1, contrast: 0.5, temperature: 1, tint: 0),
                                    to: solid(SIMD3<Float>(0.2, 0.2, 0.2)))
        XCTAssertEqual(out.pixels[0].x, 0.69, accuracy: 1e-4)   // (0.2*2*1.3 - 0.18)*1.5 + 0.18
        XCTAssertEqual(out.pixels[0].y, 0.51, accuracy: 1e-4)   // (0.2*2*1.0 - 0.18)*1.5 + 0.18
        XCTAssertEqual(out.pixels[0].z, 0.33, accuracy: 1e-4)   // (0.2*2*0.7 - 0.18)*1.5 + 0.18
    }
}
