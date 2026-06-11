package com.jdpneto.stackstackstack

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mirrors iOS [CaptureOrientationTests]: the quarter-turns table must match the device-verified
 * iOS mapping (CaptureOrientation.swift) including the landscape SWAP fixed in commit 5423ecb —
 * UIDeviceOrientation.landscapeLeft (home button right, device rotated counter-clockwise) is
 * Android's [Surface.ROTATION_90], and .landscapeRight is [Surface.ROTATION_270].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureOrientationTest {

    @Test
    fun testMappingMatchesIOSTable() {
        assertEquals("portrait → 1 turn",               1, CaptureOrientation.quarterTurns(Surface.ROTATION_0))
        assertEquals("portrait upside-down → 3 turns",  3, CaptureOrientation.quarterTurns(Surface.ROTATION_180))
        assertEquals("ROTATION_90 (≙ iOS landscapeLeft) → 0 turns",  0, CaptureOrientation.quarterTurns(Surface.ROTATION_90))
        assertEquals("ROTATION_270 (≙ iOS landscapeRight) → 2 turns", 2, CaptureOrientation.quarterTurns(Surface.ROTATION_270))
    }

    @Test
    fun testUnknownRotationAssumesPortrait() {
        assertEquals(1, CaptureOrientation.quarterTurns(-1))
        assertEquals(1, CaptureOrientation.quarterTurns(99))
    }
}
