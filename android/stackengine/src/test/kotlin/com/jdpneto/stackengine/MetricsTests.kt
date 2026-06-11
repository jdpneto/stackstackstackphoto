package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTests {

    @Test
    fun testPSNRIdenticalIsInfinite() {
        val a: ByteArray = byteArrayOf(10, 20, 30, 255.toByte())
        assertEquals(Double.POSITIVE_INFINITY, Metrics.psnr(a, a))
    }

    @Test
    fun testPSNRDecreasesWithError() {
        val a: ByteArray = byteArrayOf(100.toByte(), 100.toByte(), 100.toByte(), 100.toByte())
        val b: ByteArray = byteArrayOf(110.toByte(), 90.toByte(), 105.toByte(), 100.toByte())
        val p = Metrics.psnr(a, b)
        assertTrue(p > 20.0)
        assertTrue(p < 60.0)
    }

    @Test
    fun testMaxAbsDiffZeroForIdentical() {
        val a = PixelImage(2, 1, FloatArray(6).also {
            it[0] = 0.1f; it[1] = 0.2f; it[2] = 0.3f
            it[3] = 0.4f; it[4] = 0.5f; it[5] = 0.6f
        })
        assertEquals(0f, Metrics.maxAbsDiff(a, a), absoluteTolerance = 1e-7f)
    }

    @Test
    fun testMaxAbsDiffFindsLargestChannelDelta() {
        val a = PixelImage(1, 1, floatArrayOf(0.1f, 0.2f, 0.3f))
        val b = PixelImage(1, 1, floatArrayOf(0.1f, 0.7f, 0.25f))
        assertEquals(0.5f, Metrics.maxAbsDiff(a, b), absoluteTolerance = 1e-6f) // |0.2-0.7| is the max
    }

    // MARK: - SSIM tests

    @Test
    fun testSSIMIdentityIsOneAndNoiseLowers() {
        val img = gradientImage(64, 48)
        assertEquals(1.0, Metrics.ssim(img, img), absoluteTolerance = 1e-9)
        val noisy = img.copy()
        var seed: UInt = 1u
        for (i in 0 until noisy.pixelCount) {
            // seeded LCG — deterministic noise (same as Swift: seed = seed &* 1664525 &+ 1013904223)
            seed = seed * 1664525u + 1013904223u
            val n = (((seed shr 16) and 0x7FFFu).toFloat() / 32767f - 0.5f) * 0.05f
            val base = i * 3
            noisy.pixels[base]     += n
            noisy.pixels[base + 1] += n
            noisy.pixels[base + 2] += n
        }
        val s = Metrics.ssim(img, noisy)
        assertTrue(s < 0.9999)
        assertTrue(s > 0.85) // "mild noise must not crater SSIM"
    }

    // MARK: - ΔE tests

    @Test
    fun testDeltaECatchesAHueShiftPSNRBarelySees() {
        val img = gradientImage(64, 48)
        val shifted = img.copy()
        for (i in 0 until shifted.pixelCount) {
            shifted.pixels[i * 3] *= 1.06f   // 6% red gain
        }
        assertEquals(0.0, Metrics.meanDeltaE(img, img), absoluteTolerance = 1e-9)
        assertTrue(Metrics.meanDeltaE(img, shifted) > 0.8) // "a visible color cast must register"
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Build a deterministic textured PixelImage (linear-light [0,1] linear ramp in both axes).
 * Used by MetricsTests so that SSIM has enough structure to produce meaningful window statistics.
 */
private fun gradientImage(width: Int, height: Int): PixelImage {
    val img = PixelImage(width, height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val fx = x.toFloat() / (width - 1).toFloat()
            val fy = y.toFloat() / (height - 1).toFloat()
            // Distinct channel ramps so colour metrics have signal.
            val base = (y * width + x) * 3
            img.pixels[base]     = fx
            img.pixels[base + 1] = fy
            img.pixels[base + 2] = (fx + fy) * 0.5f
        }
    }
    return img
}
