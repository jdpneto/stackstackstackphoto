package com.jdpneto.stackengine

/** Center-crop aspect presets for the editor. */
enum class CropAspect(
    /** Stable persisted storage key — must NEVER change. Mirrors Swift's `rawValue`. */
    val storageKey: String
) {
    ORIGINAL("original"),
    SQUARE("square"),
    FOUR_THREE("fourThree"),
    SIXTEEN_NINE("sixteenNine");

    /** width:height ratio, or null for the original (no crop). */
    val ratio: Float?
        get() = when (this) {
            ORIGINAL     -> null
            SQUARE       -> 1f
            FOUR_THREE   -> 4.0f / 3.0f
            SIXTEEN_NINE -> 16.0f / 9.0f
        }
}

/**
 * Non-destructive global adjustments applied to a developed result (design §14).
 *
 * PORTING NOTE — serialization: the Swift type is Codable with back-compat decoding (missing keys
 * default: quarterTurns → 0 normalized, blendStrength → 1 clamped, cropAspect → original). On
 * Android that mapping lives in the APP's JSON layer (sidecar decode), not here; this engine type
 * carries the same defaults + clamping/normalization so a decoder that fills missing fields with
 * the constructor defaults reproduces the Swift semantics exactly.
 *
 * Mutable class (not a data class): [quarterTurns] re-normalizes to 0…3 on direct mutation
 * (mirrors the Swift `didSet`), which a data-class constructor property cannot express.
 */
class ImageAdjustments(
    var exposureEV: Float = 0f,        // stops; linear ×2^EV
    var contrast: Float = 0f,          // -1...1 around an 18% linear pivot
    var temperature: Float = 0f,       // -1...1, warm (+) / cool (-)
    var tint: Float = 0f,              // -1...1, magenta (+) / green (-)
    var shadows: Float = 0f,           // -1...1, lift (+) / lower (-) dark tones
    var highlights: Float = 0f,        // -1...1, lift (+) / lower (-) bright tones
    var straightenDegrees: Float = 0f, // rotation about the centre, degrees
    var cropAspect: CropAspect = CropAspect.ORIGINAL,   // centre-crop aspect
    quarterTurns: Int = 0,
    blendStrength: Float = 1f
) {
    /** 90°×k clockwise rotation, kept in 0…3 (gallery rotate). Stays canonical on direct mutation (e.g. += 1). */
    var quarterTurns: Int = ((quarterTurns % 4) + 4) % 4
        set(value) { field = ((value % 4) + 4) % 4 }

    /**
     * Look strength α: 1 = full look (today's result), 0 = the aligned reference frame; lerp
     * applied in linear light before geometry/tonal so the reference needs no separate geometry
     * pass. Missing from legacy sidecars → decoded as 1 (unchanged behaviour). (spec 2026-06-11 §3)
     * Clamped to 0…1 at construction (mirrors the Swift init/decode clamp).
     */
    var blendStrength: Float = blendStrength.coerceIn(0f, 1f)   // 0…1; 1 = identity (no blend)

    companion object {
        /** A fresh identity instance (value-semantics equivalent of Swift's `.identity`). */
        val identity: ImageAdjustments get() = ImageAdjustments()
    }

    val isIdentity: Boolean get() = this == identity

    /** True when any per-pixel tonal control is non-default (lets geometry-only edits skip the tonal pass). */
    val hasTonalAdjustments: Boolean
        get() = exposureEV != 0f || contrast != 0f || temperature != 0f || tint != 0f ||
                shadows != 0f || highlights != 0f

    /** True when blendStrength is set below 1 — i.e. the lerp-toward-reference pass is needed. */
    val hasBlend: Boolean get() = blendStrength < 1f

    /** Independent copy (value-semantics equivalent of Swift struct assignment). */
    fun copy(): ImageAdjustments = ImageAdjustments(
        exposureEV, contrast, temperature, tint, shadows, highlights,
        straightenDegrees, cropAspect, quarterTurns, blendStrength
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageAdjustments) return false
        return exposureEV == other.exposureEV &&
            contrast == other.contrast &&
            temperature == other.temperature &&
            tint == other.tint &&
            shadows == other.shadows &&
            highlights == other.highlights &&
            straightenDegrees == other.straightenDegrees &&
            cropAspect == other.cropAspect &&
            quarterTurns == other.quarterTurns &&
            blendStrength == other.blendStrength
    }

    override fun hashCode(): Int {
        var result = exposureEV.hashCode()
        result = 31 * result + contrast.hashCode()
        result = 31 * result + temperature.hashCode()
        result = 31 * result + tint.hashCode()
        result = 31 * result + shadows.hashCode()
        result = 31 * result + highlights.hashCode()
        result = 31 * result + straightenDegrees.hashCode()
        result = 31 * result + cropAspect.hashCode()
        result = 31 * result + quarterTurns
        result = 31 * result + blendStrength.hashCode()
        return result
    }

    override fun toString(): String =
        "ImageAdjustments(exposureEV=$exposureEV, contrast=$contrast, temperature=$temperature, " +
        "tint=$tint, shadows=$shadows, highlights=$highlights, straightenDegrees=$straightenDegrees, " +
        "cropAspect=$cropAspect, quarterTurns=$quarterTurns, blendStrength=$blendStrength)"
}
