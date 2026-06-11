package com.jdpneto.stackengine

import kotlin.math.pow

/**
 * sRGB ↔ linear-light encoding for 8-bit output and golden-corpus loading.
 *
 * The encode/decode pair is used:
 *   - At the output stage to write display-ready JPEG/PNG bytes.
 *   - In tests to load golden PNG buffers back into linear light for metric comparison.
 */
object OutputTransform {

    /**
     * Convert a single linear-light channel [c] to sRGB gamma-encoded float in [0,1].
     *
     * NaN-safe: NaN → 0. +Inf clamps to 1.0, -Inf clamps to 0.0.
     */
    private fun linearToSRGB(c: Float): Float {
        // NaN-safe: min/max clamp ±Inf correctly (+Inf→1, -Inf→0) but do NOT strip NaN, which
        // would reach a byte-cast trap. NaN needs special-casing → 0.
        val x = if (c.isNaN()) 0f else c.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) {
            x * 12.92f
        } else {
            (1.055 * x.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
        }
    }

    /**
     * Convert an sRGB-encoded 8-bit value [b] to linear-light float.
     *
     * Used to decode golden PNG buffers back into linear light for metric comparisons.
     * Mirrors `OutputTransform.decodeSRGB8` in Swift.
     */
    fun decodeSRGB8(b: Byte): Float {
        val c = (b.toInt() and 0xFF) / 255f
        return if (c <= 0.04045f) {
            c / 12.92f
        } else {
            ((c.toDouble() + 0.055) / 1.055).pow(2.4).toFloat()
        }
    }

    /**
     * Decode interleaved sRGB RGBA8 bytes back into a linear image (inverse of [encodeSRGB8]).
     *
     * The alpha byte (i*4+3) is ignored — developed results are always opaque.
     */
    fun decodeSRGB8(rgba8: ByteArray, width: Int, height: Int): PixelImage {
        require(rgba8.size == width * height * 4) { "rgba8 length mismatch" }
        val out = PixelImage(width, height)
        val n = width * height
        for (i in 0 until n) {
            val base = i * 4
            val px = i * 3
            out.pixels[px]     = decodeSRGB8(rgba8[base])
            out.pixels[px + 1] = decodeSRGB8(rgba8[base + 1])
            out.pixels[px + 2] = decodeSRGB8(rgba8[base + 2])
        }
        return out
    }

    /**
     * Encode a linear image to interleaved sRGB RGBA8 bytes (alpha = 255).
     *
     * Each pixel occupies 4 bytes: R, G, B, A=255.
     */
    fun encodeSRGB8(img: PixelImage): ByteArray {
        val n = img.pixelCount
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            val px = i * 3
            val ob = i * 4
            out[ob]     = (linearToSRGB(img.pixels[px])     * 255f + 0.5f).toInt().toByte()
            out[ob + 1] = (linearToSRGB(img.pixels[px + 1]) * 255f + 0.5f).toInt().toByte()
            out[ob + 2] = (linearToSRGB(img.pixels[px + 2]) * 255f + 0.5f).toInt().toByte()
            out[ob + 3] = 255.toByte()
        }
        return out
    }
}
