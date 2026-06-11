package com.jdpneto.stackstackstack.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jdpneto.stackengine.StackMode
import com.jdpneto.stackstackstack.AppSettings
import com.jdpneto.stackstackstack.Camera2CaptureService
import com.jdpneto.stackstackstack.usesSteadinessGate
import com.jdpneto.stackstackstack.CaptureRecipe
import com.jdpneto.stackstackstack.CoordinatorUiState
import com.jdpneto.stackstackstack.MotionSteadiness
import com.jdpneto.stackstackstack.StackCaptureCoordinator
import com.jdpneto.stackstackstack.SteadinessSource
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Look-picker label (mirrors iOS StackMode.shortLabel)
// ---------------------------------------------------------------------------

/** Short display name for the look picker chip. Mirrors iOS [shortLabel] extension. */
val StackMode.shortLabel: String
    get() = when (this) {
        StackMode.NOISE_REDUCTION -> "Detail"
        StackMode.SMOOTH_MOTION   -> "Smooth"
        StackMode.LIGHT_TRAILS    -> "Trails"
        StackMode.LOW_LIGHT_BOOST -> "Night"
        StackMode.DEPTH_OF_FIELD  -> "Depth"
    }

// ---------------------------------------------------------------------------
// CaptureScreen
// ---------------------------------------------------------------------------

/**
 * Full-screen capture view: live preview, look-picker, Pro panel, burst sliders, steadiness
 * overlay, status line, and shutter. 1:1 port of the iOS [CaptureView].
 *
 * The preview is a [SurfaceView] hosted in [AndroidView]. When the service is a
 * [Camera2CaptureService] the [SurfaceHolder.Callback] registers the surface before
 * [StackCaptureCoordinator.startPreview] is called so the Camera2 session includes it.
 * [FakeCaptureService] returns null from startPreview → the [AndroidView] shows a black box.
 */
