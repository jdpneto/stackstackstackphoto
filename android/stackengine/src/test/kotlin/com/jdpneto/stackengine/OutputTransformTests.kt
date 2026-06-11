package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutputTransformTests {

    @Test
    fun testSRGBRoundTripWithinQuantization() {
        // linear → sRGB8 → linear should return ~the original (8-bit quantization tolerance).
        val img = PixelImage(3, 1, FloatArray(9).also { a ->
            // pixel 0
            a[0] = 0.0f; a[1] = 0.25f; a[2] = 0.5f
            // pixel 1
            a[3] = 0.5f; a[4] = 0.75f; a[5] = 1.0f
            // pixel 2
            a[6] = 0.1f; a[7] = 0.2f;  a[8] = 0.9f
        })
        val back = OutputTransform.decodeSRGB8(OutputTransform.encodeSRGB8(img), 3, 1)
        assertEquals(3, back.pixelCount)
        for (i in 0 until 3) {
            for (ch in 0 until 3) {
                assertEquals(
                    img.pixels[i * 3 + ch],
                    back.pixels[i * 3 + ch],
                    absoluteTolerance = 0.006f,
                    message = "pixel $i ch $ch"
                )
            }
        }
    }

    @Test
    fun testSRGBEncodingKnownValues() {
        val img = PixelImage(3, 1, FloatArray(9).also { a ->
            // pixel 0: black
            a[0] = 0f; a[1] = 0f; a[2] = 0f
            // pixel 1: white
            a[3] = 1f; a[4] = 1f; a[5] = 1f
            // pixel 2: linear 0.5
            a[6] = 0.5f; a[7] = 0.5f; a[8] = 0.5f
        })
        val bytes = OutputTransform.encodeSRGB8(img)  // RGBA, 4 bytes/pixel
        assertEquals(3 * 4, bytes.size)
        // black -> 0, alpha 255
        assertEquals(0, bytes[0].toInt() and 0xFF)
        assertEquals(255, bytes[3].toInt() and 0xFF)
        // white -> 255
        assertEquals(255, bytes[4].toInt() and 0xFF)
        // linear 0.5 -> sRGB ~0.7353 -> ~188
        val val8 = bytes[8].toInt() and 0xFF
        assertTrue(kotlin.math.abs(val8 - 188) <= 1, "expected ~188, got $val8")
    }

    @Test
    fun testEncodeIsNaNAndInfSafe() {
        // A non-finite pixel (e.g. from degenerate upstream math) must not trap; it
        // encodes to 0 (NaN) / 255 (+Inf) / 0 (-Inf) instead of crashing the export.
        val img = PixelImage(3, 1, FloatArray(9).also { a ->
            a[0] = Float.NaN;              a[1] = Float.NaN;              a[2] = Float.NaN
            a[3] = Float.POSITIVE_INFINITY; a[4] = Float.POSITIVE_INFINITY; a[5] = Float.POSITIVE_INFINITY
            a[6] = Float.NEGATIVE_INFINITY; a[7] = Float.NEGATIVE_INFINITY; a[8] = Float.NEGATIVE_INFINITY
        })
        val bytes = OutputTransform.encodeSRGB8(img)
        assertEquals(0,   bytes[0].toInt() and 0xFF)   // NaN → 0
        assertEquals(255, bytes[4].toInt() and 0xFF)   // +Inf → clamped to 1 → 255
        assertEquals(0,   bytes[8].toInt() and 0xFF)   // -Inf → clamped to 0 → 0
    }
}
