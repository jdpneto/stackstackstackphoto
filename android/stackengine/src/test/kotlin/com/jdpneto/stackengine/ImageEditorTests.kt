package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageEditorTests {

    private fun solid(c: Vec3): PixelImage = PixelImage(1, 1, floatArrayOf(c.x, c.y, c.z))

    @Test
    fun testIdentityIsNoOp() {
        val img = solid(Vec3(0.2f, 0.3f, 0.4f))
        assertEquals(img[0, 0], ImageEditor.apply(ImageAdjustments.identity, img)[0, 0])
    }

    @Test
    fun testExposureDoublesAtPlusOneEV() {
        val out = ImageEditor.apply(ImageAdjustments(exposureEV = 1f), solid(Vec3(0.2f, 0.2f, 0.2f)))
        assertEquals(0.4f, out[0, 0].x, absoluteTolerance = 1e-4f)   // ×2
    }

    @Test
    fun testWhiteBalanceWarmsRedCoolsBlue() {
        val out = ImageEditor.apply(ImageAdjustments(temperature = 1f), solid(Vec3(0.5f, 0.5f, 0.5f)))
        assertEquals(0.5f * 1.3f, out[0, 0].x, absoluteTolerance = 1e-4f)   // R ×1.3
        assertEquals(0.5f * 0.7f, out[0, 0].z, absoluteTolerance = 1e-4f)   // B ×0.7
    }

    @Test
    fun testContrastPushesAwayFromPivot() {
        // value above pivot (0.18) gets brighter with +contrast
        val out = ImageEditor.apply(ImageAdjustments(contrast = 0.5f), solid(Vec3(0.5f, 0.5f, 0.5f)))
        assertEquals((0.5f - 0.18f) * 1.5f + 0.18f, out[0, 0].x, absoluteTolerance = 1e-4f)
    }

    @Test
    fun testNegativesAreClampedToZero() {
        // a strong cut can't go below 0
        val out = ImageEditor.apply(ImageAdjustments(exposureEV = -10f), solid(Vec3(0.5f, 0.5f, 0.5f)))
        assertTrue(out[0, 0].x >= 0f)
    }

    @Test
    fun testWhiteBalanceTintMagentaReducesGreen() {
        // tint = magenta(+)/green(-): +tint must REDUCE green.
        val out = ImageEditor.apply(ImageAdjustments(tint = 1f), solid(Vec3(0.5f, 0.5f, 0.5f)))
        assertEquals(0.5f * 0.7f, out[0, 0].y, absoluteTolerance = 1e-4f)   // G × (1 - 0.3)
    }

    @Test
    fun testContrastBelowPivotIsClamped() {
        // p=0.05 is below the 0.18 pivot; +contrast pushes it negative → clamp holds it at 0.
        val out = ImageEditor.apply(ImageAdjustments(contrast = 0.5f), solid(Vec3(0.05f, 0.05f, 0.05f)))
        assertEquals(0f, out[0, 0].x, absoluteTolerance = 1e-6f)
    }

    @Test
    fun testCombinedAdjustmentsApplyInOrder() {
        // exposure ×2 → WB(temp=1) → contrast 0.5, hand-computed from p=0.2.
        val out = ImageEditor.apply(ImageAdjustments(exposureEV = 1f, contrast = 0.5f, temperature = 1f, tint = 0f),
                                    solid(Vec3(0.2f, 0.2f, 0.2f)))
        assertEquals(0.69f, out[0, 0].x, absoluteTolerance = 1e-4f)   // (0.2*2*1.3 - 0.18)*1.5 + 0.18
        assertEquals(0.51f, out[0, 0].y, absoluteTolerance = 1e-4f)   // (0.2*2*1.0 - 0.18)*1.5 + 0.18
        assertEquals(0.33f, out[0, 0].z, absoluteTolerance = 1e-4f)   // (0.2*2*0.7 - 0.18)*1.5 + 0.18
    }

    @Test
    fun testDefaultedFieldsMatchOldSidecarSemantics() {
        // Swift's `testDecodesOldAdjustmentsWithoutNewKeys` pins JSON back-compat decoding; the
        // Android JSON layer is app-side (P4–P6). The engine-side half: constructing with only the
        // old four tonal fields leaves every newer field at its documented default.
        val adj = ImageAdjustments(exposureEV = 1f, contrast = 0f, temperature = 0f, tint = 0f)
        assertEquals(1f, adj.exposureEV, absoluteTolerance = 1e-6f)
        assertEquals(0f, adj.shadows)                       // defaulted
        assertEquals(0f, adj.highlights)                    // defaulted
        assertEquals(0f, adj.straightenDegrees)             // defaulted
        assertEquals(CropAspect.ORIGINAL, adj.cropAspect)   // defaulted
        assertFalse(ImageAdjustments(exposureEV = 1f).isIdentity)
    }

    @Test
    fun testCropSquareCentersToSmallerSide() {
        val out = ImageEditor.apply(ImageAdjustments(cropAspect = CropAspect.SQUARE),
                                    PixelImage(16, 8, Vec3(0.5f, 0.5f, 0.5f)))
        assertEquals(8, out.width)
        assertEquals(8, out.height)
    }

    @Test
    fun testStraighten180FlipsRow() {
        val img = PixelImage(4, 1, floatArrayOf(
            1f, 1f, 1f,  0f, 0f, 0f,  0f, 0f, 0f,  0f, 0f, 0f))
        val r = ImageEditor.straighten(img, degrees = 180f)
        assertEquals(1f, r[3, 0].x, absoluteTolerance = 1e-4f)   // bright pixel rotated to the far end
        assertEquals(0f, r[0, 0].x, absoluteTolerance = 1e-4f)
    }

    @Test
    fun testShadowsLiftBlack() {
        val out = ImageEditor.apply(ImageAdjustments(shadows = 1f), solid(Vec3(0f, 0f, 0f)))
        assertEquals(0.5f, out[0, 0].x, absoluteTolerance = 1e-4f)   // 0 + 1·0.5·(1-0)² = 0.5
    }

    @Test
    fun testHighlightsPullWhite() {
        val out = ImageEditor.apply(ImageAdjustments(highlights = -1f), solid(Vec3(1f, 1f, 1f)))
        assertEquals(0.5f, out[0, 0].x, absoluteTolerance = 1e-4f)   // 1 + (-1)·0.5·1² = 0.5
    }

    @Test
    fun testStraightenAutoZoomsKeepingDimensionsAndCenter() {
        val img = PixelImage(9, 9, Vec3(0.2f, 0.2f, 0.2f))
        img[4, 4] = Vec3(1f, 1f, 1f)                   // bright centre
        val r = ImageEditor.straighten(img, degrees = 10f)
        assertEquals(9, r.width)
        assertEquals(9, r.height)                      // dimensions preserved (auto-zoom fills the frame)
        assertTrue(r[4, 4].x > 0.5f)                   // rotation is about the centre → centre stays bright
    }

    @Test
    fun testStraightenNonSquareExtremeAngleKeepsDimensions() {
        // Exercises the non-square auto-zoom path at the UI's max angle (regression for the
        // w/h vs (w-1)/(h-1) under-zoom that left a corner sliver on wide frames).
        val r = ImageEditor.straighten(PixelImage(16, 8, Vec3(0.3f, 0.3f, 0.3f)), degrees = 15f)
        assertEquals(16, r.width)
        assertEquals(8, r.height)
    }

    @Test
    fun testQuarterTurnRotatesViaImageGeometry() {
        val px = FloatArray(6 * 3)
        for (i in 0 until 6) { px[i * 3] = i.toFloat() }
        val img = PixelImage(3, 2, px)
        val adj = ImageAdjustments(quarterTurns = 1)
        assertEquals(ImageGeometry.rotated(img, quarterTurns = 1), ImageEditor.apply(adj, img))
    }

    @Test
    fun testQuarterTurnMakesNonIdentity() {
        assertFalse(ImageAdjustments(quarterTurns = 1).isIdentity)
        assertTrue(ImageAdjustments(quarterTurns = 0).isIdentity)
    }

    @Test
    fun testBlendLerpsTowardReferenceInLinearLight() {
        val img = PixelImage(8, 8, Vec3(0.8f, 0.8f, 0.8f))
        val ref = PixelImage(8, 8, Vec3(0.2f, 0.2f, 0.2f))
        val adj = ImageAdjustments.identity
        adj.blendStrength = 0.5f
        val out = ImageEditor.apply(adj, img, reference = ref)
        assertEquals(0.5f, out[3, 3].x, absoluteTolerance = 1e-5f, "α=0.5 is the linear midpoint")
        adj.blendStrength = 0f
        assertEquals(0.2f, ImageEditor.apply(adj, img, reference = ref)[3, 3].x, absoluteTolerance = 1e-5f)
        adj.blendStrength = 1f
        assertEquals(0.8f, ImageEditor.apply(adj, img, reference = ref)[3, 3].x, absoluteTolerance = 1e-5f)
    }

    @Test
    fun testBlendSkipsOnMissingOrMismatchedReference() {
        val img = PixelImage(8, 8, Vec3(0.8f, 0.8f, 0.8f))
        val adj = ImageAdjustments.identity
        adj.blendStrength = 0f
        assertEquals(0.8f, ImageEditor.apply(adj, img, reference = null)[3, 3].x, absoluteTolerance = 1e-5f)
        val small = PixelImage(4, 4, Vec3(0.2f, 0.2f, 0.2f))
        assertEquals(0.8f, ImageEditor.apply(adj, img, reference = small)[3, 3].x, absoluteTolerance = 1e-5f,
                     "dimension mismatch must skip the blend, never trap")
    }

    @Test
    fun testBlendAppliesBeforeTonal() {
        // EV +1 on an α=0 blend must double the REFERENCE, proving lerp-first ordering.
        val img = PixelImage(8, 8, Vec3(0.8f, 0.8f, 0.8f))
        val ref = PixelImage(8, 8, Vec3(0.2f, 0.2f, 0.2f))
        val adj = ImageAdjustments.identity
        adj.blendStrength = 0f
        adj.exposureEV = 1f
        assertEquals(0.4f, ImageEditor.apply(adj, img, reference = ref)[3, 3].x, absoluteTolerance = 1e-4f)
    }
}