@Composable
fun CaptureScreen(
    coordinator: StackCaptureCoordinator,
    onOpenEditor: (java.util.UUID) -> Unit
) {
    val uiState by coordinator.uiState.collectAsState()
    val steadiness = coordinator.steadinessSource as? MotionSteadiness
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ── Lazy camera permission (Fix 2) ───────────────────────────────────────
    // Mirror iOS: when the capture screen becomes active and permission is missing, request it
    // once; until granted show the black preview + "Camera access is off…" status line.
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Record that the prompt has been shown — the onboarding camera page's tri-state
        // ("Enable Camera" vs "Open Settings") depends on it. (mirrors iOS .denied semantics)
        AppSettings(context.getSharedPreferences("sss_prefs", Context.MODE_PRIVATE))
            .cameraPermissionRequested = true
        cameraGranted = granted
    }
    // Request once on first composition if not already granted; the onboarding page's request
    // stays as is — this guard only fires in the main-tab capture screen.
    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    // Re-check on every ON_RESUME: a user who grants the permission in system Settings and
    // returns must get a live preview, not a permanently black screen (the launcher callback
    // never fires in that flow).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The result JPEG decoded to a bitmap — decoded once per new result (no disk read).
    var resultBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uiState.lastResultJPEG) {
        resultBitmap = uiState.lastResultJPEG?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size)
        }
    }

    // Focus-indicator state (tap point + locked flag).
    var focusIndicator by remember { mutableStateOf<Pair<Offset, Boolean>?>(null) }
    // Clear the persistent lock square when aeAfLocked drops to false.
    LaunchedEffect(uiState.aeAfLocked) {
        if (!uiState.aeAfLocked && focusIndicator?.second == true) {
            focusIndicator = null
        }
    }

    var showPro by remember { mutableStateOf(false) }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)) {

        // ── Live preview ──────────────────────────────────────────────────────
        CameraPreview(
            coordinator = coordinator,
            cameraGranted = cameraGranted,
            // Same derived gate the coordinator itself uses — previously this omitted
            // processingCount, so the focus square drew while the coordinator ignored the tap.
            tapToFocusEnabled = uiState.tapToFocusEnabled,
            onFocusTap = { x, y, lock ->
                coordinator.focusAndExpose(x, y, lock)
                focusIndicator = Pair(Offset(x, y), lock)
                if (!lock) {
                    // Auto-dismiss transient tap indicator after 1s.
                    scope.launch {
                        kotlinx.coroutines.delay(1_000)
                        if (focusIndicator?.second == false) focusIndicator = null
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Burst sliders (long-exposure looks only, hidden while capturing) ──
        BurstSliders(uiState, coordinator)

        // ── Capture-progress overlay ──────────────────────────────────────────
        CaptureProgressOverlay(uiState)

        // ── Steadiness overlay ────────────────────────────────────────────────
        steadiness?.let { SteadinessOverlay(uiState, it) }

        // ── Focus indicator ───────────────────────────────────────────────────
        FocusIndicatorOverlay(focusIndicator)

        // ── AE/AF lock banner ─────────────────────────────────────────────────
        if (uiState.aeAfLocked) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .semantics { contentDescription = "ae-af-lock-banner" }
                    .testTag("ae-af-lock-banner"),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    "AE/AF LOCK",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier
                        .background(Color.Yellow, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // ── Bottom control stack ──────────────────────────────────────────────
        // Fix 4: align the controls block to BottomCenter of the parent Box so it stays pinned
        // to the bottom in BOTH portrait and landscape. The iOS equivalent is a Spacer() that
        // pushes the controls block to the end of the VStack. Using Alignment.BottomCenter on
        // the Column's Box alignment is more robust than a full-size Column with Arrangement.Bottom
        // (which floats mid-screen in landscape because the content is shorter than the view height).
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Result preview or look label.
            resultBitmap?.let { bmp ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Result preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (uiState.lastSavedID != null) {
                            Button(
                                onClick = { uiState.lastSavedID?.let(onOpenEditor) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) { Text("Edit") }
                        }
                        Button(
                            onClick = { coordinator.dismissResult() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            modifier = Modifier.testTag("dismiss-result")
                        ) { Text("Done") }
                    }
                }
            } ?: run {
                Text(
                    uiState.mode.shortLabel,
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Look picker.
            LookPicker(uiState, coordinator)

            // Pro panel.
            ProPanel(uiState, coordinator, showPro, onToggle = { showPro = !showPro })

            // Status label.
            StatusLabel(uiState, cameraGranted)

            // Cancel-processing button.
            if (uiState.processingCount > 0) {
                TextButton(
                    onClick = { coordinator.cancelProcessing() },
                    modifier = Modifier.testTag("cancel-processing")
                ) {
                    Text("Cancel", color = Color.White)
                }
            }

            // Shutter button.
            ShutterButton(
                busy = uiState.isBusy,
                onShoot = {
                    // Snapshot the display rotation BEFORE shooting so the stacked result is
                    // baked upright for the orientation the shot was framed in (mirrors iOS
                    // reading UIDevice.current.orientation at shutter press,
                    // StackCaptureCoordinator.swift:141).
                    coordinator.displayRotation = currentDisplayRotation(context)
                    scope.launch { coordinator.shoot() }
                }
            )
        }
    }
}

/**
 * The current display rotation ([Surface.ROTATION_*]); [Surface.ROTATION_0] when the context has
 * no display association. minSdk 33 ⇒ [Context.getDisplay] is always available; it throws
 * [UnsupportedOperationException] on non-visual contexts, hence the runCatching.
 */
private fun currentDisplayRotation(context: Context): Int =
    runCatching { context.display?.rotation }.getOrNull() ?: Surface.ROTATION_0

// ---------------------------------------------------------------------------
// Camera preview
// ---------------------------------------------------------------------------

/**
 * A [SurfaceView] that registers itself as the Camera2 preview surface via
 * [Camera2CaptureService.setPreviewSurface] before [StackCaptureCoordinator.startPreview] runs.
 * [FakeCaptureService] ignores [setPreviewSurface] and returns null from [startPreview]
 * (the black box is the natural result). Mirrors the iOS [CameraPreviewView] lifecycle.
 *
 * [cameraGranted]: when false the surface is created but [startPreview] is NOT called (Fix 2).
 * Once [cameraGranted] flips to true the [LaunchedEffect] restarts and calls [startPreview].
 */
@Composable
private fun CameraPreview(
    coordinator: StackCaptureCoordinator,
    cameraGranted: Boolean,
    tapToFocusEnabled: Boolean,
    onFocusTap: (x: Float, y: Float, lock: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    // Start (or restart) preview whenever permission is granted. surfaceReady tracks whether the
    // SurfaceHolder.Callback has fired — we need BOTH the surface and the permission.
    var surfaceReady by remember { mutableStateOf(false) }
    LaunchedEffect(cameraGranted, surfaceReady) {
        if (cameraGranted && surfaceReady) {
            coordinator.startPreview()
        }
    }
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also { sv ->
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // Provide the surface to the real service before startPreview runs.
                        (coordinator.captureService as? Camera2CaptureService)
                            ?.setPreviewSurface(holder.surface)
                        // Signal readiness; the LaunchedEffect above will call startPreview if
                        // permission is already granted (or once it becomes granted).
                        surfaceReady = true
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        surfaceReady = false
                    }
                })
            }
        },
        modifier = modifier
            .pointerInput(tapToFocusEnabled) {
                if (!tapToFocusEnabled) return@pointerInput
                detectTapGestures(
                    onTap = { offset ->
                        val x = (offset.x / size.width).coerceIn(0f, 1f)
                        val y = (offset.y / size.height).coerceIn(0f, 1f)
                        onFocusTap(x, y, false)
                    },
                    onLongPress = { offset ->
                        val x = (offset.x / size.width).coerceIn(0f, 1f)
                        val y = (offset.y / size.height).coerceIn(0f, 1f)
                        onFocusTap(x, y, true)
                    }
                )
            }
    )
}

// ---------------------------------------------------------------------------
// Burst sliders
// ---------------------------------------------------------------------------

/**
 * Vertical Photos/Time sliders pinned to the left/right edges; shown only for the long-exposure
 * looks and hidden while capturing so the progress overlay has clear space.
 * Mirrors iOS [burstSliders]. Test-tagged per accessibility-id convention.
 */
@Composable
private fun BurstSliders(state: CoordinatorUiState, coordinator: StackCaptureCoordinator) {
    if (!state.mode.isLongExposure || state.isCapturing) return
    val busy = state.isBusy

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Photos slider
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Photos", color = Color.White, fontSize = 10.sp)
            Text(
                "${state.burst.photoCount}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.testTag("burst-photos-value")
            )
            var photoCount by remember(state.burst.photoCount) { mutableFloatStateOf(state.burst.photoCount.toFloat()) }
            Slider(
                value = photoCount,
                onValueChange = { photoCount = it },
                onValueChangeFinished = {
                    coordinator.burst = com.jdpneto.stackstackstack.BurstSettings(
                        photoCount = photoCount.toInt(),
                        durationSeconds = state.burst.durationSeconds
                    )
                },
                valueRange = 2f..com.jdpneto.stackstackstack.BurstSettings.MAX_PHOTO_COUNT.toFloat(),
                steps = com.jdpneto.stackstackstack.BurstSettings.MAX_PHOTO_COUNT - 2,
                enabled = !busy,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                modifier = Modifier
                    .width(180.dp)
                    .rotate(-90f)
                    .height(44.dp)
                    .testTag("burst-photos-slider")
                    .semantics { contentDescription = "Photos" }
            )
        }

        // Right: Time slider
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Time", color = Color.White, fontSize = 10.sp)
            Text(
                "${state.burst.durationSeconds.toInt()}s",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.testTag("burst-time-value")
            )
            var duration by remember(state.burst.durationSeconds) { mutableFloatStateOf(state.burst.durationSeconds.toFloat()) }
            Slider(
                value = duration,
                onValueChange = { duration = it },
                onValueChangeFinished = {
                    coordinator.burst = com.jdpneto.stackstackstack.BurstSettings(
                        photoCount = state.burst.photoCount,
                        durationSeconds = duration.toDouble()
                    )
                },
                valueRange = 1f..60f,
                steps = 58,
                enabled = !busy,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                modifier = Modifier
                    .width(180.dp)
                    .rotate(-90f)
                    .height(44.dp)
                    .testTag("burst-time-slider")
                    .semantics { contentDescription = "Time" }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Capture-progress overlay
// ---------------------------------------------------------------------------

/**
 * During a burst: photos-taken counter (left) and seconds-remaining countdown (right).
 * Mirrors iOS [captureProgressOverlay].
 */
@Composable
private fun CaptureProgressOverlay(state: CoordinatorUiState) {
    if (!state.isCapturing) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 60.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ProgressLabel(
            "Photos",
            "${state.capturedCount}/${state.captureTotal}",
            Modifier.testTag("capture-photo-count")
        )
        ProgressLabel(
            "Time",
            "${state.captureRemainingSeconds}s",
            Modifier.testTag("capture-time-remaining")
        )
    }
}

@Composable
private fun ProgressLabel(title: String, value: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(title, color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

// ---------------------------------------------------------------------------
// Steadiness overlay
// ---------------------------------------------------------------------------

/**
 * Two-circle steadiness guide: a fixed big ring + a small circle that drifts with device tilt;
 * green inside tolerance, red outside. Shown while a long-exposure burst or Depth sweep is capturing.
 * Mirrors iOS [steadinessOverlay].
 */
@Composable
private fun SteadinessOverlay(state: CoordinatorUiState, steadiness: MotionSteadiness) {
    if (!state.isCapturing || !state.mode.usesSteadinessGate) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val big   = 120.dp.toPx()
        val small = 36.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxShift = (big / 2f - small / 2f) / kotlin.math.sqrt(2.0).toFloat()
        // Snapshot-state reads (NOT the @Volatile gate fields) — draw-phase reads of snapshot
        // state re-invalidate this Canvas on every sensor update, keeping the dot live.
        val (offX, offY) = steadiness.offset
        val dotColor = if (steadiness.isSteadyUi) Color.Green else Color.Red

        // Big ring
        drawCircle(
            color = Color.White.copy(alpha = 0.7f),
            radius = big / 2f,
            center = Offset(cx, cy),
            style = Stroke(width = 3.dp.toPx())
        )
        // Moving dot
        drawCircle(
            color = dotColor,
            radius = small / 2f,
            center = Offset(
                cx + (offX * maxShift).toFloat(),
                cy + (offY * maxShift).toFloat()
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Focus indicator
// ---------------------------------------------------------------------------

/**
 * Yellow square at the tap location. Transient for a tap, persistent while AE/AF locked.
 * Mirrors iOS [focusIndicatorOverlay].
 */
@Composable
private fun FocusIndicatorOverlay(indicator: Pair<Offset, Boolean>?) {
    indicator ?: return
    val (point, _) = indicator
    Canvas(modifier = Modifier
        .fillMaxSize()
        .testTag("focus-indicator")
        .semantics { contentDescription = "focus-indicator" }) {
        val squareSide = 80.dp.toPx()
        val half = squareSide / 2f
        drawRect(
            color = Color.Yellow,
            topLeft = Offset(point.x * size.width - half, point.y * size.height - half),
            size = androidx.compose.ui.geometry.Size(squareSide, squareSide),
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}

// ---------------------------------------------------------------------------
// Look picker
// ---------------------------------------------------------------------------

/**
 * Horizontal row of look chips. The Depth chip is disabled when `supportsDepth` is false.
 * Each chip carries a test-tag `look-<storageKey>` (e.g. `look-noiseReduction`).
 * Mirrors iOS [lookPicker].
 */
@Composable
private fun LookPicker(state: CoordinatorUiState, coordinator: StackCaptureCoordinator) {
    val busy = state.isBusy
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mode in StackMode.values()) {
                val selected = state.mode == mode
                val disabled = busy || (mode == StackMode.DEPTH_OF_FIELD && !state.supportsDepth)
                FilterChip(
                    selected = selected,
                    onClick = { if (!disabled) coordinator.mode = mode },
                    label = { Text(mode.shortLabel, fontSize = 11.sp) },
                    enabled = !disabled,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color.White,
                        selectedLabelColor = Color.Black,
                        containerColor = Color.White.copy(alpha = 0.18f),
                        labelColor = Color.White
                    ),
                    modifier = Modifier
                        .testTag("look-${mode.storageKey}")
                        .semantics { contentDescription = "look-${mode.storageKey}" }
                )
            }
        }
        if (!state.supportsDepth) {
            Text(
                "Depth needs manual-focus hardware this camera doesn't have",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 9.sp
            )
        }
        if (!state.supportsRAW) {
            // Standard quality caption for non-RAW hardware (spec 2026-06-11 §3).
            Text(
                "Standard quality — RAW not available on this camera",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 9.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Pro panel
// ---------------------------------------------------------------------------

/**
 * Collapsible manual-override panel. For the Depth look it exposes Near/Far sweep-range
 * controls instead of a single Focus slider. Mirrors iOS [proPanel] + [optControl].
 */
@Composable
private fun ProPanel(
    state: CoordinatorUiState,
    coordinator: StackCaptureCoordinator,
    showPro: Boolean,
    onToggle: () -> Unit
) {
    val busy = state.isBusy
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextButton(
            onClick = onToggle,
            enabled = !busy,
            modifier = Modifier
                .testTag("pro-toggle")
                .semantics { contentDescription = "Pro controls toggle" }
        ) {
            Text(if (showPro) "Pro ▴" else "Pro ▾", color = Color.White, fontSize = 11.sp)
        }
        if (showPro) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Frames (non-long-exposure looks only — long-exposure uses burst sliders).
                if (!state.mode.isLongExposure) {
                    OptControl(
                        label = "Frames",
                        value = state.pro.frameCount?.toDouble(),
                        range = 2.0..CaptureRecipe.MAX_BURST_FRAMES.toDouble(),
                        step = 1.0,
                        defaultValue = CaptureRecipe.recipe(state.mode).frameCount.toDouble(),
                        format = { "${it.toInt()}" },
                        enabled = !busy,
                        onValueChange = { v ->
                            coordinator.pro = state.pro.copy(frameCount = v?.toInt())
                        }
                    )
                }
                OptControl(
                    label = "ISO",
                    value = state.pro.iso,
                    range = 50.0..3200.0,
                    step = 10.0,
                    defaultValue = 400.0,
                    format = { "${it.toInt()}" },
                    enabled = !busy,
                    onValueChange = { coordinator.pro = state.pro.copy(iso = it) }
                )
                OptControl(
                    label = "Shutter",
                    value = state.pro.shutterSeconds,
                    range = 0.001..1.0,
                    step = 0.001,
                    defaultValue = 0.02,
                    format = { "1/${(1.0 / it.coerceAtLeast(0.0001)).toInt()}" },
                    enabled = !busy,
                    onValueChange = { coordinator.pro = state.pro.copy(shutterSeconds = it) }
                )
                if (state.mode == StackMode.DEPTH_OF_FIELD) {
                    // Near/Far sweep-range controls (spec 2026-06-10 §6).
                    OptControl(
                        label = "Near",
                        value = state.pro.focusSweepNear,
                        range = 0.0..1.0,
                        step = 0.01,
                        defaultValue = 0.0,
                        format = { "%.2f".format(it) },
                        enabled = !busy,
                        onValueChange = { coordinator.pro = state.pro.copy(focusSweepNear = it) }
                    )
                    OptControl(
                        label = "Far",
                        value = state.pro.focusSweepFar,
                        range = 0.0..1.0,
                        step = 0.01,
                        defaultValue = 1.0,
                        format = { "%.2f".format(it) },
                        enabled = !busy,
                        onValueChange = { coordinator.pro = state.pro.copy(focusSweepFar = it) }
                    )
                } else {
                    OptControl(
                        label = "Focus",
                        value = state.pro.focus,
                        range = 0.0..1.0,
                        step = 0.01,
                        defaultValue = 0.5,
                        format = { "%.2f".format(it) },
                        enabled = !busy,
                        onValueChange = { coordinator.pro = state.pro.copy(focus = it) }
                    )
                }
            }
        }
    }
}

/**
 * A labelled optional control: toggle enables it, showing "Auto" when off and a value slider
 * when on. Mirrors iOS [optControl].
 */
@Composable
private fun OptControl(
    label: String,
    value: Double?,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    defaultValue: Double,
    format: (Double) -> String,
    enabled: Boolean,
    onValueChange: (Double?) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (value != null) "$label: ${format(value)}" else "$label: Auto",
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = value != null,
                onCheckedChange = { on -> onValueChange(if (on) defaultValue else null) },
                enabled = enabled
            )
        }
        if (value != null) {
            var sliderVal by remember(value) { mutableFloatStateOf(value.toFloat()) }
            Slider(
                value = sliderVal,
                onValueChange = { sliderVal = it },
                onValueChangeFinished = { onValueChange(sliderVal.toDouble()) },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Status label
// ---------------------------------------------------------------------------

/**
 * Composing Saved✓/notes/errors into one status line. Mirrors iOS [statusLabel].
 * (spec 2026-06-11 §2)
 *
 * [cameraGranted]: when false shows the iOS-parity "Camera access is off…" denial copy (Fix 2).
 */
@Composable
private fun StatusLabel(state: CoordinatorUiState, cameraGranted: Boolean = true) {
    val text = when {
        !cameraGranted -> "Camera access is off — tap Allow in the system prompt to enable it"
        state.isCapturing -> "Capturing…"
        state.processingCount > 0 ->
            if (state.processingCount > 1)
                "Processing ${state.processingCount}… you can lower your phone"
            else
                "Processing… you can lower your phone"
        state.lastError != null -> "Failed: ${state.lastError}"
        state.lastResultJPEG != null ->
            state.photosExportNote?.let { "Saved ✓ · $it" } ?: "Saved ✓"
        else ->
            state.environmentNote?.let { "Ready · $it" } ?: "Ready"
    }
    val color = if (state.lastError != null) Color.Red else Color.White
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

// ---------------------------------------------------------------------------
// Shutter button
// ---------------------------------------------------------------------------

/**
 * Classic iOS-style shutter circle. Disabled while [busy].
 *
 * Fix 3: testTag and semantics are placed on the Button (the clickable node) — NOT on the
 * outer Box (a non-clickable container). Compose merges child semantics into the nearest
 * clickable ancestor, so a tag on a non-clickable parent is dropped from the a11y tree.
 * Putting them directly on the Button guarantees the node appears in uiautomator dumps.
 */
@Composable
private fun ShutterButton(busy: Boolean, onShoot: () -> Unit) {
    Box(
        modifier = Modifier.size(72.dp),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onShoot,
            enabled = !busy,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                disabledContainerColor = Color.White.copy(alpha = 0.4f)
            ),
            modifier = Modifier
                .size(72.dp)
                .testTag("shutter")
                .semantics { contentDescription = "Shutter" }
        ) { /* no label; the circle IS the button */ }
    }
}
