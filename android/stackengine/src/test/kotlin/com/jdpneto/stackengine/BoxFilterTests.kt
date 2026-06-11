package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertTrue

class BoxFilterTests {

    @Test
    fun testMeanOfConstantIsConstant() {
        val out = BoxFilter.mean(FloatArray(8 * 8) { 0.3f }, 8, 8, 2)
        assertTrue(out.max() <= 0.3f + 1e-5f)
        assertTrue(out.min() >= 0.3f - 1e-5f)
    }

    @Test
    fun testMeanSpreadsAnImpulse() {
        val src = FloatArray(9 * 9)
        src[4 * 9 + 4] = 9f
        val out = BoxFilter.mean(src, 9, 9, 1)
        assertTrue(out[4 * 9 + 4] < 9f)       // central value reduced
        assertTrue(out[4 * 9 + 3] > 0f)       // neighbour raised
    }
}
