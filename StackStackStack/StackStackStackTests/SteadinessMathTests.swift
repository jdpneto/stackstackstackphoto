import XCTest
import CoreGraphics
@testable import StackStackStack

final class SteadinessMathTests: XCTestCase {
    func testCenteredIsSteadyWithZeroOffset() {
        let r = SteadinessMath.evaluate(deltaPitch: 0, deltaRoll: 0, tolerance: 0.05, fullScale: 0.12)
        XCTAssertTrue(r.steady)
        XCTAssertEqual(r.offset.x, 0, accuracy: 1e-9)
        XCTAssertEqual(r.offset.y, 0, accuracy: 1e-9)
    }

    func testWithinToleranceIsSteady() {
        let r = SteadinessMath.evaluate(deltaPitch: 0.04, deltaRoll: 0.0, tolerance: 0.05, fullScale: 0.12)
        XCTAssertTrue(r.steady)
    }

    func testLargeDeviationIsNotSteadyAndOffsetClampsToUnit() {
        let r = SteadinessMath.evaluate(deltaPitch: 0.5, deltaRoll: 0.0, tolerance: 0.05, fullScale: 0.12)
        XCTAssertFalse(r.steady)
        XCTAssertEqual(r.offset.y, 1.0, accuracy: 1e-9, "offset clamps to +1 at full scale")
    }

    func testNegativeRollMapsToNegativeX() {
        let r = SteadinessMath.evaluate(deltaPitch: 0.0, deltaRoll: -0.12, tolerance: 0.05, fullScale: 0.12)
        XCTAssertEqual(r.offset.x, -1.0, accuracy: 1e-9)
    }
}
