import XCTest
import simd
@testable import StackEngineCore

final class LaplacianPyramidBlendTests: XCTestCase {
    func testFullWeightOnOneFrameReturnsThatFrame() {
        let w = 16, h = 16, n = w * h
        let a = PixelImage(width: w, height: h, fill: SIMD3<Float>(0.8, 0.2, 0.2))
        let b = PixelImage(width: w, height: h, fill: SIMD3<Float>(0.2, 0.2, 0.8))
        let out = LaplacianPyramidBlend.blend(images: [a, b],
                                              weights: [[Float](repeating: 1, count: n),
                                                        [Float](repeating: 0, count: n)])
        XCTAssertEqual(out[8, 8].x, 0.8, accuracy: 2e-3)   // all weight on A → A
        XCTAssertEqual(out[8, 8].z, 0.2, accuracy: 2e-3)
    }

    func testCombinesEachFramesSharpRegion() {
        // Frame A: left half checker (sharp), right half flat. Frame B: the opposite.
        let w = 32, h = 16
        func frame(sharpLeft: Bool) -> PixelImage {
            var img = PixelImage(width: w, height: h, fill: SIMD3<Float>(0.5, 0.5, 0.5))
            for y in 0..<h { for x in 0..<w {
                let inLeft = x < w / 2
                if inLeft == sharpLeft {
                    let v: Float = ((x + y) % 2 == 0) ? 0.85 : 0.15
                    img[x, y] = SIMD3<Float>(v, v, v)
                }
            }}
            return img
        }
        let a = frame(sharpLeft: true), b = frame(sharpLeft: false)
        // Weights pick the in-focus frame per half.
        var wA = [Float](repeating: 0, count: w * h), wB = wA
        for y in 0..<h { for x in 0..<w {
            if x < w / 2 { wA[y * w + x] = 1 } else { wB[y * w + x] = 1 }
        }}
        let out = LaplacianPyramidBlend.blend(images: [a, b], weights: [wA, wB])
        // The composite is detailed in BOTH halves → total sharpness exceeds either single frame.
        let total = SharpnessMap.compute(out).reduce(0, +)
        XCTAssertGreaterThan(total, SharpnessMap.compute(a).reduce(0, +) * 1.3)
        XCTAssertGreaterThan(total, SharpnessMap.compute(b).reduce(0, +) * 1.3)
    }
}
