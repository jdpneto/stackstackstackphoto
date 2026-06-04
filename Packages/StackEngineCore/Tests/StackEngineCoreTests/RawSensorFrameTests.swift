import XCTest
import simd
@testable import StackEngineCore

final class RawSensorFrameTests: XCTestCase {
    // RGGB layout (top-left 2x2 = R G / G B)
    func testCFAColorRGGB() {
        XCTAssertEqual(cfaColor(.rggb, 0, 0), .red)
        XCTAssertEqual(cfaColor(.rggb, 1, 0), .green)
        XCTAssertEqual(cfaColor(.rggb, 0, 1), .green)
        XCTAssertEqual(cfaColor(.rggb, 1, 1), .blue)
    }
    func testCFAColorHandlesNegativeCoords() {
        // -1 should have the same parity as 1
        XCTAssertEqual(cfaColor(.rggb, -1, 0), cfaColor(.rggb, 1, 0))
        XCTAssertEqual(cfaColor(.rggb, 0, -1), cfaColor(.rggb, 0, 1))
    }
    func testLinearizeSample() {
        // (v - black) / (white - black), clamped
        XCTAssertEqual(linearizeSample(64, black: 64, white: 1024), 0.0, accuracy: 1e-6)
        XCTAssertEqual(linearizeSample(1024, black: 64, white: 1024), 1.0, accuracy: 1e-6)
        XCTAssertEqual(linearizeSample(544, black: 64, white: 1024), 0.5, accuracy: 1e-6)
        XCTAssertEqual(linearizeSample(0, black: 64, white: 1024), 0.0, accuracy: 1e-6) // clamped
    }
}
