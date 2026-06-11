package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StackReducerTests {

    private fun flat(v: Float): PixelImage =
        PixelImage(1, 1, floatArrayOf(v, v, v))

    @Test
    fun testPlainMeanWhenNoOutliers() {
        val imgs = listOf(flat(0.2f), flat(0.4f), flat(0.6f), flat(0.8f))
        val out = StackReducer.sigmaClippedMean(imgs, kappa = 1.5f)
        assertEquals(0.5f, out[0, 0].x, absoluteTolerance = 1e-5f)
    }

    @Test
    fun testRejectsOutlier() {
        // four 0.5s and one 10.0 -> clipped -> ~0.5
        val imgs = listOf(flat(0.5f), flat(0.5f), flat(0.5f), flat(0.5f), flat(10.0f))
        val out = StackReducer.sigmaClippedMean(imgs, kappa = 1.5f)
        assertEquals(0.5f, out[0, 0].x, absoluteTolerance = 1e-4f)
    }

    @Test
    fun testTwoFramesReturnPlainMean() {
        val out = StackReducer.sigmaClippedMean(listOf(flat(0.2f), flat(0.8f)), kappa = 1.5f)
        assertEquals(0.5f, out[0, 0].x, absoluteTolerance = 1e-5f)
    }

    @Test
    fun testRejectsOutlierWithDefaultKappa() {
        // At N=6, kappa=2.0 CAN reject a single extreme outlier.
        val imgs = listOf(flat(0.5f), flat(0.5f), flat(0.5f), flat(0.5f), flat(0.5f), flat(10.0f))
        val out = StackReducer.sigmaClippedMean(imgs) // default kappa 2.0
        assertEquals(0.5f, out[0, 0].x, absoluteTolerance = 1e-4f)
    }

    @Test
    fun testSmallBurstWithDefaultKappaIsPlainMean() {
        // Documents the known limitation: at N=5, kappa=2.0 cannot reject any single
        // outlier (max z-score is exactly 2.0, and the filter keeps boundary values),
        // so the result is the plain mean.
        val imgs = listOf(flat(0.0f), flat(0.0f), flat(0.0f), flat(0.0f), flat(5.0f))
        val out = StackReducer.sigmaClippedMean(imgs) // default kappa 2.0
        assertEquals(1.0f, out[0, 0].x, absoluteTolerance = 1e-4f) // (0+0+0+0+5)/5 = 1.0, no clipping
    }

    @Test
    fun testMeanIsPlainAverage() {
        // Plain mean keeps every sample (no clipping) — even an extreme one.
        val out = StackReducer.mean(listOf(flat(0.0f), flat(0.4f), flat(0.8f), flat(10.0f)))
        assertEquals((0.0f + 0.4f + 0.8f + 10.0f) / 4f, out[0, 0].x, absoluteTolerance = 1e-5f)
    }

    @Test
    fun testLightenTakesPerChannelMax() {
        val a = PixelImage(1, 1, floatArrayOf(0.2f, 0.8f, 0.1f))
        val b = PixelImage(1, 1, floatArrayOf(0.7f, 0.3f, 0.5f))
        val out = StackReducer.lighten(listOf(a, b))
        assertEquals(0.7f, out[0, 0].x, absoluteTolerance = 1e-6f)
        assertEquals(0.8f, out[0, 0].y, absoluteTolerance = 1e-6f)
        assertEquals(0.5f, out[0, 0].z, absoluteTolerance = 1e-6f)
    }

    @Test
    fun testBoostedMeanAppliesGain() {
        // Robust mean of five 0.3s is 0.3; gain 2.0 → 0.6 (linear; output clamps later).
        val imgs = listOf(flat(0.3f), flat(0.3f), flat(0.3f), flat(0.3f), flat(0.3f))
        assertEquals(0.6f, StackReducer.boostedMean(imgs, gain = 2.0f)[0, 0].x, absoluteTolerance = 1e-5f)
        // gain 1.0 is identical to the robust mean.
        assertEquals(0.3f, StackReducer.boostedMean(imgs, gain = 1.0f)[0, 0].x, absoluteTolerance = 1e-5f)
    }
}

private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "expected $expected but got $actual (tolerance $absoluteTolerance)"
    )
}
