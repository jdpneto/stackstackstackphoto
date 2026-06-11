package com.jdpneto.stackstackstack

/**
 * User-controllable burst length + window for the long-exposure looks (Smooth/Trails).
 * Photo count is hard-capped at [MAX_PHOTO_COUNT] and duration at 1…60 s.
 *
 * These looks use the STREAMING stack, whose peak memory is bounded by ~1–2 frames regardless
 * of count, so the cap is set by speed/UX rather than memory — [MAX_PHOTO_COUNT] (30) stays well
 * under a minute to process in Release. The constructor clamps, so out-of-range slider values
 * can never escape. (design 2026-06-07 §5)
 *
 * Mirrors iOS [BurstSettings] 1:1. Uses a regular class (not data class) so the primary
 * constructor enforces clamping the way Swift's custom init does.
 */
class BurstSettings(photoCount: Int, durationSeconds: Double) {

    val photoCount: Int = photoCount.coerceIn(2, MAX_PHOTO_COUNT)
    val durationSeconds: Double = durationSeconds.coerceIn(1.0, 60.0)

    companion object {
        /** Hard ceiling on the long-exposure (streaming) burst. */
        const val MAX_PHOTO_COUNT = 30

        /** Default seed for the long-exposure looks. */
        val default: BurstSettings = BurstSettings(photoCount = 10, durationSeconds = 2.0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BurstSettings) return false
        return photoCount == other.photoCount && durationSeconds == other.durationSeconds
    }

    override fun hashCode(): Int = 31 * photoCount + durationSeconds.hashCode()

    override fun toString(): String = "BurstSettings(photoCount=$photoCount, durationSeconds=$durationSeconds)"
}
