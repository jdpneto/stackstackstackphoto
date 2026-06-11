package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.ImageAdjustments
import com.jdpneto.stackengine.OutputTransform
import com.jdpneto.stackengine.PixelImage
import com.jdpneto.stackengine.Vec3
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mirrors iOS [ResultRendererTests] 1:1 — same pixel-level assertions, same tolerances,
 * same JPEG/crop/blend-strength semantics.
 *
 * HEIC: gated on runtime support (same Robolectric honesty rule as [ImageEncoderTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResultRendererTest {

    private fun encodeGrey(level: Float, w: Int = 4, h: Int = 4): ByteArray {
        val img = PixelImage(w, h, Vec3(level, level, level))
        val rgba = OutputTransform.encodeSRGB8(img)
        return ImageEncoder.encode(rgba, w, h, ImageEncoder.Format.JPEG, quality = 1.0)
    }

    @Test
    fun testRenderRoundTripsAndAppliesExposure() {
        // Build a small grey JPEG via the engine + encoder.
        val grey = PixelImage(4, 4, Vec3(0.25f, 0.25f, 0.25f))
        val rgba = OutputTransform.encodeSRGB8(grey)
        val jpeg = ImageEncoder.encode(rgba, 4, 4, ImageEncoder.Format.JPEG, quality = 1.0)

        // Identity render returns a valid JPEG of the same dimensions.
        val identity = ResultRenderer.render(jpeg, ImageAdjustments.identity)
        assertNotNull("identity render must succeed", identity)
        val (idRgba, w, h) = ImageDecoder.rgba8(identity!!)!!
        assertEquals(4, w); assertEquals(4, h); assertEquals(4 * 4 * 4, idRgba.size)

        // +1 EV brightens the decoded result vs the identity render.
        val brighter = ResultRenderer.render(jpeg, ImageAdjustments(exposureEV = 1f))
        assertNotNull(brighter)
        val (brRgba, _, _) = ImageDecoder.rgba8(brighter!!)!!
        assertTrue("pixel got brighter",
            (brRgba[0].toInt() and 0xFF) > (idRgba[0].toInt() and 0xFF))
    }

    @Test
    fun testRenderWithMaxPixelDownscales() {
        val big = PixelImage(64, 64, Vec3(0.5f, 0.5f, 0.5f))
        val jpeg = ImageEncoder.encode(OutputTransform.encodeSRGB8(big), 64, 64,
                                       ImageEncoder.Format.JPEG, quality = 1.0)
        val preview = ResultRenderer.render(jpeg, ImageAdjustments.identity, maxPixel = 16)
        assertNotNull(preview)
        val (_, pw, ph) = ImageDecoder.rgba8(preview!!)!!
        assertTrue("preview must be ≤ maxPixel on the long edge", maxOf(pw, ph) <= 16)
        assertTrue(pw > 0)
    }

    @Test
    fun testRenderWithCropProducesCroppedDimensions() {
        // Regression: a square crop changes the image dimensions; the encoder must use the
        // adjusted size, not the stale decode size (else the pixel buffer mismatches w*h).
        val img = PixelImage(16, 8, Vec3(0.5f, 0.5f, 0.5f))
        val jpeg = ImageEncoder.encode(OutputTransform.encodeSRGB8(img), 16, 8,
                                       ImageEncoder.Format.JPEG, quality = 1.0)
        val rendered = ResultRenderer.render(jpeg, ImageAdjustments(cropAspect = com.jdpneto.stackengine.CropAspect.SQUARE))
        assertNotNull(rendered)
        val (rgba, w, h) = ImageDecoder.rgba8(rendered!!)!!
        assertEquals("centre-cropped to the smaller side", 8, w)
        assertEquals(8, h)
        assertEquals(w * h * 4, rgba.size)
    }

    @Test
    fun testRenderReturnsNullOnGarbageInput() {
        // Corrupt / non-image data must fail soft (null), not trap an engine precondition.
        //
        // ROBOLECTRIC HONESTY NOTE: Robolectric's shadow BitmapFactory.decodeByteArray creates
        // a placeholder Bitmap for ANY byte input including empty or garbage bytes — it never
        // returns null in the shadow implementation. This is an irreconcilable difference from
        // real Android's BitmapFactory which returns null for invalid image data.
        //
        // Analogous to the iOS Intel-mac XCTSkip pattern for HEIC: we cannot assert null here
        // under Robolectric. We verify only that no engine precondition is violated (no crash or
        // exception propagates to the caller). Real-device null-return behaviour is covered by
        // the device test plan (TaskB4).
        //
        // Both inputs must NOT throw (fail soft is the contract):
        ResultRenderer.render(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04), ImageAdjustments.identity)
        ResultRenderer.render(ByteArray(0), ImageAdjustments(exposureEV = 1f))
        // (No assertion on the result — shadow returns non-null, real device returns null.)
    }

    // MARK: - blend-strength

    @Test
    fun testRenderAtAlphaZeroMatchesReference() {
        // Two flat images: original 0.8 grey, reference 0.2 grey; α=0 must render ≈ reference.
        val original  = encodeGrey(0.8f, 8, 8)
        val reference = encodeGrey(0.2f, 8, 8)
        val adj = ImageAdjustments(blendStrength = 0f)
        val out = ResultRenderer.render(original, adj, quality = 0.95, referenceData = reference)
        assertNotNull(out)
        val (rgba,  w, h) = ImageDecoder.rgba8(out!!)!!
        val (refRgba, _, _) = ImageDecoder.rgba8(reference)!!
        // Centre pixel ≈ the reference's sRGB-encoded 0.2-linear grey; tolerance for JPEG.
        val px  = (rgba[(h / 2 * w + w / 2) * 4].toInt() and 0xFF)
        val ref = (refRgba[(h / 2 * w + w / 2) * 4].toInt() and 0xFF)
        assertTrue("α=0 render must match the reference within JPEG tolerance",
                   Math.abs(px - ref) < 6)
    }

    // MARK: - HEIC output (runtime-gated)

    @Test
    fun testRenderInHEICGatedOnRuntimeSupport() {
        val grey = PixelImage(4, 4, Vec3(0.25f, 0.25f, 0.25f))
        val rgba = OutputTransform.encodeSRGB8(grey)
        val original = ImageEncoder.encode(rgba, 4, 4, ImageEncoder.Format.JPEG, quality = 1.0)
        val heicSupported = try {
            val probe = ImageEncoder.encode(ByteArray(4 * 4 * 4) { 128.toByte() }, 4, 4,
                                            ImageEncoder.Format.HEIC, quality = 0.9)
            // Only "supported" if the output is actually HEIC (not JPEG fallback).
            probe.isNotEmpty() && !(probe[0] == 0xFF.toByte() && probe[1] == 0xD8.toByte())
        } catch (e: Exception) { false }
        if (!heicSupported) return   // Robolectric honesty rule
        val out = ResultRenderer.render(original, ImageAdjustments.identity, quality = 0.9,
                                         format = ImageEncoder.Format.HEIC)
        assertNotNull("HEIC output must decode", out?.let { ImageDecoder.rgba8(it) })
        assertFalse("must not be JPEG magic bytes",
            out!![0] == 0xFF.toByte() && out[1] == 0xD8.toByte())
    }
}
