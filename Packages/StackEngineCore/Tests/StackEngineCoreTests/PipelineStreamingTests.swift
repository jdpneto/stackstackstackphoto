import XCTest
import simd
@testable import StackEngineCore

final class PipelineStreamingTests: XCTestCase {
    func testIsLongExposureClassification() {
        XCTAssertTrue(StackMode.smoothMotion.isLongExposure)
        XCTAssertTrue(StackMode.lightTrails.isLongExposure)
        XCTAssertFalse(StackMode.noiseReduction.isLongExposure)
        XCTAssertFalse(StackMode.lowLightBoost.isLongExposure)
    }
}
