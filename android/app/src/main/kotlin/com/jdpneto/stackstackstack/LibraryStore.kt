package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.ImageAdjustments
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Minimal file-backed library: results (JPEG or HEIC) + a JSON index in [root].
 *
 * All writes are atomic (write-to-temp + rename) and `loadAll` self-heals (drops records whose
 * file vanished) and preserves a corrupt index rather than letting the next save overwrite it.
 *
 * Threading contract (mirrors iOS):
 * - **Reads** (`loadAll`, `originalData`, `referenceData`, `adjustments`, `storageUsedBytes`)
 *   are safe to call off the main thread.
 * - **Writes** (`save`, `applyEdit`, `delete`, `deleteAll`, `reconcileOrphans`) must stay
 *   on the main thread (or a single serialized thread): they are read-modify-write on
 *   `index.json`, so two concurrent writers could lose a record.
 */
class LibraryStore(val root: File = defaultRoot()) {

    private val indexFile = File(root, "index.json")

    /** kotlinx.serialization Json instance — ignores unknown keys for back-compat. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true   // ensure nullable fields are always written (so re-reads are stable)
    }

    init {
        root.mkdirs()
    }

    companion object {
        /**
         * Default root: `<filesDir>/Stacks`. Callers on the app side pass
         * `context.filesDir` at construction; tests inject a temp dir.
         */
        fun defaultRoot(filesDir: File = File(System.getProperty("user.home")!!)): File =
            File(filesDir, "Stacks")
    }

    // MARK: - Saved-stack handle

    data class SavedStack(val id: UUID, val resultFile: File)

    // MARK: - Write operations

    /**
     * Save a stacked result. Writes the result + original + optional reference first, then
     * updates the index last — so a failure never leaves an index entry without its file.
     *
     * @param result          Encoded image bytes (JPEG or HEIC).
     * @param reference       Aligned reference frame for blend-strength editing; null for depth
     *                        and any look that produced no reference (slider hidden when null).
     *                        A failed reference write must not lose the shot — the record simply
     *                        has no blend. (spec §6)
     * @param format          Encoder format.
     * @param mode            [com.jdpneto.stackengine.StackMode.storageKey] of the look.
     * @param frameCount      Number of burst frames stacked.
     * @param iso             First-frame ISO speed; null for legacy/fallback path.
     * @param shutterSeconds  First-frame exposure time; null for legacy/fallback path.
     */
    @Throws(Exception::class)
    fun save(
        result: ByteArray,
        reference: ByteArray? = null,
        format: ImageEncoder.Format,
        mode: String,
        frameCount: Int,
        iso: Double? = null,
        shutterSeconds: Double? = null
    ): SavedStack {
        val id = UUID.randomUUID()
        val fileName = "${id.toString().uppercase()}.${format.fileExtension}"
        val resultFile = File(root, fileName)
        val nowPosix = System.currentTimeMillis() / 1000.0
        val nowApple = StackRecord.posixToAppleEpoch(nowPosix)

        // Write files BEFORE updating the index (atomically) — failure leaves no dangling entry.
        resultFile.writeAtomically(result)
        originalFile(id, format).writeAtomically(result)       // immutable original
        if (reference != null) {
            runCatching { referenceFile(id, format).writeAtomically(reference) }
            // Swallow failures — a missing reference just hides the blend slider (spec §6).
        }

        val records = loadRaw().toMutableList()
        records.add(0, StackRecord(
            id                  = id,
            createdAtAppleEpoch = nowApple,
            mode                = mode,
            frameCount          = frameCount,
            resultFileName      = fileName,
            updatedAtAppleEpoch = nowApple,
            format              = format.rawValue,
            iso                 = iso,
            shutterSeconds      = shutterSeconds
        ))
        persist(records)
        return SavedStack(id = id, resultFile = resultFile)
    }

    /**
     * All persisted records for DISPLAY: self-heals by dropping records whose result file is gone.
     * Mutations (`save`/`delete`/`applyEdit`) use `loadRaw` instead, so a transient read-time
     * miss can never feed back into `persist` and permanently drop a still-recoverable record.
     */
    @Throws(Exception::class)
    fun loadAll(): List<StackRecord> =
        loadRaw().filter { File(root, it.resultFileName).exists() }

    /**
     * The persisted record for an id (null if absent).
     */
    fun record(id: UUID): StackRecord? =
        loadRaw().firstOrNull { it.id == id }

    /**
     * Remove a stack's record and all of its files (result, original, reference, edit sidecar).
     */
    @Throws(Exception::class)
    fun delete(id: UUID) {
        val records = loadRaw().toMutableList()
        val format = records.firstOrNull { it.id == id }?.encoderFormat
        val toDelete = mutableListOf(editsFile(id))
        // Unknown record → sweep both extensions (mirrors iOS).
        val formats: List<ImageEncoder.Format> = format?.let { listOf(it) }
            ?: listOf(ImageEncoder.Format.JPEG, ImageEncoder.Format.HEIC)
        for (f in formats) {
            toDelete += resultFile(id, f)
            toDelete += originalFile(id, f)
            toDelete += referenceFile(id, f)
        }
        toDelete.forEach { runCatching { it.delete() } }
        records.removeAll { it.id == id }
        persist(records)
    }

    /**
     * Delete every stack (Settings ▸ Storage). Main-thread only, like all writes.
     */
    @Throws(Exception::class)
    fun deleteAll() {
        val records = loadRaw()
        for (rec in records) {
            val toDelete = mutableListOf(editsFile(rec.id))
            toDelete += resultFile(rec.id, rec.encoderFormat)
            toDelete += originalFile(rec.id, rec.encoderFormat)
            toDelete += referenceFile(rec.id, rec.encoderFormat)
            toDelete.forEach { runCatching { it.delete() } }
        }
        persist(emptyList())
    }

    /**
     * Total bytes of all library files (results, originals, sidecars, index).
     * Stateless file I/O — safe to call off the main thread.
     */
    fun storageUsedBytes(): Long =
        root.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Delete `<uuid>.*` files that have no matching index record (orphans from failed/partial saves).
     * Main-thread only: must not interleave with `save`.
     */
    fun reconcileOrphans() {
        val ids = loadRaw().map { it.id.toString().uppercase() }.toSet()
        val files = root.listFiles() ?: return
        for (f in files) {
            val name = f.name
            // Only consider per-stack files; index.json and index.<epoch>.corrupt end in .json/.corrupt.
            if (!name.endsWith(".jpg") && !name.endsWith(".heic") && !name.endsWith(".json")) continue
            if (name == "index.json") continue
            val uuidPart = name.take(36)
            // Non-UUID-named files (e.g. index.corrupt backups) are never swept.
            try { UUID.fromString(uuidPart) } catch (e: Exception) { continue }
            if (!ids.contains(uuidPart.uppercase())) {
                runCatching { f.delete() }
            }
        }
    }

    // MARK: - Read operations (off-thread-safe)

    /**
     * The immutable original stacked image (the record's own format), used as the editing source.
     */
    fun originalData(id: UUID): ByteArray? {
        val rec = record(id) ?: return null
        return runCatching { originalFile(id, rec.encoderFormat).readBytes() }.getOrNull()
    }

    /**
     * The aligned reference frame (the blend-strength lerp's second endpoint), or null for depth
     * records, legacy records, or any record whose ref file is absent or corrupt.
     */
    fun referenceData(id: UUID): ByteArray? {
        val rec = record(id) ?: return null
        return runCatching { referenceFile(id, rec.encoderFormat).readBytes() }.getOrNull()
    }

    /**
     * The persisted adjustments for a record (identity if none / unreadable).
     * If the decoded adjustments carry a blendStrength < 1 but no reference file exists, the α is
     * normalized to 1: a persisted α without its reference would silently re-bake at a different
     * look on the next save — normalize instead. (Fix 4, spec §3)
     */
    fun adjustments(id: UUID): ImageAdjustments {
        val data = runCatching { editsFile(id).readBytes() }.getOrNull()
            ?: return ImageAdjustments.identity
        val dto = runCatching {
            json.decodeFromString<ImageAdjustmentsDto>(String(data))
        }.getOrNull() ?: return ImageAdjustments.identity
        var adj = dto.toImageAdjustments()
        if (adj.blendStrength < 1f) {
            val rec = record(id)
            if (rec != null) {
                val refFile = referenceFile(id, rec.encoderFormat)
                if (!refFile.exists()) {
                    adj.blendStrength = 1f
                }
            }
        }
        return adj
    }

    /**
     * Overwrite the displayed result with a rendered image (in the record's own format), persist
     * the adjustments, and bump `updatedAt` so gallery cells reload.
     */
    @Throws(Exception::class)
    fun applyEdit(id: UUID, adjustments: ImageAdjustments, rendered: ByteArray) {
        val records = loadRaw().toMutableList()
        val i = records.indexOfFirst { it.id == id }
        if (i < 0) throw LibraryError.RecordMissing
        val rec = records[i]
        resultFile(id, rec.encoderFormat).writeAtomically(rendered)
        val dtoJson = json.encodeToString(ImageAdjustmentsDto.from(adjustments))
        editsFile(id).writeAtomically(dtoJson.toByteArray())
        val nowApple = StackRecord.posixToAppleEpoch(System.currentTimeMillis() / 1000.0)
        records[i] = rec.copy(updatedAtAppleEpoch = nowApple)
        persist(records)
    }

    // MARK: - File paths

    fun resultURL(record: StackRecord): File = File(root, record.resultFileName)

    private fun resultFile(id: UUID, format: ImageEncoder.Format): File =
        File(root, "${id.toString().uppercase()}.${format.fileExtension}")

    private fun originalFile(id: UUID, format: ImageEncoder.Format): File =
        File(root, "${id.toString().uppercase()}.orig.${format.fileExtension}")

    /** The aligned reference frame used for blend-strength editing (`<uuid>.ref.<ext>`). */
    private fun referenceFile(id: UUID, format: ImageEncoder.Format): File =
        File(root, "${id.toString().uppercase()}.ref.${format.fileExtension}")

    private fun editsFile(id: UUID): File =
        File(root, "${id.toString().uppercase()}.edits.json")

    // MARK: - Index persistence

    /**
     * The raw decoded index, unfiltered. On a corrupt/torn index, the bytes are preserved aside
     * (timestamped) and an empty list is returned — rather than letting the next save overwrite
     * and permanently drop every prior record.
     */
    private fun loadRaw(): List<StackRecord> {
        if (!indexFile.exists()) return emptyList()
        val bytes = runCatching { indexFile.readBytes() }.getOrNull() ?: return emptyList()
        return try {
            json.decodeFromString<List<StackRecord>>(String(bytes))
        } catch (e: Exception) {
            val asideName = "index.${System.currentTimeMillis() / 1000}.corrupt"
            runCatching { indexFile.renameTo(File(root, asideName)) }
            emptyList()
        }
    }

    private fun persist(records: List<StackRecord>) {
        val text = json.encodeToString(records)
        indexFile.writeAtomically(text.toByteArray())
    }
}

// MARK: - Extension helpers

/**
 * Atomic write: write to a temp file then rename over the target.
 * Prevents truncated writes from being observable. Mirrors iOS `.atomic` write option.
 */
private fun File.writeAtomically(bytes: ByteArray) {
    val tmp = File(parentFile ?: File("."), "${name}.tmp")
    tmp.writeBytes(bytes)
    tmp.renameTo(this)
}
