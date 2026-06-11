package com.jdpneto.stackstackstack

import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
 * - Settings ↔ coordinator mirroring: [LaunchedEffect] listeners mirror iOS `.onReceive(settings.$exportFormat)`.
 * - Intent-extra test hooks: `resetOnboarding` (removes the SharedPrefs key) and `skipOnboarding`
 *   (sets it to true) so UI-automation suites can land directly in the desired state.
 *   Mirrors `-resetOnboarding` / `-skipOnboarding` launch arguments on iOS.
 */
class MainActivity : ComponentActivity() {

    private lateinit var coordinator: StackCaptureCoordinator
    private lateinit var settings: AppSettings
    private lateinit var store: LibraryStore

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
        // Initial settings sync (one-time on start; changes come from the LaunchedEffects below).
        coordinator.exportFormat        = settings.exportFormat
        coordinator.saveToPhotosEnabled = settings.saveToPhotos

        setContent {
            StackTheme {
                AppRoot(coordinator, settings, store)
            }
        }
    }

    private fun makeCaptureService(): CaptureService =
        if (Build.FINGERPRINT == "robolectric") {
            // Robolectric / unit tests — no camera hardware.
            FakeCaptureService(width = 128, height = 128)
        } else {
            Camera2CaptureService(this)
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
    // Observe settings changes → sync to coordinator (mirrors iOS .onReceive(settings.$…)).
    var exportFormat    by remember { mutableStateOf(settings.exportFormat) }
    var saveToPhotos    by remember { mutableStateOf(settings.saveToPhotos) }
    LaunchedEffect(exportFormat) { coordinator.exportFormat = exportFormat }
    LaunchedEffect(saveToPhotos) { coordinator.saveToPhotosEnabled = saveToPhotos }

    var hasSeenOnboarding by remember { mutableStateOf(settings.hasSeenOnboarding) }
    var showOnboarding    by remember { mutableStateOf(false) }   // Settings → Replay path

    if (!hasSeenOnboarding) {
        OnboardingScreen(
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
            onOnboardingDone   = { showOnboarding = false },
            onExportFormatChanged = { exportFormat = it },
            onSaveToPhotosChanged = { saveToPhotos = it }
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
    onOnboardingDone: () -> Unit,
    onExportFormatChanged: (ImageEncoder.Format) -> Unit,
    onSaveToPhotosChanged: (Boolean) -> Unit
) {
    var currentTab    by remember { mutableStateOf(Tab.CAPTURE) }
    var galleryRefresh by remember { mutableIntStateOf(0) }   // bumped to force a gallery reload

    // Editor and detail navigation state (no nav library — simple state).
    var detailRecord  by remember { mutableStateOf<com.jdpneto.stackstackstack.StackRecord?>(null) }
    var editSource    by remember { mutableStateOf<EditSource?>(null) }

    if (showOnboarding) {
        OnboardingScreen(onFinish = onOnboardingDone)
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
