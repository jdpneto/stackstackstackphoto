import XCTest
import StackEngineCore
@testable import StackStackStack

final class ResultRendererTests: XCTestCase {
    func testRenderRoundTripsAndAppliesExposure() throws {
        // Build a small grey JPEG via the engine + encoder.
        let grey = PixelImage(width: 4, height: 4, fill: SIMD3<Float>(0.25, 0.25, 0.25))
        let rgba = OutputTransform.encodeSRGB8(grey)
        let jpeg = try ImageEncoder.encode(rgba8: rgba, width: 4, height: 4, format: .jpeg, quality: 1.0)

        // Identity render returns a valid JPEG of the same dimensions.
        let identity = try XCTUnwrap(ResultRenderer.render(originalJPEG: jpeg, adjustments: .identity))
        let (idRGBA, w, h) = try XCTUnwrap(ImageDecoder.rgba8(from: identity))
        XCTAssertEqual(w, 4); XCTAssertEqual(h, 4); XCTAssertEqual(idRGBA.count, 4 * 4 * 4)

        // +1 EV brightens the decoded result vs the identity render.
        let brighter = try XCTUnwrap(ResultRenderer.render(originalJPEG: jpeg, adjustments: ImageAdjustments(exposureEV: 1)))
        let (brRGBA, _, _) = try XCTUnwrap(ImageDecoder.rgba8(from: brighter))
        XCTAssertGreaterThan(Int(brRGBA[0]), Int(idRGBA[0]))   // pixel got brighter
    }
}
