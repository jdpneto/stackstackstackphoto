package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals

class PixelImageTests {

    @Test
    fun testSubscriptRoundTrip() {
        val img = PixelImage(2, 2)
        img[1, 0] = Vec3(0.1f, 0.2f, 0.3f)
        val p = img[1, 0]
        assertEquals(0.1f, p.x, absoluteTolerance = 1e-7f)
        assertEquals(0.2f, p.y, absoluteTolerance = 1e-7f)
        assertEquals(0.3f, p.z, absoluteTolerance = 1e-7f)
        val zero = img[0, 0]
        assertEquals(0f, zero.x)
        assertEquals(0f, zero.y)
        assertEquals(0f, zero.z)
        assertEquals(4, img.pixelCount)
    }
}
