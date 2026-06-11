package com.jdpneto.stackengine

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Transform2DTests {

    @Test
    fun testIdentityMapsPointToItself() {
        val (px, py) = Transform2D.identity.apply(3f, 4f)
        assertEquals(3f, px, absoluteTolerance = 1e-6f)
        assertEquals(4f, py, absoluteTolerance = 1e-6f)
    }

    @Test
    fun testSimilarityScalesAndTranslates() {
        val t = Transform2D.similarity(scale = 2f, rotation = 0f, tx = 1f, ty = -1f)
        val (px, py) = t.apply(3f, 4f)
        assertEquals(7f, px, absoluteTolerance = 1e-6f)   // 2·3 + 1
        assertEquals(7f, py, absoluteTolerance = 1e-6f)   // 2·4 − 1
    }

    @Test
    fun testSimilarityRotatesNinetyDegrees() {
        val t = Transform2D.similarity(scale = 1f, rotation = (PI / 2).toFloat(), tx = 0f, ty = 0f)
        val (px, py) = t.apply(1f, 0f)
        assertEquals(0f, px, absoluteTolerance = 1e-6f)    // (1,0) rotated +90° → (0,1)
        assertEquals(1f, py, absoluteTolerance = 1e-6f)
    }

    @Test
    fun testComposedAppliesRightHandSideFirst() {
        val scale = Transform2D.similarity(scale = 2f, rotation = 0f, tx = 0f, ty = 0f)
        val shift = Transform2D.similarity(scale = 1f, rotation = 0f, tx = 3f, ty = -1f)
        // scale ∘ shift: p → scale(shift(p)) = 2·(p + (3,−1))
        val (px, py) = scale.composed(with = shift).apply(1f, 1f)
        assertEquals(8f, px, absoluteTolerance = 1e-5f)
        assertEquals(0f, py, absoluteTolerance = 1e-5f)
    }

    @Test
    fun testComposedWithIdentityIsUnchanged() {
        val t = Transform2D.similarity(scale = 1.04f, rotation = 0.02f, tx = 2f, ty = -1f)
        // exact ==: multiplying by exact 1.0/0.0 is lossless in IEEE — don't weaken to tolerances.
        assertEquals(t, t.composed(with = Transform2D.identity))
        assertEquals(t, Transform2D.identity.composed(with = t))
    }

    @Test
    fun testInverseRoundTripsToIdentity() {
        val t = Transform2D.similarity(scale = 1.04f, rotation = 0.02f, tx = 2f, ty = -1f)
        val id = t.composed(with = t.inverse)
        assertEquals(1f, id.a, absoluteTolerance = 1e-5f)
        assertEquals(0f, id.b, absoluteTolerance = 1e-5f)
        assertEquals(0f, id.c, absoluteTolerance = 1e-5f)
        assertEquals(1f, id.d, absoluteTolerance = 1e-5f)
        assertEquals(0f, id.tx, absoluteTolerance = 1e-4f)
        assertEquals(0f, id.ty, absoluteTolerance = 1e-4f)
    }
}

// Re-export tolerance helper so it looks like Swift's accuracy: parameter
private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "expected $expected but got $actual (tolerance $absoluteTolerance)"
    )
}
