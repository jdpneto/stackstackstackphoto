package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageGeometryTests {

    // 3×2 image, each pixel uniquely valued by its row-major index.
    private fun sample(w: Int = 3, h: Int = 2): PixelImage {
        val px = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            px[i * 3] = i.toFloat(); px[i * 3 + 1] = i.toFloat() * 2f; px[i * 3 + 2] = i.toFloat() * 3f
        }
        return PixelImage(w, h, px)
    }

    @Test
    fun testZeroAndFourTurnsAreIdentity() {
        val img = sample()
        assertEquals(img, ImageGeometry.rotated(img, quarterTurns = 0))
        assertEquals(img, ImageGeometry.rotated(img, quarterTurns = 4))
        assertEquals(img, ImageGeometry.rotated(img, quarterTurns = -4))
    }

    @Test
    fun testOneTurnSwapsDimensionsAndMapsTopLeftToTopRight() {
        val img = sample(3, 2)                                       // w=3, h=2
        val r = ImageGeometry.rotated(img, quarterTurns = 1)         // 90° clockwise
        assertEquals(2, r.width)
        assertEquals(3, r.height)
        assertEquals(img[0, 0], r[r.width - 1, 0])                   // source top-left → rotated top-right
    }

    @Test
    fun testTwoTurnsIs180() {
        val img = sample(3, 2)
        val r = ImageGeometry.rotated(img, quarterTurns = 2)
        assertEquals(3, r.width); assertEquals(2, r.height)
        assertEquals(img[0, 0], r[img.width - 1, img.height - 1])
    }

    @Test
    fun testNegativeWrapsToThree() {
        val img = sample()
        assertEquals(ImageGeometry.rotated(img, quarterTurns = 3),
                     ImageGeometry.rotated(img, quarterTurns = -1))
    }

    @Test
    fun testThreeTurnsSwapsDimensionsAndMapsTopLeftToBottomLeft() {
        val img = sample(3, 2)                                       // w=3, h=2
        val r = ImageGeometry.rotated(img, quarterTurns = 3)         // 270° CW = 90° CCW
        assertEquals(2, r.width)
        assertEquals(3, r.height)
        assertEquals(img[0, 0], r[0, r.height - 1])                  // source top-left → rotated bottom-left
    }
}
