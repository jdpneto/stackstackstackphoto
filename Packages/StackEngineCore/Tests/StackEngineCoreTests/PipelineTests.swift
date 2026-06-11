import XCTest
import simd
@testable import StackEngineCore

final class PipelineTests: XCTestCase {
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

    func testNoiseReductionConvergesAndBeatsSingleFrame() {
        let n = 24
        // Deterministic high-frequency texture. Its near-delta autocorrelation gives the SSD
        // search a sharp, unambiguous minimum at the true integer shift — this avoids the
        // aperture problem that ANY smooth ramp suffers (where a pure-horizontal and a
        // pure-vertical 1px shift produce identical cost). Range ~0.15...0.9: no deep shadows.
        func texel(_ x: Int, _ y: Int) -> Float {
            var h = UInt32(truncatingIfNeeded: x &* 73856093) ^ UInt32(truncatingIfNeeded: y &* 19349663)
            h = h &* 2654435761
            h ^= h >> 13
            h = h &* 2246822519
            h ^= h >> 16
            return Float(h & 0xFFFF) / Float(0xFFFF)
        }
        var clean = PixelImage(width: n, height: n)
        for y in 0..<n { for x in 0..<n {
            let s = 0.15 + 0.75 * texel(x, y)
            clean[x, y] = SIMD3<Float>(s, s, s)
        }}
        let frames = makeNoisyShiftedStack(clean: clean, count: 12)

        // The pipeline aligns everything to the SHARPEST frame, so the result lives in that
        // frame's coordinate system (clean shifted by the reference frame's own shift).
        func trueShift(_ k: Int) -> StackEngineCore.Translation {
            StackEngineCore.Translation(dx: (k % 3) - 1, dy: ((k / 3) % 3) - 1)
        }
        let refIdx = ReferenceSelection.sharpestIndex(frames)
        let ref = trueShift(refIdx)

        // Alignment must recover each non-reference frame's true integer shift.
        for k in 0..<frames.count where k != refIdx {
            let est = Alignment.estimateTranslation(reference: frames[refIdx], moving: frames[k], searchRange: 2)
            let expected = StackEngineCore.Translation(dx: trueShift(k).dx - ref.dx, dy: trueShift(k).dy - ref.dy)
            XCTAssertEqual(est, expected, "alignment should recover frame \(k)'s true shift")
        }

        // Ground truth expressed in the reference frame's coordinates.
        var refClean = PixelImage(width: n, height: n)
        for y in 0..<n { for x in 0..<n {
            let cx = min(max(x - ref.dx, 0), n - 1), cy = min(max(y - ref.dy, 0), n - 1)
            refClean[x, y] = clean[cx, cy]
        }}

        let result = Pipeline.noiseReductionImages(frames, searchRange: 2, kappa: 2.0)
        func interiorMaxDiff(_ a: PixelImage, _ b: PixelImage) -> Float {
            var m: Float = 0
            for y in 4..<(n - 4) { for x in 4..<(n - 4) {
                let d = a[x, y] - b[x, y]
                m = max(m, max(abs(d.x), max(abs(d.y), abs(d.z))))
            }}
            return m
        }
        let stackedMax = interiorMaxDiff(result, refClean)
        // No-op baseline: the single sharpest frame still carries full per-pixel noise.
        let baselineMax = interiorMaxDiff(frames[refIdx], refClean)

        XCTAssertLessThan(stackedMax, baselineMax * 0.5, "stacking should clearly beat one frame")
        XCTAssertLessThan(stackedMax, 0.01, "stacked result should converge to the clean scene")

        // PSNR over the interior only: warp edge-clamping cannot reconstruct content that
        // shifted in from outside the frame, so borders are excluded (same region as maxDiff).
        func interiorCrop(_ img: PixelImage) -> PixelImage {
            let m = 4
            var out = PixelImage(width: n - 2 * m, height: n - 2 * m)
            for y in m..<(n - m) { for x in m..<(n - m) { out[x - m, y - m] = img[x, y] } }
            return out
        }
        let psnr = Metrics.psnr(OutputTransform.encodeSRGB8(interiorCrop(result)),
                                OutputTransform.encodeSRGB8(interiorCrop(refClean)))
        XCTAssertGreaterThan(psnr, 40.0)
    }

    func testNoiseReductionRawPathProducesDevelopedImage() {
        let w = 8, h = 8
        let frames = (0..<3).map { _ in
            RawSensorFrame(width: w, height: h, mosaic: [UInt16](repeating: 600, count: w * h),
                           blackLevel: 64, whiteLevel: 1024, cfa: .rggb)
        }
        let result = Pipeline.noiseReduction(frames)
        XCTAssertEqual(result.width, w)
        XCTAssertEqual(result.height, h)
        let p = result[4, 4]
        XCTAssertGreaterThan(p.x, 0)
        XCTAssertTrue(p.x.isFinite && p.y.isFinite && p.z.isFinite)
    }
    func testNoiseReductionEncodedReturnsImageAndBytes() {
        let w = 8, h = 8
        let frames = (0..<3).map { _ in
            RawSensorFrame(width: w, height: h, mosaic: [UInt16](repeating: 600, count: w * h),
                           blackLevel: 64, whiteLevel: 1024, cfa: .rggb)
        }
        let (image, rgba8) = Pipeline.noiseReductionEncoded(frames)
        XCTAssertEqual(image.width, w)
        XCTAssertEqual(rgba8.count, w * h * 4)
    }

