package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.ImageAdjustments
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Mirrors iOS [LibraryStoreTests] 1:1 — same assertions, same tolerances, same back-compat
 * scenarios (legacy format/iso/shutterSeconds fields, stale-α normalization).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryStoreTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("sss_test_${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun makeStore() = LibraryStore(root = tempDir)

    // MARK: - Basic round-trip

    @Test
    fun testSaveAndLoadRoundTrip() {
        val store = makeStore()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val saved = store.save(result = jpeg, format = ImageEncoder.Format.JPEG,
                               mode = "noiseReduction", frameCount = 8)
        val all = store.loadAll()
        assertEquals(1, all.size)
        assertEquals(saved.id, all[0].id)
        assertEquals(8, all[0].frameCount)
        assertTrue(saved.resultFile.exists())
    }

    @Test
    fun testKeepsOriginalAndAppliesEdit() {
        val store = makeStore()
        val original = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xD9.toByte())
        val saved = store.save(result = original, format = ImageEncoder.Format.JPEG,
                               mode = "noiseReduction", frameCount = 8)

        // The original is preserved and adjustments default to identity.
        assertArrayEquals(original, store.originalData(saved.id))
        assertEquals(ImageAdjustments.identity, store.adjustments(saved.id))

        // Applying an edit overwrites the displayed JPEG, persists adjustments, keeps the original.
        val edited = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02, 0xD9.toByte())
        val adj = ImageAdjustments(exposureEV = 1f)
        store.applyEdit(id = saved.id, adjustments = adj, rendered = edited)
        assertArrayEquals(edited, saved.resultFile.readBytes())          // displayed = edited
        assertArrayEquals(original, store.originalData(saved.id)) // original untouched
        assertEquals(1f, store.adjustments(saved.id).exposureEV, 1e-6f)
    }

    @Test
    fun testApplyEditUpdatesTheIndexRecord() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
                               format = ImageEncoder.Format.JPEG, mode = "noiseReduction", frameCount = 8)
        val before = store.loadAll().first().updatedAtAppleEpoch ?: 0.0
        store.applyEdit(id = saved.id, adjustments = ImageAdjustments(exposureEV = 1f),
                        rendered = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02, 0xD9.toByte()))
        val after = store.loadAll().first().updatedAtAppleEpoch ?: 0.0
        assertTrue("updatedAt must be bumped on edit", after >= before)
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun testDeleteRemovesRecordAndFiles() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
                               format = ImageEncoder.Format.JPEG, mode = "noiseReduction", frameCount = 8)
        store.delete(saved.id)
        assertEquals(0, store.loadAll().size)
        assertFalse(saved.resultFile.exists())
        assertNull(store.originalData(saved.id))
    }

    @Test
    fun testLoadAllDropsRecordsWhoseFileVanished() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
                               format = ImageEncoder.Format.JPEG, mode = "noiseReduction", frameCount = 8)
        saved.resultFile.delete()   // file gone, index still references it
        assertEquals("self-healed", 0, store.loadAll().size)
    }

    @Test
    fun testCorruptIndexIsPreservedNotOverwritten() {
        val store = makeStore()
        store.save(result = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
                   format = ImageEncoder.Format.JPEG, mode = "noiseReduction", frameCount = 8)
        // Corrupt the index.
        File(tempDir, "index.json").writeText("{ not json")
        assertEquals("corrupt index → empty list", 0, store.loadAll().size)
        val corruptPreserved = tempDir.listFiles()?.any { it.name.endsWith(".corrupt") } ?: false
        assertTrue("corrupt index bytes should be moved aside, not overwritten", corruptPreserved)
    }

    // MARK: - Format / back-compat

    @Test
    fun testSaveWithHEICFormatUsesHeicExtensionAndPersistsFormat() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xFF.toByte()), format = ImageEncoder.Format.HEIC,
                               mode = "noiseReduction", frameCount = 3)
        assertTrue(saved.resultFile.name.endsWith(".heic"))
        val rec = store.loadAll().first()
        assertEquals("heic", rec.format)
        assertEquals(ImageEncoder.Format.HEIC, rec.encoderFormat)
        assertTrue(store.resultURL(rec).name.endsWith(".heic"))
    }

    @Test
    fun testLegacyRecordWithoutFormatReadsAsJPEG() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xFF.toByte()), format = ImageEncoder.Format.JPEG,
                               mode = "smoothMotion", frameCount = 2)
        // Simulate a pre-format index: strip the key from the persisted JSON.
        val indexFile = File(tempDir, "index.json")
        val json = org.json.JSONArray(indexFile.readText())
        val obj = json.getJSONObject(0)
        obj.remove("format")
        indexFile.writeText(json.toString())

        val rec = store.loadAll().first()
        assertNull("format field absent → null", rec.format)
        assertEquals("nil format = JPEG (back-compat)", ImageEncoder.Format.JPEG, rec.encoderFormat)
        assertEquals("${saved.id.toString().uppercase()}.jpg", rec.resultFileName)
        assertNotNull(saved)
    }

    @Test
    fun testDeleteRemovesHeicFiles() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xFF.toByte()), format = ImageEncoder.Format.HEIC,
                               mode = "depthOfField", frameCount = 10)
        store.delete(saved.id)
        assertEquals(0, store.loadAll().size)
        assertFalse(saved.resultFile.exists())
    }

    @Test
    fun testReconcileOrphansSweepsBothExtensions() {
        val store = makeStore()
        val orphanJpg  = File(tempDir, "${UUID.randomUUID()}.jpg").also { it.writeBytes(byteArrayOf(0x01)) }
        val orphanHeic = File(tempDir, "${UUID.randomUUID()}.heic").also { it.writeBytes(byteArrayOf(0x01)) }
        store.reconcileOrphans()
        assertFalse(orphanJpg.exists())
        assertFalse(orphanHeic.exists())
    }

    @Test
    fun testDeleteAllEmptiesTheLibrary() {
        val store = makeStore()
        store.save(result = byteArrayOf(0xFF.toByte()), format = ImageEncoder.Format.JPEG,
                   mode = "noiseReduction", frameCount = 1)
        store.save(result = byteArrayOf(0xFF.toByte()), format = ImageEncoder.Format.HEIC,
                   mode = "lightTrails", frameCount = 1)
        store.deleteAll()
        assertEquals(0, store.loadAll().size)
    }

    @Test
    fun testStorageUsedBytesCountsLibraryFiles() {
        val store = makeStore()
        store.save(result = ByteArray(1000), format = ImageEncoder.Format.JPEG,
                   mode = "noiseReduction", frameCount = 1)
        assertTrue("result + original ≥ 2×1000", store.storageUsedBytes() >= 2000L)
    }

    @Test
    fun testRecordLookupByID() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xFF.toByte()), format = ImageEncoder.Format.HEIC,
                               mode = "noiseReduction", frameCount = 1)
        assertEquals(ImageEncoder.Format.HEIC, store.record(saved.id)?.encoderFormat)
        assertNull(store.record(UUID.randomUUID()))
    }

    // MARK: - iso/shutterSeconds on StackRecord

    @Test
    fun testCaptureInfoPersistsAndLegacyDecodesNull() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xAA.toByte()), reference = null,
                               format = ImageEncoder.Format.JPEG, mode = "noiseReduction",
                               frameCount = 3, iso = 250.0, shutterSeconds = 0.008)
        val rec = store.loadAll().first()
        assertEquals(250.0, rec.iso!!, 1e-9)
        assertEquals(0.008, rec.shutterSeconds!!, 1e-9)

        // Strip the new keys (legacy-record pattern) → fields decode as null.
        val indexFile = File(tempDir, "index.json")
        val json = org.json.JSONArray(indexFile.readText())
        val obj = json.getJSONObject(0)
        obj.remove("iso")
        obj.remove("shutterSeconds")
        indexFile.writeText(json.toString())

        val legacy = store.loadAll().first()
        assertNull("iso absent from JSON must decode as null", legacy.iso)
        assertNull("shutterSeconds absent from JSON must decode as null", legacy.shutterSeconds)
        assertNotNull(saved)
    }

    // MARK: - blend-strength / reference round-trip

    @Test
    fun testReferenceRoundTripAndDeletion() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xAA.toByte()), reference = byteArrayOf(0xBB.toByte()),
                               format = ImageEncoder.Format.HEIC, mode = "smoothMotion", frameCount = 3)
        assertArrayEquals(byteArrayOf(0xBB.toByte()), store.referenceData(saved.id))
        store.delete(saved.id)
        assertNull(store.referenceData(saved.id))
    }

    @Test
    fun testSaveWithoutReferenceHasNullReferenceData() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xAA.toByte()), reference = null,
                               format = ImageEncoder.Format.JPEG, mode = "depthOfField", frameCount = 10)
        assertNull(store.referenceData(saved.id))
    }

    @Test
    fun testReconcileKeepsLiveReferences() {
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xAA.toByte()), reference = byteArrayOf(0xBB.toByte()),
                               format = ImageEncoder.Format.JPEG, mode = "noiseReduction", frameCount = 3)
        store.reconcileOrphans()
        assertNotNull(store.referenceData(saved.id))
    }

    @Test
    fun testStaleAlphaWithMissingReferenceNormalizesToOne() {
        // Save WITHOUT a reference, then applyEdit with blendStrength 0.4 persisted.
        // Re-reading adjustments must return blendStrength == 1 because there's no reference
        // file to blend against — a stale α would silently re-bake at a different look. (Fix 4)
        val store = makeStore()
        val saved = store.save(result = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
                               reference = null, format = ImageEncoder.Format.JPEG,
                               mode = "noiseReduction", frameCount = 5)
        val adj = ImageAdjustments(blendStrength = 0.4f)
        store.applyEdit(id = saved.id, adjustments = adj,
                        rendered = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02, 0xD9.toByte()))
        // Re-read: the reference file is absent, so blendStrength must be normalized to 1.
        val read = store.adjustments(saved.id)
        assertEquals("stale α without a reference file must normalize to 1 on next read",
                     1f, read.blendStrength, 1e-6f)
        assertFalse("hasBlend must be false after normalization", read.hasBlend)
    }
}
