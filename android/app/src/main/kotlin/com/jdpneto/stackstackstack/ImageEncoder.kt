package com.jdpneto.stackstackstack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Thrown when encoding fails. Mirrors [ImageEncoderError] in the iOS app. */
sealed class ImageEncoderError(msg: String) : Exception(msg) {
    object ContextFailed    : ImageEncoderError("Failed to create Bitmap context")
    object DestinationFailed: ImageEncoderError("Failed to create output stream")
    object FinalizeFailed   : ImageEncoderError("Failed to compress image")
}

/**
 * Encodes/decodes images to JPEG or HEIC (API 30+). Mirrors the iOS [ImageEncoder] exactly:
 * same format raw-values, same EXIF fields, same Software tag, same sRGB colour-space guarantee.
 */
object ImageEncoder {

    /**
     * `String`-backed: raw values are persisted (AppSettings + StackRecord.format) — renaming a
     * case silently breaks stored preferences and library records. Mirrors Swift's `rawValue`.
     */
    enum class Format(
        /** Stable persisted storage key — must NEVER change. Mirrors Swift's `rawValue`. */
        val rawValue: String
    ) {
        JPEG("jpeg"),
        HEIC("heic");

        /** The file extension library files use for this format. */
        val fileExtension: String
            get() = when (this) {
                JPEG -> "jpg"
                HEIC -> "heic"
            }

        /**
         * The [Bitmap.CompressFormat] corresponding to this encoder format.
         * HEIC is accessed via reflection because [Bitmap.CompressFormat.HEIC] is not present
         * in the platform SDK stub jar (it exists on-device from API 30+ but isn't exposed
         * through the compile-time stubs). On runtimes where HEIC is unavailable, compress()
         * will return false and [ImageEncoderError.FinalizeFailed] is thrown — the coordinator
         * falls back to JPEG (spec §3 honesty rule: HEIC is OEM-defined).
         */
        val bitmapCompressFormat: Bitmap.CompressFormat
            get() = when (this) {
                JPEG -> Bitmap.CompressFormat.JPEG
                HEIC -> heicCompressFormat ?: Bitmap.CompressFormat.JPEG  // fallback in Robolectric
            }

        companion object {
            /** Parse from a persisted raw value; null for unknown/corrupt. */
            fun fromRawValue(value: String?): Format? =
                entries.firstOrNull { it.rawValue == value }

            /**
             * [Bitmap.CompressFormat.HEIC] accessed via reflection (not in compile-time stubs).
             * Null when HEIC encoding is unavailable (e.g. Robolectric, older devices).
             */
            @Suppress("UNCHECKED_CAST")
            val heicCompressFormat: Bitmap.CompressFormat? by lazy {
                try {
                    val clazz = Bitmap.CompressFormat::class.java
                    clazz.getField("HEIC").get(null) as? Bitmap.CompressFormat
                } catch (e: Exception) { null }
            }
        }
    }

    /**
     * EXIF metadata written into the encoded result. All fields are optional; null = omit.
     * Mirrors [ExifMetadata] in the iOS app.
     */
    data class ExifMetadata(
        /** ISO speed (maps to [ExifInterface.TAG_ISO_SPEED_RATINGS]). */
        val iso: Double? = null,
        /** Exposure time in seconds (maps to [ExifInterface.TAG_EXPOSURE_TIME]). */
        val shutterSeconds: Double? = null,
        /** Capture timestamp (maps to [ExifInterface.TAG_DATETIME_ORIGINAL]).
         *  Stored as POSIX seconds (seconds since 1970-01-01 UTC). */
        val capturedAtPosix: Double? = null
    )

