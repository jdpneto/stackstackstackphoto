package com.jdpneto.stackstackstack

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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

/**
 * Bug 3, round 2 (preview black after the surface itself is destroyed/recreated): SurfaceView
 * destroys its surface on hide and recreates it on show, so on return the resumeTick-keyed
 * startPreview can race surfaceCreated. The fix is SERVICE-OWNED: [Camera2CaptureService.startPreview]
 * records a sticky `previewRequested` intent, and [Camera2CaptureService.setPreviewSurface]
 * itself reconfigures and resumes the preview whenever a new surface lands — whatever the order,
 * the LAST event on sessionExecutor converges to a streaming preview.
 *
 * The camera HAL is faked out through the `protected open` configure seams; everything else
 * (executors, surface bookkeeping, the sticky flag, the restart decision) is the real service.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ServicePreviewRestartTest {

    /** Real service with the HAL-touching tail stubbed to counters (mirrors the real tail's
     *  contract: fresh configure marks `configured` and starts the repeating preview). */
    private class RestartProbeService(context: Context) : Camera2CaptureService(context) {
        val configureCalls = AtomicInteger(0)
        val previewRequestCalls = AtomicInteger(0)
        override fun ensureConfiguredLocked() {
            if (configured) return
            configureCalls.incrementAndGet()
            configured = true
            startPreviewRequestLocked()   // the real fresh-configure tail does exactly this
        }
        override fun startPreviewRequestLocked() {
            previewRequestCalls.incrementAndGet()
        }
    }

    private fun makeService(): RestartProbeService {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        return RestartProbeService(app)
    }

    private fun newSurface(): Surface = Surface(SurfaceTexture(0))

    @Test
    fun `surface arriving AFTER startPreview auto-resumes — previewRequested is sticky`() = runTest {
        val svc = makeService()
        // The racy resume ordering: startPreview lands first, with no (or a destroyed) surface.
        assertNull(svc.startPreview())
        svc.awaitSessionQuiescentForTest()
        assertEquals("no surface → must not configure", 0, svc.configureCalls.get())

        // surfaceCreated lands second — the service must restart the preview ITSELF.
        svc.setPreviewSurface(newSurface())
        svc.awaitSessionQuiescentForTest()
        assertEquals(1, svc.configureCalls.get())
        assertEquals(1, svc.previewRequestCalls.get())
    }

    @Test
    fun `surface replacement while previewing reconfigures and resumes without a UI retrigger`() = runTest {
        val svc = makeService()
        val a = newSurface()
        svc.setPreviewSurface(a)
        assertSame(a, svc.startPreview())            // normal cold start: one configure
        assertEquals(1, svc.configureCalls.get())
        assertEquals(1, svc.previewRequestCalls.get())

        // Re-registering the SAME surface must be a no-op (no teardown/reconfigure churn).
        svc.setPreviewSurface(a)
        svc.awaitSessionQuiescentForTest()
        assertEquals(1, svc.configureCalls.get())

        // Window surface destroyed+recreated behind our back: a DIFFERENT surface arrives and
        // the UI calls nothing else. The service must invalidate, reconfigure, and resume.
        svc.setPreviewSurface(newSurface())
        svc.awaitSessionQuiescentForTest()
        assertEquals(2, svc.configureCalls.get())
        assertEquals(2, svc.previewRequestCalls.get())
    }

    @Test
    fun `destroy then racy startPreview then recreate — the device failure sequence — recovers`() = runTest {
        val svc = makeService()
        svc.setPreviewSurface(newSurface())
        assertTrue(svc.startPreview() != null)
        assertEquals(1, svc.configureCalls.get())

        // App backgrounded: surfaceDestroyed → the surface is marked gone.
        svc.clearPreviewSurface()
        svc.awaitSessionQuiescentForTest()

        // Return: resumeTick's startPreview races ahead of surfaceCreated. It must NOT
        // configure against the dead/absent surface (the on-device 52 ms start→stop stream).
        assertNull(svc.startPreview())
        svc.awaitSessionQuiescentForTest()
        assertEquals(1, svc.configureCalls.get())

        // The recreated surface lands last and resumes the preview by itself.
        svc.setPreviewSurface(newSurface())
        svc.awaitSessionQuiescentForTest()
        assertEquals(2, svc.configureCalls.get())
        assertEquals(2, svc.previewRequestCalls.get())
    }

    @Test
    fun `setPreviewSurface before any startPreview only stores the surface`() = runTest {
        val svc = makeService()
        svc.setPreviewSurface(newSurface())
        svc.awaitSessionQuiescentForTest()
        assertEquals("preview never requested → no camera open", 0, svc.configureCalls.get())
        assertEquals(0, svc.previewRequestCalls.get())
    }

    @Test
    fun `surfaces arriving after close do not reopen the camera`() = runTest {
        val svc = makeService()
        svc.setPreviewSurface(newSurface())
        svc.startPreview()
        assertEquals(1, svc.configureCalls.get())
        svc.close()
        // SurfaceHolder callbacks can outlive the service — must not throw or reopen.
        svc.setPreviewSurface(newSurface())
        assertEquals(1, svc.configureCalls.get())
    }
}
