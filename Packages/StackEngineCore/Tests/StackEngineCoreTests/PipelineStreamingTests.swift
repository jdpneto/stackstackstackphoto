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
        for i in 0..<imgs[0].pixels.count {
            XCTAssertEqual(streamed.pixels[i].x, imgs[0].pixels[i].x, accuracy: 1e-6)
        }
    }
}
