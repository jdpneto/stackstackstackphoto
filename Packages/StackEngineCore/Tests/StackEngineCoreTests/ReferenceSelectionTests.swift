import XCTest
import simd
@testable import StackEngineCore

final class ReferenceSelectionTests: XCTestCase {
    private func checkerboard(_ n: Int) -> PixelImage {
        var img = PixelImage(width: n, height: n)
        for y in 0..<n { for x in 0..<n {
            let v: Float = ((x + y) % 2 == 0) ? 1 : 0
            img[x, y] = SIMD3<Float>(v, v, v)
        }}
        return img
    }
    private func flat(_ n: Int, _ v: Float) -> PixelImage {
        PixelImage(width: n, height: n, fill: SIMD3<Float>(v, v, v))
    }

    func testSharpnessHigherForCheckerboard() {
        XCTAssertGreaterThan(Luma.sharpness(checkerboard(8)), Luma.sharpness(flat(8, 0.5)))
    }
    func testReferenceSelectionPicksSharpest() {
        let frames = [flat(8, 0.5), checkerboard(8), flat(8, 0.3)]
        XCTAssertEqual(ReferenceSelection.sharpestIndex(frames), 1)
    }

    func testSingleFrameReturnsZero() {
        XCTAssertEqual(ReferenceSelection.sharpestIndex([flat(8, 0.5)]), 0)
    }
}
