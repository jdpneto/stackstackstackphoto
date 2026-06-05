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
    func testCFAColorBGGR() {
        XCTAssertEqual(cfaColor(.bggr, 0, 0), .blue)
        XCTAssertEqual(cfaColor(.bggr, 1, 0), .green)
        XCTAssertEqual(cfaColor(.bggr, 0, 1), .green)
        XCTAssertEqual(cfaColor(.bggr, 1, 1), .red)
    }
    func testCFAColorGRBG() {
        XCTAssertEqual(cfaColor(.grbg, 0, 0), .green)
        XCTAssertEqual(cfaColor(.grbg, 1, 0), .red)
        XCTAssertEqual(cfaColor(.grbg, 0, 1), .blue)
        XCTAssertEqual(cfaColor(.grbg, 1, 1), .green)
    }
    func testCFAColorGBRG() {
        XCTAssertEqual(cfaColor(.gbrg, 0, 0), .green)
        XCTAssertEqual(cfaColor(.gbrg, 1, 0), .blue)
        XCTAssertEqual(cfaColor(.gbrg, 0, 1), .red)
        XCTAssertEqual(cfaColor(.gbrg, 1, 1), .green)
    }
    func testLinearizeSample() {
        // (v - black) / (white - black), LOW-clamped only (highlight headroom preserved).
        XCTAssertEqual(linearizeSample(64, black: 64, white: 1024), 0.0, accuracy: 1e-6)
        XCTAssertEqual(linearizeSample(1024, black: 64, white: 1024), 1.0, accuracy: 1e-6)
        XCTAssertEqual(linearizeSample(544, black: 64, white: 1024), 0.5, accuracy: 1e-6)
        XCTAssertEqual(linearizeSample(0, black: 64, white: 1024), 0.0, accuracy: 1e-6) // clamped to 0
        // Above white: NOT clamped — headroom is kept so white balance keeps clipped highlights neutral.
        XCTAssertEqual(linearizeSample(2048, black: 64, white: 1024), 1984.0 / 960.0, accuracy: 1e-6)
    }

    func testLinearizeHandlesDegenerateLevels() {
        // white <= black (missing/degenerate metadata) must not divide by zero or return NaN.
        XCTAssertEqual(linearizeSample(500, black: 1024, white: 1024), 0.0)   // white == black
        XCTAssertEqual(linearizeSample(500, black: 1024, white: 64), 0.0)     // white < black
    }
}
