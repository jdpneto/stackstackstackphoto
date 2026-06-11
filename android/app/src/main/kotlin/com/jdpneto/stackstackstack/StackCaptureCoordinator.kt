package com.jdpneto.stackstackstack

import android.view.Surface
import com.jdpneto.stackengine.DepthConfig
import com.jdpneto.stackengine.FocusStacker
import com.jdpneto.stackengine.ImageGeometry
import com.jdpneto.stackengine.OutputTransform
import com.jdpneto.stackengine.Pipeline
import com.jdpneto.stackengine.PixelImage
import com.jdpneto.stackengine.StackCancellationException
import com.jdpneto.stackengine.StackMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.util.Date

// ---------------------------------------------------------------------------
// UiState — single data class carrying all published properties (Compose-friendly copy updates).
// Property names are IDENTICAL to iOS to keep the porting contract intact.
// ---------------------------------------------------------------------------

/**
 * All coordinator-published state in one data class for Compose.
 * Property names match the iOS @Published counterparts exactly so ports stay 1:1.
 * (spec §4; mirrors iOS @Published properties of [StackCaptureCoordinator])
 */
data class CoordinatorUiState(
    /** True only while the camera burst is running — the arms-up phase that gates the shutter. */
    val isCapturing: Boolean = false,
    /** How many stacks are developing/stacking in the background (queued + in-flight). */
    val processingCount: Int = 0,
    /** The most recent finished JPEG, published so the UI can show it without re-reading disk. */
    val lastResultJPEG: ByteArray? = null,
    /** The id of the most recent saved stack (for the editor). */
    val lastSavedID: java.util.UUID? = null,
    /** The most recent failure message (capture or processing); cleared when a new shot starts. */
    val lastError: String? = null,
    /**
     * Whether the camera can run a Depth focus sweep (manual-focus hardware). Optimistic true
     * until the preview configures the session; the UI disables the Depth chip when false.
     */
    val supportsDepth: Boolean = true,
    /**
     * Whether the camera vends Bayer RAW frames the engine can decode. Optimistic true until
     * the preview configures; displayed in the Settings capability report.
     */
    val supportsRAW: Boolean = true,
    /** True while AF/AE are locked via a long-press (drives the "AE/AF LOCK" banner). */
    val aeAfLocked: Boolean = false,
    /** Live capture progress — running frame count during the burst. */
    val capturedCount: Int = 0,
    /** Total frame count for the current burst (recipe.frameCount). */
    val captureTotal: Int = 0,
    /** Countdown in whole seconds for the current burst window. */
    val captureRemainingSeconds: Int = 0,
    /** The currently selected look. */
    val mode: StackMode = StackMode.NOISE_REDUCTION,
    /** Manual Pro overrides; all-null = full auto. */
    val pro: ProControls = ProControls.auto,
    /** User burst length/window for the long-exposure looks. */
    val burst: BurstSettings = BurstSettings.default,
    /**
     * Library/encode format for new captures. Kept in sync from AppSettings by the app root —
     * the coordinator is ignorant of the settings object. Snapshotted at shutter press. (spec §4)
     */
    val exportFormat: ImageEncoder.Format = ImageEncoder.Format.JPEG,
    /** Mirror saves into the system photo library (Settings toggle; synced by the app root). */
    val saveToPhotosEnabled: Boolean = false,
    /**
     * Non-blocking note when a Photos export fails (the in-app save already succeeded). (spec §5)
     */
    val photosExportNote: String? = null,
    /**
     * System-condition advisory shown on the capture screen (thermal warning, low-battery note);
     * null when conditions are nominal. Recomputed at each shutter press. (spec 2026-06-11 §2)
     */
    val environmentNote: String? = null
) {
    // NOTE: data-class default equals/hashCode are deliberate. lastResultJPEG compares by
    // ByteArray identity, which is correct here — every finished stack publishes a freshly
    // allocated array, so identity inequality is exactly "a new result arrived".

    /**
     * Gates the shutter. Capturing during a background stack is unreliable — the all-core
     * develop/align/stack starves the camera — so the shutter is disabled while processing too.
     * Mirrors iOS [isBusy]. (design 2026-06-07 §7)
     */
    val isBusy: Boolean get() = isCapturing || processingCount > 0

    /**
     * Tap-to-focus is available only in full-auto exposure/focus (no manual Pro override) and
     * while the shutter is free. Single source of truth for BOTH the coordinator gate and the
     * Compose tap gesture gate. (design tap-to-focus §3.3)
     */
    val tapToFocusEnabled: Boolean get() = !isBusy && !pro.hasManualFocusOrExposure
}

// ---------------------------------------------------------------------------
// Cancellation token — mirrors iOS CancellationToken (object identity, not coroutine cancel)
// ---------------------------------------------------------------------------

/**
 * A simple per-stack cancellation token. Coordinator-level cancellation uses this object
 * (not coroutine cancellation) so the heavy CPU work inside withContext(processing) sees the
 * signal without the coroutines machinery swallowing a CancellationException prematurely.
 * Mirrors the iOS [CancellationToken] pattern verbatim. (design 2026-06-07 §7)
 */
class CancellationToken {
    @Volatile
    private var _cancelled = false

    val isCancelled: Boolean get() = _cancelled

    fun cancel() {
        _cancelled = true
    }
}

// ---------------------------------------------------------------------------
// ProcessingError
// ---------------------------------------------------------------------------

/** Background-processing failures surfaced to the capture screen's status label. */
sealed class ProcessingError(message: String) : Exception(message) {
    object FocusStackFailed : ProcessingError(
        "Couldn't combine the focus brackets. Please try again."
    )
}

