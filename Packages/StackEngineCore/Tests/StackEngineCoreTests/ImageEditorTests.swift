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

    func testDecodesOldAdjustmentsWithoutNewKeys() throws {
        // An edits.json written before this change has only the four tonal keys.
        let oldJSON = #"{"exposureEV":1,"contrast":0,"temperature":0,"tint":0}"#.data(using: .utf8)!
        let adj = try JSONDecoder().decode(ImageAdjustments.self, from: oldJSON)
        XCTAssertEqual(adj.exposureEV, 1, accuracy: 1e-6)
        XCTAssertEqual(adj.shadows, 0)            // defaulted
        XCTAssertEqual(adj.highlights, 0)         // defaulted
        XCTAssertEqual(adj.straightenDegrees, 0)  // defaulted
        XCTAssertEqual(adj.cropAspect, .original) // defaulted
        XCTAssertFalse(ImageAdjustments(exposureEV: 1).isIdentity)
    }

    func testCropSquareCentersToSmallerSide() {
        let out = ImageEditor.apply(ImageAdjustments(cropAspect: .square),
                                    to: PixelImage(width: 16, height: 8, fill: SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertEqual(out.width, 8)
        XCTAssertEqual(out.height, 8)
    }

    func testStraighten180FlipsRow() {
        let img = PixelImage(width: 4, height: 1, pixels: [
            SIMD3<Float>(1, 1, 1), SIMD3<Float>(0, 0, 0), SIMD3<Float>(0, 0, 0), SIMD3<Float>(0, 0, 0)])
        let r = ImageEditor.straighten(img, degrees: 180)
        XCTAssertEqual(r[3, 0].x, 1, accuracy: 1e-4)   // bright pixel rotated to the far end
        XCTAssertEqual(r[0, 0].x, 0, accuracy: 1e-4)
    }

    func testShadowsLiftBlack() {
        let out = ImageEditor.apply(ImageAdjustments(shadows: 1), to: solid(SIMD3<Float>(0, 0, 0)))
        XCTAssertEqual(out.pixels[0].x, 0.5, accuracy: 1e-4)   // 0 + 1·0.5·(1-0)² = 0.5
    }

    func testHighlightsPullWhite() {
        let out = ImageEditor.apply(ImageAdjustments(highlights: -1), to: solid(SIMD3<Float>(1, 1, 1)))
        XCTAssertEqual(out.pixels[0].x, 0.5, accuracy: 1e-4)   // 1 + (-1)·0.5·1² = 0.5
    }

    func testStraightenAutoZoomsKeepingDimensionsAndCenter() {
        var img = PixelImage(width: 9, height: 9, fill: SIMD3<Float>(0.2, 0.2, 0.2))
        img[4, 4] = SIMD3<Float>(1, 1, 1)              // bright centre
        let r = ImageEditor.straighten(img, degrees: 10)
        XCTAssertEqual(r.width, 9)
        XCTAssertEqual(r.height, 9)                    // dimensions preserved (auto-zoom fills the frame)
        XCTAssertGreaterThan(r[4, 4].x, 0.5)           // rotation is about the centre → centre stays bright
    }

    func testStraightenNonSquareExtremeAngleKeepsDimensions() {
        // Exercises the non-square auto-zoom path at the UI's max angle (regression for the
        // w/h vs (w-1)/(h-1) under-zoom that left a corner sliver on wide frames).
        let r = ImageEditor.straighten(PixelImage(width: 16, height: 8, fill: SIMD3<Float>(0.3, 0.3, 0.3)),
                                       degrees: 15)
        XCTAssertEqual(r.width, 16)
        XCTAssertEqual(r.height, 8)
    }

    func testQuarterTurnRotatesViaImageGeometry() {
        let img = PixelImage(width: 3, height: 2, pixels: (0..<6).map { SIMD3(Float($0), 0, 0) })
        let adj = ImageAdjustments(quarterTurns: 1)
        XCTAssertEqual(ImageEditor.apply(adj, to: img), ImageGeometry.rotated(img, quarterTurns: 1))
    }

    func testQuarterTurnMakesNonIdentity() {
        XCTAssertFalse(ImageAdjustments(quarterTurns: 1).isIdentity)
        XCTAssertTrue(ImageAdjustments(quarterTurns: 0).isIdentity)
    }
}
