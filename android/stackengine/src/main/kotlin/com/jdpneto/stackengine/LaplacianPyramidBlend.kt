package com.jdpneto.stackengine

import kotlin.math.max

/**
 * Burt–Adelson multiband blend: composite N images by N per-pixel weight masks. Each image's
 * Laplacian pyramid is combined level-by-level using the Gaussian pyramid of its (normalized)
 * weight mask, then collapsed — giving seamless focus-boundary blending without halos (design §13.2).
 */
object LaplacianPyramidBlend {

    /**
     * `weights[k]` is a row-major per-pixel weight for frame k (length width*height). Weights need
     * not be pre-normalized; they are normalized per pixel here (non-finite weights count as 0).
     * All frames must share `images[0]`'s dimensions; image pixels are assumed finite.
     */
    fun blend(images: List<PixelImage>, weights: List<FloatArray>, minSize: Int = 4): PixelImage {
        require(images.isNotEmpty() && images.size == weights.size) { "images/weights mismatch" }
        val w = images[0].width; val h = images[0].height; val n = w * h
        val m = images.size
        require(images.all { it.width == w && it.height == h }) { "all frames must match images[0] size" }
        require(weights.all { it.size == n }) { "each weight array must have width*height entries" }

        // Sanitize a weight: non-finite (NaN/Inf) or negative → 0 (no contribution).
        fun clean(x: Float): Float = if (x.isFinite()) max(x, 0f) else 0f

        // Per-pixel normalize the weights (so they sum to 1), then carry each as a PixelImage so the
        // single pyramid machinery (3-channel) handles masks and images alike.
        val maskImgs = ArrayList<PixelImage>(m)
        val norm = FloatArray(n)
        for (i in 0 until n) { var s = 0f; for (k in 0 until m) { s += clean(weights[k][i]) }; norm[i] = s }
        for (k in 0 until m) {
            val px = FloatArray(n * 3)
            for (i in 0 until n) {
                val wgt = if (norm[i] > 0f) clean(weights[k][i]) / norm[i] else 1f / m.toFloat()
                px[i * 3] = wgt; px[i * 3 + 1] = wgt; px[i * 3 + 2] = wgt
            }
            maskImgs.add(PixelImage(w, h, px))
        }

        // Image Laplacian pyramids + mask Gaussian pyramids (same level dimensions for all frames).
        val imgLaps = images.map { ImagePyramid.laplacian(it, minSize = minSize) }
        val maskGaus = maskImgs.map { ImagePyramid.gaussian(it, minSize = minSize) }
        val levels = imgLaps[0].size

        // Blend each level: L_blend = Σ_k maskGauss_k · imgLap_k.
        val blended = ArrayList<PixelImage>(levels)
        for (lvl in 0 until levels) {
            val lw = imgLaps[0][lvl].width; val lh = imgLaps[0][lvl].height
            val px = FloatArray(lw * lh * 3)
            for (k in 0 until m) {
                val lap = imgLaps[k][lvl].pixels; val mask = maskGaus[k][lvl].pixels
                for (j in px.indices) { px[j] += lap[j] * mask[j] }
            }
            blended.add(PixelImage(lw, lh, px))
        }
        return ImagePyramid.collapse(blended)
    }
}
