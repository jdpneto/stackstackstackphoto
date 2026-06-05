import XCTest
import simd
@testable import StackEngineCore

final class FocusStackerTests: XCTestCase {
    /// A focus bracket: mid-frequency detail (sharp, in focus) in vertical third `third`, flat
    /// elsewhere — a synthetic depth bracket. (Frames are co-registered; alignment defaults off.)
    func bracket(third: Int, of count: Int, w: Int, h: Int) -> PixelImage {
        var img = PixelImage(width: w, height: h, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        let band = w / count
        for y in 0..<h { for x in 0..<w where (x / band) == third {
            let v = 0.5 + 0.4 * sin(Float(x) * 0.9) * sin(Float(y) * 0.9)   // period ~7 px
            img[x, y] = SIMD3<Float>(v, v, v)
        }}
        return img
    }

    func testAllInFocusBeatsAnySingleFrame() {
        let w = 36, h = 18
        let frames = (0..<3).map { bracket(third: $0, of: 3, w: w, h: h) }
        let out = try! XCTUnwrap(FocusStacker.allInFocus(frames, config: DepthConfig(workingResolution: nil, maxFrames: 12)))
        let total = SharpnessMap.compute(out).reduce(0, +)
        // The composite is sharp in ALL thirds → markedly sharper than any single bracket.
        for f in frames {
            XCTAssertGreaterThan(total, SharpnessMap.compute(f).reduce(0, +) * 1.5)
        }
    }

    func testAlignPathRunsAndStaysSharp() {
        // With alignment ON, co-registered brackets still produce a sharper-than-single composite
        // (translation finds ~0 shift here; this exercises the align code path).
        let w = 36, h = 18
        let frames = (0..<3).map { bracket(third: $0, of: 3, w: w, h: h) }
        let out = try! XCTUnwrap(FocusStacker.allInFocus(frames, config: DepthConfig(workingResolution: nil, maxFrames: 12, alignFrames: true)))
        XCTAssertEqual(out.width, w); XCTAssertEqual(out.height, h)
        XCTAssertGreaterThan(SharpnessMap.compute(out).reduce(0, +), 0)
    }

    func testEmptyReturnsNilAndSingleFrameReturnsItself() {
        XCTAssertNil(FocusStacker.allInFocus([], config: .auto))
        let img = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        XCTAssertEqual(FocusStacker.allInFocus([img], config: DepthConfig(workingResolution: nil, maxFrames: 12))?.width, 8)
    }

    func testMismatchedFrameSizesReturnNil() {
        let a = PixelImage(width: 16, height: 16, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        let b = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        XCTAssertNil(FocusStacker.allInFocus([a, b], config: DepthConfig(workingResolution: nil, maxFrames: 12)))
    }

    func testWorkingResolutionDownscales() {
        let img = PixelImage(width: 64, height: 64, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        let out = try! XCTUnwrap(FocusStacker.allInFocus([img, img], config: DepthConfig(workingResolution: 20, maxFrames: 12)))
        XCTAssertLessThanOrEqual(max(out.width, out.height), 20)
    }
}
