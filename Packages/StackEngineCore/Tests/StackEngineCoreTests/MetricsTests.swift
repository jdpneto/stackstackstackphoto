import XCTest
import simd
@testable import StackEngineCore

final class MetricsTests: XCTestCase {
    func testPSNRIdenticalIsInfinite() {
        let a: [UInt8] = [10, 20, 30, 255]
        XCTAssertEqual(Metrics.psnr(a, a), .infinity)
    }
    func testPSNRDecreasesWithError() {
        let a: [UInt8] = [100, 100, 100, 100]
        let b: [UInt8] = [110, 90, 105, 100]
        let p = Metrics.psnr(a, b)
        XCTAssertGreaterThan(p, 20)
        XCTAssertLessThan(p, 60)
    }
    func testMaxAbsDiffZeroForIdentical() {
        let a = PixelImage(width: 2, height: 1,
            pixels: [SIMD3<Float>(0.1, 0.2, 0.3), SIMD3<Float>(0.4, 0.5, 0.6)])
        XCTAssertEqual(Metrics.maxAbsDiff(a, a), 0, accuracy: 1e-7)
    }
    func testMaxAbsDiffFindsLargestChannelDelta() {
        let a = PixelImage(width: 1, height: 1, pixels: [SIMD3<Float>(0.1, 0.2, 0.3)])
        let b = PixelImage(width: 1, height: 1, pixels: [SIMD3<Float>(0.1, 0.7, 0.25)])
        XCTAssertEqual(Metrics.maxAbsDiff(a, b), 0.5, accuracy: 1e-6) // |0.2-0.7| is the max
    }
}
