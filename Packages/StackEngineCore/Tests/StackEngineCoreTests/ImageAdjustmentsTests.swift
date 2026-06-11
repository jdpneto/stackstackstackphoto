import XCTest
@testable import StackEngineCore

final class ImageAdjustmentsTests: XCTestCase {
    func testQuarterTurnsCodableRoundTrip() throws {
        let adj = ImageAdjustments(quarterTurns: 3)
        let data = try JSONEncoder().encode(adj)
        let back = try JSONDecoder().decode(ImageAdjustments.self, from: data)
        XCTAssertEqual(back.quarterTurns, 3)
        XCTAssertEqual(back, adj)
    }

    func testQuarterTurnsBackCompatDefaultsToZero() throws {
        let json = #"{"exposureEV":0,"contrast":0,"temperature":0,"tint":0,"shadows":0,"highlights":0,"straightenDegrees":0,"cropAspect":"original"}"#
        let back = try JSONDecoder().decode(ImageAdjustments.self, from: Data(json.utf8))
        XCTAssertEqual(back.quarterTurns, 0)
    }

    func testQuarterTurnsStayNormalizedOnDirectMutation() {
        var adj = ImageAdjustments()
        adj.quarterTurns += 5
        XCTAssertEqual(adj.quarterTurns, 1, "out-of-range mutation re-normalizes to 0…3")
        adj.quarterTurns -= 2
        XCTAssertEqual(adj.quarterTurns, 3)
        XCTAssertEqual(adj, ImageAdjustments(quarterTurns: 3), "equality stays coherent with the canonical value")
    }

    func testBlendStrengthDefaultsToFullLookAndDecodesWhenMissing() throws {
        XCTAssertEqual(ImageAdjustments.identity.blendStrength, 1)
        // Sidecars written before the field existed must decode as full look.
        let legacy = try JSONDecoder().decode(ImageAdjustments.self, from: Data("{}".utf8))
        XCTAssertEqual(legacy.blendStrength, 1)
        XCTAssertTrue(legacy.isIdentity)
    }

    func testBlendStrengthClampsOutOfRangeValues() throws {
        // A corrupt or out-of-range sidecar value must not produce weird hasBlend states. (Fix 3)
        let low = try JSONDecoder().decode(ImageAdjustments.self, from: Data(#"{"blendStrength":-0.5}"#.utf8))
        XCTAssertEqual(low.blendStrength, 0, accuracy: 1e-6, "negative blendStrength must clamp to 0")
        // hasBlend is true when blendStrength < 1; 0 means full-reference (maximum blend) — still active.
        XCTAssertTrue(low.hasBlend, "blendStrength 0 is full-reference blend, so hasBlend must be true")

        let high = try JSONDecoder().decode(ImageAdjustments.self, from: Data(#"{"blendStrength":7}"#.utf8))
        XCTAssertEqual(high.blendStrength, 1, accuracy: 1e-6, "blendStrength > 1 must clamp to 1")
        XCTAssertFalse(high.hasBlend, "blendStrength clamped to 1 means no blend active")

        // Memberwise init also clamps.
        let initLow = ImageAdjustments(blendStrength: -0.5)
        XCTAssertEqual(initLow.blendStrength, 0, accuracy: 1e-6)
        let initHigh = ImageAdjustments(blendStrength: 7)
        XCTAssertEqual(initHigh.blendStrength, 1, accuracy: 1e-6)
    }
}
