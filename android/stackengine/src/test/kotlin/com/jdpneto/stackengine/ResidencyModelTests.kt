package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the engine-owned peak-residency coefficients the app's heap-aware working-resolution
 * budget is built on. These values encode batch/blend internals of [Pipeline] and [FocusStacker];
 * a deliberate engine refactor that changes the resident set should update BOTH the function
 * and this pin together (and the app budget follows automatically).
 */
class ResidencyModelTests {

    @Test
    fun batchPeakFrameEquivalentsPinned() {
        // held inputs N + aligned copies N + lumas N/3 + 3 frames of slack.
        assertEquals(2.0 * 8 + 8 / 3.0 + 3.0, Pipeline.batchPeakFrameEquivalents(8), 1e-12)
        assertEquals(21.0 + 2.0 / 3.0, Pipeline.batchPeakFrameEquivalents(8), 1e-12)   // N=8 (Detail)
        assertEquals(31.0, Pipeline.batchPeakFrameEquivalents(12), 1e-12)               // N=12 (Night)
    }

    @Test
    fun focusStackerPeakFrameEquivalentsPinned() {
        // inputs N + warps N + sharpness N/3 + weights N/3 + masks N + 4N/3 image pyramids
        // + 4N/3 mask pyramids ≈ 19N/3, plus 3 frames of slack.
        assertEquals(19.0 * 10 / 3.0 + 3.0, FocusStacker.peakFrameEquivalents(10), 1e-12)
        assertEquals(22.0, FocusStacker.peakFrameEquivalents(3), 1e-12)                  // 19 + 3
    }

    @Test
    fun depthPeakIsMoreConservativeThanBatchPeak() {
        // FocusStacker holds two pyramid sets + masks on top of frames + warps, so for any N ≥ 1
        // the Depth budget must demand at least as much memory per frame-equivalent as batch.
        for (n in 1..30) {
            assertTrue(
                FocusStacker.peakFrameEquivalents(n) > Pipeline.batchPeakFrameEquivalents(n),
                "depth residency must exceed batch residency (N=$n)"
            )
        }
    }
}
