import XCTest
import simd
@testable import StackEngineCore

final class ImagePyramidTests: XCTestCase {
    func testReduceHalvesDimensions(){
        let img = PixelImage(width: 8, height: 6, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        let r = ImagePyramid.reduce(img)
        XCTAssertEqual(r.width, 4); XCTAssertEqual(r.height, 3)   // ceil(w/2), ceil(h/2)
    }

    func testReduceOfConstantIsConstant() {
        let img = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.3, 0.6, 0.9))
        let r = ImagePyramid.reduce(img)
        XCTAssertEqual(r[1, 1].x, 0.3, accuracy: 1e-5)   // border-renormalized → no darkening
        XCTAssertEqual(r[1, 1].y, 0.6, accuracy: 1e-5)
        XCTAssertEqual(r[0, 0].z, 0.9, accuracy: 1e-5)   // even at the corner
    }

    func testExpandOfConstantIsConstantAtTargetSize() {
        let img = PixelImage(width: 4, height: 4, fill: SIMD3<Float>(0.4, 0.4, 0.4))
        let e = ImagePyramid.expand(img, toWidth: 8, toHeight: 7)
        XCTAssertEqual(e.width, 8); XCTAssertEqual(e.height, 7)
        XCTAssertEqual(e[3, 3].x, 0.4, accuracy: 1e-5)
    }

    func testLaplacianCollapseReconstructsConstant() {
        // For a constant image, reduce/expand are exact → collapse(laplacian(img)) == img exactly.
        let img = PixelImage(width: 12, height: 10, fill: SIMD3<Float>(0.25, 0.5, 0.75))
        let out = ImagePyramid.collapse(ImagePyramid.laplacian(img))
        XCTAssertEqual(out.width, 12); XCTAssertEqual(out.height, 10)
        XCTAssertLessThan(Metrics.maxAbsDiff(out, img), 1e-4)
    }
}
