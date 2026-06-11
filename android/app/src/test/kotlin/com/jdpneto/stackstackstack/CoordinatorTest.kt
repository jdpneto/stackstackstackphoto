package com.jdpneto.stackstackstack

import android.view.Surface
import com.jdpneto.stackengine.CFAPattern
import com.jdpneto.stackengine.PixelImage
import com.jdpneto.stackengine.RawSensorFrame
import com.jdpneto.stackengine.StackMode
import com.jdpneto.stackengine.Vec3
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Mirrors iOS [CoordinatorTests] 1:1 — every test case, every assertion, same fakes.
 * Uses [TestScope] / [StandardTestDispatcher] so all coroutines advance deterministically under
 * [advanceUntilIdle], equivalent to XCTest's `async let` + `awaitProcessing()` pattern.
 *
 * Robolectric provides Bitmap support for JPEG encoding; HEIC is runtime-gated exactly as in iOS
 * (the Intel-Mac XCTSkipIf precedent): tests that assert HEIC bytes skip when HEIC encoding fails.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CoordinatorTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("sss_coord_${UUID.randomUUID()}")
    }

    // -----------------------------------------------------------------------
    // Factory helpers (mirror iOS makeCoordinator())
    // -----------------------------------------------------------------------

    private fun makeCoordinator(
        capture: CaptureService = FakeCaptureService(width = 16, height = 16)
    ): Pair<StackCaptureCoordinator, LibraryStore> {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val scope   = TestScope(StandardTestDispatcher())
        val coord   = StackCaptureCoordinator(
            capture               = capture,
            store                 = store,
            mainScope             = scope,
            processingDispatcher  = StandardTestDispatcher(scope.testScheduler)
        )
        return Pair(coord, store)
    }

    /** Shoot and advance until all coroutines (capture + processing) are idle. */
    private suspend fun TestScope.shootAndAwait(coord: StackCaptureCoordinator) {
        coord.shoot()
        advanceUntilIdle()
    }

    // -----------------------------------------------------------------------
    // Basic shoot / save
    // -----------------------------------------------------------------------

    @Test
    fun testShootCapturesThenStacksAndSavesInBackground() = runTest {
        val scope = this
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = scope,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.shoot()
        advanceUntilIdle()
        assertFalse("capture should be finished when shoot() returns", coord.isCapturing)
        assertNull(coord.lastError)
        assertNotNull(coord.lastSavedID)
        assertNotNull(coord.lastResultJPEG)
        assertEquals(0, coord.processingCount)
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun testSmoothMotionShootProducesAResult() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode = StackMode.SMOOTH_MOTION
        coord.shoot()
        advanceUntilIdle()
        assertNull(coord.lastError)
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun testProFrameCountOverrideChangesCapturedFrames() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.pro = ProControls(frameCount = 5)   // override the look default (NoiseReduction = 8)
        coord.shoot()
        advanceUntilIdle()
        assertEquals(5, store.loadAll().first().frameCount)
    }

    @Test
    fun testConcurrentShootsAreRejected() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        // Launch two shoots; the second fires while the first is in-flight → re-entrancy guard drops it.
        launch { coord.shoot() }
        launch { coord.shoot() }
        advanceUntilIdle()
        // Only one save: the re-entrancy guard dropped the concurrent second shoot.
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun testCaptureFinishesAndShutterClearsAfterProcessing() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.shoot()
        advanceUntilIdle()
        assertFalse("capture should be finished when shoot() returns", coord.isCapturing)
        assertFalse("shutter is free once the background stack finishes", coord.isBusy)
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun testCancelDiscardsQueuedStackAndFreesUI() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode = StackMode.SMOOTH_MOTION
        // shoot() completes the capture phase and enqueues the processing job.
        // With StandardTestDispatcher the enqueued withContext(processingDispatcher) job
        // hasn't run yet when shoot() returns (it's queued but not dispatched), so we can
        // cancel before advanceUntilIdle() runs it. Mirrors iOS MainActor serial guarantee.
        coord.shoot()   // capture done; processingCount == 1; processing job queued, not run
        coord.cancelProcessing()   // token flipped before the job runs
        advanceUntilIdle()         // processing job runs, sees token cancelled, bails
        assertFalse("shutter must be free after cancel", coord.isBusy)
        assertNull(coord.lastSavedID)
        assertNull("cancel is not an error", coord.lastError)
        assertEquals("a cancelled stack must not be saved", 0, store.loadAll().size)
    }

    @Test
    fun testLongExposureUsesBurstSettingsFrameCount() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode  = StackMode.SMOOTH_MOTION
        coord.burst = BurstSettings(photoCount = 7, durationSeconds = 4.0)
        coord.shoot()
        advanceUntilIdle()
        assertEquals(7, store.loadAll().first().frameCount)
    }

    @Test
    fun testStaticLookIgnoresBurstSettings() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode  = StackMode.NOISE_REDUCTION   // Detail: fixed 8-frame burst
        coord.burst = BurstSettings(photoCount = 3, durationSeconds = 4.0)
        coord.shoot()
        advanceUntilIdle()
        assertEquals(8, store.loadAll().first().frameCount)
    }

    @Test
    fun testChangingLookDropsTheStaleResult() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.shoot()
        advanceUntilIdle()
        assertNotNull(coord.lastResultJPEG)
        coord.mode = StackMode.SMOOTH_MOTION   // switch looks → a new shot is implied
        assertNull("switching looks should drop the stale result preview", coord.lastResultJPEG)
        assertNull(coord.lastSavedID)
    }

    // -----------------------------------------------------------------------
    // Tap-to-focus
    // -----------------------------------------------------------------------

    @Test
    fun testTapToFocusEnabledInAutoModeAndFree() = runTest {
        val (coord, _) = makeCoordinator()
        assertTrue(coord.tapToFocusEnabled)
    }

    @Test
    fun testTapToFocusDisabledWithEachManualOverride() = runTest {
        val (coord, _) = makeCoordinator()
        coord.pro = ProControls(focus = 0.5)
        assertFalse("manual focus disables tap-to-focus", coord.tapToFocusEnabled)
        coord.pro = ProControls(iso = 800.0)
        assertFalse("manual ISO disables tap-to-focus", coord.tapToFocusEnabled)
        coord.pro = ProControls(shutterSeconds = 0.01)
        assertFalse("manual shutter disables tap-to-focus", coord.tapToFocusEnabled)
        coord.pro = ProControls.auto
        assertTrue("back to auto re-enables", coord.tapToFocusEnabled)
    }

    @Test
    fun testFocusAndExposeTogglesAeAfLock() = runTest {
        val (coord, _) = makeCoordinator()
        coord.focusAndExpose(0.5f, 0.5f, lock = true)
        assertTrue(coord.aeAfLocked)
        coord.focusAndExpose(0.3f, 0.3f, lock = false)
        assertFalse("a normal tap clears the lock", coord.aeAfLocked)
    }

    @Test
    fun testEnablingManualClearsAeAfLock() = runTest {
        val (coord, _) = makeCoordinator()
        coord.focusAndExpose(0.5f, 0.5f, lock = true)
        coord.pro = ProControls(iso = 800.0)
        assertFalse("entering manual drops the AE/AF lock", coord.aeAfLocked)
    }

    @Test
    fun testChangingLookClearsAeAfLock() = runTest {
        val (coord, _) = makeCoordinator()
        coord.focusAndExpose(0.5f, 0.5f, lock = true)
        coord.mode = StackMode.SMOOTH_MOTION
        assertFalse("switching looks drops the AE/AF lock", coord.aeAfLocked)
    }

    @Test
    fun testFocusAndExposeIsNoOpWhenDisabled() = runTest {
        val (coord, _) = makeCoordinator()
        coord.pro = ProControls(iso = 800.0)   // manual exposure → tapToFocusEnabled == false
        coord.focusAndExpose(0.5f, 0.5f, lock = true)
        assertFalse("a gesture while disabled (manual mode) must not set the AE/AF lock", coord.aeAfLocked)
    }

    @Test
    fun testDismissResultClearsPreview() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.shoot()
        advanceUntilIdle()
        assertNotNull(coord.lastResultJPEG)
        assertNotNull(coord.lastSavedID)
        coord.dismissResult()
        assertNull("dismiss clears the result preview", coord.lastResultJPEG)
        assertNull(coord.lastSavedID)
    }

    @Test
    fun testShootClearsAeAfLock() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.focusAndExpose(0.5f, 0.5f, lock = true)
        assertTrue(coord.aeAfLocked)
        launch { coord.shoot() }
        testScheduler.advanceTimeBy(1)   // advance past the yield() inside FakeCaptureService
        assertFalse("shooting clears the AE/AF lock so the banner doesn't linger", coord.aeAfLocked)
        advanceUntilIdle()
    }

    @Test
    fun testTapToFocusDisabledWhileBusy() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        // shoot() returns after capture completes (processingCount was incremented before the
        // withContext(processingDispatcher) dispatch — the job is queued but not yet run under
        // StandardTestDispatcher, so isBusy is true immediately after shoot() returns).
        coord.shoot()
        // Capture done; processingCount == 1; the withContext heavy job is queued on the dispatcher.
        assertFalse("tap-to-focus is disabled while a stack is processing", coord.tapToFocusEnabled)
        advanceUntilIdle()   // let the processing job run and decrement processingCount
        assertTrue("re-enabled once processing finishes", coord.tapToFocusEnabled)
    }

    @Test
    fun testCapturePublishesProgress() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode = StackMode.NOISE_REDUCTION   // Detail: fixed 8-frame burst
        coord.shoot()
        advanceUntilIdle()
        assertEquals("total reflects the recipe frame count", 8, coord.captureTotal)
        assertEquals("counter reaches the captured frame count", 8, coord.capturedCount)
    }

    // -----------------------------------------------------------------------
    // CoordinatorUiState derived gates (single source of truth for UI + coordinator)
    // -----------------------------------------------------------------------

    @Test
    fun testUiStateDerivedGates() {
        val idle = CoordinatorUiState()
        assertFalse(idle.isBusy)
        assertTrue(idle.tapToFocusEnabled)

        val capturing = idle.copy(isCapturing = true)
        assertTrue(capturing.isBusy)
        assertFalse(capturing.tapToFocusEnabled)

        // processingCount alone must gate the tap too — the Compose tap gate previously omitted
        // it, drawing a focus square the coordinator then ignored.
        val processing = idle.copy(processingCount = 1)
        assertTrue(processing.isBusy)
        assertFalse(processing.tapToFocusEnabled)

        val manual = idle.copy(pro = ProControls(iso = 800.0))
        assertFalse(manual.isBusy)
        assertFalse(manual.tapToFocusEnabled)
    }

    // -----------------------------------------------------------------------
    // Depth
    // -----------------------------------------------------------------------

    @Test
    fun testDepthShootRoutesToFocusStackerAndSaves() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode = StackMode.DEPTH_OF_FIELD
        coord.shoot()
        advanceUntilIdle()
        assertNull(coord.lastError)
        assertNotNull(coord.lastResultJPEG)
        val record = store.loadAll().first()
        assertEquals("depthOfField", record.mode)
        assertEquals("Depth default is a 10-bracket sweep", 10, record.frameCount)
    }

    @Test
    fun testDepthHonoursProFrameCount() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode = StackMode.DEPTH_OF_FIELD
        coord.pro  = ProControls(frameCount = 4)
        coord.shoot()
        advanceUntilIdle()
        assertEquals(4, store.loadAll().first().frameCount)
    }

    @Test
    fun testSupportsDepthIsTrueWithTheFake() = runTest {
        val (coord, _) = makeCoordinator()
        coord.startPreview()
        assertTrue("the fake always supports a focus sweep", coord.supportsDepth)
    }

    // -----------------------------------------------------------------------
    // Export format
    // -----------------------------------------------------------------------

    @Test
    fun testShootHonoursExportFormat() = runTest {
        // Gate on HEIC availability — Robolectric on CI may not have HEIC encode (same as iOS
        // Intel-Mac XCTSkipIf guard).
        val probeResult = runCatching {
            ImageEncoder.encode(
                rgba8 = byteArrayOf(0, 0, 0, 255.toByte()),
                width = 1, height = 1,
                format  = ImageEncoder.Format.HEIC,
                quality = 0.9
            )
        }
        if (probeResult.isFailure) return@runTest   // HEIC unavailable on this host — skip

        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.exportFormat = ImageEncoder.Format.HEIC
        coord.shoot()
        advanceUntilIdle()
        val rec = store.loadAll().first()
        assertEquals(ImageEncoder.Format.HEIC, rec.encoderFormat)
        assertEquals("heic", store.resultURL(rec).extension)
    }

    @Test
    fun testHEICEncodeFailureFallsBackToJPEG() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler),
            encodeImage          = { rgba8, w, h, format, quality, exif ->
                if (format == ImageEncoder.Format.HEIC) throw ImageEncoderError.FinalizeFailed
                ImageEncoder.encode(rgba8, w, h, format, quality, exif)
            }
        )
        coord.exportFormat = ImageEncoder.Format.HEIC
        coord.shoot()
        advanceUntilIdle()
        assertNull("fallback must not surface an error", coord.lastError)
        val rec = store.loadAll().first()
        assertEquals("record stamped with the format actually encoded", ImageEncoder.Format.JPEG, rec.encoderFormat)
        // Verify the bytes are real JPEG (FF D8 FF magic).
        val bytes = store.resultURL(rec).readBytes()
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[2])
    }

    @Test
    fun testHEICFallbackKeepsResultAndReferencePaired() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler),
            encodeImage          = { rgba8, w, h, format, quality, exif ->
                if (format == ImageEncoder.Format.HEIC) throw ImageEncoderError.FinalizeFailed
                ImageEncoder.encode(rgba8, w, h, format, quality, exif)
            }
        )
        coord.mode         = StackMode.SMOOTH_MOTION   // a look that stores a reference
        coord.exportFormat = ImageEncoder.Format.HEIC
        coord.shoot()
        advanceUntilIdle()
        val rec = store.loadAll().first()
        assertEquals(ImageEncoder.Format.JPEG, rec.encoderFormat)
        val ref = store.referenceData(rec.id)
        assertNotNull("reference must survive the fallback, paired as JPEG", ref)
        assertEquals(0xFF.toByte(), ref!![0])
        assertEquals(0xD8.toByte(), ref[1])
        assertEquals(0xFF.toByte(), ref[2])
    }

    /**
     * The REAL encoder (no injected failure) on a runtime without HEIC (Robolectric) must route
     * a HEIC request through the throw→fallback path and produce an HONEST jpeg-labelled record —
     * never JPEG bytes in a `.heic` file. (spec §3 honesty rule; mirrors the iOS contract where
     * a HEIC encode failure throws and the coordinator re-encodes as JPEG.)
     */
    @Test
    fun testHeicUnavailableAtRuntimeYieldsHonestJpegRecord() = runTest {
        if (ImageEncoder.Format.heicCompressFormat != null) {
            return@runTest   // runtime CAN encode HEIC — covered by testShootHonoursExportFormat
        }
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
            // NOTE: default encodeImage — the production ImageEncoder, not a test stub.
        )
        coord.exportFormat = ImageEncoder.Format.HEIC
        coord.shoot()
        advanceUntilIdle()
        assertNull("fallback must not surface an error", coord.lastError)
        val rec = store.loadAll().first()
        assertEquals("record format must be what was ACTUALLY encoded",
                     ImageEncoder.Format.JPEG, rec.encoderFormat)
        assertEquals("file extension must match the real bytes", "jpg", store.resultURL(rec).extension)
        val bytes = store.resultURL(rec).readBytes()
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }

    @Test
    fun testDefaultFormatIsJPEG() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.shoot()
        advanceUntilIdle()
        assertEquals(ImageEncoder.Format.JPEG, store.loadAll().first().encoderFormat)
    }

    // -----------------------------------------------------------------------
    // Photos auto-export (spec §5)
    // -----------------------------------------------------------------------

    @Test
    fun testPhotosExportRunsOnlyWhenEnabled() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val exportLog = ExportLog()
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler),
            photosExporter       = { data, _ -> exportLog.record(data.size) }
        )
        coord.shoot()   // saveToPhotosEnabled defaults to false
        advanceUntilIdle()
        assertEquals("no export when the toggle is off", 0, exportLog.count)

        coord.saveToPhotosEnabled = true
        coord.shoot()
        advanceUntilIdle()
        assertEquals("one export per save when enabled", 1, exportLog.count)
        assertNull(coord.photosExportNote)
    }

    @Test
    fun testPhotosExportFailureIsNonBlocking() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler),
            photosExporter       = { _, _ -> throw RuntimeException("export failed") }
        )
        coord.saveToPhotosEnabled = true
        coord.shoot()
        advanceUntilIdle()
        assertEquals("in-app save unaffected", 1, store.loadAll().size)
        assertNotNull("failure surfaces as a note, not an error", coord.photosExportNote)
        assertNull(coord.lastError)
    }

    // -----------------------------------------------------------------------
    // RAW capability probe
    // -----------------------------------------------------------------------

    @Test
    fun testSupportsRAWIsTrueWithTheFake() = runTest {
        val (coord, _) = makeCoordinator()
        coord.startPreview()
        assertTrue(coord.supportsRAW)
    }

    // -----------------------------------------------------------------------
    // Blend-strength reference (spec §3, spec 2026-06-11 §4)
    // -----------------------------------------------------------------------

    @Test
    fun testShootSavesAReferenceForBlendableLooks() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode = StackMode.SMOOTH_MOTION
        coord.shoot()
        advanceUntilIdle()
        val rec = store.loadAll().first()
        assertNotNull("long-exposure looks store the blend reference", store.referenceData(rec.id))
    }

    @Test
    fun testDepthShootSavesNoReference() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode = StackMode.DEPTH_OF_FIELD
        coord.shoot()
        advanceUntilIdle()
        val rec = store.loadAll().first()
        assertNull("no blend semantics for focus stacks", store.referenceData(rec.id))
    }

    // -----------------------------------------------------------------------
    // CaptureEnvironment policy (spec 2026-06-11 §2)
    // -----------------------------------------------------------------------

    @Test
    fun testCriticalThermalBlocksTheShot() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler),
            environment          = CaptureEnvironment(
                thermalStatus    = { android.os.PowerManager.THERMAL_STATUS_CRITICAL },
                batteryLevel     = { 1f },
                batteryCharging  = { false },
                freeDiskBytes    = { Long.MAX_VALUE }
            )
        )
        coord.shoot()
        advanceUntilIdle()
        assertEquals("Too hot — let the phone cool down.", coord.lastError)
        assertEquals(0, store.loadAll().size)
    }

    @Test
    fun testSeriousThermalHalvesTheBurst() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler),
            environment          = CaptureEnvironment(
                thermalStatus    = { android.os.PowerManager.THERMAL_STATUS_SEVERE },
                batteryLevel     = { 1f },
                batteryCharging  = { false },
                freeDiskBytes    = { Long.MAX_VALUE }
            )
        )
        coord.mode = StackMode.NOISE_REDUCTION   // base recipe = 8 frames
        coord.shoot()
        advanceUntilIdle()
        assertEquals("serious thermal halves the burst", 4, store.loadAll().first().frameCount)
        assertNotNull(coord.environmentNote)
    }

    @Test
    fun testLowStorageBlocksTheShot() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler),
            environment          = CaptureEnvironment(
                thermalStatus    = { 0 },
                batteryLevel     = { 1f },
                batteryCharging  = { false },
                freeDiskBytes    = { 50_000_000L }
            )
        )
        coord.shoot()
        advanceUntilIdle()
        assertEquals("Not enough storage to capture.", coord.lastError)
        assertEquals(0, store.loadAll().size)
    }

    @Test
    fun testLowBatteryWarnsButShoots() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = FakeCaptureService(16, 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler),
            environment          = CaptureEnvironment(
                thermalStatus    = { 0 },
                batteryLevel     = { 0.05f },
                batteryCharging  = { false },
                freeDiskBytes    = { Long.MAX_VALUE }
            )
        )
        coord.shoot()
        advanceUntilIdle()
        assertEquals("low battery never blocks", 1, store.loadAll().size)
        assertEquals("Low battery", coord.environmentNote)
    }

    // -----------------------------------------------------------------------
    // CaptureInfo / CapturedBurst struct (spec 2026-06-11)
    // -----------------------------------------------------------------------

    @Test
    fun testCaptureInfoLandsOnRecord() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = RawFakeWithInfo(width = 16, height = 16),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode = StackMode.NOISE_REDUCTION
        coord.shoot()
        advanceUntilIdle()
        assertNull(coord.lastError)
        val rec = store.loadAll().first()
        assertEquals(640.0, rec.iso ?: 0.0, 1e-9)
        assertEquals(0.01,  rec.shutterSeconds ?: 0.0, 1e-9)
    }

    // -----------------------------------------------------------------------
    // Non-RAW fallback (developed payload) (spec 2026-06-11 §3)
    // -----------------------------------------------------------------------

    @Test
    fun testDevelopedBurstSavesForStaticAndLongExposure() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = DevelopedFake(width = 16, height = 16, count = 20),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        for (mode in listOf(StackMode.NOISE_REDUCTION, StackMode.SMOOTH_MOTION)) {
            coord.mode = mode
            coord.shoot()
            advanceUntilIdle()
            assertNull("mode=$mode", coord.lastError)
        }
        assertEquals(2, store.loadAll().size)
        // Blendable look on the fallback path still stores a reference.
        val smooth = store.loadAll().first { it.mode == "smoothMotion" }
        assertNotNull(store.referenceData(smooth.id))
    }

    @Test
    fun testDevelopedBurstDepthSaves() = runTest {
        val testDir = File(tempDir, UUID.randomUUID().toString())
        val store   = LibraryStore(root = testDir)
        val coord   = StackCaptureCoordinator(
            capture              = DevelopedFake(width = 24, height = 16, count = 10),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode = StackMode.DEPTH_OF_FIELD
        coord.shoot()
        advanceUntilIdle()
        assertNull(coord.lastError)
        assertEquals("depthOfField", store.loadAll().first().mode)
    }
}

