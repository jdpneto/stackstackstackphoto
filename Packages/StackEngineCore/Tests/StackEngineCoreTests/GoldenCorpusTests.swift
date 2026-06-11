import XCTest
import simd
import ImageIO
import CoreGraphics
@testable import StackEngineCore

// ============================================================
// REGENERATION PROCEDURE
// ============================================================
// When the pipeline changes intentionally and the golden images need updating:
//
//   SSS_REGENERATE_GOLDENS=1 swift test --filter GoldenCorpusTests -c release
//
// The test writes 5 PNGs to /tmp/sss-goldens/ and then XCTSkip-s (no comparisons
// are made during regeneration so a stale golden can never self-bless).
// Then copy the PNGs into Tests/StackEngineCoreTests/Resources/golden/ and re-run
// without the env variable — all 5 cases should pass with PSNR=inf/SSIM≈1/ΔE≈0.
//
// CROSS-PLATFORM CONTRACT
// These goldens are the Android equivalence guardrail (spec §3, bible §18).
// The fixture generators (goldenBurst / goldenBrackets) are fully deterministic:
// seeded LCG only, no Date/SystemRandom, no platform-specific paths.
// Tolerances (minPSNR=45 dB, minSSIM=0.98, maxDeltaE=1.0) are loose enough for
// cross-platform Float accumulation drift and libm (sin/pow) last-ULP variance across
// platforms, yet tight enough to catch algorithm changes. These tolerances are absorbed
// by the test assertions on both platforms.
// Determinism is preserved by parallelMap's order-preserving slot-indexed writes,
// ensuring bit-reproducible results across parallel processing.
// ============================================================

// MARK: - Fixture generators

/// A synthetic 96×64 Bayer burst — the cross-platform input contract for all four reducer looks.
///
/// Scene: a linear ramp base (prevents constant-image degeneracy) with two sine octaves
/// (prevents aliasing into a flat field after stacking). All arithmetic is integer/Float with a
/// seeded LCG — no Date or SystemRandom so the output is bit-for-bit identical everywhere.
///
/// Per-frame jitter (dx,dy) and CFA-mosaic sampling keep the fixture honest:
///   • Translation jitter forces the aligner to do real work (a zero-offset burst collapses to
///     a trivial identity alignment where any mistake is invisible).
///   • RGGB mosaic + develop exercises the full ColorPipeline path, not just StackReducer.
///
/// Layout (matches CFA contract in RawSensorFrame.swift):
///   RGGB:  (even row, even col)=R, (even row, odd col)=G,
///          (odd  row, even col)=G, (odd  row, odd  col)=B
func goldenBurst() -> [RawSensorFrame] {
    let W = 96, H = 64
    let blackLevel: Float = 64, whiteLevel: Float = 1024

    // Per-frame (dx, dy) integer translation jitter — kept small so the aligner doesn't saturate.
    // These are the cross-platform input: do NOT randomize them.
    let jitter: [(Int, Int)] = [(0, 0), (1, 0), (-1, 1), (2, -1), (0, 2), (-2, 0)]
    assert(jitter.count == 6, "goldenBurst: expected 6 frames")

    // LCG parameters (Numerical Recipes): multiplier=1664525, increment=1013904223, mod=2^32.
    // Seed is reset to the SAME value for each frame so noise is independent and reproducible.
    let lcgMul: UInt32 = 1664525
    let lcgInc: UInt32 = 1013904223

    var frames: [RawSensorFrame] = []
    frames.reserveCapacity(6)

    for (frameIdx, (dx, dy)) in jitter.enumerated() {
        // Unique per-frame seed (based on frame index) so noise patterns differ across frames.
        var seed = UInt32(42 + frameIdx * 7919)

        var mosaic = [UInt16](repeating: 0, count: W * H)

        for row in 0..<H {
            for col in 0..<W {
                // Sample from the scene using this frame's translation offset.
                // Clamp to valid range so edge frames don't wrap.
                let sx = min(max(col - dx, 0), W - 1)
                let sy = min(max(row - dy, 0), H - 1)

                let fx = Float(sx) / Float(W - 1)   // ∈ [0,1]
                let fy = Float(sy) / Float(H - 1)   // ∈ [0,1]

                // Scene: ramp + two sine octaves.  All channels receive the same luma
                // (neutral scene) — the CFA pattern provides the R/G/B split.
                let scene: Float = 0.3
                    + 0.4 * fx                              // ramp
                    + 0.15 * sin(Float.pi * 4 * fx) * sin(Float.pi * 3 * fy)   // octave 1
                    + 0.08 * sin(Float.pi * 9 * fx) * sin(Float.pi * 7 * fy)   // octave 2

                // Noise amplitude ±8 on the [200,800] sensor range.
                seed = seed &* lcgMul &+ lcgInc
                let noise = (Float(seed >> 16 & 0x7FFF) / 32767.0 - 0.5) * 16.0   // ±8

                // Map [0,1] scene to sensor range [200, 800] + noise.
                let raw = scene * 600.0 + 200.0 + noise
                let clamped = max(0, min(Float(UInt16.max), raw))
                mosaic[row * W + col] = UInt16(clamped)
            }
        }

        frames.append(RawSensorFrame(
            width: W, height: H,
            mosaic: mosaic,
            blackLevel: blackLevel, whiteLevel: whiteLevel,
            cfa: .rggb,
            wbGains: SIMD3<Float>(1, 1, 1),
            colorMatrix: matrix_identity_float3x3
        ))
    }
    return frames
}

