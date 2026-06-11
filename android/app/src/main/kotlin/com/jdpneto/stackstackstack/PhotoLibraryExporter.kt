package com.jdpneto.stackstackstack

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes an encoded image into the system media store using the app's own media collection
 * (no runtime permission needed for owned media on API 29+).
 *
 * Mirrors iOS [PhotoLibraryExporter]: same fire-and-forget contract, non-blocking note, same
 * add-only semantics. On Android 29+ the app owns the MediaStore entry so no storage
 * permission is needed — simpler than iOS. (spec §5)
 */
object PhotoLibraryExporter {

    sealed class ExportError(message: String) : Exception(message) {
        object WriteFailed : ExportError("Failed to insert image into MediaStore.")
    }

    /**
     * Insert [data] into the device's picture library as a "Stack Stack Stack" photo.
     *
     * @param context Application context.
     * @param data    Encoded image bytes (JPEG or HEIC).
     * @param format  Encoder format — determines the MIME type and file extension.
     * @throws [ExportError] on failure (non-blocking: callers treat failures as informational).
     */
    @Throws(ExportError::class)
    suspend fun export(context: Context, data: ByteArray, format: ImageEncoder.Format) =
        withContext(Dispatchers.IO) {
            val mimeType = when (format) {
                ImageEncoder.Format.JPEG -> "image/jpeg"
                ImageEncoder.Format.HEIC -> "image/heic"
            }
            val filename = "SSS_${System.currentTimeMillis()}.${format.fileExtension}"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Stack Stack Stack")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw ExportError.WriteFailed
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(data)
                } ?: throw ExportError.WriteFailed
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw ExportError.WriteFailed
            }
        }
}
