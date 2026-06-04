import XCTest
import simd
@testable import StackEngineCore

final class OutputTransformTests: XCTestCase {
    func testSRGBEncodingKnownValues() {
        let img = PixelImage(width: 3, height: 1, pixels: [
            SIMD3<Float>(0, 0, 0),
            SIMD3<Float>(1, 1, 1),
            SIMD3<Float>(0.5, 0.5, 0.5),
        ])
        let bytes = OutputTransform.encodeSRGB8(img) // RGBA, 4 bytes/pixel
        XCTAssertEqual(bytes.count, 3 * 4)
        // black -> 0, alpha 255
        XCTAssertEqual(bytes[0], 0); XCTAssertEqual(bytes[3], 255)
        // white -> 255
        XCTAssertEqual(bytes[4], 255)
        // linear 0.5 -> sRGB ~0.7353 -> ~188
        XCTAssertEqual(Int(bytes[8]), 188, accuracy: 1)
    }
}

private func XCTAssertEqual(_ a: Int, _ b: Int, accuracy: Int,
                            file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertLessThanOrEqual(abs(a - b), accuracy, file: file, line: line)
}
