package com.jdpneto.stackstackstack

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jdpneto.stackengine.CFAPattern
import com.jdpneto.stackengine.RawSensorFrame
import com.jdpneto.stackengine.StackMode
import com.jdpneto.stackengine.Vec3
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Tests for the RAW burst disk spool (the Pixel 30-frame OOM fix): bit-exact frame round-trip,
 * on-demand lazy loading, spool cleanup, and the service's app-start cleanup of leftovers.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class BurstSpoolTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("sss_spool_${UUID.randomUUID()}")
    }

    // -----------------------------------------------------------------------
    // Synthetic frame with every metadata field distinctive (and >15-bit mosaic
    // values so the signed-Short representation is exercised).
    // -----------------------------------------------------------------------

    private fun makeFrame(seed: Int = 0, width: Int = 8, height: Int = 6): RawSensorFrame {
        val mosaic = IntArray(width * height) { i -> (seed * 1013 + i * 2731 + 40000) and 0xFFFF }
        return RawSensorFrame.fromIntMosaic(
            width = width, height = height, mosaic = mosaic,
            blackLevel = 63.5f, whiteLevel = 4095f,
            cfa = CFAPattern.GRBG,
            wbGains = Vec3(2.125f, 1f, 1.625f),
            colorMatrix = FloatArray(9) { 0.5f + it * 0.0625f + seed }
        )
    }

    private fun assertFramesEqual(expected: RawSensorFrame, actual: RawSensorFrame) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertArrayEquals(expected.mosaic, actual.mosaic)
        assertEquals(expected.blackLevel, actual.blackLevel, 0f)   // bit-exact
        assertEquals(expected.whiteLevel, actual.whiteLevel, 0f)
        assertEquals(expected.cfa, actual.cfa)
        assertEquals(expected.wbGains, actual.wbGains)
        assertArrayEquals(expected.colorMatrix, actual.colorMatrix, 0f)
    }

    // -----------------------------------------------------------------------
    // Round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `write then read round-trips a frame bit-exactly`() {
        val frame = makeFrame(seed = 3)
        val file = File(tempDir, "gen-1/frame-000.bin")
        BurstSpool.write(file, frame)
        assertTrue("spool file should exist", file.isFile)
        assertFramesEqual(frame, BurstSpool.read(file))
    }

    @Test
    fun `read rejects a foreign file`() {
        val file = File(tempDir, "junk.bin")
        file.writeBytes(ByteArray(256) { it.toByte() })
        try {
            BurstSpool.read(file)
            fail("expected IOException for a non-spool file")
        } catch (e: IOException) {
            // expected — wrong magic
        }
    }

    // -----------------------------------------------------------------------
    // Lazy list
    // -----------------------------------------------------------------------

    @Test
    fun `lazy list loads frames on demand — a held result survives its file's deletion`() {
        val frame0 = makeFrame(seed = 0)
        val frame1 = makeFrame(seed = 1)
        val file0 = File(tempDir, "gen-2/frame-000.bin")
        val file1 = File(tempDir, "gen-2/frame-001.bin")
        BurstSpool.write(file0, frame0)
        BurstSpool.write(file1, frame1)

        val list = BurstSpool.LazyFrameList(listOf(file0, file1))
        assertEquals(2, list.size)

        val loaded0 = list[0]
        assertFramesEqual(frame0, loaded0)

        // Delete frame 1's file: get(1) must fail (proves get() reads disk on demand, holding
        // nothing in memory), while the already-loaded frame 0 result stays fully usable.
        assertTrue(file1.delete())
        try {
            list[1]
            fail("expected IOException reading a deleted spool file")
        } catch (e: IOException) {
            // expected
        }
        assertFramesEqual(frame0, loaded0)
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    @Test
    fun `clearSpools removes everything under the root except the kept dir`() {
        val root = File(tempDir, "burst-spool")
        val old1 = File(root, "gen-1").apply { mkdirs() }
        File(old1, "frame-000.bin").writeBytes(byteArrayOf(1, 2, 3))
        val old2 = File(root, "gen-2").apply { mkdirs() }
        val keep = File(root, "gen-3").apply { mkdirs() }
        File(keep, "frame-000.bin").writeBytes(byteArrayOf(4, 5, 6))

        BurstSpool.clearSpools(root, keep = keep)
        assertFalse("old spool dir should be deleted", old1.exists())
        assertFalse("old spool dir should be deleted", old2.exists())
        assertTrue("kept spool dir must survive with its contents", File(keep, "frame-000.bin").isFile)

        BurstSpool.clearSpools(root)
        assertFalse("clearSpools without keep removes everything", keep.exists())
    }

    @Test
    fun `Camera2CaptureService construction clears leftover spools from a killed process`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "burst-spool")
        val leftover = File(root, "gen-7").apply { mkdirs() }
        File(leftover, "frame-000.bin").writeBytes(byteArrayOf(1, 2, 3))

        val service = Camera2CaptureService(context)
        try {
            // The cleanup runs on the service's conversion executor — poll briefly.
            val deadline = System.currentTimeMillis() + 5_000
            while (leftover.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertFalse("app-start cleanup should remove leftover spools", leftover.exists())
        } finally {
            service.close()
        }
    }

    // -----------------------------------------------------------------------
    // End-to-end: the engine pipeline consumes a disk-backed lazy RAW payload
    // -----------------------------------------------------------------------

    /**
     * A fake whose RAW payload is a [BurstSpool.LazyFrameList] — exactly what
     * [Camera2CaptureService.finishLocked] now returns — proving the unchanged engine API
     * (List<RawSensorFrame>) processes a disk-backed burst end-to-end.
     */
    private class SpoolingFakeService(
        private val inner: FakeCaptureService,
        private val spoolDir: File
    ) : CaptureService {
        override suspend fun startPreview() = inner.startPreview()
        override suspend fun captureBurst(
            recipe: CaptureRecipe,
            isSteady: () -> Boolean,
            onProgress: ((Int) -> Unit)?
        ): CapturedBurst {
            val burst = inner.captureBurst(recipe, isSteady, onProgress)
            val frames = (burst.payload as CapturedBurst.Payload.Raw).frames
            val files = frames.mapIndexed { i, frame ->
                File(spoolDir, "frame-%03d.bin".format(i)).also { BurstSpool.write(it, frame) }
            }
            return CapturedBurst(
                payload = CapturedBurst.Payload.Raw(BurstSpool.LazyFrameList(files)),
                info = burst.info
            )
        }
    }

    @Test
    fun `coordinator stacks a disk-backed lazy RAW burst end-to-end`() = runTest {
        val store = LibraryStore(root = File(tempDir, "lib"))
        val coord = StackCaptureCoordinator(
            capture              = SpoolingFakeService(FakeCaptureService(16, 16), File(tempDir, "spool")),
            store                = store,
            mainScope            = this,
            processingDispatcher = StandardTestDispatcher(testScheduler)
        )
        coord.mode = StackMode.SMOOTH_MOTION   // the streaming path — indexes frames one at a time
        coord.shoot()
        advanceUntilIdle()
        assertNull(coord.lastError)
        assertNotNull(coord.lastResultJPEG)
        assertEquals(1, store.loadAll().size)
    }
}
