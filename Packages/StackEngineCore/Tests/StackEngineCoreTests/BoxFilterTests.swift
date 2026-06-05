import XCTest
@testable import StackEngineCore

final class BoxFilterTests: XCTestCase {
    func testMeanOfConstantIsConstant() {
        let out = BoxFilter.mean([Float](repeating: 0.3, count: 8 * 8), width: 8, height: 8, radius: 2)
        XCTAssertEqual(out.max()!, 0.3, accuracy: 1e-5)
        XCTAssertEqual(out.min()!, 0.3, accuracy: 1e-5)
    }

    func testMeanSpreadsAnImpulse() {
        var src = [Float](repeating: 0, count: 9 * 9); src[4 * 9 + 4] = 9
        let out = BoxFilter.mean(src, width: 9, height: 9, radius: 1)
        XCTAssertLessThan(out[4 * 9 + 4], 9)        // central value reduced
        XCTAssertGreaterThan(out[4 * 9 + 3], 0)     // neighbour raised
    }
}
