import XCTest
import simd
@testable import StackEngineCore

final class PixelImageTests: XCTestCase {
    func testSubscriptRoundTrip() {
        var img = PixelImage(width: 2, height: 2)
        img[1, 0] = SIMD3<Float>(0.1, 0.2, 0.3)
        XCTAssertEqual(img[1, 0], SIMD3<Float>(0.1, 0.2, 0.3))
        XCTAssertEqual(img[0, 0], SIMD3<Float>(0, 0, 0))
        XCTAssertEqual(img.pixels.count, 4)
    }
}
