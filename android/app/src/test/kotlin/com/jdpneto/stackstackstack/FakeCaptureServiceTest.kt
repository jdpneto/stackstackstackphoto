package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.ColorPipeline
import com.jdpneto.stackengine.Pipeline
import com.jdpneto.stackengine.StackMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mirrors iOS [FakeCaptureServiceTests] 1:1 — same assertions, same pixel-level checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FakeCaptureServiceTest {

    @Test
    fun testFakeReturnsRecipeFrameCount() = runBlocking {
        val svc = FakeCaptureService(width = 8, height = 8)
        val burst = svc.captureBurst(CaptureRecipe(frameCount = 5, durationSeconds = 1.0))
        assertTrue("expected .raw burst", burst.payload is CapturedBurst.Payload.Raw)
        val payload = burst.payload as CapturedBurst.Payload.Raw
        assertEquals(5, payload.frames.size)
        assertEquals(8, payload.frames[0].width)
        val img = ColorPipeline.process(payload.frames[0])
        assertEquals(64, img.pixels.size / 3)   // 8×8 = 64 pixels, 3 floats each
    }

    @Test
    fun testMotionMakesLightTrailsBrighterThanSmoothMotion() = runBlocking {
        val svc = FakeCaptureService(width = 32, height = 32)
        val burst = svc.captureBurst(CaptureRecipe(frameCount = 16, durationSeconds = 1.0))
        assertTrue("expected .raw burst", burst.payload is CapturedBurst.Payload.Raw)
        val payload = burst.payload as CapturedBurst.Payload.Raw
        val frames = payload.frames
        val trails = Pipeline.reduce(frames, StackMode.LIGHT_TRAILS)    // per-channel max → bright streak
        val smooth = Pipeline.reduce(frames, StackMode.SMOOTH_MOTION)   // mean → faint blur

        // Across the centre row the moving object leaves a brighter max-streak than the mean.
        val y = 16
        var trailsMax = 0f
        var smoothMax = 0f
        for (x in 0 until 32) {
            trailsMax = maxOf(trailsMax, trails[x, y].x)
            smoothMax = maxOf(smoothMax, smooth[x, y].x)
        }
        assertTrue("light trails must keep the moving highlight brighter than smooth motion",
                   trailsMax > smoothMax)
    }

    @Test
    fun testFocusSweepRecipeProducesDistinctOrderedBrackets() = runBlocking {
        val svc = FakeCaptureService(width = 32, height = 16)
        val recipe = CaptureRecipe.recipe(StackMode.DEPTH_OF_FIELD)
            .applying(ProControls(frameCount = 4))
        val burst = svc.captureBurst(recipe)
        assertTrue("expected .raw burst", burst.payload is CapturedBurst.Payload.Raw)
        val payload = burst.payload as CapturedBurst.Payload.Raw
        assertEquals(4, payload.frames.size)
        // Each bracket is sharp in a different band → mosaics must differ pairwise.
        for (i in payload.frames.indices) {
            for (j in (i + 1) until payload.frames.size) {
                assertFalse("bracket $i and $j must differ (different sharp band)",
                    payload.frames[i].mosaic.contentEquals(payload.frames[j].mosaic))
            }
        }
    }

    @Test
    fun testFocusSweepReportsProgressPerBracket() = runBlocking {
        val svc = FakeCaptureService(width = 32, height = 16)
        val recipe = CaptureRecipe.recipe(StackMode.DEPTH_OF_FIELD)
            .applying(ProControls(frameCount = 3))
        val log = mutableListOf<Int>()
        // FakeCaptureService fires onProgress synchronously per bracket.
        svc.captureBurst(recipe, isSteady = { true }, onProgress = { n -> log.add(n) })
        assertEquals(listOf(1, 2, 3), log)
    }
}
