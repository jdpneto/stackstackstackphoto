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

// ---------------------------------------------------------------------------
// Burst / capture-request mapping helpers (pure; the service feeds them primitives)
// ---------------------------------------------------------------------------

/**
 * Focus-distance mapping (Camera2 LENS_FOCUS_DISTANCE, in diopters):
 *   iOS `lensPosition`: 0 = closest focus, 1 = infinity (far).
 *   Camera2: 0 = infinity, large positive = closest (diopters).
 *   Mapping: diopters = (1 - position) × minimumFocusDistance.
 * `minimumFocusDistance` (LENS_INFO_MINIMUM_FOCUS_DISTANCE — paradoxically named) is the LARGEST
 * diopter value, the closest-focus limit of the lens. This inversion is intentional and mirrors
 * exactly the opposite of the iOS API; documented so future porters don't accidentally re-invert.
 */
internal fun focusDiopters(position: Float, minimumFocusDistance: Float): Float =
    (1f - position.coerceIn(0f, 1f)) * minimumFocusDistance

/**
 * Manual ISO → SENSOR_SENSITIVITY, clamped to SENSOR_INFO_SENSITIVITY_RANGE (null bound = no
 * clamp on that side; legacy HALs may not report the range). Mirrors iOS clamping to
 * `activeFormat.minISO…maxISO`.
 */
internal fun clampedSensitivity(requested: Int, lower: Int?, upper: Int?): Int {
    var v = requested
    if (lower != null && v < lower) v = lower
    if (upper != null && v > upper) v = upper
    return v
}

/**
 * Manual shutter seconds → SENSOR_EXPOSURE_TIME nanoseconds, clamped to
 * SENSOR_INFO_EXPOSURE_TIME_RANGE (null bound = no clamp). Mirrors iOS clamping to
 * `activeFormat.minExposureDuration…maxExposureDuration`.
 */
internal fun clampedExposureNanos(seconds: Double, lowerNs: Long?, upperNs: Long?): Long {
    var ns = (seconds * 1_000_000_000.0).toLong()
    if (lowerNs != null && ns < lowerNs) ns = lowerNs
    if (upperNs != null && ns > upperNs) ns = upperNs
    return ns
}

/**
 * Burst completion predicate: every frame has been requested ([remaining] == 0) AND every
 * completed capture's image+result join has finished converting ([joined] ≥ [expectedJoins]).
 * Finishing on [remaining] alone dropped the last frame whenever its buffer was still in flight
 * (a 1-frame burst became NoFramesProduced); the join side is bounded by the service's drain
 * timeout, never waited on unconditionally.
 */
internal fun burstShouldFinish(remaining: Int, expectedJoins: Int, joined: Int): Boolean =
    remaining <= 0 && joined >= expectedJoins

// ---------------------------------------------------------------------------
// Tap-to-focus metering geometry
// ---------------------------------------------------------------------------

/** Axis-aligned rectangle in SENSOR_INFO_ACTIVE_ARRAY_SIZE pixel coordinates. */
internal data class ActiveArrayRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Metering region edge length as a fraction of each active-array dimension. */
internal const val METERING_REGION_FRACTION = 0.1f

/**
 * Map a normalized tap on the PREVIEW (0…1 in the displayed, rotated image) into a
 * MeteringRectangle-ready rect in SENSOR active-array PIXEL coordinates.
 *
 * Camera2 metering regions live in SENSOR_INFO_ACTIVE_ARRAY_SIZE pixel space ((0,0) = top-left
 * of the active array), NOT a normalized 0–1000 space — passing normalized values produced a
 * region pinned to the sensor's top-left corner.
 *
 * The preview shows the sensor image rotated CLOCKWISE by SENSOR_ORIENTATION (back camera,
 * natural-portrait display — the app's capture screen; display-rotation compensation is the
 * coordinator's concern). This function applies the INVERSE rotation to the tap point:
 *   0°: (u,v) = (x, y)      90°: (u,v) = (y, 1-x)
 * 180°: (u,v) = (1-x, 1-y) 270°: (u,v) = (1-y, x)
 * then centres a [sizeFraction]-sized rect on the sensor point, clamped inside the active array.
 */
internal fun meteringRectFromPreviewTap(
    x: Float,
    y: Float,
    sensorOrientation: Int,
    activeArrayWidth: Int,
    activeArrayHeight: Int,
    sizeFraction: Float = METERING_REGION_FRACTION
): ActiveArrayRect {
    val px = x.coerceIn(0f, 1f)
    val py = y.coerceIn(0f, 1f)
    val (u, v) = when (((sensorOrientation % 360) + 360) % 360) {
        90   -> py to (1f - px)
        180  -> (1f - px) to (1f - py)
        270  -> (1f - py) to px
        else -> px to py
    }
    val rw = (activeArrayWidth * sizeFraction).toInt().coerceIn(1, activeArrayWidth)
    val rh = (activeArrayHeight * sizeFraction).toInt().coerceIn(1, activeArrayHeight)
    val left = ((u * activeArrayWidth).toInt() - rw / 2).coerceIn(0, activeArrayWidth - rw)
    val top  = ((v * activeArrayHeight).toInt() - rh / 2).coerceIn(0, activeArrayHeight - rh)
    return ActiveArrayRect(left, top, rw, rh)
}
