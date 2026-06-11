package com.jdpneto.stackengine

/**
 * Separable window mean (edge-clamped, normalized by the true in-image sample count at borders).
 * The smoothing primitive behind the guided filter.
 */
internal object BoxFilter {

    /**
     * Compute the box-filter mean of [src] with the given half-window [radius].
     *
     * Two-pass separable implementation (horizontal then vertical). Border pixels are handled
     * by edge-clamping: only in-image samples contribute and the normalization denominator
     * counts only those samples (so border values are the mean of real samples, not darkened).
     */
    fun mean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)    // horizontal pass result
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var n = 0
                for (dx in -r..r) {
                    val xx = x + dx
                    if (xx in 0 until w) { s += src[y * w + xx]; n++ }
                }
                tmp[y * w + x] = s / n.toFloat()
            }
        }
        val out = FloatArray(w * h)    // vertical pass result
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var n = 0
                for (dy in -r..r) {
                    val yy = y + dy
                    if (yy in 0 until h) { s += tmp[yy * w + x]; n++ }
                }
                out[y * w + x] = s / n.toFloat()
            }
        }
        return out
    }
}
