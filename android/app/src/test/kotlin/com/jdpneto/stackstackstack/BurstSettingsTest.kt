package com.jdpneto.stackstackstack

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mirrors iOS [BurstSettingsTests] 1:1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BurstSettingsTest {

    @Test
    fun testPhotoCountIsClampedToTwoThroughMax() {
        assertEquals("long-exposure (streaming) burst cap", 30, BurstSettings.MAX_PHOTO_COUNT)
        assertEquals(BurstSettings.MAX_PHOTO_COUNT,
            BurstSettings(photoCount = 99, durationSeconds = 5.0).photoCount)
        assertEquals(2, BurstSettings(photoCount = 0, durationSeconds = 5.0).photoCount)
        assertEquals(2, BurstSettings(photoCount = 2, durationSeconds = 5.0).photoCount)  // lower boundary inclusive
        assertEquals(BurstSettings.MAX_PHOTO_COUNT,
            BurstSettings(photoCount = BurstSettings.MAX_PHOTO_COUNT, durationSeconds = 5.0).photoCount) // upper boundary inclusive
        assertEquals(25, BurstSettings(photoCount = 25, durationSeconds = 5.0).photoCount) // within the new range
    }

    @Test
    fun testDurationIsClampedToOneThroughSixty() {
        assertEquals(60.0, BurstSettings(photoCount = 10, durationSeconds = 999.0).durationSeconds, 1e-9)
        assertEquals(1.0, BurstSettings(photoCount = 10, durationSeconds = 0.0).durationSeconds, 1e-9)
        assertEquals(18.0, BurstSettings(photoCount = 10, durationSeconds = 18.0).durationSeconds, 1e-9)
    }
}
