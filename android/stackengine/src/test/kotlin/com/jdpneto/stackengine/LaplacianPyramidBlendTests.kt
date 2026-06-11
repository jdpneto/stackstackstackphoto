package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LaplacianPyramidBlendTests {

    @Test
    fun testFullWeightOnOneFrameReturnsThatFrame() {
        val w = 16; val h = 16; val n = w * h
        val a = PixelImage(w, h, Vec3(0.8f, 0.2f, 0.2f))
        val b = PixelImage(w, h, Vec3(0.2f, 0.2f, 0.8f))
        val out = LaplacianPyramidBlend.blend(images = listOf(a, b),
                                              weights = listOf(FloatArray(n) { 1f },
                                                               FloatArray(n) { 0f }))
        assertEquals(0.8f, out[8, 8].x, absoluteTolerance = 2e-3f)   // all weight on A → A
        assertEquals(0.2f, out[8, 8].z, absoluteTolerance = 2e-3f)
    }

    @Test
    fun testCombinesEachFramesSharpRegion() {
        // Frame A: left half checker (sharp), right half flat. Frame B: the opposite.
        val w = 32; val h = 16
        fun frame(sharpLeft: Boolean): PixelImage {
            val img = PixelImage(w, h, Vec3(0.5f, 0.5f, 0.5f))
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val inLeft = x < w / 2
                    if (inLeft == sharpLeft) {
                        val v: Float = if ((x + y) % 2 == 0) 0.85f else 0.15f
                        img[x, y] = Vec3(v, v, v)
                    }
                }
            }
            return img
        }
        val a = frame(sharpLeft = true); val b = frame(sharpLeft = false)
        // Weights pick the in-focus frame per half.
        val wA = FloatArray(w * h); val wB = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < w / 2) { wA[y * w + x] = 1f } else { wB[y * w + x] = 1f }
            }
        }
        val out = LaplacianPyramidBlend.blend(images = listOf(a, b), weights = listOf(wA, wB))
        // The composite is detailed in BOTH halves → total sharpness exceeds either single frame.
        val total = SharpnessMap.compute(out).sum()
        assertTrue(total > SharpnessMap.compute(a).sum() * 1.3f)
        assertTrue(total > SharpnessMap.compute(b).sum() * 1.3f)
    }

    @Test
    fun testNonFiniteWeightsAreTreatedAsNoContribution() {
        // An Inf/NaN weight must not leak NaN into the composite; it counts as 0 weight.
        val w = 8; val h = 8; val n = w * h
        val a = PixelImage(w, h, Vec3(0.7f, 0.7f, 0.7f))
        val b = PixelImage(w, h, Vec3(0.3f, 0.3f, 0.3f))
        val wA = FloatArray(n) { 1f }; wA[10] = Float.POSITIVE_INFINITY; wA[20] = Float.NaN
        val out = LaplacianPyramidBlend.blend(images = listOf(a, b), weights = listOf(wA, FloatArray(n)))
        for (v in out.pixels) { assertTrue(v.isFinite()) }
    }
}
