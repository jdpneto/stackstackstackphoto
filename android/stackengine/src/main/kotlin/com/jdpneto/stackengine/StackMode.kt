package com.jdpneto.stackengine

/**
 * The processing look applied to an aligned burst (design §13). Every look shares the
 * align step and differs only in the per-pixel reducer.
 *
 * [storageKey] is the stable, contract-guaranteed storage key (renaming a case won't silently
 * change persisted keys — mirrors the Swift `String` rawValue). DO NOT change storageKey values.
 */
enum class StackMode(
    /** Stable persisted storage key — must NEVER change. Mirrors Swift's `rawValue`. */
    val storageKey: String
) {
    NOISE_REDUCTION("noiseReduction"),     // robust (sigma-clipped) mean — clean detail
    SMOOTH_MOTION("smoothMotion"),         // plain temporal mean — silky water / clouds
    LIGHT_TRAILS("lightTrails"),           // per-channel lighten (max) — light streaks
    LOW_LIGHT_BOOST("lowLightBoost"),      // robust mean + exposure gain — brighter night shot
    DEPTH_OF_FIELD("depthOfField");        // all-in-focus focus sweep — stacked by FocusStacker, not Pipeline.reduce

    /**
     * The looks that capture a continuous burst over a window and use the streaming reducer
     * (vs. the static fast-burst looks). (design 2026-06-07 §3)
     */
    val isLongExposure: Boolean
        get() = when (this) {
            SMOOTH_MOTION, LIGHT_TRAILS -> true
            NOISE_REDUCTION, LOW_LIGHT_BOOST, DEPTH_OF_FIELD -> false
        }

    /**
     * Looks whose result can be re-blended against the aligned reference (α look-strength).
     * Depth has no single-reference semantics — its frames differ by focus, not by time.
     */
    val supportsBlendReference: Boolean
        get() = this != DEPTH_OF_FIELD
}
