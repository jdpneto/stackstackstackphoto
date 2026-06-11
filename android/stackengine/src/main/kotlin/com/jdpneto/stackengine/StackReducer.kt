package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.sqrt

object StackReducer {

    /**
     * Default exposure gain for the low-light-boost look (design §13.3). Named here rather than
     * a magic literal at the call site; a tunable/adaptive gain is future Pro-controls work.
     */
    const val defaultLowLightGain: Float = 2.0f

    /**
     * Per-pixel, per-channel sigma-clipped mean across aligned frames, optionally scaled by [scale].
     *
     * IMPORTANT: a single outlier's maximum z-score is bounded by sqrt(N-1) for N samples,
     * so with the default [kappa] = 2.0 an outlier is only rejectable when N >= 6. For
     * smaller bursts (N <= 5) at kappa 2.0 this returns the plain mean — no clipping is
     * mathematically possible. Use a smaller [kappa] (e.g. 1.5) to clip on small bursts.
     */
    fun sigmaClippedMean(
        imgs: List<PixelImage>,
        kappa: Float = 2.0f,
        iterations: Int = 3,
        scale: Float = 1f
    ): PixelImage {
        val (w, h) = validatedDimensions(imgs)
        val n = imgs.size
        val out = PixelImage(w, h)
        // One reusable scratch buffer for the entire image, refilled per pixel/channel —
        // avoids a heap allocation in the innermost loop (tens of millions on a large frame).
        val kept = FloatArray(n)
        for (i in 0 until (w * h)) {
            for (ch in 0 until 3) {
                for (k in 0 until n) { kept[k] = imgs[k].pixels[i * 3 + ch] }
                var count = n
                var iter = 0
                while (iter < iterations && count > 2) {
                    var sum = 0f
                    for (k in 0 until count) { sum += kept[k] }
                    val mean = sum / count.toFloat()
                    var varSum = 0f
                    for (k in 0 until count) { val d = kept[k] - mean; varSum += d * d }
                    val sd = sqrt(varSum / count.toFloat())
                    if (sd == 0f) break
                    val threshold = kappa * sd
                    // Compact survivors to the front of `kept` in place (no allocation).
                    var survivors = 0
                    for (k in 0 until count) {
                        if (abs(kept[k] - mean) <= threshold) { kept[survivors] = kept[k]; survivors++ }
                    }
                    if (survivors < 3) break        // too few survivors — keep the current set
                    if (survivors == count) break   // converged — nothing rejected
                    count = survivors
                    iter++
                }
                var sum = 0f
                for (k in 0 until count) { sum += kept[k] }
                out.pixels[i * 3 + ch] = (sum / count.toFloat()) * scale
            }
        }
        return out
    }

    /** Plain per-pixel temporal mean — keeps scene motion (smooth-motion look). */
    fun mean(imgs: List<PixelImage>): PixelImage {
        val (w, h) = validatedDimensions(imgs)
        val out = PixelImage(w, h)
        val inv = 1f / imgs.size.toFloat()
        for (i in 0 until (w * h)) {
            var ax = 0f; var ay = 0f; var az = 0f
            for (im in imgs) {
                ax += im.pixels[i * 3]
                ay += im.pixels[i * 3 + 1]
                az += im.pixels[i * 3 + 2]
            }
            out.pixels[i * 3]     = ax * inv
            out.pixels[i * 3 + 1] = ay * inv
            out.pixels[i * 3 + 2] = az * inv
        }
        return out
    }

    /** Per-channel lighten (max) across frames — light streaks accumulate (light-trails look). */
    fun lighten(imgs: List<PixelImage>): PixelImage {
        val (w, h) = validatedDimensions(imgs)
        val out = PixelImage(w, h)
        for (i in 0 until (w * h)) {
            var mx = imgs[0].pixels[i * 3]
            var my = imgs[0].pixels[i * 3 + 1]
            var mz = imgs[0].pixels[i * 3 + 2]
            for (k in 1 until imgs.size) {
                val p = imgs[k].pixels
                if (p[i * 3]     > mx) mx = p[i * 3]
                if (p[i * 3 + 1] > my) my = p[i * 3 + 1]
                if (p[i * 3 + 2] > mz) mz = p[i * 3 + 2]
            }
            out.pixels[i * 3]     = mx
            out.pixels[i * 3 + 1] = my
            out.pixels[i * 3 + 2] = mz
        }
        return out
    }

    /**
     * Robust (sigma-clipped) mean with an exposure gain — low-light-boost look. Output may
     * exceed 1.0 (the output transform clamps). gain > 1 brightens; gain == 1 == noise reduction.
     * Folds the gain into the reducer's final divide (single pass over the image).
     */
    fun boostedMean(imgs: List<PixelImage>, gain: Float): PixelImage {
        require(gain > 0f) { "gain must be > 0" }
        return sigmaClippedMean(imgs, scale = gain)
    }

    /** Shared input validation for all reducers: non-empty and uniform size. */
    private fun validatedDimensions(imgs: List<PixelImage>): Pair<Int, Int> {
        require(imgs.isNotEmpty())
        val w = imgs[0].width; val h = imgs[0].height
        require(imgs.all { it.width == w && it.height == h }) { "all images must be the same size" }
        return Pair(w, h)
    }
}
