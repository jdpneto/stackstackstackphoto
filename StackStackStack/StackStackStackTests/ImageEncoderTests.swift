import XCTest
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
}
