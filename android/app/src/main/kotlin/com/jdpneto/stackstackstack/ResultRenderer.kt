package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.ImageAdjustments
import com.jdpneto.stackengine.ImageEditor
import com.jdpneto.stackengine.OutputTransform
import com.jdpneto.stackengine.PixelImage

/**
 * Renders a developed result through non-destructive adjustments and re-encodes it.
 *
 * - Pass [maxPixel] to render a downscaled preview cheaply.
 * - Pass [format] to produce HEIC instead of JPEG — renders of an existing record always
 *   pass the record's own format so what-you-see-is-what-gets-written (spec §4).
 * - Pass [referenceData] (the stored aligned reference frame) to enable blend-strength:
 *   the reference is decoded at the SAME [maxPixel], linearised, and threaded into
 *   [ImageEditor.apply] for the α lerp (spec 2026-06-11 §3).
 *
 * Mirrors the iOS [ResultRenderer] exactly — same parameter names, same semantics.
 */
object ResultRenderer {

    /**
     * @param originalData  Encoded original bytes (JPEG or HEIC) — the immutable source.
     * @param adjustments   Non-destructive adjustments to apply.
     * @param quality       Compression quality (0.0–1.0); default 0.95.
     * @param maxPixel      Optional downscale ceiling on the long edge. Never upscales.
     * @param format        Output format; default JPEG.
     * @param referenceData Optional aligned reference frame bytes for blend-strength lerp.
     * @return Encoded bytes, or null on decode/encode failure.
     */
    fun render(
        originalData: ByteArray,
        adjustments: ImageAdjustments,
        quality: Double = 0.95,
        maxPixel: Int? = null,
        format: ImageEncoder.Format = ImageEncoder.Format.JPEG,
        referenceData: ByteArray? = null
    ): ByteArray? {
        val (rgba, w, h) = ImageDecoder.rgba8(originalData, maxPixel) ?: return null
        val linear = OutputTransform.decodeSRGB8(rgba, w, h)

        // Decode the reference at the same scale so dimensions match for the blend-strength lerp.
        // Dimension mismatch (e.g. a decode that chose a slightly different scale) is caught
        // defensively inside ImageEditor.apply and skips the blend rather than trapping.
        // Skip the disk/decode work entirely when no blend is active.
        val refLinear: PixelImage? = if (adjustments.hasBlend) {
            referenceData?.let { refBytes ->
                val decoded = ImageDecoder.rgba8(refBytes, maxPixel) ?: return@let null
                val (refRgba, rw, rh) = decoded
                OutputTransform.decodeSRGB8(refRgba, rw, rh)
            }
        } else null

        val adjusted = ImageEditor.apply(adjustments, linear, refLinear)
        val outRgba = OutputTransform.encodeSRGB8(adjusted)
        // Use the ADJUSTED dimensions — crop changes them, so w/h from the decode are stale.
        return runCatching {
            ImageEncoder.encode(outRgba, adjusted.width, adjusted.height, format, quality)
        }.getOrNull()
    }

    /**
     * Decode encoded bytes straight to the linear-light [PixelImage] the editor math runs on
     * (same decode + linearize steps as [render]). The result is INVARIANT across slider moves,
     * so the editor decodes its original/reference ONCE per session and re-renders previews from
     * the cached linear images instead of re-doing the multi-MB decode per slider release.
     * [ImageEditor.apply] never mutates its inputs, so the cached image is safe to reuse.
     */
    fun decodeLinear(data: ByteArray, maxPixel: Int? = null): PixelImage? {
        val (rgba, w, h) = ImageDecoder.rgba8(data, maxPixel) ?: return null
        return OutputTransform.decodeSRGB8(rgba, w, h)
    }

    /**
     * Render a PREVIEW [android.graphics.Bitmap] from an already-decoded linear source: apply the
     * adjustments and wrap the resulting sRGB RGBA8 bytes directly in a bitmap
     * ([android.graphics.Bitmap.copyPixelsFromBuffer] — ARGB_8888's native memory layout IS
     * RGBA byte order). No JPEG encode→decode round-trip: that pair existed in the save path's
     * shape, but a preview is only ever displayed, never written. The SAVE path stays on
     * [render] (encoded bytes, byte-identical behavior).
     *
     * [reference] is only threaded into the blend lerp when [adjustments] has one active,
     * mirroring [render]'s hasBlend gate.
     */
    fun renderPreviewBitmap(
        linear: PixelImage,
        adjustments: ImageAdjustments,
        reference: PixelImage? = null
    ): android.graphics.Bitmap? {
        val adjusted = ImageEditor.apply(adjustments, linear, if (adjustments.hasBlend) reference else null)
        val rgba = OutputTransform.encodeSRGB8(adjusted)
        return runCatching {
            android.graphics.Bitmap.createBitmap(
                adjusted.width, adjusted.height, android.graphics.Bitmap.Config.ARGB_8888
            ).also { it.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba)) }
        }.getOrNull()
    }
}
