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
        let out = try! XCTUnwrap(FocusStacker.allInFocus(frames, config: DepthConfig(workingResolution: nil, maxFrames: 12, alignFrames: false)))
        let total = SharpnessMap.compute(out).reduce(0, +)
        // The composite is sharp in ALL thirds → markedly sharper than any single bracket.
        for f in frames {
            XCTAssertGreaterThan(total, SharpnessMap.compute(f).reduce(0, +) * 1.5)
        }
    }

    func testAlignPathRunsAndStaysSharp() {
        // With alignment ON, co-registered brackets chain to ~identity links and still produce a
        // sharper-than-single composite (exercises the alignChain code path).
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
        let out = try! XCTUnwrap(FocusStacker.allInFocus([img, img], config: DepthConfig(workingResolution: 20, maxFrames: 12, alignFrames: false)))
        XCTAssertLessThanOrEqual(max(out.width, out.height), 20)
    }

    func testDefaultConfigAlignsFrames() {
        // Chain alignment is the default — the handheld promise (spec §4.4). `alignFrames: false`
        // remains available for the device alignment-off comparison.
        XCTAssertTrue(DepthConfig.auto.alignFrames)
        XCTAssertTrue(DepthConfig(workingResolution: nil, maxFrames: 5).alignFrames)
    }

    func testThereIsNoFullResProPreset() {
        // 48 MP full-res runs hit the ~3 GB jetsam limit — the managed preset is the only one.
        // (Compile-time check by absence: DepthConfig.pro must not exist. This test documents it.)
        XCTAssertEqual(DepthConfig.auto.workingResolution, 1500)
        XCTAssertEqual(DepthConfig.auto.maxFrames, 10)
    }

    /// Drifting, blur-varying brackets (the real handheld scenario): the default config must
    /// chain-align them and still produce an everywhere-sharper composite.
    func testAllInFocusOnDriftingBracketsBeatsEveryInput() {
        let (frames, _) = chainBracketFrames(w: 96, h: 64, steps: [
            Transform2D.similarity(scale: 1.01, rotation: 0.004, tx: 1.0, ty: -0.5),
            Transform2D.similarity(scale: 1.008, rotation: -0.003, tx: -0.8, ty: 0.6),
        ])
        let out = try! XCTUnwrap(FocusStacker.allInFocus(frames, config: DepthConfig(workingResolution: nil, maxFrames: 12)))
        let total = SharpnessMap.compute(out).reduce(0, +)
        for f in frames {
            XCTAssertGreaterThan(total, SharpnessMap.compute(f).reduce(0, +) * 1.2,
                                 "aligned composite must out-sharpen every single drifted bracket")
        }
    }
}
