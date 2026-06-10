import XCTest
import ImageIO
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

    func testRenderReturnsNilOnGarbageInput() {
        // Corrupt / non-image data must fail soft (nil), not trap an engine precondition.
        XCTAssertNil(ResultRenderer.render(originalJPEG: Data([0x00, 0x01, 0x02, 0x03, 0x04]),
                                           adjustments: .identity))
        XCTAssertNil(ResultRenderer.render(originalJPEG: Data(),
                                           adjustments: ImageAdjustments(exposureEV: 1)))
    }

    func testRenderedJPEGCarriesNoGPSMetadata() throws {
        // Privacy property: the develop→encode path builds JPEGs from raw RGBA with no metadata
        // dictionary, so a saved/edited result must never carry GPS/location.
        let img = PixelImage(width: 4, height: 4, fill: SIMD3<Float>(0.4, 0.4, 0.4))
        let jpeg = try ImageEncoder.encode(rgba8: OutputTransform.encodeSRGB8(img),
                                           width: 4, height: 4, format: .jpeg, quality: 1.0)
        let rendered = try XCTUnwrap(ResultRenderer.render(originalJPEG: jpeg,
                                                           adjustments: ImageAdjustments(exposureEV: 1)))
        let src = try XCTUnwrap(CGImageSourceCreateWithData(rendered as CFData, nil))
        let props = (CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]) ?? [:]
        XCTAssertNil(props[kCGImagePropertyGPSDictionary], "rendered result must carry no GPS metadata")
    }

    func testRenderInHEICProducesDecodableHEIC() throws {
        // Build a small grey JPEG as the source (the original is always stored in the record's format,
        // but we exercise the format param with a JPEG source decoded then re-encoded as HEIC).
        let grey = PixelImage(width: 4, height: 4, fill: SIMD3<Float>(0.25, 0.25, 0.25))
        let rgba = OutputTransform.encodeSRGB8(grey)
        let original = try ImageEncoder.encode(rgba8: rgba, width: 4, height: 4, format: .jpeg, quality: 1.0)
        let out = try XCTUnwrap(ResultRenderer.render(originalJPEG: original, adjustments: .identity,
                                                      quality: 0.9, format: .heic))
        XCTAssertNotNil(ImageDecoder.rgba8(from: out, maxPixel: nil), "HEIC output must decode")
        XCTAssertNotEqual(out.prefix(3), Data([0xFF, 0xD8, 0xFF]), "must not be JPEG magic bytes")
    }

    // MARK: - Task 2 (blend-strength) tests

    private func encodeFlat(level: Float) throws -> Data {
        let img = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(level, level, level))
        let rgba = OutputTransform.encodeSRGB8(img)
        return try ImageEncoder.encode(rgba8: rgba, width: 8, height: 8, format: .jpeg, quality: 1.0)
    }

    func testRenderAtAlphaZeroMatchesReference() throws {
        // Two flat images: original 0.8 grey, reference 0.2 grey; α=0 must render ≈ the reference.
        let original = try encodeFlat(level: 0.8)
        let reference = try encodeFlat(level: 0.2)
        var adj = ImageAdjustments.identity
        adj.blendStrength = 0
        let out = try XCTUnwrap(ResultRenderer.render(originalJPEG: original, adjustments: adj,
                                                      quality: 0.95, referenceJPEG: reference))
        let (rgba, w, h) = try XCTUnwrap(ImageDecoder.rgba8(from: out, maxPixel: nil))
        // Centre pixel ≈ the reference's sRGB-encoded 0.2-linear grey (same encode path); tolerance for JPEG.
        let refDecoded = try XCTUnwrap(ImageDecoder.rgba8(from: reference, maxPixel: nil))
        XCTAssertTrue(abs(Int(rgba[(h/2 * w + w/2) * 4]) - Int(refDecoded.0[(h/2 * w + w/2) * 4])) < 6,
                      "α=0 render must match the reference within JPEG tolerance")
    }
}
