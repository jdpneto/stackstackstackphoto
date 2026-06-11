import XCTest
import simd
@testable import StackEngineCore

final class MetricsTests: XCTestCase {
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
    func testMaxAbsDiffZeroForIdentical() {
        let a = PixelImage(width: 2, height: 1,
            pixels: [SIMD3<Float>(0.1, 0.2, 0.3), SIMD3<Float>(0.4, 0.5, 0.6)])
        XCTAssertEqual(Metrics.maxAbsDiff(a, a), 0, accuracy: 1e-7)
    }
    func testMaxAbsDiffFindsLargestChannelDelta() {
        let a = PixelImage(width: 1, height: 1, pixels: [SIMD3<Float>(0.1, 0.2, 0.3)])
        let b = PixelImage(width: 1, height: 1, pixels: [SIMD3<Float>(0.1, 0.7, 0.25)])
        XCTAssertEqual(Metrics.maxAbsDiff(a, b), 0.5, accuracy: 1e-6) // |0.2-0.7| is the max
    }

    // MARK: - SSIM tests

    func testSSIMIdentityIsOneAndNoiseLowers() {
        let img = gradientImage(64, 48)
        XCTAssertEqual(Metrics.ssim(img, img), 1.0, accuracy: 1e-9)
        var noisy = img
        var seed: UInt32 = 1
        for i in 0..<noisy.pixels.count {        // seeded LCG — deterministic noise
            seed = seed &* 1664525 &+ 1013904223
            let n = (Float(seed >> 16 & 0x7FFF) / 32767 - 0.5) * 0.05
            noisy.pixels[i] += SIMD3(repeating: n)
        }
        let s = Metrics.ssim(img, noisy)
        XCTAssertLessThan(s, 0.9999)
        XCTAssertGreaterThan(s, 0.85, "mild noise must not crater SSIM")
    }

    // MARK: - ΔE tests

    func testDeltaECatchesAHueShiftPSNRBarelySees() {
        let img = gradientImage(64, 48)
        var shifted = img
        for i in 0..<shifted.pixels.count { shifted.pixels[i].x *= 1.06 }   // 6% red gain
        XCTAssertEqual(Metrics.meanDeltaE(img, img), 0, accuracy: 1e-9)
        XCTAssertGreaterThan(Metrics.meanDeltaE(img, shifted), 0.8, "a visible color cast must register")
    }
}

// MARK: - Helpers

/// Build a deterministic textured PixelImage (linear-light [0,1] linear ramp in both axes).
/// Used by MetricsTests so that SSIM has enough structure to produce meaningful window statistics.
private func gradientImage(_ width: Int, _ height: Int) -> PixelImage {
    var pixels = [SIMD3<Float>](repeating: .zero, count: width * height)
    for y in 0..<height {
        for x in 0..<width {
            let fx = Float(x) / Float(width - 1)
            let fy = Float(y) / Float(height - 1)
            // Distinct channel ramps so colour metrics have signal.
            pixels[y * width + x] = SIMD3<Float>(fx, fy, (fx + fy) * 0.5)
        }
    }
    return PixelImage(width: width, height: height, pixels: pixels)
}
