package com.jdpneto.stackstackstack

import android.content.SharedPreferences
import android.view.Surface
import com.jdpneto.stackengine.CFAPattern
import com.jdpneto.stackengine.RawSensorFrame
import com.jdpneto.stackengine.StackMode
import com.jdpneto.stackengine.Vec3
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.io.File

/**
 * Robolectric smoke tests for B3 state-logic gating. These are pure-logic tests (no Compose UI
 * host needed): they exercise the coordinator state machine and settings-mirroring flows that
 * would be wired to the UI in the real app, verifying the booleans the Compose layer reads.
 *
 * Three tests per the plan:
 * 1. Onboarding gates root — [AppSettings.hasSeenOnboarding] controls which screen shows.
 * 2. Settings mirroring — format + saveToPhotos changes propagate to coordinator.
 * 3. Look-picker gating — Depth chip is disabled when supportsDepth=false; standard-quality
 *    caption appears when supportsRAW=false.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class UiSmokeTest {

    // ---------------------------------------------------------------------------
    // Test 1: Onboarding gates the root
    // ---------------------------------------------------------------------------

    /**
     * The app root's conditional logic: show onboarding when hasSeenOnboarding=false,
     * main tabs when true. Pure boolean; no Compose host needed.
     */
    @Test
    fun `onboarding gates root — shows onboarding when not seen, main tabs when seen`() {
        val prefs = makePrefs()
        val settings = AppSettings(prefs)

        // Fresh install: onboarding not seen → root should show OnboardingScreen.
        assertFalse("Fresh install: onboarding should not be seen", settings.hasSeenOnboarding)
        // Simulate finish() call from OnboardingScreen.
        settings.hasSeenOnboarding = true
        assertTrue("After finish: onboarding should be marked seen", settings.hasSeenOnboarding)
        // Reset test: remove the key so the next test starts fresh.
        settings.hasSeenOnboarding = false
        assertFalse("After reset: should be back to unseen", settings.hasSeenOnboarding)
    }

    // ---------------------------------------------------------------------------
    // Test 2: Settings mirroring → coordinator sync
    // ---------------------------------------------------------------------------

    /**
     * The app root mirrors settings to the coordinator (mirrors iOS .onReceive(settings.$exportFormat)).
     * Verify that changing AppSettings propagates to CoordinatorUiState.
     */
    @Test
    fun `settings mirroring — format and saveToPhotos propagate to coordinator`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val prefs = makePrefs()
        val settings = AppSettings(prefs)
        val store = LibraryStore(createTempDir())
        val coordinator = StackCaptureCoordinator(
            capture = FakeCaptureService(8, 8),
            store = store,
            mainScope = TestScope(testDispatcher),
            processingDispatcher = testDispatcher
        )

        // Default should be JPEG.
        assertEquals(ImageEncoder.Format.JPEG, coordinator.exportFormat)

        // Simulate app-root LaunchedEffect sync: settings changes → coordinator.
        settings.exportFormat = ImageEncoder.Format.HEIC
        coordinator.exportFormat = settings.exportFormat
        assertEquals(ImageEncoder.Format.HEIC, coordinator.exportFormat)
        assertEquals(ImageEncoder.Format.HEIC, coordinator.uiState.value.exportFormat)

        // saveToPhotos
        assertFalse(coordinator.saveToPhotosEnabled)
        settings.saveToPhotos = true
        coordinator.saveToPhotosEnabled = settings.saveToPhotos
        assertTrue(coordinator.saveToPhotosEnabled)
        assertTrue(coordinator.uiState.value.saveToPhotosEnabled)
    }

    // ---------------------------------------------------------------------------
    // Test 3: Look-picker gating booleans
    // ---------------------------------------------------------------------------

    /**
     * The look picker disables the Depth chip when supportsDepth=false and shows the
     * standard-quality caption when supportsRAW=false. These are CoordinatorUiState booleans
     * that the Compose FilterChip disabled parameter reads directly.
     */
    @Test
    fun `look-picker gating — supportsDepth and supportsRAW drive chip state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = LibraryStore(createTempDir())
        // Use a custom CaptureService that reports no depth / no RAW.
        val noDepthFake = object : CaptureService {
            override val supportsDepthOfField: Boolean = false
            override val supportsRAWCapture: Boolean   = false
            override suspend fun startPreview(): android.view.Surface? = null
            override suspend fun captureBurst(
                recipe: CaptureRecipe,
                isSteady: () -> Boolean,
                onProgress: ((Int) -> Unit)?
            ): CapturedBurst {
                val frame = com.jdpneto.stackengine.RawSensorFrame(
                    width = 8, height = 8,
                    mosaic = ShortArray(64) { 200.toShort() },
                    blackLevel = 0f, whiteLevel = 1023f,
                    cfa = com.jdpneto.stackengine.CFAPattern.RGGB,
                    wbGains = com.jdpneto.stackengine.Vec3(1f, 1f, 1f)
                )
                return CapturedBurst(payload = CapturedBurst.Payload.Raw(listOf(frame)))
            }
        }
        val coordinator = StackCaptureCoordinator(
            capture = noDepthFake,
            store = store,
            mainScope = TestScope(testDispatcher),
            processingDispatcher = testDispatcher
        )

        // Before startPreview, the coordinator defaults to optimistic true.
        assertTrue("Default supportsDepth should be optimistic true", coordinator.supportsDepth)
        assertTrue("Default supportsRAW should be optimistic true", coordinator.supportsRAW)

        // After startPreview, capabilities are updated from the service.
        coordinator.startPreview()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(
            "Depth chip should be disabled when service reports supportsDepthOfField=false",
            coordinator.uiState.value.supportsDepth
        )
        assertFalse(
            "Standard-quality caption should show when service reports supportsRAWCapture=false",
            coordinator.uiState.value.supportsRAW
        )

        // Verify the Depth look can be selected but the chip gating logic reflects the flag.
        // (The chip's `enabled` param = !busy && !(mode==DEPTH && !supportsDepth))
        val depthChipEnabled = !coordinator.isBusy &&
            !(StackMode.DEPTH_OF_FIELD == StackMode.DEPTH_OF_FIELD && !coordinator.supportsDepth)
        assertFalse("Depth chip should be disabled per gating logic", depthChipEnabled)

        // Non-depth looks should remain selectable.
        val noiseChipEnabled = !coordinator.isBusy &&
            !(StackMode.NOISE_REDUCTION == StackMode.DEPTH_OF_FIELD && !coordinator.supportsDepth)
        assertTrue("Noise-reduction chip should remain enabled", noiseChipEnabled)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun makePrefs(): SharedPreferences {
        val context = RuntimeEnvironment.getApplication()
        return context.getSharedPreferences("sss_smoke_test_${System.nanoTime()}", 0)
    }

    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir")!!, "sss_test_${System.nanoTime()}").also { it.mkdirs() }
}
