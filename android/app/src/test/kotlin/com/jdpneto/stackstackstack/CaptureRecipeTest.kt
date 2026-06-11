package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.StackMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mirrors iOS [CaptureRecipeTests] 1:1 — same assertions, same tolerances, same edge cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureRecipeTest {

    @Test
    fun testLongExposureLooksCaptureMoreFramesThanNoiseReduction() {
        val nr     = CaptureRecipe.recipe(StackMode.NOISE_REDUCTION)
        val low    = CaptureRecipe.recipe(StackMode.LOW_LIGHT_BOOST)
        val smooth = CaptureRecipe.recipe(StackMode.SMOOTH_MOTION)
        val trails = CaptureRecipe.recipe(StackMode.LIGHT_TRAILS)
        assertTrue(low.frameCount >= nr.frameCount)
        assertTrue(smooth.frameCount > nr.frameCount)
        assertTrue(trails.frameCount > nr.frameCount)
        assertTrue(smooth.durationSeconds > nr.durationSeconds)
        assertTrue(trails.durationSeconds > smooth.durationSeconds)
        assertTrue(nr.frameCount > 0)
    }

    @Test
    fun testApplyingOverridesFrameCountOnly() {
        val base = CaptureRecipe.recipe(StackMode.NOISE_REDUCTION)  // 8 frames, 0.5s
        val r = base.applying(ProControls(frameCount = 20))
        assertEquals(20, r.frameCount)
        assertEquals(base.durationSeconds, r.durationSeconds, 1e-9)
        assertNull(r.manualISO)
    }

    @Test
    fun testApplyingAutoLeavesRecipeUnchanged() {
        val base = CaptureRecipe.recipe(StackMode.LIGHT_TRAILS)
        assertEquals(base, base.applying(ProControls.auto))
    }

    @Test
    fun testApplyingPropagatesManualExposure() {
        val r = CaptureRecipe.recipe(StackMode.NOISE_REDUCTION)
            .applying(ProControls(iso = 800.0, shutterSeconds = 0.02, focus = 0.5))
        assertEquals(800f, r.manualISO!!, 1e-6f)
        assertEquals(0.02, r.manualShutterSeconds!!, 1e-9)
        assertEquals(0.5f, r.manualFocus!!, 1e-6f)
    }

    @Test
    fun testApplyingClampsFrameCountToAtLeastOne() {
        val r = CaptureRecipe.recipe(StackMode.NOISE_REDUCTION).applying(ProControls(frameCount = 0))
        assertEquals(1, r.frameCount)
    }

    @Test
    fun testProFrameCountIsCappedAt20() {
        val recipe = CaptureRecipe(frameCount = 8, durationSeconds = 0.5)
            .applying(ProControls(frameCount = 40))
        assertEquals("burst frame count must be hard-capped at 20", 20, recipe.frameCount)
    }

    @Test
    fun testProFrameCountFloorIsRespected() {
        val recipe = CaptureRecipe(frameCount = 8, durationSeconds = 0.5)
            .applying(ProControls(frameCount = 0))
        assertEquals("frame count must stay >= 1", 1, recipe.frameCount)
    }

    // MARK: - Depth focus sweep (spec 2026-06-10 §5.1)

    @Test
    fun testDepthRecipeHasFullRangeSweepMatchingFrameCount() {
        val r = CaptureRecipe.recipe(StackMode.DEPTH_OF_FIELD)
        assertNotNull("expected a non-null focusSweep", r.focusSweep)
        val sweep = r.focusSweep!!
        assertEquals("one bracket per sweep step", r.frameCount, sweep.steps)
        assertEquals(0f, sweep.near, 1e-6f)
        assertEquals(1f, sweep.far, 1e-6f)
        assertEquals(r.frameCount, sweep.positions.size)
        assertEquals(0f, sweep.positions.first(), 1e-6f)
        assertEquals(1f, sweep.positions.last(), 1e-6f)
    }

    @Test
    fun testSweepPositionsAreMonotonicNearToFar() {
        val positions = FocusSweep(near = 0.2f, far = 0.8f, steps = 5).positions
        assertEquals(5, positions.size)
        for (i in 1 until positions.size) {
            assertTrue("sweep order is what the chain aligner relies on",
                positions[i] > positions[i - 1])
        }
    }

    @Test
    fun testSweepNormalizesAReversedRange() {
        val s = FocusSweep(near = 0.9f, far = 0.1f, steps = 5)
        assertEquals(0.1f, s.near, 1e-6f)
        assertEquals(0.9f, s.far, 1e-6f)
    }

    @Test
    fun testApplyingMergesSweepRangeAndKeepsStepsEqualToFrames() {
        val r = CaptureRecipe.recipe(StackMode.DEPTH_OF_FIELD)
            .applying(ProControls(frameCount = 6, focusSweepNear = 0.2, focusSweepFar = 0.8))
        assertNotNull("expected non-null focusSweep", r.focusSweep)
        val sweep = r.focusSweep!!
        assertEquals(6, r.frameCount)
        assertEquals(6, sweep.steps)
        assertEquals(0.2f, sweep.near, 1e-6f)
        assertEquals(0.8f, sweep.far, 1e-6f)
    }

    @Test
    fun testManualFocusIsIgnoredForSweepRecipes() {
        // The sweep owns lens position; a lingering Pro single-focus value must not leak in.
        val r = CaptureRecipe.recipe(StackMode.DEPTH_OF_FIELD).applying(ProControls(focus = 0.5))
        assertNull(r.manualFocus)
    }

    @Test
    fun testNonDepthRecipesHaveNoSweep() {
        assertNull(CaptureRecipe.recipe(StackMode.NOISE_REDUCTION).focusSweep)
        assertNull(CaptureRecipe.recipe(StackMode.LIGHT_TRAILS).focusSweep)
    }

    @Test
    fun testSteadinessGatePolicy() {
        // Long-exposure looks gate (existing) and Depth gates too.
        assertTrue(StackMode.SMOOTH_MOTION.usesSteadinessGate)
        assertTrue(StackMode.LIGHT_TRAILS.usesSteadinessGate)
        assertTrue(StackMode.DEPTH_OF_FIELD.usesSteadinessGate)
        assertFalse(StackMode.NOISE_REDUCTION.usesSteadinessGate)
        assertFalse(StackMode.LOW_LIGHT_BOOST.usesSteadinessGate)
    }
}
