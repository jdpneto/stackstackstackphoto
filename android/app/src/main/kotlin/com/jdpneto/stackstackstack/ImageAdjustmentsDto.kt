package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.CropAspect
import com.jdpneto.stackengine.ImageAdjustments
import kotlinx.serialization.Serializable

/**
 * Serializable DTO for [ImageAdjustments] sidecars (`.edits.json`).
 *
 * Back-compat contract (pinned in the plan, mirrors iOS `init(from:)` exactly):
 * - All fields nullable with defaults so missing keys decode as null → identity value.
 * - `blendStrength` absent → decoded as null → defaults to 1 (unchanged behaviour).
 * - `quarterTurns` normalized mod 4 on decode (mirrors Swift `didSet`).
 * - `cropAspect` unknown string → ORIGINAL (forward-compat safety valve).
 * - Field names IDENTICAL to iOS CodingKeys (`exposureEV`, `contrast`, `quarterTurns`, etc.).
 */
@Serializable
data class ImageAdjustmentsDto(
    val exposureEV: Float? = null,
    val contrast: Float? = null,
    val temperature: Float? = null,
    val tint: Float? = null,
    val shadows: Float? = null,
    val highlights: Float? = null,
    val straightenDegrees: Float? = null,
    /** Stored as the [CropAspect.storageKey] string (e.g. "square"). */
    val cropAspect: String? = null,
    val quarterTurns: Int? = null,
    /**
     * Look-strength α. Absent from legacy sidecars → null → decoded as 1 (unchanged behaviour).
     * Clamped 0..1 on decode, exactly like the iOS `min(max(rawBlend, 0), 1)`. (spec 2026-06-11 §3)
     */
    val blendStrength: Float? = null
) {
    /**
     * Convert the DTO to a live [ImageAdjustments] applying the same back-compat rules as
     * the iOS `init(from:)` custom decoder.
     */
    fun toImageAdjustments(): ImageAdjustments {
        val rawTurns = quarterTurns ?: 0
        val normalizedTurns = ((rawTurns % 4) + 4) % 4
        val rawBlend = blendStrength ?: 1f
        val clampedBlend = rawBlend.coerceIn(0f, 1f)
        val crop = cropAspect?.let { key ->
            CropAspect.entries.firstOrNull { it.storageKey == key } ?: CropAspect.ORIGINAL
        } ?: CropAspect.ORIGINAL
        return ImageAdjustments(
            exposureEV        = exposureEV ?: 0f,
            contrast          = contrast ?: 0f,
            temperature       = temperature ?: 0f,
            tint              = tint ?: 0f,
            shadows           = shadows ?: 0f,
            highlights        = highlights ?: 0f,
            straightenDegrees = straightenDegrees ?: 0f,
            cropAspect        = crop,
            quarterTurns      = normalizedTurns,
            blendStrength     = clampedBlend
        )
    }

    companion object {
        /** Convert a live [ImageAdjustments] to a DTO for persistence. */
        fun from(adj: ImageAdjustments): ImageAdjustmentsDto = ImageAdjustmentsDto(
            exposureEV        = adj.exposureEV,
            contrast          = adj.contrast,
            temperature       = adj.temperature,
            tint              = adj.tint,
            shadows           = adj.shadows,
            highlights        = adj.highlights,
            straightenDegrees = adj.straightenDegrees,
            cropAspect        = adj.cropAspect.storageKey,
            quarterTurns      = adj.quarterTurns,
            blendStrength     = adj.blendStrength
        )
    }
}