    /**
     * Encode interleaved sRGB RGBA8 bytes into JPEG/HEIC data.
     *
     * @param rgba8   Row-major RGBA bytes, length = width × height × 4.
     * @param width   Image width in pixels.
     * @param height  Image height in pixels.
     * @param format  Target format.
     * @param quality Compression quality 0.0–1.0 (maps to 0–100 for [Bitmap.compress]).
     * @param exif    Optional EXIF metadata to embed. Null omits the EXIF block.
     * @throws ImageEncoderError on any failure.
     */
    @Throws(ImageEncoderError::class)
    fun encode(
        rgba8: ByteArray,
        width: Int,
        height: Int,
        format: Format,
        quality: Double,
        exif: ExifMetadata? = null
    ): ByteArray {
        // Guard: buffer size must match dimensions exactly (OOB protection mirrors iOS).
        if (width <= 0 || height <= 0 || rgba8.size != width * height * 4) {
            throw ImageEncoderError.ContextFailed
        }

        // Build an ARGB_8888 Bitmap in sRGB from the RGBA bytes.
        // RGBA8 → ARGB_8888: Android's Bitmap stores ARGB in native byte order via Color.argb();
        // the engine's rgba8 has R at [0], G at [1], B at [2], A at [3] per pixel.
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888,
                /* hasAlpha */ false, ColorSpace.get(ColorSpace.Named.SRGB))
        } catch (e: Exception) {
            throw ImageEncoderError.ContextFailed
        }

        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val base = i * 4
            val r = rgba8[base].toInt() and 0xFF
            val g = rgba8[base + 1].toInt() and 0xFF
            val b = rgba8[base + 2].toInt() and 0xFF
            // Alpha ignored (engine always writes fully-opaque RGBA).
            pixels[i] = Color.rgb(r, g, b)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // Compress to the target format.
        val out = ByteArrayOutputStream()
        val q = (quality * 100).toInt().coerceIn(0, 100)
        val ok = bitmap.compress(format.bitmapCompressFormat, q, out)
        bitmap.recycle()
        if (!ok) throw ImageEncoderError.FinalizeFailed

        val compressed = out.toByteArray()

        // Embed EXIF + Software tag via ExifInterface (mutates the byte array in place).
        // Always write the Software tag; write the Exif block only when exif != null.
        return embedExif(compressed, format, exif)
    }

    /**
     * Embed EXIF metadata into an already-compressed JPEG/HEIC byte array via a temp file.
     * [ExifInterface.saveAttributes] requires file-backed mode to actually persist; stream-backed
     * mode is read-only. Returns the byte array with embedded metadata, or [data] unchanged if
     * EXIF embedding fails (EXIF failure must never lose the compressed image).
     */
    private fun embedExif(data: ByteArray, format: Format, exif: ExifMetadata?): ByteArray {
        return try {
            val tmp = java.io.File.createTempFile("sss_exif", ".${format.fileExtension}")
            try {
                tmp.writeBytes(data)
                val ei = ExifInterface(tmp.absolutePath)

                // Software tag — always written (mirrors iOS "Stack Stack Stack").
                ei.setAttribute(ExifInterface.TAG_SOFTWARE, "Stack Stack Stack")

                if (exif != null) {
                    exif.iso?.let { iso ->
                        ei.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, iso.toInt().toString())
                    }
                    exif.shutterSeconds?.let { shutter ->
                        // EXIF ExposureTime as a rational string "num/denom".
                        // For small shutter speeds, use a fraction; for >= 1s, use whole seconds.
                        ei.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, doubleToExifRational(shutter))
                    }
                    exif.capturedAtPosix?.let { posix ->
                        // EXIF DateTimeOriginal format: "yyyy:MM:dd HH:mm:ss" in the local timezone.
                        // Matches the iOS FIXED en_US_POSIX locale formatting for the same field.
                        val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                        sdf.timeZone = java.util.TimeZone.getDefault()
                        val date = java.util.Date((posix * 1000).toLong())
                        ei.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, sdf.format(date))
                    }
                }

                ei.saveAttributes()
                tmp.readBytes()
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            // EXIF embedding failure must not lose the compressed image — return without EXIF.
            data
        }
    }

    /**
     * Convert a shutter-speed Double to an EXIF rational string "num/denom".
     * E.g. 0.02 → "1/50", 1.5 → "3/2".
     */
    private fun doubleToExifRational(value: Double): String {
        if (value <= 0) return "0/1"
        if (value >= 1.0) {
            val num = (value * 100).toLong()
            val den = 100L
            val g = gcd(num, den)
            return "${num / g}/${den / g}"
        }
        // Find a denominator with 1 as numerator for fast shutter speeds.
        val denom = (1.0 / value).toLong().coerceAtLeast(1)
        return "1/$denom"
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}
