package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceSelectionTests {

    private fun checkerboard(n: Int): PixelImage {
        val img = PixelImage(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            val v: Float = if ((x + y) % 2 == 0) 1f else 0f
            img[x, y] = Vec3(v, v, v)
        }
        return img
    }

    private fun flat(n: Int, v: Float): PixelImage = PixelImage(n, n, Vec3.repeating(v))

    @Test
    fun testSharpnessHigherForCheckerboard() {
        assertTrue(Luma.sharpness(checkerboard(8)) > Luma.sharpness(flat(8, 0.5f)))
    }

    @Test
    fun testReferenceSelectionPicksSharpest() {
        val frames = listOf(flat(8, 0.5f), checkerboard(8), flat(8, 0.3f))
        assertEquals(1, ReferenceSelection.sharpestIndex(frames))
    }

    @Test
    fun testSingleFrameReturnsZero() {
        assertEquals(0, ReferenceSelection.sharpestIndex(listOf(flat(8, 0.5f))))
    }
}
