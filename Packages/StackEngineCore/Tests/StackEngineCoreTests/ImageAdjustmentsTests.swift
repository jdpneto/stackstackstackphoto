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
}
