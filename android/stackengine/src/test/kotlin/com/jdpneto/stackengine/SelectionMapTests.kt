package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionMapTests {

    @Test
    fun testFavoursTheSharperFramePerRegion() {
        // Frame 0 sharp on the left half; frame 1 sharp on the right half.
        val w = 24; val h = 12
        fun sharp(left: Boolean): FloatArray {
            val s = FloatArray(w * h)
            for (y in 0 until h) { for (x in 0 until w) { s[y * w + x] = if ((x < w / 2) == left) 1.0f else 0.05f } }
            return s
        }
        val guide = FloatArray(w * h) { 0.5f }
        val weights = SelectionMap.weights(sharpness = listOf(sharp(left = true), sharp(left = false)),
                                           guide = guide, width = w, height = h)
        val li = 6 * w + 4; val ri = 6 * w + (w - 4)
        assertTrue(weights[0][li] > 0.7f)   // left region → frame 0
        assertTrue(weights[1][ri] > 0.7f)   // right region → frame 1
        assertEquals(1.0f, weights[0][li] + weights[1][li], absoluteTolerance = 1e-4f)   // sums to 1
    }

    @Test
    fun testNoDetailGivesEqualWeights() {
        val w = 8; val h = 8; val flat = FloatArray(w * h)
        val weights = SelectionMap.weights(sharpness = listOf(flat, flat), guide = flat, width = w, height = h)
        assertEquals(0.5f, weights[0][0], absoluteTolerance = 1e-4f)
        assertEquals(0.5f, weights[1][0], absoluteTolerance = 1e-4f)
    }

    @Test
    fun testNonFiniteInputsDoNotLeakNaN() {
        // A NaN/Inf sharpness or guide value must not leak into the weights; it degrades to no-weight.
        val w = 6; val h = 6; val n = w * h
        val bad = FloatArray(n) { 0.5f }; bad[n / 2] = Float.POSITIVE_INFINITY
        val ok = FloatArray(n) { 0.5f }
        val guide = FloatArray(n) { 0.5f }; guide[0] = Float.NaN
        val weights = SelectionMap.weights(sharpness = listOf(bad, ok), guide = guide, width = w, height = h)
        for (k in 0 until 2) {
            for (v in weights[k]) {
                assertTrue(v.isFinite()); assertTrue(v >= 0f); assertTrue(v <= 1f)
            }
        }
        for (i in 0 until n) {
            assertEquals(1.0f, weights[0][i] + weights[1][i], absoluteTolerance = 1e-3f)   // still sums to 1
        }
    }
}
