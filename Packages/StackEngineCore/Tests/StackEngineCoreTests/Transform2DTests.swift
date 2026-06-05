import XCTest
import simd
@testable import StackEngineCore

final class Transform2DTests: XCTestCase {
    func testIdentityMapsPointToItself() {
        let p = Transform2D.identity.apply(3, 4)
        XCTAssertEqual(p.x, 3, accuracy: 1e-6)
        XCTAssertEqual(p.y, 4, accuracy: 1e-6)
    }

    func testSimilarityScalesAndTranslates() {
        let t = Transform2D.similarity(scale: 2, rotation: 0, tx: 1, ty: -1)
        let p = t.apply(3, 4)
        XCTAssertEqual(p.x, 7, accuracy: 1e-6)   // 2·3 + 1
        XCTAssertEqual(p.y, 7, accuracy: 1e-6)   // 2·4 − 1
    }

    func testSimilarityRotatesNinetyDegrees() {
        let t = Transform2D.similarity(scale: 1, rotation: .pi / 2, tx: 0, ty: 0)
        let p = t.apply(1, 0)
        XCTAssertEqual(p.x, 0, accuracy: 1e-6)    // (1,0) rotated +90° → (0,1)
        XCTAssertEqual(p.y, 1, accuracy: 1e-6)
    }
}
