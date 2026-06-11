package com.jdpneto.stackengine

import kotlin.math.abs

/** Rec.709 luminance utilities. */
object Luma {

    /** Rec.709 luminance of a single linear-RGB pixel. */
    fun rec709(p: Vec3): Float = 0.2126f * p.x + 0.7152f * p.y + 0.0722f * p.z

    /** Rec.709 luminance of each pixel. */
    fun luminance(img: PixelImage): FloatArray {
        val n = img.pixelCount
        val out = FloatArray(n)
        for (i in 0 until n) {
            val base = i * 3
            out[i] = 0.2126f * img.pixels[base] + 0.7152f * img.pixels[base + 1] + 0.0722f * img.pixels[base + 2]
        }
        return out
    }

    /** Sharpness = sum of |Laplacian| over the luminance image (higher = sharper). */
    fun sharpness(img: PixelImage): Float =
        sharpness(luminance(img), img.width, img.height)

    /**
     * Sharpness over a precomputed luminance buffer, so callers can reuse the buffer.
     * Uses edge-clamped border handling.
     */
    fun sharpness(l: FloatArray, w: Int, h: Int): Float {
        fun at(x: Int, y: Int): Float {
            val cx = x.coerceIn(0, w - 1)
            val cy = y.coerceIn(0, h - 1)
            return l[cy * w + cx]
        }
        var s = 0f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val lap = at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1) - 4f * at(x, y)
                s += abs(lap)
            }
        }
        return s
    }
}
