package com.jdpneto.stackengine

import kotlin.math.max
import kotlin.math.min

/**
 * Motion-aware compositing: keep static regions tack-sharp from a clean base, and apply the look's
 * effect only where the scene actually moves. "What moved" is the per-pixel temporal luma range
 * across the ALIGNED frames — once hand shake is registered out, a static pixel barely varies (just
 * sensor noise) while a passing car / person / on-screen video varies a lot. This is what lets a
 * look like light-trails streak the motion while the static text behind it stays readable.
 */
internal object MotionComposite {

    /**
     * Per-pixel motion weight in [0,1]: 0 = static, 1 = moving. [lo]/[hi] bound the luma-range band
     * (below `lo` → static, above `hi` → moving) with a smoothstep, then a box blur removes hard
     * seams between static and moving regions.
     */
    fun motionMask(imgs: List<PixelImage>, lo: Float, hi: Float, smoothRadius: Int): FloatArray {
        val w = imgs[0].width; val h = imgs[0].height
        val mask = FloatArray(w * h)
        val invSpan = 1f / max(hi - lo, 1e-6f)
        for (i in 0 until (w * h)) {
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (im in imgs) {
                val base = i * 3
                val l = 0.2126f * im.pixels[base] + 0.7152f * im.pixels[base + 1] + 0.0722f * im.pixels[base + 2]
                if (l < mn) mn = l
                if (l > mx) mx = l
            }
            val c = min(max((mx - mn - lo) * invSpan, 0f), 1f)
            mask[i] = c * c * (3f - 2f * c)   // smoothstep
        }
        return if (smoothRadius > 0) BoxFilter.mean(mask, w, h, smoothRadius) else mask
    }

    /** Per-pixel lerp: [base] where the mask is ~0 (static), [effect] where it's ~1 (moving). */
    fun blend(staticBase: PixelImage, effect: PixelImage, mask: FloatArray): PixelImage {
        require(staticBase.width == effect.width && staticBase.height == effect.height)
        require(mask.size == staticBase.width * staticBase.height)
        val out = PixelImage(staticBase.width, staticBase.height)
        for (i in 0 until (staticBase.width * staticBase.height)) {
            val m = mask[i]
            out.pixels[i * 3]     = staticBase.pixels[i * 3]     + (effect.pixels[i * 3]     - staticBase.pixels[i * 3])     * m
            out.pixels[i * 3 + 1] = staticBase.pixels[i * 3 + 1] + (effect.pixels[i * 3 + 1] - staticBase.pixels[i * 3 + 1]) * m
            out.pixels[i * 3 + 2] = staticBase.pixels[i * 3 + 2] + (effect.pixels[i * 3 + 2] - staticBase.pixels[i * 3 + 2]) * m
        }
        return out
    }
}
