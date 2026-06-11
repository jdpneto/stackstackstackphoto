package com.jdpneto.stackstackstack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace

/**
 * Decodes image bytes into interleaved sRGB RGBA8 bytes + dimensions, optionally downscaled.
 * Mirrors iOS [ImageDecoder]: maxPixel is a CEILING (never upscales), guard bounds identical.
 */
object ImageDecoder {

    /**
     * Decode [data] into sRGB RGBA8 bytes.
     *
     * @param data      JPEG or HEIC bytes.
     * @param maxPixel  Optional downscale ceiling on the long edge. When null, full resolution.
     *                  Never upscales (mirrors the iOS `kCGImageSourceCreateThumbnailFromImageAlways`
     *                  + `min(maxPixel, max(srcW, srcH))` guard).
     * @return Triple of (rgba bytes, width, height), or null on decode failure.
     */
    fun rgba8(data: ByteArray, maxPixel: Int? = null): Triple<ByteArray, Int, Int>? {
        // Fast-decode just the dimensions first (no pixel decode — mirrors the iOS srcW/srcH guard).
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val srcW = opts.outWidth
        val srcH = opts.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            if (maxPixel != null) {
                val longEdge = maxOf(srcW, srcH)
                // CEILING: only downscale, never upscale (matches the iOS maxPixel = min(maxPixel, longEdge)).
                val target = minOf(maxPixel, longEdge)
                // inSampleSize must be a power of 2; pick the largest that keeps long edge >= target.
                inSampleSize = computeSampleSize(longEdge, target)
            }
        }

        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts)
            ?: return null

        val w = bitmap.width
        val h = bitmap.height
        // Guard: w*h*4 overflow + absurd size (30000² ≈ 0.9 GP) — same bounds as iOS.
        if (w <= 0 || h <= 0 || w > 30_000 || h > 30_000) {
            bitmap.recycle()
            return null
        }

        // Convert to sRGB ARGB_8888 if necessary, then extract RGBA bytes.
        val srgb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: run { bitmap.recycle(); return null }

        val pixels = IntArray(w * h)
        srgb.getPixels(pixels, 0, w, 0, 0, w, h)
        if (srgb !== bitmap) srgb.recycle()
        bitmap.recycle()

        // Unpack ARGB int → RGBA bytes.
        val rgba = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            val base = i * 4
            rgba[base]     = Color.red(p).toByte()
            rgba[base + 1] = Color.green(p).toByte()
            rgba[base + 2] = Color.blue(p).toByte()
            rgba[base + 3] = 0xFF.toByte()  // fully opaque
        }
        return Triple(rgba, w, h)
    }

    /**
     * Decode an image FILE to a [Bitmap], downsampled so the long edge is at most [maxPixel].
     *
     * This is the single owner of the downsample policy for on-disk results (bounds-only first
     * pass → power-of-2 [computeSampleSize] → sRGB-preferred decode → never upscales), streaming
     * straight from the file via [BitmapFactory.decodeFile] — no whole-file `readBytes()` copy
     * of a multi-MB JPEG/HEIC per call. Used by the gallery grid and the photo detail viewer.
     *
     * @return The decoded bitmap, or null when the file is missing/corrupt.
     */
    fun decodeFile(path: String, maxPixel: Int? = null): Bitmap? {
        // Bounds-only pass (no pixel decode) to size the subsample.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            if (maxPixel != null) {
                val longEdge = maxOf(srcW, srcH)
                // CEILING: only downscale, never upscale (same guard as [rgba8]).
                val target = minOf(maxPixel, longEdge)
                inSampleSize = computeSampleSize(longEdge, target)
            }
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    /** Compute an inSampleSize (power of 2) such that the decoded long edge is ≤ [target]. */
    private fun computeSampleSize(longEdge: Int, target: Int): Int {
        var sample = 1
        while (longEdge / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }
}
