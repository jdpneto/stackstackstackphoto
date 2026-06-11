package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PipelineTests {

    /** Deterministic synthetic scene + per-frame noise + small shifts. */
    private fun makeNoisyShiftedStack(clean: PixelImage, count: Int): List<PixelImage> {
        val w = clean.width; val h = clean.height
        val frames = mutableListOf<PixelImage>()
        for (k in 0 until count) {
            // Deterministic shift pattern and additive noise (no RNG, so the test is stable).
            val sx = (k % 3) - 1            // -1,0,1,-1,0,1...
            val sy = ((k / 3) % 3) - 1
            val img = PixelImage(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val cx = (x - sx).coerceIn(0, w - 1)
                val cy = (y - sy).coerceIn(0, h - 1)
                // Deterministic noise in scene coordinates: scene-coord indexing ensures
                // the noise contribution at each pixel is consistent across aligned frames,
                // so it averages out after stacking.
                val noise = ((k * 37 + cx * 7 + cy * 13) % 11 - 5).toFloat() / 200.0f
                val base = clean[cx, cy]
                img[x, y] = Vec3(base.x + noise, base.y + noise, base.z + noise)
            }
            frames.add(img)
        }
        return frames
    }

    @Test
    fun testNoiseReductionConvergesAndBeatsSingleFrame() {
        val n = 24
        // Deterministic high-frequency texture.
        fun texel(x: Int, y: Int): Float {
            var h = (x * 73856093).toUInt() xor (y * 19349663).toUInt()
            h = h * 2654435761u
            h = h xor (h shr 13)
            h = h * 2246822519u
            h = h xor (h shr 16)
            return (h and 0xFFFFu).toFloat() / 0xFFFF.toFloat()
        }
        val clean = PixelImage(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            val s = 0.15f + 0.75f * texel(x, y)
            clean[x, y] = Vec3(s, s, s)
        }
        val frames = makeNoisyShiftedStack(clean = clean, count = 12)

        fun trueShift(k: Int) = Translation(dx = (k % 3) - 1, dy = ((k / 3) % 3) - 1)
        val refIdx = ReferenceSelection.sharpestIndex(frames)
        val ref = trueShift(refIdx)

        // Alignment must recover each non-reference frame's true integer shift.
        for (k in frames.indices) {
            if (k == refIdx) continue
            val est = Alignment.estimateTranslation(reference = frames[refIdx], moving = frames[k], searchRange = 2)
            val expected = Translation(dx = trueShift(k).dx - ref.dx, dy = trueShift(k).dy - ref.dy)
            assertEquals(expected, est, "alignment should recover frame $k's true shift")
        }

        // Ground truth expressed in the reference frame's coordinates.
        val refClean = PixelImage(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            val cx = (x - ref.dx).coerceIn(0, n - 1)
            val cy = (y - ref.dy).coerceIn(0, n - 1)
            refClean[x, y] = clean[cx, cy]
        }

        val result = Pipeline.noiseReductionImages(frames, searchRange = 2, kappa = 2.0f)
        fun interiorMaxDiff(a: PixelImage, b: PixelImage): Float {
            var m = 0f
            for (y in 4 until (n - 4)) for (x in 4 until (n - 4)) {
                val d = a[x, y] - b[x, y]
                m = max(m, max(abs(d.x), max(abs(d.y), abs(d.z))))
            }
            return m
        }
        val stackedMax = interiorMaxDiff(result, refClean)
        val baselineMax = interiorMaxDiff(frames[refIdx], refClean)

        assertTrue(stackedMax < baselineMax * 0.5f, "stacking should clearly beat one frame")
        assertTrue(stackedMax < 0.01f, "stacked result should converge to the clean scene")

        // PSNR over the interior only.
        fun interiorCrop(img: PixelImage): PixelImage {
            val m = 4
            val out = PixelImage(n - 2 * m, n - 2 * m)
            for (y in m until (n - m)) for (x in m until (n - m)) { out[x - m, y - m] = img[x, y] }
            return out
        }
        val psnr = Metrics.psnr(
            OutputTransform.encodeSRGB8(interiorCrop(result)),
            OutputTransform.encodeSRGB8(interiorCrop(refClean))
        )
        assertTrue(psnr > 40.0, "PSNR should exceed 40 dB, got $psnr")
    }

    @Test
    fun testNoiseReductionRawPathProducesDevelopedImage() {
        val w = 8; val h = 8
        val frames = (0 until 3).map {
            RawSensorFrame.fromIntMosaic(w, h, IntArray(w * h) { 600 }, 64f, 1024f, CFAPattern.RGGB)
        }
        val result = Pipeline.noiseReduction(frames)
        assertEquals(w, result.width)
        assertEquals(h, result.height)
        val p = result[4, 4]
        assertTrue(p.x > 0f)
        assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
    }

    @Test
    fun testNoiseReductionEncodedReturnsImageAndBytes() {
        val w = 8; val h = 8
        val frames = (0 until 3).map {
            RawSensorFrame.fromIntMosaic(w, h, IntArray(w * h) { 600 }, 64f, 1024f, CFAPattern.RGGB)
        }
        val (image, rgba8) = Pipeline.noiseReductionEncoded(frames)
        assertEquals(w, image.width)
        assertEquals(w * h * 4, rgba8.size)
    }

    @Test
    fun testReduceImagesDispatchesByMode() {
        // Two aligned frames (no shift) with distinct values; check each mode's reducer is used.
        // searchRange: 0 → identity translation (mag=0 shell only); isolates the reducer, not alignment.
        val a = PixelImage(2, 1, floatArrayOf(0.2f, 0.2f, 0.2f,  0.2f, 0.2f, 0.2f))
        val b = PixelImage(2, 1, floatArrayOf(0.8f, 0.8f, 0.8f,  0.8f, 0.8f, 0.8f))
        // smoothMotion → mean = 0.5
        assertApprox(0.5f, Pipeline.reduceImages(listOf(a, b), mode = StackMode.SMOOTH_MOTION, searchRange = 0)[0, 0].x, 1e-5f)
        // lightTrails → max = 0.8
        assertApprox(0.8f, Pipeline.reduceImages(listOf(a, b), mode = StackMode.LIGHT_TRAILS, searchRange = 0)[0, 0].x, 1e-5f)
        // lowLightBoost → robust mean (0.5) × 2.0 = 1.0
        assertApprox(1.0f, Pipeline.reduceImages(listOf(a, b), mode = StackMode.LOW_LIGHT_BOOST, searchRange = 0)[0, 0].x, 1e-5f)

        // noiseReduction vs smoothMotion MUST differ: 6 frames with one outlier — sigma-clip drops it
        // (≈0.5) while the plain mean keeps it (≈2.08). This pins the two dispatch arms apart.
        fun flat(v: Float) = PixelImage(1, 1, floatArrayOf(v, v, v))
        val outlierStack = listOf(flat(0.5f), flat(0.5f), flat(0.5f), flat(0.5f), flat(0.5f), flat(10.0f))
        val nr = Pipeline.reduceImages(outlierStack, mode = StackMode.NOISE_REDUCTION, searchRange = 0)[0, 0].x
        val sm = Pipeline.reduceImages(outlierStack, mode = StackMode.SMOOTH_MOTION, searchRange = 0)[0, 0].x
        assertApprox(0.5f, nr, 1e-3f)                   // outlier clipped
        assertApprox((0.5f * 5 + 10f) / 6f, sm, 1e-3f) // outlier kept
        assertTrue(sm - nr > 1.0f)                      // the two dispatch arms are distinguishable
    }

    @Test
    fun testReduceImagesDownscalesToWorkingResolution() {
        // workingResolution caps the long edge before align/stack.
        val big = (0 until 3).map { PixelImage(64, 48, Vec3.repeating(0.5f)) }
        val out = Pipeline.reduceImages(big, mode = StackMode.SMOOTH_MOTION, searchRange = 2, workingResolution = 20)
        assertTrue(maxOf(out.width, out.height) <= 20)
        assertTrue(out.width > 0)
    }

    @Test
    fun testReduceImagesWithReferenceReturnsTheAnchor() {
        // Two aligned frames (no shift); both dimensions match and the reference has finite sharpness.
        val a = PixelImage(8, 8, Vec3.repeating(0.3f))
        val b = PixelImage(8, 8, Vec3.repeating(0.7f))
        val (result, reference) = Pipeline.reduceImagesWithReference(listOf(a, b), mode = StackMode.NOISE_REDUCTION)
        assertEquals(result.width, reference.width)
        assertEquals(result.height, reference.height)
        assertEquals(reference.pixelCount, reference.pixels.size / 3)
        // Fold-in: the reference must contain pixel content from one of the INPUT frames — i.e. the
        // anchor is either frame `a` (0.3) or frame `b` (0.7), NOT the stacked mean. This catches any
        // regression where the result is returned instead of the aligned anchor. (spec 2026-06-11 §3)
        val refPx = reference.pixels[0]
        assertTrue(
            abs(refPx - 0.3f) < 1e-5f || abs(refPx - 0.7f) < 1e-5f,
            "reference pixel must equal one input frame's fill value, got $refPx"
        )
    }

    @Test
    fun testLowLightBoostReferenceIsGainMatched() {
        // For .lowLightBoost the result is boostedMean(gain: 2.0); the reference must be scaled
        // by the same gain so α trades noise vs. clean at constant brightness, not brightness.
        val fill = 0.25f
        val frames = (0 until 3).map { PixelImage(4, 4, Vec3.repeating(fill)) }
        val (_, reference) = Pipeline.reduceImagesWithReference(frames, mode = StackMode.LOW_LIGHT_BOOST, searchRange = 0)
        val expected = fill * StackReducer.defaultLowLightGain
        assertApprox(expected, reference.pixels[0], 1e-5f, "reference pixel must equal gain × input fill for LOW_LIGHT_BOOST")
        assertApprox(expected, reference.pixels[1], 1e-5f)
        assertApprox(expected, reference.pixels[2], 1e-5f)
    }

    @Test
    fun testReduceRawPathHandlesAllModes() {
        val w = 8; val h = 8
        val frames = (0 until 3).map {
            RawSensorFrame.fromIntMosaic(w, h, IntArray(w * h) { 600 }, 64f, 1024f, CFAPattern.RGGB)
        }
        // All modes except depthOfField (which routes to FocusStacker, not Pipeline.reduce).
        val modesForPipeline = StackMode.entries.filter { it != StackMode.DEPTH_OF_FIELD }
        for (mode in modesForPipeline) {
            val result = Pipeline.reduce(frames, mode = mode)
            assertEquals(w, result.width, "$mode")
            assertEquals(h, result.height, "$mode")
            assertTrue(result[4, 4].x.isFinite(), "$mode")
        }
    }
}

private fun assertApprox(expected: Float, actual: Float, tol: Float = 1e-5f, msg: String = "") {
    assertTrue(abs(expected - actual) <= tol,
        if (msg.isEmpty()) "expected $expected got $actual (tol $tol)" else "$msg: expected $expected got $actual")
}

private fun abs(v: Float) = kotlin.math.abs(v)
