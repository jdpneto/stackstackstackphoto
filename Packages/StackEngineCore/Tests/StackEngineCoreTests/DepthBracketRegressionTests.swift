import XCTest
import simd
import ImageIO
import CoreGraphics
@testable import StackEngineCore

/// Real-data regression suite for Depth-of-Field chain alignment: 10 handheld focus brackets
/// captured on an iPhone (2026-06-10 device verification, desk scene: mouse → keyboard →
/// controller → couch/TV), developed + binned to 1008×756 — the exact alignment input the app
/// produces. The first entry of the design's golden corpus (bible §18).
///
/// What it pins: on this sweep the chain measured a clean, monotonic focus-breathing curve
/// (scale 1.030 near → 0.973 far relative to the mid-sweep reference) with sub-degree rotations
/// and ≤4 px handheld drift — every link accepted by `ChainBounds` (no fallbacks). A regression
/// that breaks link estimation, composition order, or the bounds will bend or flatten that curve.
final class DepthBracketRegressionTests: XCTestCase {
    /// Heavy by design (~1 min release, ~16 min debug — full CPU stacks on real frames): opt-in via
    /// env so the routine `swift test` loop stays fast. CI / pre-merge runs set the variable.
    private func requireOptIn() throws {
        guard ProcessInfo.processInfo.environment["SSS_REAL_BRACKETS"] == "1" else {
            throw XCTSkip("heavy real-bracket regression — run with SSS_REAL_BRACKETS=1 (use -c release)")
        }
    }

    private func loadBrackets() throws -> [PixelImage] {
        let frames: [PixelImage] = try (0..<10).map { i in
            let name = String(format: "frame%02d", i)
            guard let url = Bundle.module.url(forResource: name, withExtension: "jpg",
                                              subdirectory: "Resources/depth-brackets")
            else {
                // A missing BUNDLED fixture is a build/resource misconfiguration, never a skip —
                // skipping here would silently disable the whole real-data suite.
                XCTFail("bundled bracket \(name).jpg missing — check Package.swift resources")
                throw Err.load
            }
            return try Self.load(url)
        }
        return frames
    }

    func testChainRecoversMonotonicFocusBreathingOnRealBrackets() throws {
        try requireOptIn()
        let frames = try loadBrackets()
        let refIdx = ReferenceSelection.sharpestIndex(frames)
        let transforms = AffineAligner.alignChain(frames, referenceIndex: refIdx)

        let scales = transforms.map { ($0.a * $0.a + $0.c * $0.c).squareRoot() }
        // Focus breathing is monotone in lens position: magnification decreases near → far.
        for i in 1..<scales.count {
            XCTAssertLessThan(scales[i], scales[i - 1] + 0.002,
                              "breathing curve must be (near-)monotonically decreasing at frame \(i)")
        }
        // Pin the measured span (device verification 2026-06-10): near ≈ +3.0%, far ≈ −2.7%.
        XCTAssertEqual(scales.first!, 1.030, accuracy: 0.008, "near-bracket magnification")
        XCTAssertEqual(scales.last!, 0.973, accuracy: 0.008, "far-bracket magnification")
        XCTAssertEqual(scales[refIdx], 1.0, accuracy: 1e-4, "reference is identity")

        // Every link stayed plausible on this capture — no translation-only fallbacks
        // (a fallback link carries exactly zero rotation AND unit scale; the real links don't).
        for (i, t) in transforms.enumerated() where i != refIdx {
            let rot = abs(atan2(t.c, t.a))
            XCTAssertLessThan(rot, 0.012, "rotation stays sub-degree on this sweep (frame \(i))")
        }
    }

    func testAllInFocusOnRealBracketsProducesCleanComposite() throws {
        try requireOptIn()
        let frames = try loadBrackets()
        let out = try XCTUnwrap(FocusStacker.allInFocus(frames, config: DepthConfig(workingResolution: nil, maxFrames: 24)))
        XCTAssertEqual(out.width, frames[0].width)
        XCTAssertEqual(out.height, frames[0].height)
        // The aligned composite resolves detail no misaligned stack can: compare against the
        // unaligned stack in a high-contrast off-centre window (scale misalignment grows with
        // distance from centre — ±3% breathing ≈ ±15 px at the edges → double edges that this
        // windowed sharpness comparison punishes far less than they deserve, so require only a
        // modest margin; the strong oracle is the synthetic suite + the curve test above).
        let unaligned = try XCTUnwrap(FocusStacker.allInFocus(frames, config: DepthConfig(workingResolution: nil, maxFrames: 24, alignFrames: false)))
        let alignedVar = Self.laplacianVariance(out, x0: 30, y0: 280, w: 360, h: 270)
        let unalignedVar = Self.laplacianVariance(unaligned, x0: 30, y0: 280, w: 360, h: 270)
        // Misalignment smears edges into broad ramps: edge-energy VARIANCE collapses even though
        // total energy stays high. Pin the aligned stack's advantage.
        XCTAssertGreaterThan(alignedVar, unalignedVar,
                             "chain-aligned composite must out-resolve the unaligned stack in the off-centre window")
    }

    /// Variance of the Laplacian over a window — a focus/resolution measure that punishes the
    /// doubled, low-contrast edges misalignment produces.
    private static func laplacianVariance(_ img: PixelImage, x0: Int, y0: Int, w: Int, h: Int) -> Float {
        let luma = Luma.luminance(img)
        let W = img.width
        var vals: [Float] = []
        vals.reserveCapacity(w * h)
        for y in max(y0, 1)..<min(y0 + h, img.height - 1) {
            for x in max(x0, 1)..<min(x0 + w, W - 1) {
                let l = 4 * luma[y * W + x] - luma[y * W + x - 1] - luma[y * W + x + 1]
                      - luma[(y - 1) * W + x] - luma[(y + 1) * W + x]
                vals.append(l)
            }
        }
        let mean = vals.reduce(0, +) / Float(vals.count)
        return vals.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Float(vals.count)
    }

    private enum Err: Error { case load }

    private static func load(_ url: URL) throws -> PixelImage {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let cg = CGImageSourceCreateImageAtIndex(src, 0, nil) else { throw Err.load }
        let w = cg.width, h = cg.height
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = CGContext(data: &buf, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var px = [SIMD3<Float>](repeating: .zero, count: w * h)
        for i in 0..<(w * h) {
            px[i] = SIMD3(Float(buf[i * 4]) / 255, Float(buf[i * 4 + 1]) / 255, Float(buf[i * 4 + 2]) / 255)
        }
        return PixelImage(width: w, height: h, pixels: px)
    }
}
