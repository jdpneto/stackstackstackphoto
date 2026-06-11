package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NOTE — serialization tests: the Swift suite also pins Codable round-trip + back-compat decode
 * (`testQuarterTurnsCodableRoundTrip`, `testQuarterTurnsBackCompatDefaultsToZero`, and the decode
 * halves of the blendStrength tests). The Android engine has no JSON layer; those decode semantics
 * (missing keys → constructor defaults, then the same normalization/clamping) are an APP-LAYER
 * TODO for the sidecar decoder (P4–P6). The type-level invariants are pinned here.
 */
class ImageAdjustmentsTests {

    @Test
    fun testQuarterTurnsNormalizeAtConstruction() {
        // Construction normalizes to 0…3 (the engine-side half of the back-compat decode contract).
        assertEquals(3, ImageAdjustments(quarterTurns = 3).quarterTurns)
        assertEquals(1, ImageAdjustments(quarterTurns = 5).quarterTurns)
        assertEquals(3, ImageAdjustments(quarterTurns = -1).quarterTurns)
        assertEquals(0, ImageAdjustments().quarterTurns)
    }

    @Test
    fun testQuarterTurnsStayNormalizedOnDirectMutation() {
        val adj = ImageAdjustments()
        adj.quarterTurns += 5
        assertEquals(1, adj.quarterTurns, "out-of-range mutation re-normalizes to 0…3")
        adj.quarterTurns -= 2
        assertEquals(3, adj.quarterTurns)
        assertEquals(ImageAdjustments(quarterTurns = 3), adj, "equality stays coherent with the canonical value")
    }

    @Test
    fun testBlendStrengthDefaultsToFullLook() {
        assertEquals(1f, ImageAdjustments.identity.blendStrength)
        // A decoder that fills missing fields with constructor defaults must yield full look.
        val legacy = ImageAdjustments()
        assertEquals(1f, legacy.blendStrength)
        assertTrue(legacy.isIdentity)
    }

    @Test
    fun testBlendStrengthClampsOutOfRangeValues() {
        // A corrupt or out-of-range value must not produce weird hasBlend states. (Fix 3)
        val low = ImageAdjustments(blendStrength = -0.5f)
        assertEquals(0f, low.blendStrength, absoluteTolerance = 1e-6f, "negative blendStrength must clamp to 0")
        // hasBlend is true when blendStrength < 1; 0 means full-reference (maximum blend) — still active.
        assertTrue(low.hasBlend, "blendStrength 0 is full-reference blend, so hasBlend must be true")

        val high = ImageAdjustments(blendStrength = 7f)
        assertEquals(1f, high.blendStrength, absoluteTolerance = 1e-6f, "blendStrength > 1 must clamp to 1")
        assertFalse(high.hasBlend, "blendStrength clamped to 1 means no blend active")
    }
}
