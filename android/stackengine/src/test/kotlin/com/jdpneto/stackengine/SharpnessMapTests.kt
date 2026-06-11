package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertTrue

class SharpnessMapTests {

    @Test
    fun testUniformImageHasNearZeroSharpness() {
        val s = SharpnessMap.compute(PixelImage(16, 16, Vec3(0.5f, 0.5f, 0.5f)))
        assertTrue((s.maxOrNull() ?: 0f) < 1e-5f)
    }

    @Test
    fun testSharpnessHigherInDetailedRegion() {
        // Left half: high-frequency checker (in focus). Right half: flat (no detail).
        val w = 32; val h = 16
        val img = PixelImage(w, h, Vec3(0.5f, 0.5f, 0.5f))
        for (y in 0 until h) {
            for (x in 0 until (w / 2)) {
                val v: Float = if ((x + y) % 2 == 0) 0.9f else 0.1f
                img[x, y] = Vec3(v, v, v)
            }
        }
        val s = SharpnessMap.compute(img)
        fun avg(x0: Int, x1: Int): Float {
            var sum = 0f; var n = 0
            for (y in 4 until (h - 4)) { for (x in (x0 + 4) until (x1 - 4)) { sum += s[y * w + x]; n++ } }
            return sum / n.toFloat()
        }
        assertTrue(avg(0, w / 2) > avg(w / 2, w) * 5f)   // detailed region much sharper
    }
}
