package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuidedFilterTests {

    @Test
    fun testConstantInputStaysConstant() {
        val guide = FloatArray(64) { (it % 8).toFloat() / 8f }   // arbitrary guide
        val p = FloatArray(64) { 0.5f }                          // constant input
        val out = GuidedFilter.filter(input = p, guide = guide, width = 8, height = 8, radius = 2, eps = 1e-3f)
        for (v in out) { assertEquals(0.5f, v, absoluteTolerance = 1e-3f) }
    }

    @Test
    fun testPreservesAGuideEdge() {
        // Guide is a vertical step; input tracks it with deterministic noise. Output keeps the step.
        val w = 16; val h = 8
        val I = FloatArray(w * h); val p = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val step: Float = if (x < w / 2) 0.2f else 0.8f
                I[y * w + x] = step
                p[y * w + x] = step + (if ((x * 7 + y * 13) % 5 == 0) 0.05f else -0.03f)
            }
        }
        val out = GuidedFilter.filter(input = p, guide = I, width = w, height = h, radius = 2, eps = 1e-4f)
        val left = out[4 * w + (w / 2 - 1)]; val right = out[4 * w + (w / 2)]
        assertTrue(right - left > 0.4f)   // step preserved (not blurred away)
    }
}
