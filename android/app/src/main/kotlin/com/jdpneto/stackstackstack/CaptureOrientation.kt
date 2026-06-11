package com.jdpneto.stackstackstack

import android.view.Surface

/**
 * Maps the physical device orientation at shutter time to the clockwise quarter-turns needed
 * to make the back camera's native-landscape stacked result upright.
 *
 * Device-verified: portrait shows upright, and the two landscape cases are *swapped* relative
 * to the naive guess — [Surface.ROTATION_90] / [Surface.ROTATION_270] correspond to the
 * opposite video orientations (the classic landscape inversion), so a landscape shot otherwise
 * saves upside-down.
 *
 * Android uses [Surface.ROTATION_*] constants (0=portrait, 90=landscape-left, etc.).
 * Mirrors iOS [CaptureOrientation.quarterTurns(for:)] with the same mapping table.
 */
object CaptureOrientation {
    /**
     * Map a [Surface] rotation constant to the clockwise quarter-turns needed to upright the
     * result image.
     *
     * @param displayRotation One of [Surface.ROTATION_0], [Surface.ROTATION_90],
     *   [Surface.ROTATION_180], [Surface.ROTATION_270].
     */
    fun quarterTurns(displayRotation: Int): Int = when (displayRotation) {
        Surface.ROTATION_0   -> 1   // portrait
        Surface.ROTATION_180 -> 3   // portrait upside-down
        Surface.ROTATION_90  -> 0   // landscape (left home button) — maps to ROTATION_90
        Surface.ROTATION_270 -> 2   // landscape (right home button) — maps to ROTATION_270
        else                 -> 1   // unknown → assume portrait
    }
}
