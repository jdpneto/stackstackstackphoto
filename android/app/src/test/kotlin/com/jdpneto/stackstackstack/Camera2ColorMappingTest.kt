package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.RawSensorFrame
import com.jdpneto.stackengine.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-Kotlin tests for the Camera2 metadata → engine mapping helpers
 * (Camera2ColorMapping.kt). No Robolectric needed — the helpers take primitives.
 */
class Camera2ColorMappingTest {

    private val eps = 1e-5f

    private fun assertVec3(expected: Vec3, actual: Vec3) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
        assertEquals("z", expected.z, actual.z, eps)
    }

    // --- wbGainsFromRggb (COLOR_CORRECTION_GAINS: R, G_even, G_odd, B) ---

    @Test
    fun rggbGainsAverageTheTwoGreens() {
        // G = (1.2 + 0.8) / 2 = 1.0 → gains pass through unchanged.
        assertVec3(Vec3(2.1f, 1f, 1.5f), wbGainsFromRggb(2.1f, 1.2f, 0.8f, 1.5f))
    }

    @Test
    fun rggbGainsAreNormalizedGreenRelative() {
        // G = 2 → everything divided by 2 (exposure-preserving, mirrors iOS G==1 contract).
        assertVec3(Vec3(1f, 1f, 1.5f), wbGainsFromRggb(2f, 2f, 2f, 3f))
    }

    @Test
    fun rggbGainsNonPositiveComponentFallsBackToIdentity() {
        assertVec3(Vec3(1f, 1f, 1f), wbGainsFromRggb(0f, 1f, 1f, 1.5f))
        assertVec3(Vec3(1f, 1f, 1f), wbGainsFromRggb(2f, 0f, 0f, 1.5f))
        assertVec3(Vec3(1f, 1f, 1f), wbGainsFromRggb(2f, 1f, 1f, -1f))
    }

    // --- wbGainsFromNeutralPoint (SENSOR_NEUTRAL_COLOR_POINT) ---

    @Test
    fun neutralPointGainsAreReciprocalNormalizedGreen() {
        // neutral (0.5, 1.0, 0.6) → raw gains (2, 1, 1/0.6); G already 1 → (2, 1, 1.6667).
        assertVec3(Vec3(2f, 1f, 1f / 0.6f), wbGainsFromNeutralPoint(0.5f, 1f, 0.6f))
    }

    @Test
    fun neutralPointGainsNormalizeWhenGreenIsNotOne() {
        // neutral (0.4, 0.8, 0.5) → (g/r, 1, g/b) = (2, 1, 1.6).
        assertVec3(Vec3(2f, 1f, 1.6f), wbGainsFromNeutralPoint(0.4f, 0.8f, 0.5f))
    }

    @Test
    fun neutralPointNonPositiveFallsBackToIdentity() {
        assertVec3(Vec3(1f, 1f, 1f), wbGainsFromNeutralPoint(0f, 1f, 0.6f))
        assertVec3(Vec3(1f, 1f, 1f), wbGainsFromNeutralPoint(0.5f, -1f, 0.6f))
    }

    // --- engineColorMatrixFromRowMajor (COLOR_CORRECTION_TRANSFORM → engine column-major) ---

    @Test
    fun colorMatrixIsTransposedFromRowMajorToColumnMajor() {
        // Row-major [1..9] → column-major: column 0 is row-major column 0 = (1, 4, 7), etc.
        val rowMajor = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val expected = floatArrayOf(1f, 4f, 7f, 2f, 5f, 8f, 3f, 6f, 9f)
        assertArrayEquals(expected, engineColorMatrixFromRowMajor(rowMajor), eps)
    }

    @Test
    fun colorMatrixIdentityIsFixedPoint() {
        assertArrayEquals(
            RawSensorFrame.IDENTITY_3X3,
            engineColorMatrixFromRowMajor(RawSensorFrame.IDENTITY_3X3.copyOf()),
            eps
        )
    }

    @Test
    fun colorMatrixWrongSizeFallsBackToIdentity() {
        assertArrayEquals(RawSensorFrame.IDENTITY_3X3, engineColorMatrixFromRowMajor(FloatArray(4)), eps)
        assertArrayEquals(RawSensorFrame.IDENTITY_3X3, engineColorMatrixFromRowMajor(FloatArray(0)), eps)
    }

    // --- blackLevelFromPattern (SENSOR_BLACK_LEVEL_PATTERN) ---

    @Test
    fun blackLevelAveragesThePatternOffsets() {
        // Mirrors iOS averaging of the per-channel DNG BlackLevel array.
        assertEquals(64.5f, blackLevelFromPattern(floatArrayOf(64f, 64f, 64f, 66f)), eps)
        assertEquals(64f, blackLevelFromPattern(floatArrayOf(64f, 64f, 64f, 64f)), eps)
    }

    @Test
    fun blackLevelEmptyPatternFallsBackTo64() {
        assertEquals(64f, blackLevelFromPattern(FloatArray(0)), eps)
    }

    // --- chooseRawSize (12 MP cap parity with iOS, PR #27) ---

    private fun area(s: Pair<Int, Int>): Long = s.first.toLong() * s.second.toLong()

    @Test
    fun chooseRawSizePicksLargestUnderTheCap() {
        // 50 MP (8160×6144), 12.5 MP (4080×3072), 3 MP (2040×1536) → the 12.5 MP mode.
        val sizes = listOf(8160 to 6144, 4080 to 3072, 2040 to 1536)
        assertEquals(4080 to 3072, chooseRawSize(sizes, areaOf = ::area))
    }

    @Test
    fun chooseRawSizeBoundaryExactlyAtCapQualifies() {
        val atCap = 4200 to 3000   // 12.6 MP exactly
        assertEquals(atCap, chooseRawSize(listOf(8160 to 6144, atCap), areaOf = ::area))
    }

    @Test
    fun chooseRawSizeAllOverCapPicksSmallest() {
        val sizes = listOf(8160 to 6144, 5440 to 4096)   // 50 MP, 22 MP
        assertEquals(5440 to 4096, chooseRawSize(sizes, areaOf = ::area))
    }

    @Test
    fun chooseRawSizeEmptyReturnsNull() {
        assertNull(chooseRawSize(emptyList<Pair<Int, Int>>(), areaOf = ::area))
    }
}
