package com.jdpneto.stackengine

import kotlin.math.abs

/**
 * Per-pixel focus measure: summed modified-Laplacian energy over a (2·radius+1)² window of luma
 * (design §13.2). Higher = more in-focus. The basis for the focus-stacking selection map.
 */
object SharpnessMap {

    fun compute(img: PixelImage, radius: Int = 2): FloatArray =
        compute(Luma.luminance(img), img.width, img.height, radius)

    internal fun compute(l: FloatArray, w: Int, h: Int, radius: Int = 2): FloatArray {
        fun at(x: Int, y: Int): Float =
            l[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

        // Modified Laplacian per pixel: |2L − L(x−1) − L(x+1)| + |2L − L(y−1) − L(y+1)|.
        val ml = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val lx = abs(2f * at(x, y) - at(x - 1, y) - at(x + 1, y))
                val ly = abs(2f * at(x, y) - at(x, y - 1) - at(x, y + 1))
                ml[y * w + x] = lx + ly
            }
        }
        // Sum over the window (edge-clamped).
        fun mlAt(x: Int, y: Int): Float =
            ml[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (dy in -radius..radius) { for (dx in -radius..radius) { s += mlAt(x + dx, y + dy) } }
                out[y * w + x] = s
            }
        }
        return out
    }
}
