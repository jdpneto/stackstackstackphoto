package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class AffineAlignerTests {

    /**
     * A deterministic, SMOOTH, non-periodic fixture: an asymmetric product ramp (unique global
     * structure → unimodal SSD, so translation init is reliable and scale is observable) plus a
     * gentle low-frequency undulation. Low pixel-frequency keeps bilinear resampling accurate, so
     * the warp→align→compare round-trip isn't confounded by interpolation aliasing.
     */
    private fun texture(w: Int, h: Int): PixelImage {
        val img = PixelImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = x.toFloat() / (w - 1).toFloat()
            val fy = y.toFloat() / (h - 1).toFloat()
            val v = 0.15f + 0.5f * fx * fy + 0.2f * sin(2.5f * fx) * sin(2.0f * fy)
            img[x, y] = Vec3(v, v, v)
        }
        return img
    }

    @Test
    fun testWarpByIdentityReturnsSameImage() {
        val img = texture(24, 24)
        val out = AffineAligner.warp(img, by = Transform2D.identity)
        assertTrue(Metrics.maxAbsDiff(out, img) < 1e-5f)
    }

    @Test
    fun testWarpByPureTranslationShiftsContent() {
        val img = texture(24, 24)
        // similarity(scale 1, rot 0, tx 2, ty 0): out[x,y] samples img at (x+2, y) → content shifts left by 2.
        val out = AffineAligner.warp(img, by = Transform2D.similarity(scale = 1f, rotation = 0f, tx = 2f, ty = 0f))
        var maxd = 0f
        for (y in 4 until 20) for (x in 4 until 20) {
            maxd = max(maxd, abs(out[x, y].x - img[x + 2, y].x))
        }
        assertTrue(maxd < 1e-4f, "maxd=$maxd should be < 1e-4")
    }

    @Test
    fun testEstimateRecoversSimilarity() {
        val ref = texture(48, 48)
        // Focus-breathing: moving is ref scaled up ~4% + rotated ~1.1° + shifted.
        val known = Transform2D.similarity(scale = 1.04f, rotation = 0.02f, tx = 2f, ty = -1f)
        val mov = AffineAligner.warp(ref, by = known)
        val est = AffineAligner.estimate(reference = ref, moving = mov)
        val aligned = AffineAligner.warp(mov, by = est)
        var maxd = 0f
        for (y in 10 until 38) for (x in 10 until 38) {
            maxd = max(maxd, abs(aligned[x, y].x - ref[x, y].x))
        }
        assertTrue(maxd < 0.05f, "aligned interior should match the reference: maxd=$maxd")
    }

    @Test
    fun testEstimateOnIdenticalFramesIsNearIdentity() {
        val ref = texture(32, 32)
        val est = AffineAligner.estimate(reference = ref, moving = ref)
        val aligned = AffineAligner.warp(ref, by = est)
        assertTrue(Metrics.maxAbsDiff(aligned, ref) < 1e-3f)
    }

    @Test
    fun testAlignReducesPureScaleBreathing() {
        val ref = texture(48, 48)
        // Pure focus breathing: a 3% magnification, no shift/rotation.
        val breathing = Transform2D.similarity(scale = 1.03f, rotation = 0f, tx = 0f, ty = 0f)
        val mov = AffineAligner.warp(ref, by = breathing)
        val beforeDiff = interiorMaxDiff(mov, ref)          // misaligned
        val aligned = AffineAligner.align(reference = ref, moving = mov)
        val afterDiff = interiorMaxDiff(aligned, ref)       // aligned
        assertTrue(afterDiff < beforeDiff * 0.5f, "alignment must materially reduce the residual")
        assertTrue(afterDiff < 0.05f)
    }

    private fun interiorMaxDiff(a: PixelImage, b: PixelImage): Float {
        var m = 0f
        for (y in 10 until 38) for (x in 10 until 38) { m = max(m, abs(a[x, y].x - b[x, y].x)) }
        return m
    }

    @Test
    fun testEstimateKeepsScaleSaneAndFinite() {
        // A 3× magnification's true inverse (~0.33) is below the clamp floor; the search must stop
        // at the floor rather than walk to a degenerate/near-zero scale, and stay finite.
        val ref = texture(40, 40)
        val mov = AffineAligner.warp(ref, by = Transform2D.similarity(scale = 3.0f, rotation = 0f, tx = 0f, ty = 0f))
        val est = AffineAligner.estimate(reference = ref, moving = mov)
        val scale = sqrt(est.a * est.a + est.c * est.c)
        assertTrue(est.a.isFinite() && est.c.isFinite() && est.tx.isFinite() && est.ty.isFinite())
        assertTrue(scale >= 0.5f - 1e-3f, "scale floor: $scale")   // clamp floor held
        assertTrue(scale <= 2.0f + 1e-3f, "scale ceiling: $scale") // clamp ceiling held
    }

    @Test
    fun testEstimateRecoversSimilarityOnDetailedImage() {
        // A realistic detailed image: a low-frequency ramp plus high-frequency texture.
        val w = 64; val h = 64
        val ref = PixelImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = x.toFloat() / (w - 1).toFloat()
            val fy = y.toFloat() / (h - 1).toFloat()
            val v = 0.15f + 0.4f * (fx + fy) / 2f + 0.22f * sin(0.7f * x.toFloat()) * sin(0.6f * y.toFloat())
            ref[x, y] = Vec3(v, v, v)
        }
        val known = Transform2D.similarity(scale = 1.03f, rotation = 0.015f, tx = 2f, ty = -1f)
        val mov = AffineAligner.warp(ref, by = known)
        val aligned = AffineAligner.align(reference = ref, moving = mov)
        var maxd = 0f
        for (y in 16 until 48) for (x in 16 until 48) { maxd = max(maxd, abs(aligned[x, y].x - ref[x, y].x)) }
        assertTrue(maxd < 0.08f, "coarse-to-fine alignment registers a detailed frame: maxd=$maxd")
    }
}
