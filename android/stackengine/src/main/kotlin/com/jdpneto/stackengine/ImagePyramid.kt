package com.jdpneto.stackengine

/**
 * Gaussian/Laplacian image pyramids for multiband blending (Burt–Adelson). [reduce]/[expand] use a
 * separable 5-tap binomial kernel, renormalized at borders so edges aren't darkened. Reconstruction
 * is exact for constant images and a smooth approximation otherwise — sufficient for blending.
 */
object ImagePyramid {

    private val kernel: FloatArray = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)

    /**
     * Blur + downsample by 2 → dimensions ceil(w/2) × ceil(h/2).
     *
     * Uses a 5-tap separable binomial kernel applied to the source at 2× spacing.
     * Out-of-bounds source pixels are excluded and the weight sum is renormalized so
     * border output pixels are not darkened.
     */
    fun reduce(img: PixelImage): PixelImage {
        val w = img.width
        val h = img.height
        val ow = (w + 1) / 2
        val oh = (h + 1) / 2
        val out = PixelImage(ow, oh)
        // Hot loop (every downscale AND every estimate's pyramid build, 25 taps per output
        // pixel): read the flat pixel array directly into the 3 scalar accumulators — the old
        // `img[sx, sy]` allocated a Vec3 PER TAP under ART (no escape analysis on device).
        // Same float ops in the same order — bit-identical (identity test).
        val src = img.pixels
        val dst = out.pixels
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                var ax = 0f; var ay = 0f; var az = 0f; var wsum = 0f
                for (dy in 0 until 5) {
                    val sy = 2 * oy + dy - 2
                    if (sy < 0 || sy >= h) continue
                    val ky = kernel[dy]
                    val rowBase = sy * w
                    for (dx in 0 until 5) {
                        val sx = 2 * ox + dx - 2
                        if (sx < 0 || sx >= w) continue
                        val wgt = kernel[dx] * ky
                        val base = (rowBase + sx) * 3
                        ax += src[base] * wgt; ay += src[base + 1] * wgt; az += src[base + 2] * wgt
                        wsum += wgt
                    }
                }
                // renormalize at borders
                if (wsum > 0f) {
                    val obase = (oy * ow + ox) * 3
                    dst[obase] = ax / wsum; dst[obase + 1] = ay / wsum; dst[obase + 2] = az / wsum
                }
                // else leaves zero (wsum==0 can't happen for non-empty images, guard is defensive)
            }
        }
        return out
    }

    /**
     * Upsample to an exact target size with the binomial kernel (border-renormalized interpolation).
     *
     * Inverse of [reduce]: interleaves source pixels on even output positions and fills odd
     * positions by interpolation. The 5-tap kernel picks contributing source pixels whose
     * output-domain positions align with each target pixel.
     */
    fun expand(img: PixelImage, toWidth: Int, toHeight: Int): PixelImage {
        val w = img.width
        val h = img.height
        val out = PixelImage(toWidth, toHeight)
        // Same flat-array treatment as [reduce]: scalar accumulators, no Vec3 per tap
        // (expand runs per pixel in every Laplacian build/collapse of the DoF blend).
        val src = img.pixels
        val dst = out.pixels
        for (ty in 0 until toHeight) {
            for (tx in 0 until toWidth) {
                var ax = 0f; var ay = 0f; var az = 0f; var wsum = 0f
                for (dy in 0 until 5) {
                    val syNum = ty + dy - 2
                    if (syNum % 2 != 0) continue
                    val sy = syNum / 2
                    if (sy < 0 || sy >= h) continue
                    val ky = kernel[dy]
                    val rowBase = sy * w
                    for (dx in 0 until 5) {
                        val sxNum = tx + dx - 2
                        if (sxNum % 2 != 0) continue
                        val sx = sxNum / 2
                        if (sx < 0 || sx >= w) continue
                        val wgt = kernel[dx] * ky
                        val base = (rowBase + sx) * 3
                        ax += src[base] * wgt; ay += src[base + 1] * wgt; az += src[base + 2] * wgt
                        wsum += wgt
                    }
                }
                if (wsum > 0f) {
                    val obase = (ty * toWidth + tx) * 3
                    dst[obase] = ax / wsum; dst[obase + 1] = ay / wsum; dst[obase + 2] = az / wsum
                }
            }
        }
        return out
    }

    /**
     * Gaussian pyramid, finest first, down to a min dimension of [minSize] (default 4) — but at
     * least one level beyond the input.
     */
    fun gaussian(img: PixelImage, minSize: Int = 4): List<PixelImage> {
        val levels = mutableListOf(img)
        while (minOf(levels.last().width, levels.last().height) > minSize) {
            levels.add(reduce(levels.last()))
        }
        return levels
    }

    /**
     * Laplacian pyramid: L[i] = G[i] − expand(G[i+1] → G[i] size); the coarsest level is G[last].
     *
     * Used for multiband blending: each level captures detail at a different scale.
     */
    fun laplacian(img: PixelImage, minSize: Int = 4): List<PixelImage> {
        val g = gaussian(img, minSize)
        val lap = mutableListOf<PixelImage>()
        for (i in 0 until g.size - 1) {
            val up = expand(g[i + 1], g[i].width, g[i].height)
            // PORTING TRAP: Swift mutated a copy (`var d = g[i]`); Kotlin needs explicit copy().
            val d = g[i].copy()
            val dp = d.pixels
            val up_p = up.pixels
            val n = dp.size
            for (j in 0 until n) { dp[j] -= up_p[j] }
            lap.add(d)
        }
        lap.add(g.last())   // coarsest residual
        return lap
    }

    /**
     * Collapse a Laplacian pyramid back to a single image.
     *
     * Iterates from coarsest to finest, expanding and adding each Laplacian level.
     */
    fun collapse(lap: List<PixelImage>): PixelImage {
        // PORTING TRAP: Swift `var out = lap.last!` is a copy; Kotlin needs .copy().
        var out = lap.last().copy()
        for (i in lap.size - 2 downTo 0) {
            val up = expand(out, lap[i].width, lap[i].height)
            // PORTING TRAP: Swift `var sum = lap[i]` is a copy; Kotlin needs .copy().
            val sum = lap[i].copy()
            val sp = sum.pixels
            val up_p = up.pixels
            val n = sp.size
            for (j in 0 until n) { sp[j] += up_p[j] }
            out = sum
        }
        return out
    }
}
