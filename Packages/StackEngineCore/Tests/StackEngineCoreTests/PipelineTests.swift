import XCTest
import simd
@testable import StackEngineCore

final class PipelineTests: XCTestCase {
    func testPSNRIdenticalIsInfinite() {
        let a: [UInt8] = [10, 20, 30, 255]
        XCTAssertEqual(Metrics.psnr(a, a), .infinity)
    }
    func testPSNRDecreasesWithError() {
        let a: [UInt8] = [100, 100, 100, 100]
        let b: [UInt8] = [110, 90, 105, 100]
        let p = Metrics.psnr(a, b)
        XCTAssertGreaterThan(p, 20)
        XCTAssertLessThan(p, 60)
    }

    /// Deterministic synthetic scene + per-frame noise + small shifts.
    private func makeNoisyShiftedStack(clean: PixelImage, count: Int) -> [PixelImage] {
        let w = clean.width, h = clean.height
        var frames = [PixelImage]()
        for k in 0..<count {
            // Deterministic shift pattern and additive noise (no RNG, so the test is stable).
            let sx = (k % 3) - 1            // -1,0,1,-1,0,1...
            let sy = ((k / 3) % 3) - 1
            var img = PixelImage(width: w, height: h)
            for y in 0..<h { for x in 0..<w {
                let cx = min(max(x - sx, 0), w - 1), cy = min(max(y - sy, 0), h - 1)
                // Deterministic noise in scene coordinates: scene-coord indexing ensures
                // the noise contribution at each pixel is consistent across aligned frames,
                // so it averages out after stacking. Amplitude ÷200 (≈±0.025) stays below
                // the per-pixel luma gradient (≈0.030) so alignment is reliable.
                let noise = Float((k * 37 + cx * 7 + cy * 13) % 11 - 5) / 200.0
                let base = clean[cx, cy]
                img[x, y] = SIMD3<Float>(base.x + noise, base.y + noise, base.z + noise)
            }}
            frames.append(img)
        }
        return frames
    }

    func testNoiseReductionConvergesToCleanScene() {
        let n = 24
        var clean = PixelImage(width: n, height: n)
        for y in 0..<n { for x in 0..<n {
            let v = Float(x) / Float(n)               // horizontal gradient (alignable)
            clean[x, y] = SIMD3<Float>(v, v * 0.8, 1 - v)
        }}
        let frames = makeNoisyShiftedStack(clean: clean, count: 12)
        let result = Pipeline.noiseReductionImages(frames, searchRange: 2, kappa: 2.0)

        // Compare interior (avoid edge-clamp artifacts from warping).
        var maxDiff: Float = 0
        for y in 4..<(n-4) { for x in 4..<(n-4) {
            let d = result[x, y] - clean[x, y]
            maxDiff = max(maxDiff, max(abs(d.x), max(abs(d.y), abs(d.z))))
        }}
        XCTAssertLessThan(maxDiff, 0.03, "stacked result should converge to the clean scene")

        // And the encoded result should be high-PSNR vs the encoded clean scene.
        let psnr = Metrics.psnr(OutputTransform.encodeSRGB8(result),
                                OutputTransform.encodeSRGB8(clean))
        XCTAssertGreaterThan(psnr, 30.0)
    }
}
