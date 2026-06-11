package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** An integer 2-D translation. */
data class Translation(val dx: Int, val dy: Int)

object Alignment {

    /**
     * Integer translation (dx,dy) minimizing mean luma SSD where ref[x,y] ~ moving[x+dx, y+dy].
     * [robustClip] caps each pixel's squared residual (null = plain SSD).
     */
    fun estimateTranslation(
        reference: PixelImage,
        moving: PixelImage,
        searchRange: Int,
        robustClip: Float? = null
    ): Translation {
        require(reference.width == moving.width && reference.height == moving.height)
        return estimateTranslation(
            referenceLuma = Luma.luminance(reference),
            movingLuma = Luma.luminance(moving),
            width = reference.width, height = reference.height,
            searchRange = searchRange, robustClip = robustClip
        )
    }

    /**
     * Integer translation over precomputed luminance buffers (lets the pipeline reuse luma).
     * [robustClip] (when set) caps each pixel's squared residual before accumulating, so
     * focus-blur mismatches (or any outlier region) cannot pull the estimate away from the
     * common background signal. null = plain SSD.
     */
    internal fun estimateTranslation(
        referenceLuma: FloatArray,
        movingLuma: FloatArray,
        width: Int, height: Int,
        searchRange: Int,
        robustClip: Float? = null
    ): Translation {
        require(searchRange >= 0) { "searchRange must be >= 0" }
        val w = width; val h = height
        var best = Translation(dx = 0, dy = 0)
        var bestCost = Float.POSITIVE_INFINITY
        // Iterate from zero outward (magnitude shells) so equal-cost ties are broken in
        // favour of the SMALLEST displacement.
        for (mag in 0..searchRange) {
            for (dy in -mag..mag) {
                for (dx in -mag..mag) {
                    if (abs(dx) != mag && abs(dy) != mag) continue  // current shell only
                    var cost = 0f
                    var count = 0f
                    val yStart = max(0, -dy); val yEnd = min(h, h - dy)
                    val xStart = max(0, -dx); val xEnd = min(w, w - dx)
                    if (yStart >= yEnd || xStart >= xEnd) continue
                    for (y in yStart until yEnd) {
                        for (x in xStart until xEnd) {
                            val diff = referenceLuma[y * w + x] - movingLuma[(y + dy) * w + (x + dx)]
                            val d2 = diff * diff
                            cost += if (robustClip != null) minOf(d2, robustClip) else d2
                            count += 1f
                        }
                    }
                    val mean = cost / count
                    if (mean < bestCost) { bestCost = mean; best = Translation(dx, dy) }
                }
            }
        }
        return best
    }

    /**
     * Coarse-to-fine integer translation on a luma pyramid: estimate on a heavily-downscaled level
     * (cheap, captures large shifts) then refine ±2 per finer level. Cost is ~O(image) instead of
     * O(image × searchRange²) — the key to making full-resolution alignment fast on device.
     * Collapses to a single-level `±maxShift` box search when the image is already small (so it
     * matches [estimateTranslation] for small inputs), and returns identity for `maxShift <= 0`.
     * Matches the full-resolution search on real content (textured scene + noise); it can diverge
     * only on pathologically smooth/periodic inputs (pure gradients, sinusoids) where translation
     * estimation is ill-posed regardless — a non-issue for photos.
     */
    internal fun estimateTranslationCoarseToFine(
        referenceLuma: FloatArray, movingLuma: FloatArray,
        width: Int, height: Int, maxShift: Int, minDim: Int = 64
    ): Translation {
        if (maxShift <= 0) return Translation(0, 0)
        // Build matching luma pyramids (finest first), halving until the min dimension hits minDim.
        data class Level(val l: FloatArray, val w: Int, val h: Int)

        val refP = mutableListOf(Level(referenceLuma, width, height))
        val movP = mutableListOf(Level(movingLuma,    width, height))
        while (minOf(refP.last().w, refP.last().h) > minDim) {
            halveLuma(Triple(refP.last().l, refP.last().w, refP.last().h)).let { (l, w, h) -> refP.add(Level(l, w, h)) }
            halveLuma(Triple(movP.last().l, movP.last().w, movP.last().h)).let { (l, w, h) -> movP.add(Level(l, w, h)) }
        }
        val levels = refP.size
        var dx = 0; var dy = 0
        for (lvl in (levels - 1) downTo 0) {   // coarsest → finest
            val r = refP[lvl]; val m = movP[lvl]
            val range = if (levels == 1) maxShift
                        else if (lvl == levels - 1) maxOf(2, maxShift shr lvl)
                        else 2
            val (ndx, ndy) = bestShiftAround(r.l, m.l, r.w, r.h, dx, dy, range)
            dx = ndx; dy = ndy
            if (lvl > 0) { dx *= 2; dy *= 2 }   // a coarse-pixel shift is 2 fine-pixels
        }
        return Translation(dx, dy)
    }

