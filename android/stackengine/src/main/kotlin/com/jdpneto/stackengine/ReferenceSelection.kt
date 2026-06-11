package com.jdpneto.stackengine

object ReferenceSelection {

    /** Index of the sharpest frame — the geometric anchor for alignment. */
    fun sharpestIndex(imgs: List<PixelImage>): Int {
        require(imgs.isNotEmpty())
        val lumas = imgs.map { Luma.luminance(it) }
        return sharpestIndex(lumas = lumas, width = imgs[0].width, height = imgs[0].height)
    }

    /** Sharpest frame given precomputed luminance buffers (avoids recomputing luminance). */
    internal fun sharpestIndex(lumas: List<FloatArray>, width: Int, height: Int): Int {
        require(lumas.isNotEmpty())
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for ((i, l) in lumas.withIndex()) {
            val s = Luma.sharpness(l, width, height)
            if (s > bestScore) { bestScore = s; best = i }
        }
        return best
    }
}