// ---------------------------------------------------------------------------
// Test helpers (mirrors iOS CoordinatorTests helper types)
// ---------------------------------------------------------------------------

/**
 * Fallback-path fake: returns already-developed frames, as a non-RAW device's JPEG path would.
 * Mirrors iOS [DevelopedFake] — same pixel pattern, same behavior.
 */
private class DevelopedFake(
    private val width: Int,
    private val height: Int,
    private val count: Int
) : CaptureService {
    override suspend fun startPreview(): Surface? = null
    override val supportsRAWCapture: Boolean = false

    override suspend fun captureBurst(
        recipe: CaptureRecipe,
        isSteady: () -> Boolean,
        onProgress: ((Int) -> Unit)?
    ): CapturedBurst {
        yield()
        val n = minOf(recipe.frameCount, count)
        val imgs = (0 until n).map { k ->
            val img = PixelImage(width, height)
            val fill = 0.4f
            for (i in 0 until width * height * 3) img.pixels[i] = fill
            // Per-frame variation — mirrors iOS `img[k % width, 0] = SIMD3<Float>(0.9, 0.9, 0.9)`.
            val px = (k % width) * 3
            img.pixels[px] = 0.9f; img.pixels[px + 1] = 0.9f; img.pixels[px + 2] = 0.9f
            onProgress?.invoke(k + 1)
            img
        }
        return CapturedBurst(payload = CapturedBurst.Payload.Developed(imgs), info = null)
    }
}

