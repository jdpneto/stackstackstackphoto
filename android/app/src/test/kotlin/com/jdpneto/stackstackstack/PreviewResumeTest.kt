package com.jdpneto.stackstackstack

import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Bug 3 (black preview after backgrounding): the ON_RESUME path calls
 * [StackCaptureCoordinator.startPreview] again, so the coordinator side of that call must be
 * idempotent — safe to repeat, and re-publishing the capability probe each time (the service may
 * have reconfigured from scratch in between when the system evicted the camera).
 *
 * The Camera2 half (device StateCallback → invalidateSessionLocked → reconfigure) needs a real
 * camera HAL and is device-verified; these tests cover the seam the Compose resume observer
 * drives.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PreviewResumeTest {

    /** Counting fake whose capabilities can change between startPreview calls (re-probe). */
    private class CountingPreviewService : CaptureService {
        var previewCalls = 0
        var rawSupported = true
        var depthSupported = true
        override suspend fun startPreview(): Surface? {
            previewCalls++
            return null
        }
        override suspend fun captureBurst(
            recipe: CaptureRecipe,
            isSteady: () -> Boolean,
            onProgress: ((Int) -> Unit)?
        ): CapturedBurst = error("not used in preview tests")
        override val supportsRAWCapture: Boolean get() = rawSupported
        override val supportsDepthOfField: Boolean get() = depthSupported
    }

    private fun makeCoordinator(service: CaptureService): StackCaptureCoordinator {
        val dir = createTempDir("sss_preview_${UUID.randomUUID()}")
        return StackCaptureCoordinator(
            capture   = service,
            store     = LibraryStore(root = File(dir, "lib")),
            mainScope = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher()),
            processingDispatcher = StandardTestDispatcher()
        )
    }

    @Test
    fun `startPreview can be called repeatedly — the resume path re-invokes it safely`() = runTest {
        val service = CountingPreviewService()
        val coord = makeCoordinator(service)

        // First composition: surface ready + permission granted.
        assertNull(coord.startPreview())
        assertEquals(1, service.previewCalls)

        // ON_RESUME after backgrounding: the resumeTick re-keys the LaunchedEffect and calls
        // startPreview again — must simply forward to the service (idempotence lives there).
        assertNull(coord.startPreview())
        assertNull(coord.startPreview())
        assertEquals(3, service.previewCalls)
    }

    @Test
    fun `startPreview re-publishes the capability probe on every call`() = runTest {
        val service = CountingPreviewService()
        val coord = makeCoordinator(service)

        coord.startPreview()
        assertTrue(coord.supportsRAW)
        assertTrue(coord.supportsDepth)

        // The service reconfigured from scratch while backgrounded and probed differently
        // (e.g. a different camera won the RAW pick); resume must surface the fresh probe.
        service.rawSupported = false
        service.depthSupported = false
        coord.startPreview()
        assertFalse(coord.supportsRAW)
        assertFalse(coord.supportsDepth)
    }
}
