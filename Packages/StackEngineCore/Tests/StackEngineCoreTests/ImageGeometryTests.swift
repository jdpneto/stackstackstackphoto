import XCTest
import simd
@testable import StackEngineCore

final class ImageGeometryTests: XCTestCase {
    // 3×2 image, each pixel uniquely valued by its row-major index.
    private func sample(_ w: Int = 3, _ h: Int = 2) -> PixelImage {
        PixelImage(width: w, height: h,
                   pixels: (0..<(w*h)).map { SIMD3(Float($0), Float($0) * 2, Float($0) * 3) })
    }

    func testZeroAndFourTurnsAreIdentity() {
        let img = sample()
        XCTAssertEqual(ImageGeometry.rotated(img, quarterTurns: 0), img)
        XCTAssertEqual(ImageGeometry.rotated(img, quarterTurns: 4), img)
        XCTAssertEqual(ImageGeometry.rotated(img, quarterTurns: -4), img)
    }

    func testOneTurnSwapsDimensionsAndMapsTopLeftToTopRight() {
        let img = sample(3, 2)                                  // w=3, h=2
        let r = ImageGeometry.rotated(img, quarterTurns: 1)     // 90° clockwise
        XCTAssertEqual(r.width, 2)
        XCTAssertEqual(r.height, 3)
        XCTAssertEqual(r[r.width - 1, 0], img[0, 0])           // source top-left → rotated top-right
    }

    func testTwoTurnsIs180() {
        let img = sample(3, 2)
        let r = ImageGeometry.rotated(img, quarterTurns: 2)
        XCTAssertEqual(r.width, 3); XCTAssertEqual(r.height, 2)
        XCTAssertEqual(r[img.width - 1, img.height - 1], img[0, 0])
    }

    func testNegativeWrapsToThree() {
        let img = sample()
        XCTAssertEqual(ImageGeometry.rotated(img, quarterTurns: -1),
                       ImageGeometry.rotated(img, quarterTurns: 3))
    }

    func testThreeTurnsSwapsDimensionsAndMapsTopLeftToBottomLeft() {
        let img = sample(3, 2)                                  // w=3, h=2
        let r = ImageGeometry.rotated(img, quarterTurns: 3)     // 270° CW = 90° CCW
        XCTAssertEqual(r.width, 2)
        XCTAssertEqual(r.height, 3)
        XCTAssertEqual(r[0, r.height - 1], img[0, 0])          // source top-left → rotated bottom-left
    }
}
