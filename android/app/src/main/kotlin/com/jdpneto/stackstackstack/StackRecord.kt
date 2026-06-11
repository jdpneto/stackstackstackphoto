package com.jdpneto.stackstackstack

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

/**
 * One library entry: a stacked result plus its metadata.
 *
 * Serialization back-compat contract (mirrors iOS Codable semantics, pinned in the plan):
 * - Field names are IDENTICAL to iOS JSON keys (`resultFileName`, `frameCount`, `format`, `iso`,
 *   `shutterSeconds`, `createdAt`, `updatedAt`).
 * - Dates encode as Apple-epoch Double seconds (seconds since 2001-01-01 00:00:00 UTC). This is
 *   the persisted contract — cross-device library copy is not a feature, but the format IS the
 *   recorded contract so iOS ↔ Android JSON is byte-for-byte compatible.
 * - Missing optional keys decode as null (via `ignoreUnknownKeys + nullable with default`).
 * - `format` absent → null → [encoderFormat] returns JPEG (back-compat for pre-format records).
 */
@Serializable
data class StackRecord(
    /** Stable unique identifier; the file name prefix. */
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,

    /** Creation timestamp as Apple-epoch Double seconds (since 2001-01-01 UTC). */
    @SerialName("createdAt")
    val createdAtAppleEpoch: Double,

    /** Stable storage key of the [com.jdpneto.stackengine.StackMode] (e.g. "noiseReduction"). */
    val mode: String,

    val frameCount: Int,

    /** The file name of the result image (e.g. "<uuid>.jpg"). */
    val resultFileName: String,

    /**
     * Bumped whenever the result is re-rendered by an edit, so gallery cells reload.
     * Optional for back-compat: records written before this field existed decode as null.
     */
    @SerialName("updatedAt")
    val updatedAtAppleEpoch: Double? = null,

    /**
     * Encoded format of the result/original ("jpeg"/"heic"). Optional for back-compat: records
     * written before this field existed are JPEG. Stored as the raw string so the index stays a
     * stable contract (same rule as StackMode storage keys).
     */
    val format: String? = null,

    /**
     * First-frame ISO speed from the capture burst (null for legacy records). Optional for back-compat.
     */
    val iso: Double? = null,

    /**
     * First-frame exposure time in seconds from the capture burst (null for legacy records).
     * Optional for back-compat.
     */
    val shutterSeconds: Double? = null
) {
    /**
     * The record's encoder format; null/unknown = JPEG (every pre-format record is a JPEG).
     * Mirrors `var encoderFormat: ImageEncoder.Format` on the iOS side.
     */
    val encoderFormat: ImageEncoder.Format
        get() = ImageEncoder.Format.fromRawValue(format) ?: ImageEncoder.Format.JPEG

    /** Return the URL of the result file relative to [dir]. */
    fun resultURL(dir: java.io.File): java.io.File = java.io.File(dir, resultFileName)

    companion object {
        /**
         * Apple epoch offset in seconds: 2001-01-01 00:00:00 UTC − 1970-01-01 00:00:00 UTC.
         * Used to convert between POSIX seconds and Apple-epoch seconds (the JSON wire format).
         * Value: 978307200 seconds (31 years × 365.25d × 86400s, verified against Foundation).
         */
        const val APPLE_EPOCH_OFFSET_SECONDS = 978_307_200.0

        /** Convert POSIX seconds (since 1970) to Apple-epoch seconds (since 2001). */
        fun posixToAppleEpoch(posixSeconds: Double): Double = posixSeconds - APPLE_EPOCH_OFFSET_SECONDS

        /** Convert Apple-epoch seconds (since 2001) to POSIX seconds (since 1970). */
        fun appleEpochToPosix(appleEpochSeconds: Double): Double = appleEpochSeconds + APPLE_EPOCH_OFFSET_SECONDS
    }
}

/** kotlinx.serialization custom serializer for [UUID] (as String). */
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) =
        encoder.encodeString(value.toString().uppercase())

    override fun deserialize(decoder: Decoder): UUID =
        UUID.fromString(decoder.decodeString())
}
