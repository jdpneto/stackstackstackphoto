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

    func testRenderWithMaxPixelDownscales() throws {
        let big = PixelImage(width: 64, height: 64, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        let jpeg = try ImageEncoder.encode(rgba8: OutputTransform.encodeSRGB8(big),
                                           width: 64, height: 64, format: .jpeg, quality: 1.0)
        let preview = try XCTUnwrap(ResultRenderer.render(originalJPEG: jpeg, adjustments: .identity, maxPixel: 16))
        let (_, w, h) = try XCTUnwrap(ImageDecoder.rgba8(from: preview))
        XCTAssertLessThanOrEqual(max(w, h), 16)   // preview path downscaled to <= maxPixel
        XCTAssertGreaterThan(w, 0)
    }

    func testRenderWithCropProducesCroppedDimensions() throws {
        // Regression: a square crop changes the image dimensions; the encoder must use the
        // adjusted size, not the stale decode size (else the pixel buffer mismatches w*h).
        let img = PixelImage(width: 16, height: 8, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        let jpeg = try ImageEncoder.encode(rgba8: OutputTransform.encodeSRGB8(img),
                                           width: 16, height: 8, format: .jpeg, quality: 1.0)
        let rendered = try XCTUnwrap(ResultRenderer.render(originalJPEG: jpeg,
                                                           adjustments: ImageAdjustments(cropAspect: .square)))
        let (rgba, w, h) = try XCTUnwrap(ImageDecoder.rgba8(from: rendered))
        XCTAssertEqual(w, 8); XCTAssertEqual(h, 8)        // centre-cropped to the smaller side
        XCTAssertEqual(rgba.count, w * h * 4)             // buffer matches the reported dimensions
    }
}
