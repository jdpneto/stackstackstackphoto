import XCTest
@testable import StackEngineCore

final class StackModeTests: XCTestCase {
    func testRawValuesAreStableStorageKeys() {
        // Raw values are persisted library keys — pin every one (renames silently break libraries).
        XCTAssertEqual(StackMode.noiseReduction.rawValue, "noiseReduction")
        XCTAssertEqual(StackMode.smoothMotion.rawValue, "smoothMotion")
        XCTAssertEqual(StackMode.lightTrails.rawValue, "lightTrails")
        XCTAssertEqual(StackMode.lowLightBoost.rawValue, "lowLightBoost")
        XCTAssertEqual(StackMode.depthOfField.rawValue, "depthOfField")
        XCTAssertEqual(StackMode.allCases.count, 5)
    }

    func testDepthOfFieldIsNotLongExposure() {
        // Depth is a static fast-ish sweep (frame-count sliders, no duration window).
        XCTAssertFalse(StackMode.depthOfField.isLongExposure)
    }

    func testSupportsBlendReference() {
        // All looks support blend-reference except depthOfField (frames differ by focus, not time).
        XCTAssertTrue(StackMode.noiseReduction.supportsBlendReference)
        XCTAssertTrue(StackMode.smoothMotion.supportsBlendReference)
        XCTAssertTrue(StackMode.lightTrails.supportsBlendReference)
        XCTAssertTrue(StackMode.lowLightBoost.supportsBlendReference)
        XCTAssertFalse(StackMode.depthOfField.supportsBlendReference)
    }
}
