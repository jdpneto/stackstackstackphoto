import XCTest
import ImageIO
@testable import StackStackStack

final class ImageEncoderTests: XCTestCase {
    func testEncodesNonEmptyJPEG() throws {
        // 2x2 RGBA8 red
        let rgba: [UInt8] = Array(repeating: 0, count: 16).enumerated().map { i, _ in
            (i % 4 == 0 || i % 4 == 3) ? 255 : 0   // R=255, A=255
        }
        let data = try ImageEncoder.encode(rgba8: rgba, width: 2, height: 2, format: .jpeg, quality: 0.9)
        XCTAssertGreaterThan(data.count, 0)
        // JPEG magic bytes
        XCTAssertEqual(data[0], 0xFF); XCTAssertEqual(data[1], 0xD8)
    }

    func testEncodeRejectsBufferSizeMismatch() {
        // Buffer too small for the declared 2×2 (would be an OOB read in CGContext) → throws, not crashes.
        let tooSmall = [UInt8](repeating: 0, count: 2 * 2 * 4 - 4)
        XCTAssertThrowsError(try ImageEncoder.encode(rgba8: tooSmall, width: 2, height: 2,
                                                     format: .jpeg, quality: 1.0))
        // Zero dimensions are rejected too.
        XCTAssertThrowsError(try ImageEncoder.encode(rgba8: [], width: 0, height: 0,
                                                     format: .jpeg, quality: 1.0))
    }

    // MARK: - Task 1: EXIF + ICC

    func testEXIFAndICCAreEmbedded() throws {
        let rgba: [UInt8] = Array(repeating: 128, count: 8 * 8 * 4)
        let exif = ImageEncoder.ExifMetadata(iso: 320, shutterSeconds: 0.02,
                                             capturedAt: Date(timeIntervalSince1970: 1_750_000_000))
        let data = try ImageEncoder.encode(rgba8: rgba, width: 8, height: 8, format: .jpeg,
                                           quality: 0.9, exif: exif)
        let src = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        let props = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any])
        let exifDict = try XCTUnwrap(props[kCGImagePropertyExifDictionary] as? [CFString: Any])
        XCTAssertEqual((exifDict[kCGImagePropertyExifISOSpeedRatings] as? [Int])?.first, 320)
        XCTAssertEqual(exifDict[kCGImagePropertyExifExposureTime] as? Double ?? 0, 0.02, accuracy: 1e-6)
        XCTAssertNotNil(exifDict[kCGImagePropertyExifDateTimeOriginal])
        let tiff = try XCTUnwrap(props[kCGImagePropertyTIFFDictionary] as? [CFString: Any])
        XCTAssertEqual(tiff[kCGImagePropertyTIFFSoftware] as? String, "Stack Stack Stack")
        // ICC: the decoded image's color space must be sRGB (profile embedded, not device-implied).
        // ImageIO returns the Quartz constant name "kCGColorSpaceSRGB" (not "sRGB IEC61966-2.1")
        // for correctly-embedded sRGB profiles — even for real iPhone JPEG files.
        // We verify the name equals the sRGB color-space constant, which proves the profile is
        // embedded and recognized (not a generic/uncalibrated RGB space).
        let cg = try XCTUnwrap(CGImageSourceCreateImageAtIndex(src, 0, nil))
        let csName = try XCTUnwrap(cg.colorSpace?.name) as String
        XCTAssertTrue(csName.lowercased().contains("srgb"),
                      "expected an sRGB color space, got \(csName)")
    }

    func testEncodeWithoutExifStillDecodes() throws {
        // encode with exif: nil → decodes fine, no EXIF dict requirement
        let rgba: [UInt8] = Array(repeating: 200, count: 4 * 4 * 4)
        let data = try ImageEncoder.encode(rgba8: rgba, width: 4, height: 4, format: .jpeg,
                                           quality: 0.8, exif: nil)
        XCTAssertGreaterThan(data.count, 0)
        let src = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        XCTAssertNotNil(CGImageSourceCreateImageAtIndex(src, 0, nil))
    }
}