/// A synthetic 96×64 focus-bracket sequence for the depth-of-field golden.
/// Reuses the existing `chainBracketFrames` fixture (defined in AffineAlignerChainTests.swift)
/// with two small similarity steps — translation-dominant, inside ChainBounds, deterministic.
func goldenBrackets() -> [PixelImage] {
    let steps: [Transform2D] = [
        .similarity(scale: 1.005, rotation: 0.002, tx:  0.8, ty: -0.4),
        .similarity(scale: 1.004, rotation: -0.001, tx: -0.6, ty:  0.5),
    ]
    return chainBracketFrames(w: 96, h: 64, steps: steps).frames
}

// MARK: - Shared PNG helpers

/// Save a PixelImage as a PNG file at the given path.  Uses the CGContext→CGImageDestination
/// pattern from _DebugRealFrames.save — reused here to keep one canonical PNG-write path.
private func savePNG(_ img: PixelImage, to path: String) throws {
    // Encode linear → sRGB8 via OutputTransform (same path as production output).
    let rgba8 = OutputTransform.encodeSRGB8(img)

    // Build a CGImage from the RGBA8 bytes.
    var buf = rgba8   // copy for mutable pointer
    guard let ctx = CGContext(
        data: &buf,
        width: img.width, height: img.height,
        bitsPerComponent: 8, bytesPerRow: img.width * 4,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ), let cg = ctx.makeImage() else {
        throw GoldenError.save
    }
    let url = URL(fileURLWithPath: path) as CFURL
    guard let dst = CGImageDestinationCreateWithURL(url, "public.png" as CFString, 1, nil) else {
        throw GoldenError.save
    }
    CGImageDestinationAddImage(dst, cg, nil)
    guard CGImageDestinationFinalize(dst) else { throw GoldenError.save }
}