    /** 2×2 box-downscale of a luma buffer. Returns Triple(outArray, outW, outH). */
    private fun halveLuma(p: Triple<FloatArray, Int, Int>): Triple<FloatArray, Int, Int> {
        val (l, w, h) = p
        val ow = (w + 1) / 2; val oh = (h + 1) / 2
        val out = FloatArray(ow * oh)
        for (oy in 0 until oh) {
            val y0 = 2 * oy; val y1 = minOf(y0 + 1, h - 1)
            for (ox in 0 until ow) {
                val x0 = 2 * ox; val x1 = minOf(x0 + 1, w - 1)
                out[oy * ow + ox] = (l[y0 * w + x0] + l[y0 * w + x1] + l[y1 * w + x0] + l[y1 * w + x1]) * 0.25f
            }
        }
        return Triple(out, ow, oh)
    }

    /**
     * Integer shift (dx,dy) in a box of radius [range] around (baseDx,baseDy) minimizing mean luma
     * SSD over the overlap, ties broken toward the smaller displacement.
     */
    private fun bestShiftAround(
        lr: FloatArray, lm: FloatArray, w: Int, h: Int,
        baseDx: Int, baseDy: Int, range: Int
    ): Pair<Int, Int> {
        var bestDx = baseDx; var bestDy = baseDy; var bestCost = Float.POSITIVE_INFINITY
        for (dy in (baseDy - range)..(baseDy + range)) {
            val yStart = max(0, -dy); val yEnd = min(h, h - dy)
            if (yStart >= yEnd) continue
            for (dx in (baseDx - range)..(baseDx + range)) {
                val xStart = max(0, -dx); val xEnd = min(w, w - dx)
                if (xStart >= xEnd) continue
                var cost = 0f; var count = 0f
                for (y in yStart until yEnd) {
                    val ro = y * w; val mo = (y + dy) * w + dx
                    for (x in xStart until xEnd) { val d = lr[ro + x] - lm[mo + x]; cost += d * d; count++ }
                }
                val mean = cost / count
                val mag = abs(dx) + abs(dy); val bestMag = abs(bestDx) + abs(bestDy)
                if (mean < bestCost - 1e-9f || (mean < bestCost + 1e-9f && mag < bestMag)) {
                    bestCost = mean; bestDx = dx; bestDy = dy
                }
            }
        }
        return Pair(bestDx, bestDy)
    }

    /**
     * Warp by (dx,dy): out[x,y] = img[x+dx, y+dy] (edge-clamped), aligning [img] to the reference.
     */
    fun warp(img: PixelImage, by: Translation): PixelImage {
        val w = img.width; val h = img.height
        val out = PixelImage(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = (x + by.dx).coerceIn(0, w - 1)
                val sy = (y + by.dy).coerceIn(0, h - 1)
                out[x, y] = img[sx, sy]
            }
        }
        return out
    }
}