    func testReduceImagesDispatchesByMode() {
        // Two aligned frames (no shift) with distinct values; check each mode's reducer is used.
        // searchRange: 0 → identity translation (mag=0 shell only); isolates the reducer, not alignment.
        let a = PixelImage(width: 2, height: 1, pixels: [SIMD3<Float>(0.2, 0.2, 0.2), SIMD3<Float>(0.2, 0.2, 0.2)])
        let b = PixelImage(width: 2, height: 1, pixels: [SIMD3<Float>(0.8, 0.8, 0.8), SIMD3<Float>(0.8, 0.8, 0.8)])
        // smoothMotion → mean = 0.5
        XCTAssertEqual(Pipeline.reduceImages([a, b], mode: .smoothMotion, searchRange: 0)[0, 0].x, 0.5, accuracy: 1e-5)
        // lightTrails → max = 0.8
        XCTAssertEqual(Pipeline.reduceImages([a, b], mode: .lightTrails, searchRange: 0)[0, 0].x, 0.8, accuracy: 1e-5)
        // lowLightBoost → robust mean (0.5) × 2.0 = 1.0
        XCTAssertEqual(Pipeline.reduceImages([a, b], mode: .lowLightBoost, searchRange: 0)[0, 0].x, 1.0, accuracy: 1e-5)

        // noiseReduction vs smoothMotion MUST differ: 6 frames with one outlier — sigma-clip drops it
        // (≈0.5) while the plain mean keeps it (≈2.08). This pins the two dispatch arms apart.
        func flat(_ v: Float) -> PixelImage { PixelImage(width: 1, height: 1, pixels: [SIMD3<Float>(v, v, v)]) }
        let outlierStack = [flat(0.5), flat(0.5), flat(0.5), flat(0.5), flat(0.5), flat(10.0)]
        let nr = Pipeline.reduceImages(outlierStack, mode: .noiseReduction, searchRange: 0)[0, 0].x
        let sm = Pipeline.reduceImages(outlierStack, mode: .smoothMotion, searchRange: 0)[0, 0].x
        XCTAssertEqual(nr, 0.5, accuracy: 1e-3)                   // outlier clipped
        XCTAssertEqual(sm, (0.5 * 5 + 10) / 6, accuracy: 1e-3)   // outlier kept
        XCTAssertGreaterThan(sm - nr, 1.0)                       // the two dispatch arms are distinguishable
    }

    func testReduceImagesDownscalesToWorkingResolution() {
        // workingResolution caps the long edge before align/stack (the on-device speed lever).
        let big = (0..<3).map { _ in PixelImage(width: 64, height: 48, fill: SIMD3<Float>(0.5, 0.5, 0.5)) }
        let out = Pipeline.reduceImages(big, mode: .smoothMotion, searchRange: 2, workingResolution: 20)
        XCTAssertLessThanOrEqual(max(out.width, out.height), 20)
        XCTAssertGreaterThan(out.width, 0)
    }

    func testReduceImagesWithReferenceReturnsTheAnchor() {
        // Two aligned frames (no shift); both dimensions match and the reference has finite sharpness.
        let a = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.3, 0.3, 0.3))
        let b = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.7, 0.7, 0.7))
        let (result, reference) = Pipeline.reduceImagesWithReference([a, b], mode: .noiseReduction)
        XCTAssertEqual(result.width, reference.width)
        XCTAssertEqual(result.height, reference.height)
        XCTAssertEqual(reference.pixels.count, reference.width * reference.height)
        // Fold-in: the reference must contain pixel content from one of the INPUT frames — i.e. the
        // anchor is either frame `a` (0.3) or frame `b` (0.7), NOT the stacked mean. This catches any
        // regression where the result is returned instead of the aligned anchor. (spec 2026-06-11 §3)
        XCTAssertTrue([Float(0.3), Float(0.7)].contains(reference.pixels[0].x),
                      "reference pixel must equal one input frame's fill value, got \(reference.pixels[0].x)")
    }

    func testLowLightBoostReferenceIsGainMatched() {
        // For .lowLightBoost the result is boostedMean(gain: 2.0); the reference must be scaled
        // by the same gain so α trades noise vs. clean at constant brightness, not brightness. (Fix 1)
        let fill: Float = 0.25
        let frames = (0..<3).map { _ in
            PixelImage(width: 4, height: 4, fill: SIMD3<Float>(fill, fill, fill))
        }
        let (_, reference) = Pipeline.reduceImagesWithReference(frames, mode: .lowLightBoost, searchRange: 0)
        let expected = fill * StackReducer.defaultLowLightGain
        XCTAssertEqual(reference.pixels[0].x, expected, accuracy: 1e-5,
                       "reference pixel must equal gain × input fill for .lowLightBoost")
        XCTAssertEqual(reference.pixels[0].y, expected, accuracy: 1e-5)
        XCTAssertEqual(reference.pixels[0].z, expected, accuracy: 1e-5)
    }

    func testReduceRawPathHandlesAllModes() {
        let w = 8, h = 8
        let frames = (0..<3).map { _ in
            RawSensorFrame(width: w, height: h, mosaic: [UInt16](repeating: 600, count: w * h),
                           blackLevel: 64, whiteLevel: 1024, cfa: .rggb)
        }
        // All modes except depthOfField (which routes to FocusStacker, not Pipeline.reduce).
        let modesForPipeline = StackMode.allCases.filter { $0 != .depthOfField }
        for mode in modesForPipeline {
            let result = Pipeline.reduce(frames, mode: mode)
            XCTAssertEqual(result.width, w, "\(mode)")
            XCTAssertEqual(result.height, h, "\(mode)")
            XCTAssertTrue(result[4, 4].x.isFinite, "\(mode)")
        }
    }
}
