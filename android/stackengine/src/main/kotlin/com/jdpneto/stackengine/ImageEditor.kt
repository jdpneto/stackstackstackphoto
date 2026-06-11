package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

object ImageEditor {
    private const val PIVOT: Float = 0.18f   // 18% linear mid-grey

    /**
     * Apply adjustments with an optional aligned reference for the blend-strength lerp
     * (out = α·img + (1−α)·reference, linear light, BEFORE geometry/tonal so the reference
     * needs no separate geometry pass). Missing/mismatched reference skips the blend. (spec §3)
     */
    fun apply(adj: ImageAdjustments, img: PixelImage, reference: PixelImage?): PixelImage {
        var base = img
        if (adj.hasBlend && reference != null && reference.width == img.width && reference.height == img.height) {
            val a = max(0f, min(1f, adj.blendStrength))
            base = PixelImage(img.width, img.height)
            for (i in img.pixels.indices) {
                base.pixels[i] = img.pixels[i] * a + reference.pixels[i] * (1f - a)
            }
        }
        return apply(adj, base)
    }

    /**
     * Apply all adjustments: geometry (straighten → crop) then tonal (exposure → WB → contrast →
     * shadows/highlights), clamped ≥ 0 (design §14).
     */
    fun apply(adj: ImageAdjustments, img: PixelImage): PixelImage {
        if (adj.isIdentity) return img
        var out = img
        if (adj.quarterTurns != 0) { out = ImageGeometry.rotated(out, quarterTurns = adj.quarterTurns) }
        if (adj.straightenDegrees != 0f) { out = straighten(out, degrees = adj.straightenDegrees) }
        if (adj.cropAspect.ratio != null) { out = crop(out, aspect = adj.cropAspect) }
        // Geometry-only edits skip the per-pixel tonal pass entirely.
        return if (adj.hasTonalAdjustments) tonal(adj, out) else out
    }

    /**
     * Per-pixel tonal adjustments in linear light: exposure → white balance → contrast about the
     * pivot → tone curve (shadows/highlights). Output clamped ≥ 0.
     */
    internal fun tonal(adj: ImageAdjustments, img: PixelImage): PixelImage {
        val expGain = 2.0.pow(adj.exposureEV.toDouble()).toFloat()       // UI constrains EV to ±2
        val contrastFactor = 1f + max(-0.9f, min(1f, adj.contrast))      // contrast = -1 must not flat-grey
        // tint is magenta(+)/green(-): positive tint REDUCES green.
        val wb = Vec3(1f + adj.temperature * 0.3f, 1f - adj.tint * 0.3f, 1f - adj.temperature * 0.3f)
        val pivotVec = Vec3.repeating(PIVOT)
        val one = Vec3.repeating(1f); val zero = Vec3.repeating(0f)
        val out = PixelImage(img.width, img.height)
        val n = img.pixelCount
        for (i in 0 until n) {
            val base = i * 3
            var p = Vec3(img.pixels[base], img.pixels[base + 1], img.pixels[base + 2]) * expGain   // exposure
            p = p * wb                                                   // white balance
            p = (p - pivotVec) * contrastFactor + pivotVec               // contrast about the pivot
            // Tone curve: shadows weighted to the dark end ((1-tone)²), highlights to the bright end (tone²).
            val tone = p.clamp(zero, one)
            p = p + adj.shadows * 0.5f * (one - tone) * (one - tone)
            p = p + adj.highlights * 0.5f * tone * tone
            val q = p.max(zero)                                          // no negative light
            out.pixels[base] = q.x; out.pixels[base + 1] = q.y; out.pixels[base + 2] = q.z
        }
        return out
    }

    /**
     * Rotate about the centre by [degrees], keeping dimensions. Auto-zooms to fill so the rotated
     * frame has no empty / edge-smeared corners (the standard "straighten" behaviour).
     */
    internal fun straighten(img: PixelImage, degrees: Float): PixelImage {
        val rad = degrees * (Math.PI.toFloat()) / 180f
        val cosA = cos(rad); val sinA = sin(rad)
        val w = img.width; val h = img.height
        val cx = (w - 1).toFloat() / 2f; val cy = (h - 1).toFloat() / 2f
        // Zoom so every output corner back-maps inside the source half-extents (no edge-smeared corners).
        // Derived from the actual extents cx,cy (not w/h) so it's exact for non-square frames too.
        val needX = if (cx > 0f) (cx * abs(cosA) + cy * abs(sinA)) / cx else 1f
        val needY = if (cy > 0f) (cx * abs(sinA) + cy * abs(cosA)) / cy else 1f
        val scale = max(max(needX, needY), 1f)
        val out = PixelImage(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x.toFloat() - cx) / scale; val dy = (y.toFloat() - cy) / scale
                out[x, y] = bilinear(img, cx + dx * cosA + dy * sinA, cy - dx * sinA + dy * cosA)
            }
        }
        return out
    }

    /** Centre-crop to the aspect's ratio (largest fit). */
    internal fun crop(img: PixelImage, aspect: CropAspect): PixelImage {
        val ratio = aspect.ratio ?: return img
        val w = img.width; val h = img.height
        var cw = w; var ch = h
        // Clamp to [1, source extent]: max(1,…) bars a zero size, min(w/h,…) bars a Float-rounding
        // overshoot that would push the read window out of bounds.
        if (w.toFloat() / h.toFloat() > ratio) { cw = min(w, max(1, (h.toFloat() * ratio).toInt())) }
        else { ch = min(h, max(1, (w.toFloat() / ratio).toInt())) }
        val x0 = (w - cw) / 2; val y0 = (h - ch) / 2
        val out = PixelImage(cw, ch)
        for (y in 0 until ch) {
            for (x in 0 until cw) { out[x, y] = img[x0 + x, y0 + y] }
        }
        return out
    }

    private fun bilinear(img: PixelImage, fx: Float, fy: Float): Vec3 {
        val w = img.width; val h = img.height
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0.toFloat(); val ty = fy - y0.toFloat()
        fun at(x: Int, y: Int): Vec3 = img[x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)]
        val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        val bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (bot - top) * ty
    }
}
