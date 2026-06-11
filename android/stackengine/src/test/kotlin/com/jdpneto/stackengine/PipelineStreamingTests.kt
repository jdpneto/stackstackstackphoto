package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineStreamingTests {

    @Test
    fun testIsLongExposureClassification() {
        assertTrue(StackMode.SMOOTH_MOTION.isLongExposure)
        assertTrue(StackMode.LIGHT_TRAILS.isLongExposure)
        assertFalse(StackMode.NOISE_REDUCTION.isLongExposure)
        assertFalse(StackMode.LOW_LIGHT_BOOST.isLongExposure)
    }

    /** N developed frames: static gray background + a single bright pixel that moves across frames. */
    private fun movingSpotFrames(n: Int, w: Int = 8, h: Int = 8): List<PixelImage> {
        return (0 until n).map { k ->
            val px = FloatArray(w * h * 3) { 0.2f }
            val x = (k * (w - 1)) / maxOf(n - 1, 1)
            val i = (h / 2) * w + x
            px[i * 3]     = 1f
            px[i * 3 + 1] = 1f
            px[i * 3 + 2] = 1f
            PixelImage(w, h, px)
        }
    }

    @Test
    fun testStreamingSmoothMatchesBatchMean() {
        val imgs = movingSpotFrames(6)
        val streamed = Pipeline.streamingReduce(count = imgs.size, mode = StackMode.SMOOTH_MOTION) { imgs[it] }
        val batch = StackReducer.mean(imgs)
        assertEquals(batch.pixels.size, streamed.pixels.size)
        for (i in batch.pixels.indices) {
            assertApprox(batch.pixels[i], streamed.pixels[i], 1e-5f)
        }
    }

    @Test
    fun testStreamingTrailsMatchesBatchComposite() {
        val imgs = movingSpotFrames(6)
        val streamed = Pipeline.streamingReduce(count = imgs.size, mode = StackMode.LIGHT_TRAILS) { imgs[it] }
        val base = StackReducer.mean(imgs)
        val streaks = StackReducer.lighten(imgs)
        val mask = MotionComposite.motionMask(imgs, Pipeline.trailsMotionLo, Pipeline.trailsMotionHi, smoothRadius = 2)
        val batch = MotionComposite.blend(staticBase = base, effect = streaks, mask = mask)
        for (i in batch.pixels.indices) {
            assertApprox(batch.pixels[i], streamed.pixels[i], 1e-5f)
        }
    }

    @Test
    fun testStreamingCancellationThrows() {
        val imgs = movingSpotFrames(10)
        var calls = 0
        assertFailsWith<StackCancellationException> {
            Pipeline.streamingReduce(
                count = imgs.size, mode = StackMode.SMOOTH_MOTION,
                shouldCancel = { calls++; calls > 2 }
            ) { imgs[it] }
        }
    }

    @Test
    fun testStreamingSingleFrameReturnsThatFrame() {
        val imgs = movingSpotFrames(1)
        val streamed = Pipeline.streamingReduce(count = 1, mode = StackMode.SMOOTH_MOTION) { imgs[it] }
        assertTrue(imgs[0].pixels.contentEquals(streamed.pixels))
        // lightTrails on a single frame: zero temporal range → mask 0 → returns frame 0 unchanged.
        val trailed = Pipeline.streamingReduce(count = 1, mode = StackMode.LIGHT_TRAILS) { imgs[it] }
        assertTrue(imgs[0].pixels.contentEquals(trailed.pixels))
    }

    // MARK: - reduceImagesStreamingWithReference (non-RAW fallback, Fix 1a / spec 2026-06-11 §3)

    private fun movingSpotFrames3(): List<PixelImage> = movingSpotFrames(3, w = 16, h = 16)

    @Test
    fun testReduceImagesStreamingWithReference_dimsMatchAndReferenceIsFrame0() {
        val imgs = movingSpotFrames3()
        val (result, reference) = Pipeline.reduceImagesStreamingWithReference(imgs, mode = StackMode.SMOOTH_MOTION)
        assertEquals(imgs[0].width, result.width)
        assertEquals(imgs[0].height, result.height)
        // Reference must be pixel-identical to frame 0 (the anchor is returned directly).
        assertEquals(imgs[0].pixels.size, reference.pixels.size)
        for (i in imgs[0].pixels.indices) {
            assertApprox(imgs[0].pixels[i], reference.pixels[i], 1e-6f, "reference pixel $i")
        }
    }

    @Test
    fun testReduceImagesStreamingWithReference_lightTrailsProducesResult() {
        val imgs = movingSpotFrames3()
        val (result, reference) = Pipeline.reduceImagesStreamingWithReference(imgs, mode = StackMode.LIGHT_TRAILS)
        assertEquals(imgs[0].width, result.width)
        assertEquals(imgs[0].height, result.height)
        assertEquals(imgs[0].width, reference.width)
        assertTrue(result.pixels.all { it.isFinite() })
    }

    @Test
    fun testReduceImagesStreamingWithReference_cancellationThrows() {
        val imgs = movingSpotFrames(6, w = 16, h = 16)
        var calls = 0
        assertFailsWith<StackCancellationException> {
            Pipeline.reduceImagesStreamingWithReference(
                imgs, mode = StackMode.SMOOTH_MOTION,
                shouldCancel = { calls++; calls > 1 }
            )
        }
    }

    private fun grayRaw(value: Int, w: Int = 64, h: Int = 64): RawSensorFrame =
        RawSensorFrame.fromIntMosaic(w, h, IntArray(w * h) { value }, 64f, 1024f, CFAPattern.RGGB,
            wbGains = Vec3(1f, 1f, 1f))

    @Test
    fun testReduceStreamingFromRawFramesProducesResult() {
        val frames = (0 until 5).map { grayRaw(300 + it * 10) }
        val result = Pipeline.reduceStreaming(frames, mode = StackMode.SMOOTH_MOTION, workingResolution = 32)
        assertTrue(result.width > 0)
        assertTrue(result.height > 0)
        assertEquals(result.width * result.height, result.pixels.size / 3)
    }

    @Test
    fun testReduceStreamingHonorsCancellation() {
        val frames = (0 until 8).map { grayRaw(300 + it * 10) }
        var calls = 0
        assertFailsWith<StackCancellationException> {
            Pipeline.reduceStreaming(frames, mode = StackMode.LIGHT_TRAILS, workingResolution = 32,
                shouldCancel = { calls++; calls > 1 })
        }
    }

    @Test
    fun testReduceStreamingWithReferenceReturnsTheAnchorFrame() {
        // Anchor = frame 0's developed image; result and reference must have matching dimensions.
        val frames = (0 until 3).map { grayRaw(300 + it * 10) }
        val (result, reference) = Pipeline.reduceStreamingWithReference(
            frames, mode = StackMode.SMOOTH_MOTION, binnedDevelop = true
        )
        assertEquals(result.width, reference.width)
        assertEquals(result.height, reference.height)
        assertTrue(reference.pixels[0].isFinite())
        // Fold-in: the reference pixel must equal frame 0's developed content.
        val anchor0 = ColorPipeline.processBinned(frames[0])
        val cx = anchor0.width / 2; val cy = anchor0.height / 2
        val idx = cy * anchor0.width + cx
        assertApprox(anchor0.pixels[idx * 3], reference.pixels[idx * 3], 1e-5f,
            "reference centre pixel must equal frame 0's developed value, not the stack mean")
    }
}

private fun assertApprox(expected: Float, actual: Float, tol: Float = 1e-5f, msg: String = "") {
    assertTrue(abs(expected - actual) <= tol,
        if (msg.isEmpty()) "expected $expected got $actual (tol $tol)" else "$msg: expected $expected got $actual")
}