/// Load a PNG at `url` and return:
///   - A PixelImage decoded to linear-light (sRGB→linear via OutputTransform.decodeSRGB8)
///   - The raw RGBA8 bytes (for PSNR comparison against another sRGB-encoded byte buffer)
///
/// Uses the CGContext → RGBA8 pattern from DepthBracketRegressionTests.load, extended to
/// also return the raw bytes so the caller avoids a second re-encode just for PSNR.
private func loadGoldenPNG(url: URL) throws -> (image: PixelImage, rgba8: [UInt8]) {
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
          let cg = CGImageSourceCreateImageAtIndex(src, 0, nil) else { throw GoldenError.load }
    let w = cg.width, h = cg.height
    var buf = [UInt8](repeating: 0, count: w * h * 4)
    guard let ctx = CGContext(
        data: &buf,
        width: w, height: h,
        bitsPerComponent: 8, bytesPerRow: w * 4,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { throw GoldenError.load }
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
    // Decode sRGB bytes → linear PixelImage for SSIM and ΔE comparisons.
    let image = OutputTransform.decodeSRGB8(buf, width: w, height: h)
    return (image, buf)
}

private enum GoldenError: Error { case load, save }

// MARK: - GoldenCorpusTests

/// Always-on golden regression suite for every look's full-pipeline output.
///
/// Five cases (noiseReduction / smoothMotion / lightTrails / lowLightBoost / depth) each:
///   1. Run the pipeline on a fully-deterministic synthetic input.
///   2. Compare against a committed reference PNG (Resources/golden/*.png).
///   3. Assert PSNR ≥ 45 dB, SSIM ≥ 0.98, mean ΔE ≤ 1.0.
///
/// These are the Android cross-platform equivalence guardrails (bible §18, spec §3).
/// The fast, synthetic inputs make this suite suitable for every `swift test` run.
///
/// See REGENERATION PROCEDURE at the top of this file.
final class GoldenCorpusTests: XCTestCase {

    // The Android contract — pinned here, not buried in per-test magic numbers.
    private static let minPSNR:    Double = 45.0
    private static let minSSIM:    Double = 0.98
    private static let maxDeltaE:  Double = 1.0

    // MARK: - Reducer looks (synthetic Bayer burst → Pipeline.reduce)

    func testGoldenNoiseReduction() throws {
        try runGoldenTest(name: "noiseReduction") {
            Pipeline.reduce(goldenBurst(), mode: .noiseReduction, binnedDevelop: false)
        }
    }

    func testGoldenSmoothMotion() throws {
        try runGoldenTest(name: "smoothMotion") {
            Pipeline.reduce(goldenBurst(), mode: .smoothMotion, binnedDevelop: false)
        }
    }

    func testGoldenLightTrails() throws {
        try runGoldenTest(name: "lightTrails") {
            Pipeline.reduce(goldenBurst(), mode: .lightTrails, binnedDevelop: false)
        }
    }

    func testGoldenLowLightBoost() throws {
        try runGoldenTest(name: "lowLightBoost") {
            Pipeline.reduce(goldenBurst(), mode: .lowLightBoost, binnedDevelop: false)
        }
    }

    // MARK: - Depth-of-field look (synthetic brackets → FocusStacker)

    func testGoldenDepth() throws {
        let config = DepthConfig(workingResolution: nil, maxFrames: 12)
        guard let result = FocusStacker.allInFocus(goldenBrackets(), config: config) else {
            XCTFail("FocusStacker.allInFocus returned nil on goldenBrackets")
            return
        }
        try runGoldenTest(name: "depth") {
            result
        }
    }

    // MARK: - Core helper

    /// Run one golden test:
    ///   - If SSS_REGENERATE_GOLDENS=1: write `name.png` to /tmp/sss-goldens/ then XCTSkip.
    ///   - Otherwise: load the bundled golden, compare PSNR/SSIM/ΔE.
    private func runGoldenTest(name: String, produce: () -> PixelImage) throws {
        let result = produce()

        if ProcessInfo.processInfo.environment["SSS_REGENERATE_GOLDENS"] == "1" {
            // Regeneration path — write the result, then skip (never compare against itself).
            let dir = "/tmp/sss-goldens"
            try FileManager.default.createDirectory(atPath: dir,
                                                     withIntermediateDirectories: true)
            let path = "\(dir)/\(name).png"
            try savePNG(result, to: path)
            print("[GoldenCorpusTests] wrote \(path)")
            print("[GoldenCorpusTests] copy command: cp \(path) Tests/StackEngineCoreTests/Resources/golden/\(name).png")
            throw XCTSkip("goldens regenerated — copy into Resources/golden and re-run without SSS_REGENERATE_GOLDENS")
        }

        // Normal path — load the bundled reference and assert tolerances.
        guard let goldenURL = Bundle.module.url(forResource: name, withExtension: "png",
                                                subdirectory: "Resources/golden") else {
            // Missing golden is a build/resource misconfiguration, not a skip.
            XCTFail("bundled golden \(name).png missing — run with SSS_REGENERATE_GOLDENS=1, copy PNGs into Resources/golden/, then re-run")
            return
        }

        let (goldenImage, goldenRGBA8) = try loadGoldenPNG(url: goldenURL)

        // Encode the result through the same sRGB8 path used when the golden was generated.
        // This round-trip (linear → sRGB8 → linear) is the canonical comparison domain: it
        // eliminates sub-ULP float differences and matches what the PNG stores.
        let resultRGBA8 = OutputTransform.encodeSRGB8(result)
        let resultImage = OutputTransform.decodeSRGB8(resultRGBA8, width: result.width, height: result.height)

        // PSNR: compare sRGB8-encoded bytes (same layout as the PNG pixels).
        let psnr = Metrics.psnr(resultRGBA8, goldenRGBA8)
        XCTAssertGreaterThanOrEqual(psnr, Self.minPSNR,
            "\(name): PSNR \(String(format: "%.1f", psnr)) dB < \(Self.minPSNR) dB threshold (pipeline output drifted from golden)")

        // SSIM and ΔE: compare in the round-tripped sRGB8-decoded linear domain so both images
        // have identical clipping (highlights above 1.0 are clipped to 1.0 in the PNG, and the
        // round-trip applies the same clamp to the result — avoids spurious failures when
        // lowLightBoost saturates highlights that the golden also stores as 1.0).
        let ssim = Metrics.ssim(resultImage, goldenImage)
        XCTAssertGreaterThanOrEqual(ssim, Self.minSSIM,
            "\(name): SSIM \(String(format: "%.4f", ssim)) < \(Self.minSSIM) threshold")

        let deltaE = Metrics.meanDeltaE(resultImage, goldenImage)
        XCTAssertLessThanOrEqual(deltaE, Self.maxDeltaE,
            "\(name): mean ΔE76 \(String(format: "%.3f", deltaE)) > \(Self.maxDeltaE) threshold")
    }
}
