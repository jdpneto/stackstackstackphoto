package com.jdpneto.stackstackstack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.jdpneto.stackstackstack.ui.CaptureScreen
import com.jdpneto.stackstackstack.ui.EditSource
import com.jdpneto.stackstackstack.ui.EditorScreen
import com.jdpneto.stackstackstack.ui.GalleryScreen
import com.jdpneto.stackstackstack.ui.OnboardingScreen
import com.jdpneto.stackstackstack.ui.PhotoDetailScreen
import com.jdpneto.stackstackstack.ui.SettingsScreen
import com.jdpneto.stackstackstack.ui.loadEditSource
import com.jdpneto.stackstackstack.ui.theme.StackTheme

/**
 * Root activity. Mirrors [StackStackStackApp]:
 * - If [AppSettings.hasSeenOnboarding] is false → show [OnboardingScreen] (the camera stack
 *   MUST NOT mount yet — same reasoning as the iOS guard).
 * - Once onboarding is complete → 3-tab navigation (Capture / Gallery / Settings).
 * - Settings ↔ coordinator mirroring: [SettingsScreen] writes the coordinator directly alongside
 *   each [AppSettings] write (the Android equivalent of iOS `.onReceive(settings.$exportFormat)`);
 *   this activity only performs the one-time initial sync.
 * - Intent-extra test hooks: `resetOnboarding` (removes the SharedPrefs key) and `skipOnboarding`
 *   (sets it to true) so UI-automation suites can land directly in the desired state.
 *   Mirrors `-resetOnboarding` / `-skipOnboarding` launch arguments on iOS.
 * - Rotation: `android:configChanges` keeps this activity alive across rotation (Compose
 *   re-lays-out on its own), so the coordinator/camera/store singletons are never duplicated.
 */
class MainActivity : ComponentActivity() {

    private lateinit var coordinator: StackCaptureCoordinator
    private lateinit var settings: AppSettings
    private lateinit var store: LibraryStore

    companion object {
        /**
         * Test seam: when set, [makeCaptureService] uses this factory instead of the real
         * camera service. Robolectric/instrumented suites install [FakeCaptureService] here
         * explicitly — no runtime fingerprint sniffing, nothing test-specific ships in release.
         */
        var captureServiceFactory: ((ComponentActivity) -> CaptureService)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("sss_prefs", MODE_PRIVATE)
        settings = AppSettings(prefs)
        store = LibraryStore(filesDir.resolve("Stacks"))

        // ── Intent-extra test hooks (mirrors iOS ProcessInfo.arguments) ──────
        if (intent.getBooleanExtra("resetOnboarding", false)) {
            settings.hasSeenOnboarding = false
        }
        if (intent.getBooleanExtra("skipOnboarding", false)) {
            settings.hasSeenOnboarding = true
        }

        coordinator = StackCaptureCoordinator(
            capture = makeCaptureService(),
            store   = store,
            steadiness = MotionSteadiness(this),
            environment = CaptureEnvironment.live(this, filesDir),
            photosExporter = { data, fmt -> PhotoLibraryExporter.export(this@MainActivity, data, fmt) }
        )
        // Initial settings sync (one-time on start; live changes are written by SettingsScreen).
        coordinator.exportFormat        = settings.exportFormat
        coordinator.saveToPhotosEnabled = settings.saveToPhotos

        // Debug-gated frame dump (offline alignment debugging + harness fixture extraction).
        val debuggableApp = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggableApp && intent.getBooleanExtra("dumpFrames", false)) {
            coordinator.dumpFramesForDiagnostics = true
            coordinator.diagDirectory = java.io.File(filesDir, "diag")
        } else if (intent.getBooleanExtra("dumpFrames", false)) {
            // The debug-keystore-SIGNED Release build is not debuggable — say so instead of
            // silently dropping the extra (the dump only works in debuggable builds).
            android.util.Log.w("SSSCoord", "dumpFrames extra ignored: not a debuggable build")
        }

        setContent {
            StackTheme {
                AppRoot(coordinator, settings, store)
            }
        }
    }

    override fun onDestroy() {
        // Release the camera on real teardown (rotation no longer destroys us — configChanges).
        if (::coordinator.isInitialized) {
            coordinator.captureService.close()
        }
        super.onDestroy()
    }

    private fun makeCaptureService(): CaptureService {
        captureServiceFactory?.let { return it(this) }
        // Debug-only intent-extra escape hatch for automation on emulators without RAW cameras;
        // ignored in release builds (consistent altitude with the onboarding intent extras, but
        // gated so a release binary can never be pushed onto the fake).
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.getBooleanExtra("useFakeCapture", false)) {
            return FakeCaptureService(width = 128, height = 128)
        }
        return Camera2CaptureService(this)
    }
}

