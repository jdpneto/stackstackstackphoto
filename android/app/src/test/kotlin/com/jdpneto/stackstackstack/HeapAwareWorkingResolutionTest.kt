package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.FocusStacker
import com.jdpneto.stackengine.Pipeline
import com.jdpneto.stackstackstack.StackCaptureCoordinator.Companion.MANAGED_WORKING_RESOLUTION
import com.jdpneto.stackstackstack.StackCaptureCoordinator.Companion.MIN_WORKING_RESOLUTION
import com.jdpneto.stackstackstack.StackCaptureCoordinator.Companion.achievableWorkingResolution
import com.jdpneto.stackstackstack.StackCaptureCoordinator.Companion.heapAwareWorkingResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StackCaptureCoordinator.heapAwareWorkingResolution] is a pure function of
 * (frameCount, frameEquivalents, maxMemory) — maxMemory is injected so these tests pin the heap.
 *
 * Formula: budget = maxMemory/2; bytesPerFrame = budget / frameEquivalents (default
 * [Pipeline.batchPeakFrameEquivalents] = 2N + N/3 + 3, pinned in the engine's
 * ResidencyModelTests); pixels = bytesPerFrame / 12; edge = sqrt(pixels / 0.75) (4:3),
 * rounded down to a multiple of 8, clamped to [1200, 2400].
 */
class HeapAwareWorkingResolutionTest {

    private val mb = 1024L * 1024L
    private val gb = 1024L * mb

    @Test
    fun pixel512MBHeapWith8FrameDetailBurstFloorsAt1200() {
        // 512MB heap, N=8 (Detail recipe): equivalents = 16 + 8/3 + 3 = 21.667;
        // raw edge = sqrt((256MB / 21.667 / 12) / 0.75) ≈ 1173.3 → 1168 → clamped to the 1200 floor.
        assertEquals(1200, heapAwareWorkingResolution(frameCount = 8, maxMemory = 512 * mb))
    }

    @Test
    fun oneGBHeapWith8FramesLandsBetweenFloorAndCeiling() {
        // Unclamped region — the exact arithmetic: raw edge ≈ 1659.27 → 1656 (multiple of 8).
        assertEquals(1656, heapAwareWorkingResolution(frameCount = 8, maxMemory = 1 * gb))
    }

    @Test
    fun twoGBHeapWith8Frames() {
        // raw edge ≈ 2346.57 → 2344 (still under the 2400 ceiling).
        assertEquals(2344, heapAwareWorkingResolution(frameCount = 8, maxMemory = 2 * gb))
    }

    @Test
    fun threeGBHeapClampsToManagedWorkingResolution() {
        // raw edge ≈ 2873.9 → would exceed iOS parity; clamps to 2400 (= iOS managedWorkingResolution).
        assertEquals(
            MANAGED_WORKING_RESOLUTION,
            heapAwareWorkingResolution(frameCount = 8, maxMemory = 3 * gb)
        )
    }

    @Test
    fun tinyHeapFloorsAt1200() {
        assertEquals(MIN_WORKING_RESOLUTION, heapAwareWorkingResolution(frameCount = 8, maxMemory = 64 * mb))
        assertEquals(MIN_WORKING_RESOLUTION, heapAwareWorkingResolution(frameCount = 20, maxMemory = 16 * mb))
    }

    @Test
    fun monotonicallyNonDecreasingInHeap() {
        var previous = 0
        for (heapMB in longArrayOf(64, 128, 256, 512, 768, 1024, 1536, 2048, 3072, 4096)) {
            val edge = heapAwareWorkingResolution(frameCount = 8, maxMemory = heapMB * mb)
            assertTrue("edge must not shrink as the heap grows ($heapMB MB)", edge >= previous)
            previous = edge
        }
    }

