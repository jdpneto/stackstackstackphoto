import XCTest
import UIKit
@testable import StackStackStack

final class CaptureOrientationTests: XCTestCase {
    func testMapping() {
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .portrait), 1)
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .portraitUpsideDown), 3)
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .landscapeLeft), 0)
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .landscapeRight), 2)
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .faceUp), 1)
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .faceDown), 1)
        XCTAssertEqual(CaptureOrientation.quarterTurns(for: .unknown), 1)
    }
}
