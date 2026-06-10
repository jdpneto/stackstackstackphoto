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

    func testComposedAppliesRightHandSideFirst() {
        let scale = Transform2D.similarity(scale: 2, rotation: 0, tx: 0, ty: 0)
        let shift = Transform2D.similarity(scale: 1, rotation: 0, tx: 3, ty: -1)
        // scale ∘ shift: p → scale(shift(p)) = 2·(p + (3,−1))
        let p = scale.composed(with: shift).apply(1, 1)
        XCTAssertEqual(p.x, 8, accuracy: 1e-5)
        XCTAssertEqual(p.y, 0, accuracy: 1e-5)
    }

    func testComposedWithIdentityIsUnchanged() {
        let t = Transform2D.similarity(scale: 1.04, rotation: 0.02, tx: 2, ty: -1)
        // exact ==: multiplying by exact 1.0/0.0 is lossless in IEEE — don't weaken to tolerances.
        XCTAssertEqual(t.composed(with: .identity), t)
        XCTAssertEqual(Transform2D.identity.composed(with: t), t)
    }

    func testInverseRoundTripsToIdentity() {
        let t = Transform2D.similarity(scale: 1.04, rotation: 0.02, tx: 2, ty: -1)
        let id = t.composed(with: t.inverse)
        XCTAssertEqual(id.a, 1, accuracy: 1e-5)
        XCTAssertEqual(id.b, 0, accuracy: 1e-5)
        XCTAssertEqual(id.c, 0, accuracy: 1e-5)
        XCTAssertEqual(id.d, 1, accuracy: 1e-5)
        XCTAssertEqual(id.tx, 0, accuracy: 1e-4)
        XCTAssertEqual(id.ty, 0, accuracy: 1e-4)
    }
}