    @Test
    fun nonIncreasingInFrameCount() {
        var previous = Int.MAX_VALUE
        for (n in 2..20) {
            val edge = heapAwareWorkingResolution(frameCount = n, maxMemory = 1 * gb)
            assertTrue("more frames must not raise the resolution (N=$n)", edge <= previous)
            previous = edge
        }
        // Spot-check the Night recipe (N=12) on the same 1GB heap: raw edge ≈ 1387.2 → 1384.
        assertEquals(1384, heapAwareWorkingResolution(frameCount = 12, maxMemory = 1 * gb))
    }

    @Test
    fun unclampedResultsAreMultiplesOf8() {
        for (heapMB in longArrayOf(700, 900, 1100, 1300, 1700)) {
            val edge = heapAwareWorkingResolution(frameCount = 8, maxMemory = heapMB * mb)
            assertEquals("edge $edge must be a multiple of 8", 0, edge % 8)
        }
    }

    @Test
    fun depthEquivalentsAreMoreConservativeThanDefault() {
        // FocusStacker holds frames + aligned copies + masks + two pyramid sets (≈ 19N/3 + 3),
        // so for the same heap Depth must come out at or below the default batch estimate.
        // The coefficients themselves are engine-owned (pinned in ResidencyModelTests).
        val n = 10
        assertTrue(FocusStacker.peakFrameEquivalents(n) > Pipeline.batchPeakFrameEquivalents(n))
        val depth = heapAwareWorkingResolution(n, frameEquivalents = FocusStacker.peakFrameEquivalents(n), maxMemory = 4 * gb)
        val batch = heapAwareWorkingResolution(n, maxMemory = 4 * gb)
        assertTrue(depth <= batch)
        // On the Pixel's 512MB heap a 10-bracket depth stack pins to the quality floor.
        assertEquals(
            MIN_WORKING_RESOLUTION,
            heapAwareWorkingResolution(n, frameEquivalents = FocusStacker.peakFrameEquivalents(n), maxMemory = 512 * mb)
        )
    }

    // -----------------------------------------------------------------------
    // achievableWorkingResolution — the engine downscale HALVES (it cannot hit
    // an arbitrary target), so the achieved edge is ceil(source / 2^k), not the budget.
    // -----------------------------------------------------------------------

    @Test
    fun achievableEdgeHalvesBinnedRawSourceBelowTheFloor() {
        // ~12 MP sensor (4032 long edge) → binned develop 2016 → one halving lands at 1008,
        // honestly BELOW the 1200 budget/floor (the next step up, 2016, busts the heap budget).
        assertEquals(1008, achievableWorkingResolution(budgetEdge = 1200, sourceLongEdge = 2016))
    }

    @Test
    fun achievableEdgeHalvesFallbackDecodeBelowTheFloor() {
        // 1500px fallback decode with a 1200 budget → 750, NOT min(1200, 1500).
        assertEquals(750, achievableWorkingResolution(budgetEdge = 1200, sourceLongEdge = 1500))
    }

    @Test
    fun achievableEdgePassesThroughWhenSourceAlreadyFits() {
        assertEquals(1500, achievableWorkingResolution(budgetEdge = 2400, sourceLongEdge = 1500))
        assertEquals(1200, achievableWorkingResolution(budgetEdge = 1200, sourceLongEdge = 1200))
    }

    @Test
    fun achievableEdgeUsesCeilingHalvingLikeImagePyramidReduce() {
        // ImagePyramid.reduce produces ceil(edge/2): 2017 → 1009 (not 1008).
        assertEquals(1009, achievableWorkingResolution(budgetEdge = 1200, sourceLongEdge = 2017))
        // Two halvings when one isn't enough: 5000 → 2500 → 1250 → 625 under a 1200 budget.
        assertEquals(625, achievableWorkingResolution(budgetEdge = 1200, sourceLongEdge = 5000))
    }

    @Test
    fun achievableEdgeFallsBackToBudgetWhenSourceUnknown() {
        assertEquals(1200, achievableWorkingResolution(budgetEdge = 1200, sourceLongEdge = 0))
    }
}
