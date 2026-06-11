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

    // --- focusDiopters (iOS lensPosition 0=near/1=far → Camera2 diopters 0=far/max=near) ---

    @Test
    fun focusDioptersInvertsTheIosConvention() {
        // Position 0 (iOS closest) → the lens's largest diopter value (closest focus).
        assertEquals(10f, focusDiopters(0f, 10f), eps)
        // Position 1 (iOS infinity) → 0 diopters (Camera2 infinity).
        assertEquals(0f, focusDiopters(1f, 10f), eps)
        // Midpoint scales linearly.
        assertEquals(5f, focusDiopters(0.5f, 10f), eps)
    }

    @Test
    fun focusDioptersClampsWildPositions() {
        assertEquals(10f, focusDiopters(-3f, 10f), eps)
        assertEquals(0f, focusDiopters(7f, 10f), eps)
    }

    // --- clampedSensitivity / clampedExposureNanos (manual Pro overrides → device ranges) ---

    @Test
    fun sensitivityWithinRangePassesThrough() {
        assertEquals(400, clampedSensitivity(400, 50, 6400))
    }

    @Test
    fun sensitivityClampsToRangeEnds() {
        assertEquals(50, clampedSensitivity(25, 50, 6400))
        assertEquals(6400, clampedSensitivity(12800, 50, 6400))
    }

    @Test
    fun sensitivityNullBoundsMeanNoClamp() {
        assertEquals(12800, clampedSensitivity(12800, null, null))
        assertEquals(50, clampedSensitivity(25, 50, null))
    }

    @Test
    fun exposureNanosConvertsSeconds() {
        assertEquals(10_000_000L, clampedExposureNanos(0.01, null, null))   // 1/100 s
        assertEquals(1_000_000_000L, clampedExposureNanos(1.0, null, null))
    }

    @Test
    fun exposureNanosClampsToRangeEnds() {
        // 1/8000 s floor, 1/2 s ceiling.
        assertEquals(125_000L, clampedExposureNanos(0.00001, 125_000L, 500_000_000L))
        assertEquals(500_000_000L, clampedExposureNanos(2.0, 125_000L, 500_000_000L))
    }

    // --- burstShouldFinish (two-sided join: frames requested AND join conversions drained) ---

    @Test
    fun burstDoesNotFinishWhileFramesRemain() {
        assertEquals(false, burstShouldFinish(remaining = 3, expectedJoins = 0, joined = 0))
    }

    @Test
    fun burstDoesNotFinishBeforeTheLastJoinLands() {
        // Regression for the dropped-last-frame race: the capture result advanced `remaining`
        // to 0 but the final image buffer is still converting → must keep waiting.
        assertEquals(false, burstShouldFinish(remaining = 0, expectedJoins = 8, joined = 7))
    }

    @Test
    fun burstFinishesWhenAllJoinsDrained() {
        assertEquals(true, burstShouldFinish(remaining = 0, expectedJoins = 8, joined = 8))
        // Zero-join finish (every capture failed) is legal — finishLocked reports NoFramesProduced.
        assertEquals(true, burstShouldFinish(remaining = 0, expectedJoins = 0, joined = 0))
    }

    // --- meteringRectFromPreviewTap (normalized preview tap → active-array pixel rect) ---

    // Round-number active array used by most cases: 4000×3000, default 10% region → 400×300.

    @Test
    fun meteringRectOrientation0IsIdentity() {
        // Centre tap → centred rect.
        assertEquals(
            ActiveArrayRect(1800, 1350, 400, 300),
            meteringRectFromPreviewTap(0.5f, 0.5f, 0, 4000, 3000)
        )
    }

    @Test
    fun meteringRectOrientation90UndoesTheClockwiseRotation() {
        // The dictated device case: Pixel-style sensorOrientation=90, active array 4032×3024,
        // portrait tap (0.9, 0.5) = right-centre of the preview = top-centre of the sensor image.
        // Inverse-rotated sensor point: u = y = 0.5, v = 1 - x = 0.100000024 (float).
        //   rw = (4032*0.1f).toInt() = 403, rh = (3024*0.1f).toInt() = 302
        //   centre = (0.5*4032, 0.100000024*3024) = (2016, 302.40007) → (2016, 302)
        //   left = 2016 - 403/2 = 2016 - 201 = 1815, top = 302 - 302/2 = 302 - 151 = 151
        assertEquals(
            ActiveArrayRect(1815, 151, 403, 302),
            meteringRectFromPreviewTap(0.9f, 0.5f, 90, 4032, 3024)
        )
    }

    @Test
    fun meteringRectOrientation180Mirrors() {
        // (u,v) = (1-x, 1-y) = (0.75, 0.75) → centre (3000, 2250) → left 2800, top 2100.
        assertEquals(
            ActiveArrayRect(2800, 2100, 400, 300),
            meteringRectFromPreviewTap(0.25f, 0.25f, 180, 4000, 3000)
        )
    }

    @Test
    fun meteringRectOrientation270IsTheOtherInverse() {
        // (u,v) = (1-y, x) = (0.5, 0.75) → centre (2000, 2250) → left 1800, top 2100.
        assertEquals(
            ActiveArrayRect(1800, 2100, 400, 300),
            meteringRectFromPreviewTap(0.75f, 0.5f, 270, 4000, 3000)
        )
    }

    @Test
    fun meteringRectClampsInsideTheActiveArray() {
        // Corner taps must stay fully inside the array (Camera2 rejects out-of-bounds regions).
        assertEquals(
            ActiveArrayRect(0, 0, 400, 300),
            meteringRectFromPreviewTap(0f, 0f, 0, 4000, 3000)
        )
        assertEquals(
            ActiveArrayRect(3600, 2700, 400, 300),
            meteringRectFromPreviewTap(1f, 1f, 0, 4000, 3000)
        )
        // Out-of-range taps are clamped to the edge, not wrapped.
        assertEquals(
            ActiveArrayRect(0, 0, 400, 300),
            meteringRectFromPreviewTap(-2f, -2f, 0, 4000, 3000)
        )
    }

    @Test
    fun meteringRectNormalizesOrientationModulo360() {
        assertEquals(
            meteringRectFromPreviewTap(0.3f, 0.6f, 90, 4000, 3000),
            meteringRectFromPreviewTap(0.3f, 0.6f, 450, 4000, 3000)
        )
        assertEquals(
            meteringRectFromPreviewTap(0.3f, 0.6f, 270, 4000, 3000),
            meteringRectFromPreviewTap(0.3f, 0.6f, -90, 4000, 3000)
        )
    }
}
