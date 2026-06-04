import XCTest
import simd
@testable import StackEngineCore

final class StackReducerTests: XCTestCase {
    private func flat(_ v: Float) -> PixelImage {
        PixelImage(width: 1, height: 1, pixels: [SIMD3<Float>(v, v, v)])
    }

    func testPlainMeanWhenNoOutliers() {
        let imgs = [flat(0.2), flat(0.4), flat(0.6), flat(0.8)]
        let out = StackReducer.sigmaClippedMean(imgs, kappa: 1.5)
        XCTAssertEqual(out[0, 0].x, 0.5, accuracy: 1e-5)
    }

    func testRejectsOutlier() {
        // four 0.5s and one 10.0 -> clipped -> ~0.5
        let imgs = [flat(0.5), flat(0.5), flat(0.5), flat(0.5), flat(10.0)]
        let out = StackReducer.sigmaClippedMean(imgs, kappa: 1.5)
        XCTAssertEqual(out[0, 0].x, 0.5, accuracy: 1e-4)
    }
}
