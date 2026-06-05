import XCTest
import ImageIO
import simd
@testable import StackStackStack

final class RawFrameConverterTests: XCTestCase {
    func testWhiteBalanceGainsFromAsShotNeutral() {
        // Neutral [0.5, 1.0, 0.6] → green-relative gains [1/0.5, 1, 1/0.6] → boost R & B, neutralize green.
        let g = RawFrameConverter.gains(fromAsShotNeutral: [0.5, 1.0, 0.6])
        XCTAssertEqual(g.x, 2.0, accuracy: 1e-4)
        XCTAssertEqual(g.y, 1.0, accuracy: 1e-4)
        XCTAssertEqual(g.z, 1.0 / 0.6, accuracy: 1e-4)
    }

    func testGainsFallBackToNeutralOnBadInput() {
        XCTAssertEqual(RawFrameConverter.gains(fromAsShotNeutral: [0.5, 0, 0.6]), SIMD3<Float>(1, 1, 1)) // zero channel
        XCTAssertEqual(RawFrameConverter.gains(fromAsShotNeutral: [0.5, 1.0]), SIMD3<Float>(1, 1, 1))     // wrong count
    }

    func testWhiteBalanceGainsFromMetadataDict() {
        let meta: [String: Any] = [kCGImagePropertyDNGDictionary as String: [
            kCGImagePropertyDNGAsShotNeutral as String:
                [NSNumber(value: 0.5), NSNumber(value: 1.0), NSNumber(value: 0.6)],
        ]]
        XCTAssertEqual(RawFrameConverter.whiteBalanceGains(from: meta).x, 2.0, accuracy: 1e-4)
    }

    func testWhiteBalanceGainsAbsentMetadataIsNeutral() {
        XCTAssertEqual(RawFrameConverter.whiteBalanceGains(from: [:]), SIMD3<Float>(1, 1, 1))
    }
}