// ---------------------------------------------------------------------------
// StackCaptureCoordinator
// ---------------------------------------------------------------------------

/**
 * Orchestrates one shot: a fast foreground BURST (the arms-up step), then develop+align+stack+save
 * SERIALIZED in the background. The shutter is gated only by the (short) capture phase, so the
 * user can take the next shot while previous ones are still stacking.
 *
 * Threading model:
 * - All state mutations happen on [mainScope] (Dispatchers.Main.immediate in production; an
 *   injected test dispatcher in unit tests) — equivalent to iOS @MainActor confinement.
 * - Heavy processing (develop+align+stack+encode) runs on [processingDispatcher] — a single-
 *   thread executor that serialises stacks the same way [processingTail] does on iOS.
 * - [mainScope] is injected (constructor) so Robolectric tests drive it deterministically.
 *
 * @param capture             Camera service (real [Camera2CaptureService] in production,
 *                            [FakeCaptureService] in tests).
 * @param store               Library persistence (injected for tests).
 * @param mainScope           Coroutine scope running on the main thread. Inject for tests.
 * @param processingDispatcher Single-thread dispatcher for the CPU-heavy pipeline. Inject for tests.
 * @param photosExporter      Seam: injectable so tests don't touch the real MediaStore. (spec §5)
 * @param encodeImage         Seam: injectable so tests can force an encoder failure to exercise
 *                            the HEIC→JPEG fallback path. (spec §8)
 * @param environment         Seam: injectable so tests simulate thermal/battery/disk states. (spec §2)
 * @param steadiness          Steadiness sensor; injected for tests. (design 2026-06-07 §8)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StackCaptureCoordinator(
    private val capture: CaptureService,
    private val store: LibraryStore,
    private val mainScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
    private val processingDispatcher: CoroutineDispatcher = newSingleThreadContext("sss.processing"),
    var photosExporter: suspend (ByteArray, ImageEncoder.Format) -> Unit = { _, _ -> },
    var encodeImage: (ByteArray, Int, Int, ImageEncoder.Format, Double, ImageEncoder.ExifMetadata?) -> ByteArray =
        { rgba8, w, h, format, quality, exif ->
            ImageEncoder.encode(rgba8, w, h, format, quality, exif)
        },
    var environment: CaptureEnvironment = CaptureEnvironment(
        thermalStatus    = { 0 },   // THERMAL_STATUS_NONE — safe default; live() injected in prod
        batteryLevel     = { 1f },
        batteryCharging  = { false },
        freeDiskBytes    = { Long.MAX_VALUE }
    ),
    private val steadiness: SteadinessSource = AlwaysSteadySource()
) {
    /**
     * Current display rotation ([Surface.ROTATION_*]), snapshotted at each shutter press.
     * The UI sets this before shooting (equivalent to iOS reading [UIDevice.current.orientation]
     * from the @MainActor). Defaults to [Surface.ROTATION_0] (portrait).
     */
    var displayRotation: Int = android.view.Surface.ROTATION_0
    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private val _uiState = MutableStateFlow(CoordinatorUiState())

    /** All published properties in one observable; UI observes this in Compose. */
    val uiState: StateFlow<CoordinatorUiState> = _uiState.asStateFlow()

    // Convenience accessors mirroring iOS property names (for tests / non-Compose callers).
    val isCapturing:             Boolean               get() = _uiState.value.isCapturing
    val processingCount:         Int                   get() = _uiState.value.processingCount
    val lastResultJPEG:          ByteArray?            get() = _uiState.value.lastResultJPEG
    val lastSavedID:             java.util.UUID?       get() = _uiState.value.lastSavedID
    val lastError:               String?               get() = _uiState.value.lastError
    val supportsDepth:           Boolean               get() = _uiState.value.supportsDepth
    val supportsRAW:             Boolean               get() = _uiState.value.supportsRAW
    val aeAfLocked:              Boolean               get() = _uiState.value.aeAfLocked
    val capturedCount:           Int                   get() = _uiState.value.capturedCount
    val captureTotal:            Int                   get() = _uiState.value.captureTotal
    val captureRemainingSeconds: Int                   get() = _uiState.value.captureRemainingSeconds
    val photosExportNote:        String?               get() = _uiState.value.photosExportNote
    val environmentNote:         String?               get() = _uiState.value.environmentNote

    // Mutable settable properties forwarded into state (mirrors iOS @Published var).
    var mode: StackMode
        get() = _uiState.value.mode
        set(value) {
            if (value != _uiState.value.mode) {
                // Switching looks drops the on-screen result — a new look implies a new shot.
                _uiState.update { it.copy(
                    mode            = value,
                    lastResultJPEG  = null,
                    lastSavedID     = null,
                    lastError       = null,
                    aeAfLocked      = false
                ) }
            }
        }

    var pro: ProControls
        get() = _uiState.value.pro
        set(value) {
            if (value != _uiState.value.pro) {
                // Entering manual focus/exposure drops any AE/AF lock so the banner doesn't linger.
                val clearLock = value.hasManualFocusOrExposure
                _uiState.update { it.copy(
                    pro        = value,
                    aeAfLocked = if (clearLock) false else it.aeAfLocked
                ) }
            }
        }

    var burst: BurstSettings
        get() = _uiState.value.burst
        set(value) { _uiState.update { it.copy(burst = value) } }

    var exportFormat: ImageEncoder.Format
        get() = _uiState.value.exportFormat
        set(value) { _uiState.update { it.copy(exportFormat = value) } }

    var saveToPhotosEnabled: Boolean
        get() = _uiState.value.saveToPhotosEnabled
        set(value) { _uiState.update { it.copy(saveToPhotosEnabled = value) } }

    // Read-only library access for the editor — matches iOS `var library: LibraryStore { store }`.
    val library: LibraryStore get() = store

    // -----------------------------------------------------------------------
    // B3 UI seams — expose services the Compose layer needs for preview wiring
    // -----------------------------------------------------------------------

    /**
     * The underlying [CaptureService]; the Compose camera-preview view casts it to
     * [Camera2CaptureService] to call [Camera2CaptureService.setPreviewSurface] before
     * [startPreview] is called. Read-only; the service is owned by this coordinator.
     */
    val captureService: CaptureService get() = capture

    /**
     * The steadiness sensor; the Compose capture screen reads [SteadinessSource.offset]
     * and [SteadinessSource.isSteady] to drive the steadiness overlay.
     */
    val steadinessSource: SteadinessSource get() = steadiness

    // -----------------------------------------------------------------------
    // Derived state
    // -----------------------------------------------------------------------

    /** Gates the shutter. Delegates to [CoordinatorUiState.isBusy] — one source of truth. */
    val isBusy: Boolean get() = _uiState.value.isBusy

    /** Delegates to [CoordinatorUiState.tapToFocusEnabled] — one source of truth. */
    val tapToFocusEnabled: Boolean get() = _uiState.value.tapToFocusEnabled

    // -----------------------------------------------------------------------
    // Serial processing tail (mirrors iOS processingTail: Task<Void,Never>?)
    // -----------------------------------------------------------------------

    /** Tail of the serial background-processing chain (mirrors iOS processingTail). */
    private var processingTail: Job? = null

    /** Live cancellation tokens for queued/in-flight stacks; cancelled together by cancelProcessing. */
    private val activeTokens = mutableListOf<CancellationToken>()

    /** Job running the capture-countdown ticks; cancelled when the burst ends. */
    private var countdownJob: Job? = null

    // -----------------------------------------------------------------------
    // Preview
    // -----------------------------------------------------------------------

    /**
     * Start the live preview and return a [Surface] (null if unavailable, e.g. the fake).
     * Also the earliest point the device capability probe is meaningful (session configured).
     * Mirrors iOS [startPreview]. Must be called on the main thread.
     */
    suspend fun startPreview(): Surface? {
        val surface = capture.startPreview()
        _uiState.update { it.copy(
            supportsDepth = capture.supportsDepthOfField,
            supportsRAW   = capture.supportsRAWCapture
        ) }
        return surface
    }

    // -----------------------------------------------------------------------
    // Focus / exposure tap
    // -----------------------------------------------------------------------

    /**
     * Focus + meter exposure at a normalized device point; [lock] (long-press) holds AF/AE and
     * shows the banner. Mirrors iOS [focusAndExpose(atDevicePoint:lock:)]. (design tap-to-focus §3.3)
     */
    fun focusAndExpose(x: Float, y: Float, lock: Boolean) {
        if (!tapToFocusEnabled) return   // ignore stale gestures fired as a burst/manual mode began
        capture.setFocusExposure(x, y, lock)
        _uiState.update { it.copy(aeAfLocked = lock) }
    }

    // -----------------------------------------------------------------------
    // Shoot
    // -----------------------------------------------------------------------

    /**
     * Capture a burst (foreground, fast), then queue the heavy processing in the background.
     * Mirrors iOS [shoot()]. Must be called on the main thread.
     */
    suspend fun shoot() {
        if (isBusy) return   // reject a rapid double-tap, and a shot during background stacking

        // Environment policy (spec 2026-06-11 §2): hard blocks first, then advisory notes.
        // Guards run AFTER isBusy and BEFORE per-shot clears so a blocked shot sets lastError and
        // returns without touching the rest of the published state.
        val thermal = ThermalLevel.from(environment.thermalStatus())
        if (thermal == ThermalLevel.CRITICAL) {
            _uiState.update { it.copy(environmentNote = null, lastError = "Too hot — let the phone cool down.") }
            return
        }
        if (environment.freeDiskBytes() < CaptureEnvironment.MINIMUM_FREE_BYTES) {
            _uiState.update { it.copy(environmentNote = null, lastError = "Not enough storage to capture.") }
            return
        }
        val battery = environment.batteryLevel()
        // Thermal wins over battery: .serious halves the burst (note explains the shortened shot).
        val envNote: String? = when {
            thermal == ThermalLevel.SERIOUS                                              -> "Device is warm — shorter bursts"
            battery >= 0f && battery < CaptureEnvironment.LOW_BATTERY_THRESHOLD
                && !environment.batteryCharging()                                        -> "Low battery"
            else                                                                         -> null
        }

        // Snapshot shutter-press values (mirrors iOS snapshot pattern — these must NOT be re-read
        // inside the background processing, which runs on a different dispatcher).
        val mode            = this.mode
        val format          = this.exportFormat
        val exportToPhotos  = this.saveToPhotosEnabled
        val encode          = this.encodeImage
        val orientationTurns = CaptureOrientation.quarterTurns(displayRotation)
        val capturedAt = Date()

        _uiState.update { it.copy(
            lastError        = null,
            lastResultJPEG   = null,   // drop the previous preview; a new shot is on the way
            photosExportNote = null,   // clear any prior export failure note
            aeAfLocked       = false,  // a long-press lock is superseded once a shot begins
            environmentNote  = envNote
        ) }

        val recipe = makeRecipe(for_ = mode, thermal = thermal)

        _uiState.update { it.copy(
            isCapturing             = true,
            capturedCount           = 0,
            captureTotal            = recipe.frameCount,
            captureRemainingSeconds = recipe.durationSeconds.toInt().let { if (recipe.durationSeconds > it) it + 1 else it }
        ) }

        startCaptureCountdown()

        // Steadiness gate (long-exposure looks + Depth).
        val isSteady: () -> Boolean
        if (mode.usesSteadinessGate) {
            steadiness.start()
            isSteady = { steadiness.isSteady }
        } else {
            isSteady = { true }
        }

        val onProgress: (Int) -> Unit = { n ->
            _uiState.update { it.copy(capturedCount = n) }
        }

        val capturedBurst: CapturedBurst
        try {
            android.util.Log.i("SSSCoord", "shoot: mode=$mode frames=${recipe.frameCount} gate=${mode.usesSteadinessGate}")
            capturedBurst = capture.captureBurst(recipe = recipe, isSteady = isSteady, onProgress = onProgress)
            android.util.Log.i("SSSCoord", "shoot: burst returned count=${capturedBurst.count}")
        } catch (e: Exception) {
            // State only — resource cleanup (steadiness + countdown) is owned by `finally` below,
            // which runs before this `return` completes.
            _uiState.update { it.copy(
                isCapturing             = false,
                lastError               = e.message ?: "Capture failed.",
                captureRemainingSeconds = 0
            ) }
            return
        } finally {
            if (mode.usesSteadinessGate) steadiness.stop()
            countdownJob?.cancel(); countdownJob = null
        }

        // Arms-up done — re-enable the shutter immediately (mirrors iOS `isCapturing = false` placement).
        _uiState.update { it.copy(
            isCapturing             = false,
            captureRemainingSeconds = 0
        ) }

        if (capturedBurst.isEmpty) {
            _uiState.update { it.copy(lastError = "No frames were captured.") }
            return
        }

        enqueueProcessing(
            payload           = capturedBurst.payload,
            frameCount        = capturedBurst.count,
            info              = capturedBurst.info,
            mode              = mode,
            format            = format,
            orientationTurns  = orientationTurns,
            capturedAt        = capturedAt,
            exportToPhotos    = exportToPhotos,
            encode            = encode
        )
    }

    /**
     * Clear the on-screen result preview, returning the capture screen to the live viewfinder.
     * Mirrors iOS [dismissResult()].
     */
    fun dismissResult() {
        _uiState.update { it.copy(
            lastResultJPEG = null,
            lastSavedID    = null,
            lastError      = null   // symmetric with iOS: no stale "Failed…" after dismiss
        ) }
    }

    // -----------------------------------------------------------------------
    // Countdown
    // -----------------------------------------------------------------------

    /** One-second ticks decrementing captureRemainingSeconds to 0 while the burst runs. */
    private fun startCaptureCountdown() {
        countdownJob?.cancel()
        countdownJob = mainScope.launch {
            while (_uiState.value.captureRemainingSeconds > 0) {
                kotlinx.coroutines.delay(1_000L)
                _uiState.update { it.copy(captureRemainingSeconds = maxOf(0, it.captureRemainingSeconds - 1)) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Recipe
    // -----------------------------------------------------------------------

    /**
     * Build the capture recipe for [mode]. Long-exposure looks take their length/window from
     * [burst] plus any manual Pro exposure overrides; static looks use the fixed per-look recipe
     * with the full Pro overrides. When the thermal state is SERIOUS the burst is halved
     * (floor, min 2). (design 2026-06-07 §5; spec 2026-06-11 §2)
     */
    private fun makeRecipe(for_: StackMode, thermal: ThermalLevel): CaptureRecipe {
        val mode = for_
        var recipe: CaptureRecipe = if (mode.isLongExposure) {
            CaptureRecipe(
                frameCount           = burst.photoCount,
                durationSeconds      = burst.durationSeconds,
                manualISO            = pro.iso?.toFloat(),
                manualShutterSeconds = pro.shutterSeconds,
                manualFocus          = pro.focus?.toFloat()
            )
        } else {
            CaptureRecipe.recipe(mode).applying(pro)
        }
        // Thermal throttle: halve the burst when the device is seriously hot.
        if (thermal == ThermalLevel.SERIOUS) {
            val halved = maxOf(2, recipe.frameCount / 2)
            val newSweep = recipe.focusSweep?.let { s ->
                FocusSweep(near = s.near, far = s.far, steps = halved)
            }
            recipe = recipe.copy(frameCount = halved, focusSweep = newSweep)
        }
        return recipe
    }

    // -----------------------------------------------------------------------
    // Processing queue
    // -----------------------------------------------------------------------

    /**
     * Queue develop→align→stack→encode→save behind any earlier job (serial), running the heavy
     * work off the main thread, then publish the result. The shutter stays free meanwhile.
     * Mirrors iOS [enqueueProcessing]. (design 2026-06-07 §7)
     *
     * Memory: the closure deliberately captures only [payloadRef] (a one-shot holder that
     * [makeResult] empties as soon as develop has consumed the frames) plus the pre-snapshotted
     * [frameCount]/[info] — never the burst itself. Capturing the burst would pin every raw
     * mosaic (~24 MB each) through align/stack/encode, defeating the heap budget.
     */
    private fun enqueueProcessing(
        payload: CapturedBurst.Payload,
        frameCount: Int,
        info: CaptureInfo?,
        mode: StackMode,
        format: ImageEncoder.Format,
        orientationTurns: Int,
        capturedAt: Date,
        exportToPhotos: Boolean,
        encode: (ByteArray, Int, Int, ImageEncoder.Format, Double, ImageEncoder.ExifMetadata?) -> ByteArray
    ) {
        _uiState.update { it.copy(processingCount = it.processingCount + 1) }
        val token = CancellationToken()
        activeTokens.add(token)
        val previous = processingTail
        // One-shot holder; do NOT reference `payload` inside the launch block (see kdoc above).
        val payloadRef = java.util.concurrent.atomic.AtomicReference<CapturedBurst.Payload?>(payload)
        processingTail = mainScope.launch {
            previous?.join()   // serialize behind earlier jobs (mirrors iOS `await previous?.value`)
            try {
                if (token.isCancelled) return@launch   // cancelled while queued

                val (encodedData, encodedRef, encodedFormat) = withContext(processingDispatcher) {
                    makeResult(
                        payloadRef       = payloadRef,
                        frameCount       = frameCount,
                        mode             = mode,
                        format           = format,
                        orientationTurns = orientationTurns,
                        capturedAt       = capturedAt,
                        info             = info,
                        shouldCancel     = { token.isCancelled },
                        encode           = encode
                    )
                }

                if (token.isCancelled) return@launch   // cancelled during processing → discard

                val saved = store.save(
                    result        = encodedData,
                    reference     = encodedRef,
                    format        = encodedFormat,
                    mode          = mode.storageKey,
                    frameCount    = frameCount,
                    iso           = info?.iso,
                    shutterSeconds = info?.shutterSeconds
                )

                // lastResultJPEG keeps its historical name; it stores the display bytes —
                // ImageDecoder can decode both JPEG and HEIC.
                _uiState.update { it.copy(
                    lastResultJPEG = encodedData,
                    lastSavedID    = saved.id
                ) }

                if (exportToPhotos) {
                    val exporter    = photosExporter
                    val payloadData = encodedData
                    val payloadFmt  = encodedFormat
                    mainScope.launch {
                        try {
                            exporter(payloadData, payloadFmt)
                        } catch (e: Exception) {
                            _uiState.update { it.copy(
                                photosExportNote = "Photos export failed — check Settings ▸ Privacy"
                            ) }
                        }
                    }
                }
            } catch (e: StackCancellationException) {
                return@launch   // discarded mid-stack — not an error
            } catch (e: Exception) {
                if (!token.isCancelled) {
                    _uiState.update { it.copy(lastError = e.message ?: "Processing failed.") }
                }
            } finally {
                payloadRef.set(null)   // cancel/error paths must not pin the frames either
                activeTokens.remove(token)
                _uiState.update { it.copy(processingCount = it.processingCount - 1) }
            }
        }
    }

    /**
     * Await all queued/in-flight background processing (tests; "wait for everything to settle").
     * Mirrors iOS [awaitProcessing()].
     */
    suspend fun awaitProcessing() {
        processingTail?.join()
    }

    /**
     * Cancel every queued/in-flight background stack. The per-job token (NOT coroutine cancellation
     * — the heavy work runs in withContext, which doesn't propagate the token) makes each job discard
     * its partial work without saving (no error surfaced). (design 2026-06-07 §7)
     */
    fun cancelProcessing() {
        for (token in activeTokens) token.cancel()
        activeTokens.clear()
    }

    // -----------------------------------------------------------------------
    // makeResult — CPU-heavy develop → downscale → align → stack → encode
    // -----------------------------------------------------------------------

    /**
     * Managed working resolution (long-edge px) the stack is processed at. Downscaling the
     * developed frames before align/stack is the dominant speed + memory win. (design §4)
     * Used as-is by the STREAMING looks (peak ≈ 2–3 frames regardless of burst length); the
     * BATCH looks derive a heap-aware value from it via [heapAwareWorkingResolution].
     */
    private val managedWorkingResolution: Int = MANAGED_WORKING_RESOLUTION

    companion object {
        /** iOS `managedWorkingResolution` (long-edge px) — the ceiling on any working resolution. */
        const val MANAGED_WORKING_RESOLUTION = 2400

        /**
         * Quality floor (long-edge px) for the heap-derived working-resolution BUDGET — below this
         * the output stops looking like a photo upgrade. A stack that doesn't fit even at the floor
         * proceeds at the floor (best effort) rather than refusing the shot. NOTE: the engine's
         * downscale is repeated 2× halving (it cannot hit an arbitrary target), so the ACHIEVED
         * edge ([achievableWorkingResolution]) can land below this floor when the source's nearest
         * halving step undershoots it — the next step up would not fit the heap budget.
         */
        const val MIN_WORKING_RESOLUTION = 1200

        /**
         * Largest working long edge whose BATCH stack fits in half the Java heap.
         *
         * The batch peak resident set (in frame-equivalents) is owned by the engine next to the
         * code it describes — [Pipeline.batchPeakFrameEquivalents] for the align+reduce path,
         * [FocusStacker.peakFrameEquivalents] for the Depth blend peak — and injected here, so an
         * engine refactor updates this budget math with it. On iOS the ~3GB per-app ceiling makes
         * 2400px safe, but Android's Java heap (512MB with largeHeap on Pixel) cannot hold an
         * 8-frame batch at the sensor's binned resolution. Derive the largest long edge that fits
         * half the heap, clamped to [1200, managedWorkingResolution]. Android deviation: output
         * long edge may be below iOS's 2400 on small-heap devices.
         *
         * Pure function of (frameCount, frameEquivalents, maxMemory) so unit tests can pin the
         * heap. Frames are linear RGB float (12 bytes/px) and assumed 4:3.
         */
        fun heapAwareWorkingResolution(
            frameCount: Int,
            frameEquivalents: Double = Pipeline.batchPeakFrameEquivalents(frameCount),
            maxMemory: Long = Runtime.getRuntime().maxMemory()
        ): Int {
            val budgetBytes = maxMemory / 2.0                       // leave half the heap for everything else
            val bytesPerFrame = budgetBytes / frameEquivalents
            val pixels = bytesPerFrame / 12.0                       // 3 floats (RGB) per pixel
            val edge = kotlin.math.sqrt(pixels / 0.75)              // 4:3: pixels = edge · (0.75·edge)
            val rounded = (edge.toInt() / 8) * 8                    // round down to a multiple of 8
            return rounded.coerceIn(MIN_WORKING_RESOLUTION, MANAGED_WORKING_RESOLUTION)
        }

        /**
         * The working long edge the engine will ACTUALLY produce from [sourceLongEdge] under a
         * [budgetEdge] target. The engine's downscale is repeated 2× Gaussian reduction with
         * ceiling dimensions (Kotlin and Swift `Pipeline.downscaleOne` both halve via
         * `ImagePyramid.reduce`; neither resizes to an exact target), so the achieved edge is
         * ceil(source / 2^k) for the smallest k that fits the budget — NOT the budget number.
         * E.g. a ~2016px binned RAW source budgeted at 1200 lands at 1008; a 1500px fallback
         * frame lands at 750. Feeding the engine this value yields bit-identical pixels to
         * feeding it [budgetEdge] (the halving loop stops at the same step); the point is that
         * the heap accounting and the comments describe a resolution that actually exists.
         */
        fun achievableWorkingResolution(budgetEdge: Int, sourceLongEdge: Int): Int {
            if (sourceLongEdge <= 0) return budgetEdge      // unknown source — keep the budget value
            var edge = sourceLongEdge                       // already fits → passes through unhalved
            while (edge > budgetEdge) { edge = (edge + 1) / 2 }
            return edge
        }
    }

    /**
     * Depth working resolution — the engine's managed preset is the single source of truth.
     * Mirrors iOS `depthWorkingResolution = DepthConfig.auto.workingResolution`.
     */
    private val depthWorkingResolution: Int? = DepthConfig.auto.workingResolution

    /**
     * DEBUG: dump the developed working-resolution frames of each RAW burst to
     * [diagDirectory]/<storageKey>-<epochMs>/frame-NN.jpg — for offline alignment debugging AND
     * for extracting real-capture fixtures for the engine's regression harness.
     * Mirrors iOS `dumpFramesForDiagnostics`. Off by default; MainActivity enables it from a
     * debug-gated `dumpFrames` intent extra. @Volatile: set on the main thread, read on
     * [processingDispatcher] — no other synchronization guards these two fields.
     */
    @Volatile var dumpFramesForDiagnostics: Boolean = false

    /** Where diagnostic frame dumps go (set by the activity to filesDir/diag). */
    @Volatile var diagDirectory: java.io.File? = null

    /**
     * Develop + dump a RAW burst's frames WITHOUT consuming the one-shot payload holder (the
     * normal pipeline re-develops them — acceptable double work behind a debug flag). JPEG q95
     * at the BATCH working resolution for every look — for Detail/Night/Depth on a small-heap
     * device that matches the pipeline's input; for the streaming looks (which run at
     * [managedWorkingResolution]) the dump is one halving BELOW device scale. Good enough for a
     * regression contract and offline debugging; NOT a device-scale fidelity guarantee.
     *
     * Best-effort by contract (iOS parity): a dump failure of ANY kind — IO, or the batch
     * develop OOMing on a long streaming burst the real pipeline would have streamed — must
     * never cost the user's shot, hence the blanket Throwable catch. Prior dumps are cleared
     * first so files/diag doesn't grow without bound across captures.
     */
    private fun dumpDiagFrames(
        payloadRef: java.util.concurrent.atomic.AtomicReference<CapturedBurst.Payload?>,
        mode: StackMode,
        frameCount: Int,
        encode: (ByteArray, Int, Int, ImageEncoder.Format, Double, ImageEncoder.ExifMetadata?) -> ByteArray
    ) {
        try {
            val dir = diagDirectory ?: return
            val frames = (payloadRef.get() as? CapturedBurst.Payload.Raw)?.frames ?: return
            dir.listFiles()?.filter { it.name.startsWith("${mode.storageKey}-") }
                ?.forEach { it.deleteRecursively() }
            val resolution = achievableWorkingResolution(
                budgetEdge     = heapAwareWorkingResolution(frameCount = frameCount),
                sourceLongEdge = peekRawLongEdge(payloadRef) / 2
            )
            val developed = Pipeline.developedFrames(frames, binnedDevelop = true, workingResolution = resolution)
            val out = java.io.File(dir, "${mode.storageKey}-${System.currentTimeMillis()}")
            if (!out.mkdirs()) return
            developed.forEachIndexed { i, img ->
                val rgba = OutputTransform.encodeSRGB8(img)
                java.io.File(out, "frame-%02d.jpg".format(i)).writeBytes(
                    encode(rgba, img.width, img.height, ImageEncoder.Format.JPEG, 0.95, null)
                )
            }
        } catch (t: Throwable) {
            android.util.Log.w("SSSCoord", "diagnostic frame dump failed (shot unaffected)", t)
        }
    }

    /**
     * Long edge of a RAW burst's mosaic frames, peeked WITHOUT consuming the one-shot holder.
     * Only an Int escapes — the frames stay unpinned (the helper's locals die at return).
     * Returns 0 when the holder is empty or holds a different payload type.
     */
    private fun peekRawLongEdge(
        ref: java.util.concurrent.atomic.AtomicReference<CapturedBurst.Payload?>
    ): Int = (ref.get() as? CapturedBurst.Payload.Raw)?.frames?.firstOrNull()
        ?.let { maxOf(it.width, it.height) } ?: 0

    /** Long edge of a developed (non-RAW fallback) burst's frames; same peek contract as above. */
    private fun peekDevelopedLongEdge(
        ref: java.util.concurrent.atomic.AtomicReference<CapturedBurst.Payload?>
    ): Int = (ref.get() as? CapturedBurst.Payload.Developed)?.images?.firstOrNull()
        ?.let { maxOf(it.width, it.height) } ?: 0

    /**
     * CPU-heavy develop → downscale → align → stack → encode. Runs on [processingDispatcher].
     * Returns (data, reference, format) where format may differ from the requested format if HEIC
     * encoding failed and fell back to JPEG — only the encode step is retried, not the full pipeline.
     * [reference] is the encoded aligned anchor frame (same orientation + format as the result);
     * null for depth stacks (no blend semantics) and when the reference encode itself fails (safe
     * degradation — never loses the main result). (spec §8, spec 2026-06-11 §3)
     *
     * [payloadRef] is a ONE-SHOT holder: each branch takes the payload exactly once, passing the
     * frames straight into the consuming pipeline call — so the burst frames become garbage as
     * soon as develop has consumed them, instead of staying pinned through align/stack/encode.
     * [frameCount] is the pre-snapshotted burst count (== frames/images size) used everywhere a
     * size is needed after the payload has been dropped.
     */
    private fun makeResult(
        payloadRef: java.util.concurrent.atomic.AtomicReference<CapturedBurst.Payload?>,
        frameCount: Int,
        mode: StackMode,
        format: ImageEncoder.Format,
        orientationTurns: Int,
        capturedAt: Date,
        info: CaptureInfo?,
        shouldCancel: () -> Boolean,
        encode: (ByteArray, Int, Int, ImageEncoder.Format, Double, ImageEncoder.ExifMetadata?) -> ByteArray
    ): Triple<ByteArray, ByteArray?, ImageEncoder.Format> {

        // Empty the holder — after this call nothing outside the consuming pipeline call
        // references the burst frames.
        fun takeRawFrames() =
            (payloadRef.getAndSet(null) as? CapturedBurst.Payload.Raw)?.frames
                ?: error("burst payload already consumed or wrong type")
        fun takeDevelopedImages() =
            (payloadRef.getAndSet(null) as? CapturedBurst.Payload.Developed)?.images
                ?: error("burst payload already consumed or wrong type")

        val result: PixelImage
        var referencePixels: PixelImage? = null   // the aligned anchor; null when !mode.supportsBlendReference

        if (dumpFramesForDiagnostics) dumpDiagFrames(payloadRef, mode, frameCount, encode)

        when (payloadRef.get()) {
            is CapturedBurst.Payload.Raw -> {
                // RAW quality path: develop → downscale → align → stack.
                if (mode.isLongExposure) {
                    // Streaming: one developed+aligned frame in flight at a time; cancellable between frames.
                    val (res, ref) = Pipeline.reduceStreamingWithReference(
                        frames           = takeRawFrames(),
                        mode             = mode,
                        workingResolution = managedWorkingResolution,
                        binnedDevelop    = true,
                        shouldCancel     = shouldCancel
                    )
                    result           = res
                    referencePixels  = ref
                } else if (!mode.supportsBlendReference) {
                    // Depth: develop all brackets at the managed depth resolution — lowered further
                    // if this heap can't hold the focus stack's blend peak (FocusStacker owns its
                    // own residency coefficients) — then focus-stack. The achievable edge models
                    // the engine's halving downscale from the binned source (peeked BEFORE the
                    // one-shot take so the frames stay unpinned).
                    // No blend-strength reference — frames differ by focus, not by time. (spec 2026-06-11 §4)
                    val depthResolution = depthWorkingResolution?.let { preset ->
                        achievableWorkingResolution(
                            budgetEdge = minOf(preset, heapAwareWorkingResolution(
                                frameCount       = frameCount,
                                frameEquivalents = FocusStacker.peakFrameEquivalents(frameCount)
                            )),
                            sourceLongEdge = peekRawLongEdge(payloadRef) / 2   // binned develop halves
                        )
                    }
                    val developed = Pipeline.developedFrames(
                        frames           = takeRawFrames(),
                        binnedDevelop    = true,
                        workingResolution = depthResolution
                    )
                    if (shouldCancel()) throw StackCancellationException()
                    result = FocusStacker.allInFocus(
                        images = developed,
                        config = DepthConfig(
                            workingResolution = depthResolution,
                            maxFrames         = maxOf(frameCount, 1)
                        )
                    ) ?: throw ProcessingError.FocusStackFailed
                    referencePixels = null
                } else {
                    // Detail/Night BATCH path (sigma-clip needs all samples at once): size the
                    // working resolution to this heap and the ACTUAL frame count so the resident
                    // set (Pipeline.batchPeakFrameEquivalents) fits. Streaming looks keep 2400.
                    // The achievable edge models the engine's halving downscale from the binned
                    // source (peeked BEFORE the one-shot take so the frames stay unpinned).
                    val batchResolution = achievableWorkingResolution(
                        budgetEdge     = heapAwareWorkingResolution(frameCount = frameCount),
                        sourceLongEdge = peekRawLongEdge(payloadRef) / 2   // binned develop halves
                    )
                    val developed = Pipeline.developedFrames(
                        frames           = takeRawFrames(),
                        binnedDevelop    = true,
                        workingResolution = batchResolution
                    )
                    if (shouldCancel()) throw StackCancellationException()
                    val (res, ref) = Pipeline.reduceImagesWithReference(developed, mode)
                    result          = res
                    referencePixels = ref
                }
            }

            is CapturedBurst.Payload.Developed -> {
                // Non-RAW fallback: frames are already at working resolution — skip the develop step
                // and route every look through the images pipeline. (spec 2026-06-11 §3)
                if (mode == StackMode.DEPTH_OF_FIELD) {
                    // Same heap-aware lowering as the RAW depth path (frames arrive at the 1500px
                    // fallback decode size; FocusStacker downscales — by HALVING — only when needed,
                    // so the achievable edge from the actual source is what really gets stacked).
                    val depthResolution = depthWorkingResolution?.let { preset ->
                        achievableWorkingResolution(
                            budgetEdge = minOf(preset, heapAwareWorkingResolution(
                                frameCount       = frameCount,
                                frameEquivalents = FocusStacker.peakFrameEquivalents(frameCount)
                            )),
                            sourceLongEdge = peekDevelopedLongEdge(payloadRef)
                        )
                    }
                    result = FocusStacker.allInFocus(
                        images = takeDevelopedImages(),
                        config = DepthConfig(
                            workingResolution = depthResolution,
                            maxFrames         = maxOf(frameCount, 1)
                        )
                    ) ?: throw ProcessingError.FocusStackFailed
                    referencePixels = null
                } else if (mode.isLongExposure) {
                    // Streaming: align + fold one frame at a time — peak memory is O(1) warped frames.
                    val (res, ref) = Pipeline.reduceImagesStreamingWithReference(
                        imgs         = takeDevelopedImages(),
                        mode         = mode,
                        shouldCancel = shouldCancel
                    )
                    result           = res
                    referencePixels  = ref
                } else {
                    if (shouldCancel()) throw StackCancellationException()
                    // Heap-aware here too: fallback frames decode at 1500px. The engine downscale
                    // HALVES (it can't hit an arbitrary target), so when the heap budget is below
                    // the source edge the stack runs at the next halving DOWN — e.g. a 1200 budget
                    // on a 1500px frame runs at 750, below the MIN floor (best effort; the next
                    // halving up wouldn't fit the heap). achievableWorkingResolution reports that
                    // real edge. Computed BEFORE the one-shot take (the peek needs the payload).
                    val batchResolution = achievableWorkingResolution(
                        budgetEdge     = heapAwareWorkingResolution(frameCount = frameCount),
                        sourceLongEdge = peekDevelopedLongEdge(payloadRef)
                    )
                    val (res, ref) = Pipeline.reduceImagesWithReference(
                        imgs             = takeDevelopedImages(),
                        mode             = mode,
                        workingResolution = batchResolution
                    )
                    result          = res
                    referencePixels = if (mode.supportsBlendReference) ref else null
                }
            }

            null -> error("burst payload already consumed")
        }

        // All cancellation checks precede the encode — a StackCancellationException here is from
        // the pipeline above, not the encode, so the catch below can't swallow one mid-encode.
        val oriented    = ImageGeometry.rotated(result, orientationTurns)   // bake upright
        // The reference must go through the SAME orientation bake so the lerp endpoints are
        // aligned pixel-to-pixel in the stored images. (spec 2026-06-11 §4)
        val orientedRef = referencePixels?.let { ImageGeometry.rotated(it, orientationTurns) }
        val rgba        = OutputTransform.encodeSRGB8(oriented)
        // Hoist the reference RGBA encode so it runs once and is shared by both the happy path
        // and the HEIC-fallback branch — eliminates the duplicate work. (spec 2026-06-11 §6)
        val refRGBA     = orientedRef?.let { OutputTransform.encodeSRGB8(it) }
        // Bind the reference pair once so both the happy-path and HEIC-fallback branch share it.
        val refPair: Pair<PixelImage, ByteArray>? =
            if (orientedRef != null && refRGBA != null) Pair(orientedRef, refRGBA) else null

        // Build EXIF metadata for the result encode. Timestamp snapshotted on the main thread (same
        // as orientationTurns — makeResult runs on processingDispatcher). (spec 2026-06-11)
        val capturedAtPosix = capturedAt.time / 1000.0
        val exif: ImageEncoder.ExifMetadata? = if (info != null) {
            ImageEncoder.ExifMetadata(
                iso            = info.iso,
                shutterSeconds = info.shutterSeconds,
                capturedAtPosix = capturedAtPosix
            )
        } else {
            ImageEncoder.ExifMetadata(capturedAtPosix = capturedAtPosix)
        }

        return try {
            val data = encode(rgba, oriented.width, oriented.height, format, 0.95, exif)
            // Encode the reference in the SAME format the result ended up with; wrap in runCatching
            // so a reference encode failure never loses the main result. (spec 2026-06-11 §6)
            val refData: ByteArray? = refPair?.let { (refImg, refRgba) ->
                runCatching {
                    encode(refRgba, refImg.width, refImg.height, format, 0.95, exif)
                }.getOrNull()
            }
            Triple(data, refData, format)
        } catch (e: Exception) {
            if (format != ImageEncoder.Format.HEIC) throw e
            // HEIC encoder hiccup → re-encode the SAME stacked pixels as JPEG; the pipeline
            // (develop+align+stack) is never re-run. Both result and reference fall back together
            // so the pair always shares one format. (spec §8)
            val data = encode(rgba, oriented.width, oriented.height, ImageEncoder.Format.JPEG, 0.95, exif)
            val refData: ByteArray? = refPair?.let { (refImg, refRgba) ->
                runCatching {
                    encode(refRgba, refImg.width, refImg.height, ImageEncoder.Format.JPEG, 0.95, exif)
                }.getOrNull()
            }
            Triple(data, refData, ImageEncoder.Format.JPEG)
        }
    }
}

