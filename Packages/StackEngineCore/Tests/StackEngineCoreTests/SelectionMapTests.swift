import XCTest
@testable import StackEngineCore

final class SelectionMapTests: XCTestCase {
    func testFavoursTheSharperFramePerRegion() {
        // Frame 0 sharp on the left half; frame 1 sharp on the right half.
        let w = 24, h = 12
        func sharp(left: Bool) -> [Float] {
            var s = [Float](repeating: 0, count: w * h)
            for y in 0..<h { for x in 0..<w { s[y * w + x] = ((x < w / 2) == left) ? 1.0 : 0.05 } }
            return s
        }
        let guide = [Float](repeating: 0.5, count: w * h)
        let weights = SelectionMap.weights(sharpness: [sharp(left: true), sharp(left: false)],
                                           guide: guide, width: w, height: h)
        let li = 6 * w + 4, ri = 6 * w + (w - 4)
        XCTAssertGreaterThan(weights[0][li], 0.7)   // left region → frame 0
        XCTAssertGreaterThan(weights[1][ri], 0.7)   // right region → frame 1
        XCTAssertEqual(weights[0][li] + weights[1][li], 1.0, accuracy: 1e-4)   // sums to 1
    }

    func testNoDetailGivesEqualWeights() {
        let w = 8, h = 8, flat = [Float](repeating: 0, count: w * h)
        let weights = SelectionMap.weights(sharpness: [flat, flat], guide: flat, width: w, height: h)
        XCTAssertEqual(weights[0][0], 0.5, accuracy: 1e-4)
        XCTAssertEqual(weights[1][0], 0.5, accuracy: 1e-4)
    }
}
