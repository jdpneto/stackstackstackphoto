package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlignmentTests {

    /** A diagonal gradient gives a unique SSD minimum. */
    private fun gradient(w: Int, h: Int): PixelImage {
        val img = PixelImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (x + 2 * y).toFloat() / (w + 2 * h).toFloat()
            img[x, y] = Vec3(v, v, v)
        }
        return img
    }

    /** moving[x,y] = ref[x - sx, y - sy] (content shifted by (sx,sy)). */
    private fun shifted(img: PixelImage, sx: Int, sy: Int): PixelImage {
        val w = img.width; val h = img.height
        val out = PixelImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val cx = min(max(x - sx, 0), w - 1)
            val cy = min(max(y - sy, 0), h - 1)
            out[x, y] = img[cx, cy]
        }
        return out
    }

    @Test
    fun testEstimateRecoversShift() {
        val ref = gradient(16, 16)
        val mov = shifted(ref, 2, -1) // content moved right 2, up 1
        // ref[x,y] = mov[x+2, y-1], so best (dx,dy) = (2,-1)
        val t = Alignment.estimateTranslation(reference = ref, moving = mov, searchRange = 4)
        assertEquals(2, t.dx)
        assertEquals(-1, t.dy)
    }

    @Test
    fun testWarpAlignsToReference() {
        val ref = gradient(16, 16)
        val mov = shifted(ref, 2, -1)
        val t = Alignment.estimateTranslation(reference = ref, moving = mov, searchRange = 4)
        val warped = Alignment.warp(mov, by = t)
        // Interior must match the reference after warping.
        var maxDiff = 0f
        for (y in 3 until 13) for (x in 3 until 13) {
            maxDiff = max(maxDiff, abs(warped[x, y].x - ref[x, y].x))
        }
        assertTrue(maxDiff < 1e-5f, "maxDiff=$maxDiff should be < 1e-5")
    }

    /**
     * On a larger image the coarse-to-fine pyramid kicks in (>64 px) yet must still recover the
     * exact integer shift — this is the fast path used for on-device full-resolution alignment.
     */
    @Test
    fun testCoarseToFineRecoversShiftOnLargerImage() {
        val n = 160
        fun texel(x: Int, y: Int): Float {
            var h = (x * 73856093).toUInt() xor (y * 19349663).toUInt()
            h = h * 2654435761u; h = h xor (h shr 13); h = h * 2246822519u; h = h xor (h shr 16)
            return 0.15f + 0.7f * (h and 0xFFFFu).toFloat() / 0xFFFF.toFloat()
        }
        val ref = PixelImage(n, n)
        for (y in 0 until n) for (x in 0 until n) { ref[x, y] = Vec3.repeating(texel(x, y)) }
        // moving = content shifted right 5 / up 3 → mov[x,y] = ref[x-5, y+3].
        val mov = PixelImage(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            mov[x, y] = ref[min(max(x - 5, 0), n - 1), min(max(y + 3, 0), n - 1)]
        }
        val t = Alignment.estimateTranslationCoarseToFine(
            referenceLuma = Luma.luminance(ref), movingLuma = Luma.luminance(mov),
            width = n, height = n, maxShift = 16
        )
        assertEquals(5, t.dx); assertEquals(-3, t.dy)
    }

    @Test
    fun testCoarseToFineZeroMaxShiftIsIdentity() {
        val img = gradient(8, 8)
        val l = Luma.luminance(img)
        val t = Alignment.estimateTranslationCoarseToFine(
            referenceLuma = l, movingLuma = l, width = 8, height = 8, maxShift = 0
        )
        assertEquals(0, t.dx); assertEquals(0, t.dy)
    }
}
