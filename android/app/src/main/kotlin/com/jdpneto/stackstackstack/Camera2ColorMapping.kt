package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.RawSensorFrame
import com.jdpneto.stackengine.Vec3

/**
 * Pure mapping helpers between Camera2 metadata and the engine's [RawSensorFrame] fields.
 *
 * Kept free of android.hardware.camera2 types so they are trivially unit-testable; the
 * [Camera2CaptureService] extracts the primitives from CaptureResult/CameraCharacteristics
 * and calls these.
 *
 * iOS parity (the spec for these semantics is `RawFrameConverter.swift`):
 * - wbGains are green-relative (green gain == 1) so the cast is neutralized without changing
 *   overall exposure — iOS derives them from DNG AsShotNeutral as (g/r, 1, g/b).
 * - blackLevel is a single scalar approximation; iOS averages the per-channel DNG BlackLevel
 *   array (true per-site subtraction is a later refinement).
 * - colorMatrix is the engine's column-major 3×3 applied AFTER white balance + demosaic
 *   (ColorPipeline: out = M * rgb, column-major storage [c0x,c0y,c0z, c1x,...]).
 */

/**
 * Maximum RAW sensor area we accept, in pixels (≈12.6 MP — fits a 4080×3072 12.5 MP mode).
 * iOS parity: PR #27 capped device RAW at 12 MP after 48 MP RAW blew the memory ceiling
 * (a single 48 MP 16-bit mosaic is ~96 MB; a burst of them jetsams the app).
 */
internal const val MAX_RAW_AREA_PIXELS: Long = 12_600_000L

/**
 * Pick the RAW output size: the largest whose area is ≤ [maxArea]; if every available size
 * is over the cap, the smallest available (better a too-big frame than no RAW at all).
 */
internal fun <T> chooseRawSize(
    sizes: List<T>,
    maxArea: Long = MAX_RAW_AREA_PIXELS,
    areaOf: (T) -> Long
): T? {
    if (sizes.isEmpty()) return null
    return sizes.filter { areaOf(it) <= maxArea }.maxByOrNull(areaOf)
        ?: sizes.minByOrNull(areaOf)
}

/**
 * Engine wbGains from Camera2 COLOR_CORRECTION_GAINS (RggbChannelVector: R, G_even, G_odd, B).
 * The two green channels are averaged into the engine's single green gain, then the triple is
 * normalized green-relative (G == 1) to mirror iOS `gains(fromAsShotNeutral:)` semantics:
 * neutralize the cast without changing overall exposure.
 * Non-positive components mean corrupt metadata → identity (no WB), matching iOS's guard.
 */
internal fun wbGainsFromRggb(r: Float, gEven: Float, gOdd: Float, b: Float): Vec3 {
    val g = (gEven + gOdd) / 2f
    if (r <= 0f || g <= 0f || b <= 0f) return Vec3(1f, 1f, 1f)
    return Vec3(r / g, 1f, b / g)
}

/**
 * Engine wbGains from Camera2 SENSOR_NEUTRAL_COLOR_POINT (the sensor RGB of a neutral patch —
 * the exact same quantity as DNG AsShotNeutral that iOS consumes). Gains are the reciprocal of
 * the neutral point, normalized green-relative (G == 1): (g/r, 1, g/b) — the literal iOS formula.
 */
internal fun wbGainsFromNeutralPoint(nR: Float, nG: Float, nB: Float): Vec3 {
    if (nR <= 0f || nG <= 0f || nB <= 0f) return Vec3(1f, 1f, 1f)
    return Vec3(nG / nR, 1f, nG / nB)
}

/**
 * Convert a row-major 3×3 (Camera2 ColorSpaceTransform element order: m[row*3 + col]) into the
 * engine's column-major flat array (RawSensorFrame: m[col*3 + row]) — a transpose of storage,
 * NOT of the matrix. ColorPipeline computes out = M * v with
 * out.x = m[0]*v.x + m[3]*v.y + m[6]*v.z, i.e. m[0],m[3],m[6] must be row 0 of M.
 * Anything but 9 elements → identity (defensive; mirrors the engine's identity default).
 */
internal fun engineColorMatrixFromRowMajor(rowMajor: FloatArray): FloatArray {
    if (rowMajor.size != 9) return RawSensorFrame.IDENTITY_3X3.copyOf()
    val out = FloatArray(9)
    for (row in 0 until 3) {
        for (col in 0 until 3) {
            out[col * 3 + row] = rowMajor[row * 3 + col]
        }
    }
    return out
}

/**
 * Engine scalar blackLevel from Camera2 SENSOR_BLACK_LEVEL_PATTERN's four CFA offsets.
 * iOS averages the per-channel DNG BlackLevel array into one scalar (RawFrameConverter:
 * "a single scalar approximation — true per-site subtraction is a later refinement");
 * averaging the 2×2 pattern offsets is the same approximation.
 */
internal fun blackLevelFromPattern(offsets: FloatArray): Float {
    if (offsets.isEmpty()) return 64f
    return offsets.sum() / offsets.size
}