// ---------------------------------------------------------------------------
// App root composable
// ---------------------------------------------------------------------------

/**
 * Conditional root: onboarding gates the main tabs (the preview must NOT start while the
 * onboarding camera-permission page is displayed — mirrors iOS comment verbatim). Once
 * onboarding is done the 3-tab shell appears.
 */
@Composable
private fun AppRoot(
    coordinator: StackCaptureCoordinator,
    settings: AppSettings,
    store: LibraryStore
) {
    // Settings → coordinator sync happens inside SettingsScreen (which writes both), so there is
    // no mirror state to keep alive here.
    var hasSeenOnboarding by remember { mutableStateOf(settings.hasSeenOnboarding) }
    var showOnboarding    by remember { mutableStateOf(false) }   // Settings → Replay path

    if (!hasSeenOnboarding) {
        OnboardingScreen(
            settings = settings,
            onFinish = {
                settings.hasSeenOnboarding = true
                hasSeenOnboarding = true
            }
        )
    } else {
        MainTabs(
            coordinator     = coordinator,
            settings        = settings,
            store           = store,
            showOnboarding  = showOnboarding,
            onReplayOnboarding = { showOnboarding = true },
            onOnboardingDone   = { showOnboarding = false }
        )
    }
}

// ---------------------------------------------------------------------------
// Main 3-tab shell
// ---------------------------------------------------------------------------

private enum class Tab { CAPTURE, GALLERY, SETTINGS }

@Composable
private fun MainTabs(
    coordinator: StackCaptureCoordinator,
    settings: AppSettings,
    store: LibraryStore,
    showOnboarding: Boolean,
    onReplayOnboarding: () -> Unit,
    onOnboardingDone: () -> Unit
) {
    var currentTab    by remember { mutableStateOf(Tab.CAPTURE) }
    var galleryRefresh by remember { mutableIntStateOf(0) }   // bumped to force a gallery reload

    // Editor and detail navigation state (no nav library — simple state).
    var detailRecord  by remember { mutableStateOf<com.jdpneto.stackstackstack.StackRecord?>(null) }
    var editSource    by remember { mutableStateOf<EditSource?>(null) }

    if (showOnboarding) {
        OnboardingScreen(settings = settings, onFinish = onOnboardingDone)
        return
    }

    if (editSource != null) {
        EditorScreen(
            editSource = editSource!!,
            store      = store,
            onSaved    = { renderedBytes ->
                // Reflect the edit back: the gallery record is now updated.
                galleryRefresh++
                detailRecord?.let { rec ->
                    // Re-open the detail with a bumped refresh (the bitmap is shown from the save).
                }
            },
            onDismiss = { editSource = null }
        )
        return
    }

    if (detailRecord != null) {
        PhotoDetailScreen(
            record   = detailRecord!!,
            store    = store,
            onChanged = { galleryRefresh++ },
            onDismiss = { detailRecord = null },
            onEdit   = { src -> editSource = src }
        )
        return
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1C1C1E)) {
                NavigationBarItem(
                    selected = currentTab == Tab.CAPTURE,
                    onClick  = { currentTab = Tab.CAPTURE },
                    icon     = { Icon(Icons.Default.CameraAlt, contentDescription = "Capture") },
                    label    = { Text("Capture") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.GALLERY,
                    onClick  = { currentTab = Tab.GALLERY },
                    icon     = { Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery") },
                    label    = { Text("Gallery") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.SETTINGS,
                    onClick  = { currentTab = Tab.SETTINGS },
                    icon     = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label    = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            when (currentTab) {
                Tab.CAPTURE -> {
                    val captureScope = rememberCoroutineScope()
                    CaptureScreen(
                        coordinator  = coordinator,
                        onOpenEditor = { id ->
                            // Load edit source off-main, then navigate to editor.
                            val rec = store.record(id) ?: return@CaptureScreen
                            captureScope.launch {
                                val src = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    loadEditSource(rec, store)
                                }
                                src?.let { editSource = it }
                            }
                        }
                    )
                }
                Tab.GALLERY -> GalleryScreen(
                    store      = store,
                    onSelect   = { rec -> detailRecord = rec },
                    refreshKey = galleryRefresh
                )
                Tab.SETTINGS -> SettingsScreen(
                    settings            = settings,
                    coordinator         = coordinator,
                    store               = store,
                    onReplayOnboarding  = onReplayOnboarding
                )
            }
        }
    }
}
