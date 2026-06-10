import XCTest
import simd
@testable import StackEngineCore

final class PipelineStreamingTests: XCTestCase {
    func testIsLongExposureClassification() {
        XCTAssertTrue(StackMode.smoothMotion.isLongExposure)
        XCTAssertTrue(StackMode.lightTrails.isLongExposure)
        XCTAssertFalse(StackMode.noiseReduction.isLongExposure)
        XCTAssertFalse(StackMode.lowLightBoost.isLongExposure)
    }

    /// N developed frames: static gray background + a single bright pixel that moves across frames.
    /// No global motion, so feeding them as already-aligned isolates accumulation from alignment.
    private func movingSpotFrames(_ n: Int, w: Int = 8, h: Int = 8) -> [PixelImage] {
        (0..<n).map { k in
            var px = [SIMD3<Float>](repeating: SIMD3(0.2, 0.2, 0.2), count: w * h)
            let x = (k * (w - 1)) / max(n - 1, 1)
            px[(h / 2) * w + x] = SIMD3(1, 1, 1)
            return PixelImage(width: w, height: h, pixels: px)
        }
    }

    func testStreamingSmoothMatchesBatchMean() throws {
        let imgs = movingSpotFrames(6)
        let streamed = try Pipeline.streamingReduce(count: imgs.count, mode: .smoothMotion) { imgs[$0] }
        let batch = StackReducer.mean(imgs)
        XCTAssertEqual(streamed.pixels.count, batch.pixels.count)
        for i in 0..<batch.pixels.count {
            XCTAssertEqual(streamed.pixels[i].x, batch.pixels[i].x, accuracy: 1e-5)
            XCTAssertEqual(streamed.pixels[i].y, batch.pixels[i].y, accuracy: 1e-5)
            XCTAssertEqual(streamed.pixels[i].z, batch.pixels[i].z, accuracy: 1e-5)
        }
    }

    func testStreamingTrailsMatchesBatchComposite() throws {
        let imgs = movingSpotFrames(6)
        let streamed = try Pipeline.streamingReduce(count: imgs.count, mode: .lightTrails) { imgs[$0] }
        let base = StackReducer.mean(imgs)
        let streaks = StackReducer.lighten(imgs)
        let mask = MotionComposite.motionMask(imgs, lo: Pipeline.trailsMotionLo,
                                              hi: Pipeline.trailsMotionHi, smoothRadius: 2)
        let batch = MotionComposite.blend(staticBase: base, effect: streaks, mask: mask)
        for i in 0..<batch.pixels.count {
            XCTAssertEqual(streamed.pixels[i].x, batch.pixels[i].x, accuracy: 1e-5)
            XCTAssertEqual(streamed.pixels[i].y, batch.pixels[i].y, accuracy: 1e-5)
            XCTAssertEqual(streamed.pixels[i].z, batch.pixels[i].z, accuracy: 1e-5)
        }
    }

    func testStreamingCancellationThrows() {
        let imgs = movingSpotFrames(10)
        var calls = 0
        XCTAssertThrowsError(
            try Pipeline.streamingReduce(count: imgs.count, mode: .smoothMotion,
                                         shouldCancel: { calls += 1; return calls > 2 }) { imgs[$0] }
        ) { error in
            XCTAssertTrue(error is CancellationError, "expected CancellationError, got \(error)")
        }
    }

    func testStreamingSingleFrameReturnsThatFrame() throws {
        let imgs = movingSpotFrames(1)
        let streamed = try Pipeline.streamingReduce(count: 1, mode: .smoothMotion) { imgs[$0] }
        XCTAssertEqual(streamed.pixels, imgs[0].pixels)
        // lightTrails on a single frame: zero temporal range → mask 0 → returns frame 0 unchanged.
        let trailed = try Pipeline.streamingReduce(count: 1, mode: .lightTrails) { imgs[$0] }
        XCTAssertEqual(trailed.pixels, imgs[0].pixels)
    }

    private func grayRaw(_ value: UInt16, w: Int = 64, h: Int = 64) -> RawSensorFrame {
        RawSensorFrame(width: w, height: h, mosaic: [UInt16](repeating: value, count: w * h),
                       blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
                       wbGains: SIMD3<Float>(1, 1, 1))
    }

    func testReduceStreamingFromRawFramesProducesResult() throws {
        let frames = (0..<5).map { grayRaw(UInt16(300 + $0 * 10)) }
        let result = try Pipeline.reduceStreaming(frames, mode: .smoothMotion, workingResolution: 32)
        XCTAssertGreaterThan(result.width, 0)
        XCTAssertGreaterThan(result.height, 0)
        XCTAssertEqual(result.pixels.count, result.width * result.height)
    }

    func testReduceStreamingHonorsCancellation() {
        let frames = (0..<8).map { grayRaw(UInt16(300 + $0 * 10)) }
        var calls = 0
        XCTAssertThrowsError(
            try Pipeline.reduceStreaming(frames, mode: .lightTrails, workingResolution: 32,
                                         shouldCancel: { calls += 1; return calls > 1 })
        ) { error in
            XCTAssertTrue(error is CancellationError)
        }
    }

    func testReduceStreamingWithReferenceReturnsTheAnchorFrame() throws {
        // Anchor = frame 0's developed image; result and reference must have matching dimensions.
        let frames = (0..<3).map { grayRaw(UInt16(300 + $0 * 10)) }
        let (result, reference) = try Pipeline.reduceStreamingWithReference(frames, mode: .smoothMotion,
                                                                             binnedDevelop: true)
        XCTAssertEqual(result.width, reference.width)
        XCTAssertEqual(result.height, reference.height)
        XCTAssertTrue(reference.pixels[0].x.isFinite)
        // Fold-in: the reference pixel must equal frame 0's developed content — i.e. the anchor is the
        // first frame, not the stacked mean. Develop frame 0 the same way and compare a centre pixel
        // with tolerance for floating-point rounding in the downscale path. (spec 2026-06-11 §3)
        let anchor0 = ColorPipeline.processBinned(frames[0])
        // Both the stored reference and this independently-developed frame go through the same
        // downscaleOne path inside reduceStreamingWithReference (workingResolution = nil → no downscale),
        // so pixels should match exactly. Pick the centre pixel as a stable representative.
        let cx = anchor0.width / 2, cy = anchor0.height / 2
        let idx = cy * anchor0.width + cx
        XCTAssertEqual(reference.pixels[idx].x, anchor0.pixels[idx].x, accuracy: 1e-5,
                       "reference centre pixel must equal frame 0's developed value, not the stack mean")
    }
}
