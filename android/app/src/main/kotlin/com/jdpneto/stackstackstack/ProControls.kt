package com.jdpneto.stackstackstack

/**
 * Manual capture overrides (design §11, Pro mode). A null field means Auto for that control.
 * Mirrors iOS [ProControls] 1:1.
 */
data class ProControls(
    /** Override the look's burst length. */
    val frameCount: Int? = null,
    /** Manual sensor gain (ISO units). */
    val iso: Double? = null,
    /** Manual exposure duration (seconds). */
    val shutterSeconds: Double? = null,
    /** Manual lens position, 0 (near) … 1 (far). */
    val focus: Double? = null,
    /** Depth: sweep start lens position (0…1); null = full range. */
    val focusSweepNear: Double? = null,
    /** Depth: sweep end lens position (0…1); null = full range. */
    val focusSweepFar: Double? = null
) {
    companion object {
        /** All-auto controls (no overrides). */
        val auto = ProControls()
    }

    /**
     * True if any focus/exposure override is set (frame count doesn't affect AF/AE).
     * Tap-to-focus requires full-auto AF/AE, so it's gated off when this is true.
     * The Depth sweep range is NOT included: the sweep owns lens position at capture
     * regardless, and a tap should still meter exposure. (spec 2026-06-10 §6)
     */
    val hasManualFocusOrExposure: Boolean
        get() = focus != null || iso != null || shutterSeconds != null
}
