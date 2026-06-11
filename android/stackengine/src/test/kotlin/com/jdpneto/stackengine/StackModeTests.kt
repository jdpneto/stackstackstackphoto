package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackModeTests {

    @Test
    fun testRawValuesAreStableStorageKeys() {
        // Storage keys are persisted library keys — pin every one (renames silently break libraries).
        assertEquals("noiseReduction", StackMode.NOISE_REDUCTION.storageKey)
        assertEquals("smoothMotion",   StackMode.SMOOTH_MOTION.storageKey)
        assertEquals("lightTrails",    StackMode.LIGHT_TRAILS.storageKey)
        assertEquals("lowLightBoost",  StackMode.LOW_LIGHT_BOOST.storageKey)
        assertEquals("depthOfField",   StackMode.DEPTH_OF_FIELD.storageKey)
        assertEquals(5, StackMode.entries.size)
    }

    @Test
    fun testDepthOfFieldIsNotLongExposure() {
        // Depth is a static fast-ish sweep (frame-count sliders, no duration window).
        assertFalse(StackMode.DEPTH_OF_FIELD.isLongExposure)
    }

    @Test
    fun testSupportsBlendReference() {
        // All looks support blend-reference except depthOfField (frames differ by focus, not time).
        assertTrue(StackMode.NOISE_REDUCTION.supportsBlendReference)
        assertTrue(StackMode.SMOOTH_MOTION.supportsBlendReference)
        assertTrue(StackMode.LIGHT_TRAILS.supportsBlendReference)
        assertTrue(StackMode.LOW_LIGHT_BOOST.supportsBlendReference)
        assertFalse(StackMode.DEPTH_OF_FIELD.supportsBlendReference)
    }
}
