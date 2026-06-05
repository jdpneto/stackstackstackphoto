import XCTest
import simd
@testable import StackEngineCore

final class SharpnessMapTests: XCTestCase {
    func testUniformImageHasNearZeroSharpness() {
        let s = SharpnessMap.compute(PixelImage(width: 16, height: 16, fill: SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertLessThan(s.max() ?? 0, 1e-5)
    }

    func testSharpnessHigherInDetailedRegion() {
        // Left half: high-frequency checker (in focus). Right half: flat (no detail).
        let w = 32, h = 16
        var img = PixelImage(width: w, height: h, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        for y in 0..<h { for x in 0..<(w / 2) {
            let v: Float = ((x + y) % 2 == 0) ? 0.9 : 0.1
            img[x, y] = SIMD3<Float>(v, v, v)
        }}
        let s = SharpnessMap.compute(img)
        func avg(_ x0: Int, _ x1: Int) -> Float {
            var sum: Float = 0; var n = 0
            for y in 4..<(h - 4) { for x in (x0 + 4)..<(x1 - 4) { sum += s[y * w + x]; n += 1 } }
            return sum / Float(n)
        }
        XCTAssertGreaterThan(avg(0, w / 2), avg(w / 2, w) * 5)   // detailed region much sharper
    }
}
