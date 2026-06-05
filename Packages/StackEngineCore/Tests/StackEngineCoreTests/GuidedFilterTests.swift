import XCTest
@testable import StackEngineCore

final class GuidedFilterTests: XCTestCase {
    func testConstantInputStaysConstant() {
        let guide = (0..<64).map { Float($0 % 8) / 8 }       // arbitrary guide
        let p = [Float](repeating: 0.5, count: 64)           // constant input
        let out = GuidedFilter.filter(input: p, guide: guide, width: 8, height: 8, radius: 2, eps: 1e-3)
        for v in out { XCTAssertEqual(v, 0.5, accuracy: 1e-3) }
    }

    func testPreservesAGuideEdge() {
        // Guide is a vertical step; input tracks it with deterministic noise. Output keeps the step.
        let w = 16, h = 8
        var I = [Float](repeating: 0, count: w * h), p = [Float](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w {
            let step: Float = x < w / 2 ? 0.2 : 0.8
            I[y * w + x] = step
            p[y * w + x] = step + (((x * 7 + y * 13) % 5 == 0) ? 0.05 : -0.03)
        }}
        let out = GuidedFilter.filter(input: p, guide: I, width: w, height: h, radius: 2, eps: 1e-4)
        let left = out[4 * w + (w / 2 - 1)], right = out[4 * w + (w / 2)]
        XCTAssertGreaterThan(right - left, 0.4)   // step preserved (not blurred away)
    }
}