/**
 * RAW-path fake that provides a [CaptureInfo] so the coordinator test can verify the info reaches
 * the [StackRecord] without needing a real camera. Mirrors iOS [RawFakeWithInfo].
 */
private class RawFakeWithInfo(
    private val width: Int,
    private val height: Int
) : CaptureService {
    override suspend fun startPreview(): Surface? = null

    override suspend fun captureBurst(
        recipe: CaptureRecipe,
        isSteady: () -> Boolean,
        onProgress: ((Int) -> Unit)?
    ): CapturedBurst {
        yield()
        val n = maxOf(recipe.frameCount, 1)
        val frames = (0 until n).map {
            RawSensorFrame(
                width      = width,
                height     = height,
                mosaic     = ShortArray(width * height) { 200 },
                blackLevel = 64f,
                whiteLevel = 1024f,
                cfa        = CFAPattern.RGGB,
                wbGains    = Vec3(1f, 1f, 1f)
            )
        }
        return CapturedBurst(
            payload = CapturedBurst.Payload.Raw(frames),
            info    = CaptureInfo(iso = 640.0, shutterSeconds = 0.01)
        )
    }
}

/** Thread-safe counter for tracking export invocations. Mirrors iOS actor [ExportLog]. */
private class ExportLog {
    @Volatile var count: Int = 0
        private set

    fun record(n: Int) { count++ }
}
