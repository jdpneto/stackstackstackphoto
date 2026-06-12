package com.jdpneto.stackstackstack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.core.content.ContextCompat
import com.jdpneto.stackengine.CFAPattern
import com.jdpneto.stackengine.OutputTransform
import com.jdpneto.stackengine.PixelImage
import com.jdpneto.stackengine.RawSensorFrame
import com.jdpneto.stackengine.Vec3
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Camera2-backed [CaptureService]: sequential paced one-in-flight state machine on a single-thread
 * executor, mirroring the iOS [AVCaptureService] queue-confinement design (where
 * `stateQueue`/`sessionQueue` → `stateExecutor`/`sessionExecutor` here).
 *
 * RAW capture: uses [CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW] + supported Bayer
 * CFA pattern ([CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT] ∈ {RGGB,GRBG,GBRG,BGGR})
 * → [ImageReader] of format [ImageFormat.RAW_SENSOR] → 16-bit row-stride-aware copy into
 * [RawSensorFrame]. RAW size is capped at [MAX_RAW_AREA_PIXELS] (iOS 12 MP cap parity, PR #27).
 * blackLevel: average of [CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]'s four offsets
 * (iOS averages the per-channel DNG BlackLevel the same way), whiteLevel from
 * [CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL].
 *
 * Frame join (the load-bearing part of the burst state machine): every frame has TWO halves that
 * arrive independently on the SAME [imageHandler] thread — the [TotalCaptureResult] (per-frame
 * color metadata: wbGains from [CaptureResult.COLOR_CORRECTION_GAINS] /
 * [CaptureResult.SENSOR_NEUTRAL_COLOR_POINT], colorMatrix from
 * [CaptureResult.COLOR_CORRECTION_TRANSFORM]) and the [Image] buffer. They are joined SYMMETRICALLY
 * by SENSOR_TIMESTAMP via [pendingMeta]/[pendingImages] (both confined to the imageHandler thread,
 * so no lock): whichever half lands first waits for the other; conversion is submitted only at join
 * time. Burst completion is `remaining <= 0 && joined >= expectedJoins` (every completed capture is
 * an expected join; the burst can't finish before the last frame's buffer is converted, and a
 * never-arriving half is bounded by [drainTimeoutMs]).
 *
 * Fallback: no RAW capability → [ImageFormat.JPEG] [ImageReader] → [ImageDecoder.rgba8] at
 * [fallbackDecodeLongEdge]=1500 → [OutputTransform.decodeSRGB8] → developed [PixelImage].
 * The emulator exercises this path live (B4). JPEG frames don't need result metadata, so they
 * skip the join and convert immediately (still counted via expectedJoins/joined).
 *
 * Exposure/focus locking (iOS `lockExposureAndFocus(recipe:)` parity): AE/AF/AWB are locked ONCE
 * for the whole burst, before frame 1 — a precapture trigger + bounded wait for a converged
 * result, then a repeating "hold" request carrying AE_LOCK/AWB_LOCK (+ frozen LENS_FOCUS_DISTANCE
 * read from the converged result, the Camera2 equivalent of iOS `.locked` focus). Manual Pro
 * overrides (ISO → SENSOR_SENSITIVITY, shutter → SENSOR_EXPOSURE_TIME with CONTROL_AE_MODE_OFF,
 * focus → LENS_FOCUS_DISTANCE with AF_MODE_OFF) are clamped to the device ranges probed at
 * configure. Every wait is bounded — a worse exposure beats a wedged shutter.
 *
 * Focus-distance mapping (LENS_FOCUS_DISTANCE, in diopters): see [focusDiopters] —
 *   diopters = (1 - position) × minimumFocusDistance, where iOS `lensPosition` is 0 = closest /
 *   1 = infinity and Camera2 is 0 = infinity / large = closest. `minimumFocusDistance`
 *   ([CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE], paradoxically named) is the
 *   LARGEST diopter value — the closest-focus limit of the lens.
 *
 * [CaptureInfo]: [CaptureResult.SENSOR_SENSITIVITY] → iso (Double),
 *                [CaptureResult.SENSOR_EXPOSURE_TIME] ns → shutterSeconds (Double).
 *
 * Thread safety: all mutable burst state is confined to [stateExecutor] (single-thread; the
 * [stateLock] inside is belt-and-suspenders for the few cross-thread reads). Session/device
 * configuration is confined to [sessionExecutor]. Capture callbacks AND ImageReader listeners run
 * on [imageHandler] (one Handler → the join maps need no lock). The 12 MP buffer copy runs on ONE
 * shared [conversionExecutor] (a previous revision leaked a fresh executor thread per image).
 * Delayed work (watchdogs, pacing, gate rechecks) runs on a dedicated [scheduler], NOT the main
 * looper — a previous revision posted all timers through the main thread, so a busy main thread
 * silently disarmed every watchdog.
 */
open class Camera2CaptureService(
    private val context: Context
) : CaptureService {

    private companion object {
        const val TAG = "SSSCamera2"
        const val NO_TOKEN = -1L
        /**
         * Age gate for the app-start spool sweep: a spool dir untouched for this long is a crash
         * leftover by definition (a live burst writes for seconds and its processing job reads
         * for minutes). Anything younger may belong to ANOTHER service instance whose orphaned
         * processing job is still reading it — see [instanceSpoolRoot].
         */
        const val SPOOL_SWEEP_MAX_AGE_MS = 60L * 60 * 1000   // 1 hour
    }

    // -----------------------------------------------------------------------
    // Executors / handler
    // -----------------------------------------------------------------------

    /** Single-thread executor: serialises all mutable burst state (mirrors iOS stateQueue). */
    private val stateExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "sss.camera2.state") }

    /** Single-thread executor: serialises session/device configuration (mirrors iOS sessionQueue). */
    private val sessionExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "sss.camera2.session") }

    /**
     * ONE shared single-thread executor for per-frame buffer conversion (mirrors iOS
     * processingQueue). Shared deliberately: serialised conversions bound peak memory to one
     * mosaic copy, and with the bulk-copy path a frame converts in ~10 ms, far faster than the
     * inter-frame pacing, so no backlog forms.
     */
    private val conversionExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "sss.camera2.convert") }

    /**
     * Delayed-task scheduler for watchdogs / pacing / gate rechecks. Dedicated thread (daemon) —
     * NEVER the main looper: the per-frame watchdog must fire even when the UI thread is busy
     * with a previous shot's stacking.
     */
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sss.camera2.timer").apply { isDaemon = true }
        }

    /** Schedule [task] on [stateExecutor] after [delayMs]. Safe after close (drops silently). */
    private fun scheduleOnState(delayMs: Long, task: () -> Unit) {
        try {
            scheduler.schedule({
                try { stateExecutor.execute(task) } catch (_: RejectedExecutionException) {}
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {}
    }

    /** HandlerThread for capture callbacks + ImageReader.OnImageAvailableListener callbacks. */
    private val imageThread = HandlerThread("sss.camera2.image").also { it.start() }
    private val imageHandler = Handler(imageThread.looper)

    // -----------------------------------------------------------------------
    // Camera state (touched only on sessionExecutor)
    // -----------------------------------------------------------------------

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawImageReader: ImageReader? = null
    private var jpegImageReader: ImageReader? = null
    /** Session fully configured. `protected` only for the test seam (PreviewResumeTest). */
    protected var configured = false
    /**
     * STICKY "the UI wants a live preview" intent (bug 3, round 2). Set by [startPreview], cleared
     * only by [close]. Sticky so that a preview surface arriving LATER than the UI's one-shot
     * [startPreview] trigger (the resume race: SurfaceView destroys its surface on hide and
     * recreates it on show, racing the resumeTick LaunchedEffect) auto-resumes the preview from
     * [setPreviewSurface] — the service owns the restart; the UI's call order can't matter.
     * sessionExecutor-confined.
     */
    private var previewRequested = false

    // -----------------------------------------------------------------------
    // Capabilities (written on sessionExecutor once; read elsewhere after configure)
    // -----------------------------------------------------------------------

    @Volatile private var _supportsDepthOfField: Boolean = true
    @Volatile private var _supportsRAWCapture:   Boolean = true
    @Volatile private var _minimumFocusDistance: Float   = 10f   // diopters; optimistic
    @Volatile private var _cfaPattern:           CFAPattern? = null
    @Volatile private var _whiteLevel:           Int         = 1023
    /** Scalar black level from SENSOR_BLACK_LEVEL_PATTERN (static key, probed at configure). */
    @Volatile private var _blackLevel:           Float       = 64f
    /** SENSOR_ORIENTATION: clockwise rotation of the sensor image relative to the natural display. */
    @Volatile private var _sensorOrientation:    Int         = 90
    /** SENSOR_INFO_ACTIVE_ARRAY_SIZE — the coordinate space MeteringRectangle lives in. */
    @Volatile private var _activeArrayWidth:     Int         = 4032
    @Volatile private var _activeArrayHeight:    Int         = 3024
    @Volatile private var _sensitivityRange:     Range<Int>?  = null   // SENSOR_INFO_SENSITIVITY_RANGE
    @Volatile private var _exposureTimeRange:    Range<Long>? = null   // SENSOR_INFO_EXPOSURE_TIME_RANGE (ns)
    @Volatile private var _maxAfRegions:         Int         = 0
    @Volatile private var _maxAeRegions:         Int         = 0

    override val supportsDepthOfField: Boolean get() = _supportsDepthOfField
    override val supportsRAWCapture:   Boolean get() = _supportsRAWCapture

    // -----------------------------------------------------------------------
    // Per-burst state (touched only on stateExecutor)
    // -----------------------------------------------------------------------

    private val stateLock = ReentrantLock()

    // Continuation for the in-flight burst.
    private var continuation: ((Result<CapturedBurst>) -> Unit)? = null

    private var burstInfo:        CaptureInfo? = null   // set-once from the first frame
    /** Spool files of this burst's converted RAW frames, in join order (see [BurstSpool]). */
    private var pendingRawFiles:  MutableList<File>          = mutableListOf()
    private var pendingDeveloped: MutableList<PixelImage>     = mutableListOf()
    private var fallbackJPEG:     Boolean = false
    private var activeRecipe:     CaptureRecipe? = null // manual Pro overrides for this burst
    private var remaining:        Int = 0              // frames still to request
    private var expectedJoins:    Int = 0              // completed captures that owe a converted frame
    private var joined:           Int = 0              // join conversions finished (success or drop)
    private var totalFrames:      Int = 0
    private var generation:       Int = 0              // bumps per burst; stale callbacks are ignored
    private var frameTokenCounter: Long = 0
    private var inFlightToken:    Long = NO_TOKEN      // token of the in-flight capture attempt
    private var drainArmed:       Boolean = false      // join-drain timeout armed for this burst
    private var pacingMs:         Long = 100L
    private var perFrameTimeoutMs: Long = 5_000L
    private var sweepPositions:   List<Float> = emptyList()
    private var isSteadyCheck:    () -> Boolean = { true }
    private var onProgress:       ((Int) -> Unit)? = null
    private var gateAttempts:     Int = 0

    private val gateRecheckMs:        Long = 100L
    private val maxStartGateAttempts: Int  = 50
    private val maxFrameGateAttempts: Int  = 30
    /** Bound on waiting for outstanding joins after the last frame: a missing buffer/result half
     *  must not wedge the burst (the live Pixel wedge was exactly an undrainable wait here). */
    private val drainTimeoutMs:       Long = 4_000L
    /** Bound on the pre-burst AE convergence wait (iOS bounds its settle the same way). */
    private val lockWaitTimeoutMs:    Long = 2_500L

    // Pre-burst lock results (written on imageHandler during the lock phase; read on sessionExecutor).
    @Volatile private var lockedLensDiopters: Float? = null   // frozen auto-focus position
    @Volatile private var meteredIso:         Int?   = null   // auto-metered, for half-manual exposure
    @Volatile private var meteredExposureNs:  Long?  = null

    // -----------------------------------------------------------------------
    // RAW frame spool (the 30-frame OOM fix — see BurstSpool)
    // -----------------------------------------------------------------------

    /** Root of all burst spool dirs (SHARED across service instances — see [instanceSpoolRoot]).
     *  cacheDir: the OS may reclaim it when the app isn't using it. */
    private val spoolRoot = File(context.cacheDir, "burst-spool")

    /**
     * THIS instance's spool namespace — unique per service instance. Spool-cleanup OWNERSHIP
     * story: activity recreation (uiMode/locale/font-scale are NOT in the manifest's
     * configChanges) builds a NEW service while the OLD coordinator's background processing job
     * survives onDestroy (its scope is never cancelled) and may still be LAZILY reading the old
     * instance's spool files ([BurstSpool.LazyFrameList] re-reads on every get). So no instance
     * may ever delete another LIVE instance's spool dir. Two cleanups, each safe by construction:
     *  - PER-BURST-ARM (captureBurst): clears only inside THIS namespace, where the coordinator's
     *    shutter gate (isBusy = capturing OR processing) guarantees this instance's previous
     *    burst payload is no longer being consumed when a new burst arms;
     *  - APP-START (init below): an AGE-GATED sweep of the shared root — only dirs older than
     *    [SPOOL_SWEEP_MAX_AGE_MS] (crash leftovers by definition) are deleted; a young foreign
     *    dir is presumed live and left alone (it is reclaimed by a later app start).
     */
    private val instanceSpoolRoot = File(spoolRoot, "svc-${java.util.UUID.randomUUID()}")

    /** This burst's spool dir (written on stateExecutor at arm time; read on conversionExecutor). */
    @Volatile private var spoolDir: File? = null

    /** Per-burst spool file index (incremented on the single-threaded conversionExecutor). */
    private val spoolCounter = AtomicInteger(0)

    init {
        // App-start sweep of CRASH LEFTOVERS only — age-gated, never the whole root: a young
        // foreign spool dir may belong to a previous service instance whose orphaned processing
        // job is still reading it (see instanceSpoolRoot kdoc for the full ownership story).
        // Runs on conversionExecutor so it serializes with spool writes.
        try {
            conversionExecutor.execute { BurstSpool.sweepStaleSpools(spoolRoot, SPOOL_SWEEP_MAX_AGE_MS) }
        } catch (_: RejectedExecutionException) {}
    }

    // -----------------------------------------------------------------------
    // Preview surface provider (B3 UI hook)
    // -----------------------------------------------------------------------

    /**
     * Provide the [Surface] the Camera2 session should send the repeating preview into. Must be
     * called BEFORE [startPreview] so [ensureConfiguredLocked] can include it in the session
     * output list (both hop onto [sessionExecutor], a FIFO single thread, so the UI-thread call
     * order is preserved).
     *
     * Mirrors the iOS pattern where [AVCaptureService.startPreview] returns a [CALayer] that the
     * view hosts directly. Here the UI creates a [android.view.SurfaceView] (or [android.graphics.SurfaceTexture]),
     * extracts its [Surface], and registers it here; [startPreview] then returns it back to the
     * coordinator so the coordinator can hand it to the Compose [AndroidView].
     *
     * A DIFFERENT surface arriving while the session is configured means the window's surface
     * was destroyed and recreated (app went to the background and back — bug 3): the session
     * still targets the dead surface, so tear it down — and, when a preview has ever been
     * requested ([previewRequested]), RECONFIGURE AND RESUME THE PREVIEW RIGHT HERE. The first
     * round of this fix only invalidated and waited for the UI to call [startPreview] again,
     * but on the Pixel the resume-keyed startPreview raced this call and ran FIRST, against the
     * stale surface (framework started then stopped the stream 52 ms later); the invalidate
     * here then tore that session down with both one-shot UI triggers already consumed — black
     * preview. Owning the restart at the service altitude makes the ordering irrelevant: the
     * surface is the last event to land, and it restarts the preview itself.
     */
    fun setPreviewSurface(surface: Surface) {
        try {
            sessionExecutor.execute {
                if (surface === previewSurface) return@execute   // same surface — nothing to do
                if (configured) invalidateSessionLocked(null)
                previewSurface = surface
                if (previewRequested) {
                    // A LIVE burst must NOT be stomped: reconfiguring here would rebuild the
                    // session with a plain auto-AE repeating preview, and the burst's REMAINING
                    // stills (which submit to whatever session exists at submit time) would
                    // capture without the burst-long AE/AWB hold — an exposure shift mixed into
                    // one stack. Defer: the sticky previewRequested intent and the new surface
                    // are both stored; finishLocked's resumePreviewAfterBurstLocked detects the
                    // dead session and runs the full reconfigure when the burst ends.
                    if (stateLock.withLock { continuation != null }) return@execute
                    try {
                        // Fresh configure includes the new surface and starts the repeating
                        // preview itself (ensureConfiguredLocked tail).
                        ensureConfiguredLocked()
                    } catch (e: Exception) {
                        Log.w(TAG, "preview restart on surface replacement failed", e)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {}   // closed — surface callbacks may outlive us
    }

    /**
     * The preview surface was destroyed (SurfaceView hides → app backgrounded). Invalidate any
     * session targeting it and FORGET it, so nothing can configure against a dead surface in
     * the window before the recreated surface arrives. [previewRequested] stays sticky — the
     * next [setPreviewSurface] auto-resumes the preview with no UI involvement.
     */
    fun clearPreviewSurface() {
        try {
            sessionExecutor.execute {
                if (previewSurface == null) return@execute
                if (configured) invalidateSessionLocked(null)
                previewSurface = null
            }
        } catch (_: RejectedExecutionException) {}   // closed — surface callbacks may outlive us
    }

    /**
     * TEST-ONLY barrier: blocks until everything queued on [sessionExecutor] so far has run
     * (FIFO single thread ⇒ a marker task is a fence). Lets PreviewResumeTest assert "nothing
     * happened" without sleeping.
     */
    internal fun awaitSessionQuiescentForTest(timeoutMs: Long = 5_000) {
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            sessionExecutor.execute { latch.countDown() }
        } catch (_: RejectedExecutionException) { return }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** TEST-ONLY probe: whether a burst is currently armed (its continuation is set). */
    internal fun isBurstActiveForTest(): Boolean = stateLock.withLock { continuation != null }

    // -----------------------------------------------------------------------
    // CaptureService: startPreview
    // -----------------------------------------------------------------------

    /**
     * Idempotent, no-op-safe retrigger (bug 3): when the session is already configured this
     * re-issues the plain repeating preview request, which is harmless if the preview is already
     * streaming and restores it if the repeating request was lost — UNLESS a burst is in flight,
     * whose AE/AWB hold owns the repeating slot. When the camera was lost in the background
     * ([invalidateSessionLocked] ran), `configured` is false and this reopens from scratch.
     *
     * Reads the CURRENT [previewSurface] at execution time on [sessionExecutor] (never a captured
     * reference — the surface may have been replaced or cleared since the caller suspended).
     * With NO surface stored (destroyed, or not created yet) this only records the sticky
     * [previewRequested] intent and returns null: configuring now would open the camera with no
     * preview output only to tear it straight down when the recreated surface lands in
     * [setPreviewSurface] — which then owns the actual (re)start.
     */
    override suspend fun startPreview(): Surface? {
        if (!ensurePermission()) return null
        return suspendCancellableCoroutine { cont ->
            try {
                sessionExecutor.execute {
                    previewRequested = true
                    if (previewSurface == null) {
                        cont.resume(null)   // sticky flag set — the next surface auto-resumes
                        return@execute
                    }
                    // A LIVE burst owns the repeating slot (its AE/AWB hold) — and, when the
                    // session was invalidated mid-burst (surface replacement), a fresh configure
                    // here would rebuild it under a plain auto-AE preview, shifting exposure
                    // inside the stack. Either way: record the sticky intent only; finishLocked's
                    // resumePreviewAfterBurstLocked restores (or fully reconfigures) at burst end.
                    if (stateLock.withLock { continuation != null }) {
                        cont.resume(previewSurface)
                        return@execute
                    }
                    try {
                        val wasConfigured = configured
                        ensureConfiguredLocked()
                        // ensureConfiguredLocked starts the repeating preview itself on a fresh
                        // configure; on the already-configured path restore it here.
                        if (wasConfigured) startPreviewRequestLocked()
                        cont.resume(previewSurface)
                    } catch (e: Exception) {
                        Log.w(TAG, "startPreview configure failed", e)
                        cont.resume(null)
                    }
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(null)   // service closed
            }
        }
    }

    // -----------------------------------------------------------------------
    // CaptureService: setFocusExposure (tap-to-focus)
    // -----------------------------------------------------------------------

    /**
     * Tap → one-shot AF + metering at the point (post-trigger AF_MODE_AUTO HOLDS the lens until
     * the next trigger — iOS `.autoFocus` parity). Long-press ([lock]) → after AF/AE converge,
     * the repeating request additionally carries AE_LOCK (honest AE *and* AF hold).
     *
     * The metering region is mapped from the normalized preview tap into
     * SENSOR_INFO_ACTIVE_ARRAY_SIZE pixel coordinates via [meteringRectFromPreviewTap],
     * undoing the SENSOR_ORIENTATION rotation the preview applies.
     */
    override fun setFocusExposure(x: Float, y: Float, lock: Boolean) {
        sessionExecutor.execute {
            // Mid-burst guard (iOS AVCaptureService parity): a stale tap can land after a burst
            // began (the UI gate races the gesture by one frame). Mid-burst it would fight the
            // burst's locked focus — and a Depth sweep's per-frame lens steps — so drop it;
            // tapping is a viewfinder-only affordance.
            if (stateLock.withLock { continuation != null }) return@execute
            val session = captureSession ?: return@execute
            val device  = cameraDevice  ?: return@execute
            val preview = previewSurface ?: return@execute
            try {
                val r = meteringRectFromPreviewTap(
                    x, y, _sensorOrientation, _activeArrayWidth, _activeArrayHeight
                )
                val region = MeteringRectangle(
                    r.x, r.y, r.width, r.height, MeteringRectangle.METERING_WEIGHT_MAX
                )

                fun tapRequest(): CaptureRequest.Builder =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                        if (_maxAfRegions > 0) set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                        if (_maxAeRegions > 0) set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                    }

                // The REPEATING request must carry the regions + AF_MODE_AUTO: a one-shot capture
                // alone is silently undone on the next preview frame by the regionless repeating
                // request (the original tap-to-focus bug here).
                val monitor = if (lock) makeTapLockMonitor { tapRequest() } else null
                session.setRepeatingRequest(tapRequest().build(), monitor, imageHandler)
                // One-shot AF trigger: one sweep to the region, then AF_MODE_AUTO holds.
                val trigger = tapRequest().apply {
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                }
                session.capture(trigger.build(), null, imageHandler)
            } catch (e: Exception) {
                Log.w(TAG, "tap-to-focus failed", e)   // best-effort enhancement
            }
        }
    }

    /**
     * Long-press monitor: waits (bounded) for AF to lock and AE to converge, then re-issues the
     * repeating request with AE_LOCK so the long-press genuinely HOLDS exposure too (it previously
     * claimed AE/AF lock but only ever set AE lock, and even that was one-shot).
     */
    private fun makeTapLockMonitor(buildTapRequest: () -> CaptureRequest.Builder) =
        object : CameraCaptureSession.CaptureCallback() {
            private var results = 0
            private var done = false
            private val maxResults = 90   // ≈3 s of preview frames; then lock with what we have

            override fun onCaptureCompleted(
                session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
            ) {
                if (done) return
                results++
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                val afDone = af == null ||
                    af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                val aeDone = ae == null ||
                    ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                if ((afDone && aeDone) || results >= maxResults) {
                    done = true
                    sessionExecutor.execute {
                        // A burst may have started while we waited — its hold request owns the
                        // repeating slot now; don't fight it.
                        if (stateLock.withLock { continuation != null }) return@execute
                        val s = captureSession ?: return@execute
                        try {
                            val held = buildTapRequest().apply {
                                set(CaptureRequest.CONTROL_AE_LOCK, true)
                            }
                            s.setRepeatingRequest(held.build(), null, imageHandler)
                        } catch (e: Exception) {
                            Log.w(TAG, "AE/AF lock hold failed", e)
                        }
                    }
                }
            }
        }

    // -----------------------------------------------------------------------
    // CaptureService: captureBurst
    // -----------------------------------------------------------------------

    override suspend fun captureBurst(
        recipe: CaptureRecipe,
        isSteady: () -> Boolean,
        onProgress: ((Int) -> Unit)?
    ): CapturedBurst {
        if (!ensurePermission()) throw CaptureError.PermissionDenied
        ensureConfigured()

        val frameCount = recipe.frameCount
        val pacingInterval = max(recipe.durationSeconds / max(frameCount - 1, 1).toDouble(), 0.05)
        val frameTimeout   = (max(recipe.manualShutterSeconds ?: 0.0, 1.0) * 3 + 4.0) * 1_000L
        Log.i(TAG, "burst: start frames=$frameCount raw=$_supportsRAWCapture pacing=${pacingInterval}s")

        return suspendCancellableCoroutine { cont ->
            stateExecutor.execute {
                stateLock.withLock {
                    if (continuation != null) {
                        cont.resumeWithException(CaptureError.Busy); return@execute
                    }
                    if (frameCount <= 0) {
                        cont.resumeWithException(CaptureError.NoFramesProduced); return@execute
                    }

                    generation++
                    pendingRawFiles   = mutableListOf()
                    pendingDeveloped  = mutableListOf()
                    burstInfo         = null
                    expectedJoins     = 0
                    joined            = 0
                    drainArmed        = false
                    continuation      = { result -> cont.resumeWith(result) }
                    activeRecipe      = recipe
                    remaining         = frameCount
                    totalFrames       = frameCount
                    this.isSteadyCheck  = isSteady
                    this.onProgress   = onProgress
                    sweepPositions    = recipe.focusSweep?.positions ?: emptyList()
                    gateAttempts      = 0
                    inFlightToken     = NO_TOKEN
                    pacingMs          = (pacingInterval * 1_000).toLong()
                    perFrameTimeoutMs = frameTimeout.toLong()
                    fallbackJPEG      = !_supportsRAWCapture

                    val gen = generation

                    // Arm this burst's RAW spool and clear THIS INSTANCE's earlier generations
                    // (safe: the coordinator's shutter gate means nothing can still be reading
                    // them). Scoped to instanceSpoolRoot — another instance's spool may still be
                    // read by its orphaned processing job (see instanceSpoolRoot kdoc).
                    val newSpool = File(instanceSpoolRoot, "gen-$gen")
                    spoolDir = newSpool
                    spoolCounter.set(0)
                    try {
                        conversionExecutor.execute { BurstSpool.clearSpools(instanceSpoolRoot, keep = newSpool) }
                    } catch (_: RejectedExecutionException) {}

                    // Cancellation must not strand the state machine: a cancelled caller leaves
                    // `continuation` set, and the next shot would see Busy forever. Resuming a
                    // cancelled continuation is a documented no-op, so finishLocked just clears.
                    cont.invokeOnCancellation {
                        stateExecutor.execute {
                            stateLock.withLock {
                                if (generation == gen && continuation != null) {
                                    Log.w(TAG, "burst cancelled by caller — releasing state machine")
                                    finishLocked()
                                }
                            }
                        }
                    }
                    Log.i(TAG, "burst: armed gen=$gen")
                    // Drop any join-half left over from an aborted previous burst.
                    imageHandler.post { clearJoinState() }
                    // Lock AE/AF/AWB once for the whole burst, then start frame 1.
                    sessionExecutor.execute { lockForBurstThenStart(gen, recipe) }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Pre-burst AE/AF/AWB lock (iOS lockExposureAndFocus parity; sessionExecutor)
    // -----------------------------------------------------------------------

    /**
     * Lock exposure/focus/WB ONCE before frame 1 (iOS `lockExposureAndFocus(recipe:)`):
     *  1. Monitor the running auto loop (kicked with an AE precapture trigger) until AE converges,
     *     recording the metered ISO/shutter and the current lens position from the result.
     *  2. Swap the repeating request for a "hold": AE_LOCK + AWB_LOCK (or AE_MODE_OFF + clamped
     *     manual values), AF frozen at the converged LENS_FOCUS_DISTANCE (or manual/sweep-start).
     *  3. Proceed to frame 1 after the hold's first result (settled), bounded by
     *     [lockWaitTimeoutMs] overall — a worse exposure beats a wedged shutter, and the per-still
     *     requests carry the same lock flags as a fallback.
     *
     * Without this, every still re-locked AE/AWB from the still-running auto loop, so exposure and
     * WB drifted across the burst. Runs on [sessionExecutor]; proceeds exactly once.
     */
    private fun lockForBurstThenStart(gen: Int, recipe: CaptureRecipe) {
        Log.i(TAG, "lock: begin gen=$gen")
        val proceeded = AtomicBoolean(false)
        fun proceed(warn: String?) {
            if (!proceeded.compareAndSet(false, true)) return
            warn?.let { Log.w(TAG, it) }
            Log.i(TAG, "lock: proceeding to frame 1")
            try {
                stateExecutor.execute { stateLock.withLock { startNextFrameLocked(gen) } }
            } catch (_: RejectedExecutionException) {}
        }
        // Bounded overall: if convergence never reports, start anyway.
        try {
            scheduler.schedule({
                if (!proceeded.get()) {
                    proceed("pre-burst AE/AF lock timed out — starting with per-request locks only")
                }
            }, lockWaitTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {}

        val stale = stateLock.withLock { generation != gen || continuation == null }
        val session = captureSession
        val device  = cameraDevice
        val preview = previewSurface
        if (stale) return
        if (session == null || device == null || preview == null) {
            // No repeating stream to lock through (headless/preview-less) — stills carry their
            // own locks; the metered fallbacks just stay null.
            proceed(null); return
        }

        lockedLensDiopters = null
        meteredIso         = null
        meteredExposureNs  = null
        val manualExposure = recipe.manualISO != null || recipe.manualShutterSeconds != null

        try {
            val monitorReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                // Manual focus / sweep start settles the lens BEFORE frame 1 (iOS settle parity).
                val preFocus = recipe.manualFocus ?: recipe.focusSweep?.positions?.firstOrNull()
                if (preFocus != null && _supportsDepthOfField) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters(preFocus, _minimumFocusDistance))
                }
            }
            val monitor = object : CameraCaptureSession.CaptureCallback() {
                private var engaged = false
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, rq: CaptureRequest, result: TotalCaptureResult
                ) {
                    // imageHandler thread.
                    if (engaged || proceeded.get()) return
                    result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { meteredIso = it }
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { meteredExposureNs = it }
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lockedLensDiopters = it }
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    val converged = ae == null ||                       // legacy HAL: no AE state
                        ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                    // Manual exposure needs no convergence — the first result vends the metered
                    // values used to fill the non-overridden half (iOS currentISO/currentExposureDuration).
                    if (manualExposure || converged) {
                        engaged = true
                        sessionExecutor.execute { engageBurstHold(gen, recipe, ::proceed) }
                    }
                }
            }
            session.setRepeatingRequest(monitorReq.build(), monitor, imageHandler)
            if (!manualExposure) {
                // Precapture trigger: makes AE converge promptly instead of waiting for drift.
                val trig = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    set(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
                    )
                }
                session.capture(trig.build(), null, imageHandler)
            }
        } catch (e: Exception) {
            proceed("pre-burst lock setup failed (${e.message}) — starting with per-request locks")
        }
    }

    /** Swap the repeating request for the burst-long AE/AWB/AF hold, then proceed (settled). */
    private fun engageBurstHold(gen: Int, recipe: CaptureRecipe, proceed: (String?) -> Unit) {
        val stale = stateLock.withLock { generation != gen || continuation == null }
        val session = captureSession
        val device  = cameraDevice
        val preview = previewSurface
        if (stale) return
        if (session == null || device == null || preview == null) { proceed(null); return }
        Log.i(TAG, "lock: engaging burst hold (iso=$meteredIso ns=$meteredExposureNs lens=$lockedLensDiopters)")
        try {
            val hold = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                applyRecipeSettings(this, recipe, recipe.focusSweep?.positions?.firstOrNull())
            }
            val settle = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, rq: CaptureRequest, result: TotalCaptureResult
                ) {
                    proceed(null)   // first held result → locks have taken effect (idempotent)
                }
            }
            session.setRepeatingRequest(hold.build(), settle, imageHandler)
        } catch (e: Exception) {
            proceed("burst hold request failed (${e.message}) — per-request locks only")
        }
    }

    // -----------------------------------------------------------------------
    // Internal burst state machine (all on stateExecutor)
    // -----------------------------------------------------------------------

    /** Issue the next capture, or finish if none remain. Must run on [stateExecutor]. */
    private fun startNextFrameLocked(gen: Int) {
        if (generation != gen || continuation == null) return
        if (remaining <= 0) { maybeFinishLocked(); return }

        // Steadiness gate (long-exposure + Depth looks): wait until the device is steady.
        // Bounded: maxStartGateAttempts (~5 s) before frame 1, then capture anyway;
        // maxFrameGateAttempts (~3 s) mid-burst, then stack what we have.
        if (!isSteadyCheck()) {
            gateAttempts++
            val isFirst   = (remaining == totalFrames)
            val maxAttempts = if (isFirst) maxStartGateAttempts else maxFrameGateAttempts
            if (gateAttempts <= maxAttempts) {
                scheduleOnState(gateRecheckMs) { stateLock.withLock { startNextFrameLocked(gen) } }
                return
            }
            if (!isFirst) {
                // Later frame never steadied → stop requesting frames, stack what we have.
                Log.w(TAG, "steadiness gate timed out mid-burst — finishing with " +
                    "${pendingRawFiles.size + pendingDeveloped.size} frames")
                remaining = 0
                maybeFinishLocked()
                return
            }
            // First frame timed out → fall through and capture anyway (≥1 frame guaranteed).
            Log.w(TAG, "steadiness gate timed out before frame 1 — capturing anyway")
        }
        gateAttempts = 0

        val frameIndex    = totalFrames - remaining
        val sweepPosition: Float? = sweepPositions.getOrNull(frameIndex)
        val recipe     = activeRecipe
        val isFallback = fallbackJPEG

        val token = ++frameTokenCounter
        inFlightToken = token

        // Per-frame watchdog — armed HERE on stateExecutor, BEFORE the sessionExecutor hop.
        // (Previously it was armed only after session.capture() returned, so a blocked session
        // thread meant no watchdog at all — one of the wedge holes.)
        scheduleOnState(perFrameTimeoutMs) {
            stateLock.withLock { timeoutFrameLocked(token, gen) }
        }

        val callback = makeBurstCaptureCallback(token, gen, isFallback)

        sessionExecutor.execute {
            val active = stateLock.withLock {
                generation == gen && inFlightToken == token && continuation != null
            }
            if (!active) return@execute   // superseded/timed out — the watchdog already handled it
            val session = captureSession
            val device  = cameraDevice
            if (session == null || device == null) {
                Log.w(TAG, "no camera session for frame $frameIndex (frame dropped)")
                stateExecutor.execute { stateLock.withLock { advanceLocked(token) } }
                return@execute
            }
            try {
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                applyRecipeSettings(req, recipe, sweepPosition)
                val targetSurface = if (isFallback) jpegImageReader?.surface else rawImageReader?.surface
                if (targetSurface == null) {
                    Log.w(TAG, "no capture surface for frame $frameIndex (frame dropped)")
                    stateExecutor.execute { stateLock.withLock { advanceLocked(token) } }
                    return@execute
                }
                req.addTarget(targetSurface)
                session.capture(req.build(), callback, imageHandler)
                Log.i(TAG, "frame ${frameIndex + 1}/$totalFrames submitted (token=$token)")
            } catch (e: Exception) {
                Log.w(TAG, "capture submit failed for frame $frameIndex (frame dropped)", e)
                stateExecutor.execute { stateLock.withLock { advanceLocked(token) } }
            }
        }
    }

    /**
     * Apply this burst's manual Pro overrides + per-frame sweep position to [req].
     * Called on sessionExecutor (stills) and for the burst hold request.
     *
     * Focus precedence: sweep position → manual focus → frozen pre-burst auto focus
     * ([lockedLensDiopters], iOS `.locked` parity) → AF_MODE_AUTO without a trigger (holds).
     * Diopter mapping: see [focusDiopters] (the iOS↔Camera2 inversion is documented there).
     *
     * Exposure: any manual half → CONTROL_AE_MODE_OFF with SENSOR_SENSITIVITY /
     * SENSOR_EXPOSURE_TIME clamped to the probed device ranges; the non-overridden half is filled
     * from the pre-burst metered values (iOS `AVCaptureDevice.currentISO/currentExposureDuration`).
     * No manual → AE_MODE_ON + AE_LOCK (held by the burst-long repeating request too).
     */
    private fun applyRecipeSettings(
        req: CaptureRequest.Builder,
        recipe: CaptureRecipe?,
        sweepPosition: Float?
    ) {
        // ---- Focus ----
        val manualPosition = sweepPosition ?: recipe?.manualFocus
        val diopters: Float? = when {
            manualPosition != null && _supportsDepthOfField ->
                focusDiopters(manualPosition, _minimumFocusDistance)
            else -> lockedLensDiopters
        }
        if (diopters != null) {
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            req.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
        } else {
            // No manual position and no frozen distance: AF_MODE_AUTO without a trigger holds
            // the lens at its current position.
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }

        // ---- Exposure ----
        val manualISO     = recipe?.manualISO
        val manualShutter = recipe?.manualShutterSeconds
        if (manualISO != null || manualShutter != null) {
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val isoRange = _sensitivityRange
            val expRange = _exposureTimeRange
            val iso: Int? = manualISO?.let {
                clampedSensitivity(it.toInt(), isoRange?.lower, isoRange?.upper)
            } ?: meteredIso
            val ns: Long? = manualShutter?.let {
                clampedExposureNanos(it, expRange?.lower, expRange?.upper)
            } ?: meteredExposureNs
            iso?.let { req.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            ns?.let  { req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else {
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            req.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        req.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        req.set(CaptureRequest.CONTROL_AWB_LOCK, true)
    }

    // -----------------------------------------------------------------------
    // Per-frame capture callback + symmetric timestamp join (imageHandler-confined maps)
    // -----------------------------------------------------------------------

    /** Develop metadata for one frame, extracted from its [TotalCaptureResult]. */
    private class FrameColorMetadata(val wbGains: Vec3, val colorMatrix: FloatArray)

    /**
     * Two-sided join, keyed by SENSOR_TIMESTAMP: results wait in [pendingMeta], buffers wait in
     * [pendingImages]; a frame converts when BOTH halves are present. Both maps are confined to
     * the [imageHandler] thread (the capture callbacks and the reader listeners are registered
     * with the same Handler) — that single-thread confinement is the lock here; do not touch the
     * maps from any other thread. Insertion-ordered so eviction drops the oldest half.
     *
     * This replaces a `lastOrNull()` fallback that paired frame i's image with frame i-1's WB
     * whenever the image beat its result, and an `outstanding` count that only the image side
     * incremented (so the result side could finish the burst before the last buffer arrived,
     * silently dropping the final frame — a 1-frame burst became NoFramesProduced).
     */
    private val pendingMeta   = LinkedHashMap<Long, FrameColorMetadata>()
    private val pendingImages = LinkedHashMap<Long, Image>()
    private val maxPendingMeta   = 8
    private val maxPendingImages = 2   // acquired reader buffers are scarce (maxImages = 3)

    /** Drop all stashed join halves. Must run on the [imageHandler] thread. */
    private fun clearJoinState() {
        pendingMeta.clear()
        for (img in pendingImages.values) {
            try { img.close() } catch (_: Throwable) {}
        }
        pendingImages.clear()
    }

    /** Per-frame Camera2 callback: one instance per capture, carrying its idempotency token. */
    private fun makeBurstCaptureCallback(token: Long, gen: Int, isFallback: Boolean) =
        object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // imageHandler thread — same thread as the ImageReader listeners (join confinement).
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.toDouble()
                val shutterSec = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    ?.let { it.toDouble() / 1_000_000_000.0 }

                var joinable = isFallback   // JPEG frames need no result half
                if (!isFallback) {
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    if (ts == null) {
                        Log.w(TAG, "capture result missing SENSOR_TIMESTAMP — frame can't be joined")
                    } else {
                        joinable = true
                        val meta = extractFrameColorMeta(result)
                        val img = pendingImages.remove(ts)
                        if (img != null) {
                            submitConversion(gen, img, meta, isJpeg = false)   // image arrived first
                        } else {
                            pendingMeta[ts] = meta                             // result arrived first
                            while (pendingMeta.size > maxPendingMeta) {
                                val evicted = pendingMeta.keys.first()
                                pendingMeta.remove(evicted)
                                Log.w(TAG, "evicted unconsumed frame metadata ts=$evicted")
                            }
                        }
                    }
                }

                stateExecutor.execute {
                    stateLock.withLock {
                        if (generation != gen) return@withLock
                        if (burstInfo == null && (iso != null || shutterSec != null)) {
                            burstInfo = CaptureInfo(iso = iso, shutterSeconds = shutterSec)
                        }
                        // The camera committed this frame: its converted frame is now owed…
                        if (joinable) expectedJoins++
                        // …and the machine may request the next frame.
                        advanceLocked(token)
                    }
                }
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                Log.w(TAG, "capture failed (reason=${failure.reason}) — frame dropped")
                stateExecutor.execute {
                    stateLock.withLock { if (generation == gen) advanceLocked(token) }
                }
            }

            override fun onCaptureBufferLost(
                session: CameraCaptureSession,
                request: CaptureRequest,
                target: Surface,
                frameNumber: Long
            ) {
                // The result may still complete and count an expected join whose buffer never
                // arrives; the drain timeout bounds that wait. Log so on-device debugging isn't blind.
                Log.w(TAG, "capture buffer lost (frameNumber=$frameNumber)")
            }
        }

    /** wbGains + colorMatrix from a [TotalCaptureResult]. Runs on the imageHandler thread. */
    private fun extractFrameColorMeta(result: TotalCaptureResult): FrameColorMetadata {
        // COLOR_CORRECTION_GAINS subtlety: for RAW captures the result is guaranteed to carry the
        // dynamic metadata DngCreator needs (gains/transform are exactly DNG AsShotNeutral /
        // ColorMatrix inputs). If gains are null we fall back to SENSOR_NEUTRAL_COLOR_POINT-derived
        // gains from the same result, then 1/1/1. Without real gains the un-balanced Bayer
        // (green has 2× the sites) comes out green-cast.
        val rggb: RggbChannelVector? = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val wb: Vec3 = when {
            rggb != null ->
                wbGainsFromRggb(rggb.red, rggb.greenEven, rggb.greenOdd, rggb.blue)
            neutral != null && neutral.size == 3 ->
                wbGainsFromNeutralPoint(
                    neutral[0].toFloat(), neutral[1].toFloat(), neutral[2].toFloat()
                )
            else -> Vec3(1f, 1f, 1f)   // both keys genuinely null — last-resort identity
        }
        // COLOR_CORRECTION_TRANSFORM maps white-balanced camera RGB → linear sRGB — the same
        // role as the iOS converter's color matrix slot. ColorSpaceTransform elements are
        // row-major; the engine stores column-major (see engineColorMatrixFromRowMajor).
        val transform: ColorSpaceTransform? = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val matrix: FloatArray = if (transform != null) {
            val rowMajor = FloatArray(9)
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    rowMajor[row * 3 + col] = transform.getElement(col, row).toFloat()
                }
            }
            engineColorMatrixFromRowMajor(rowMajor)
        } else {
            RawSensorFrame.IDENTITY_3X3.copyOf()
        }
        return FrameColorMetadata(wb, matrix)
    }

    // -----------------------------------------------------------------------
    // Frame advance / watchdogs / finish (stateExecutor)
    // -----------------------------------------------------------------------

    /** Watchdog: a capture that hasn't reported back within [perFrameTimeoutMs] is a stall. */
    private fun timeoutFrameLocked(token: Long, gen: Int) {
        if (continuation == null || generation != gen || inFlightToken != token) return
        Log.w(TAG, "per-frame watchdog fired (frame ${totalFrames - remaining}/$totalFrames) — " +
            "ending burst with ${pendingRawFiles.size + pendingDeveloped.size} frames so far")
        inFlightToken = NO_TOKEN
        remaining     = 0           // request no more frames…
        maybeFinishLocked()         // …and wait (bounded) for outstanding joins to drain
    }

    /**
     * Mark in-flight capture finished, request next frame after [pacingMs].
     * Idempotent per frame (callback + watchdog + submit-failure paths all call this; only the
     * first while `inFlightToken == token` takes effect). Must run on stateExecutor.
     */
    private fun advanceLocked(token: Long) {
        if (continuation == null) return
        if (inFlightToken != token) return   // already advanced (or watchdog ended the burst)
        inFlightToken = NO_TOKEN
        remaining--
        if (remaining <= 0) { maybeFinishLocked(); return }
        val gen = generation
        scheduleOnState(pacingMs) { stateLock.withLock { startNextFrameLocked(gen) } }
    }

    /** One join conversion finished (frame may be null = dropped). Must run on stateExecutor.
     *  RAW frames arrive as their SPOOL FILE ([BurstSpool]) — the mosaic is already on disk. */
    private fun joinDoneLocked(gen: Int, rawFile: File?, developed: PixelImage?) {
        if (generation != gen || continuation == null) return
        joined++
        when {
            rawFile != null -> {
                pendingRawFiles.add(rawFile)
                onProgress?.invoke(pendingRawFiles.size)
            }
            developed != null -> {
                pendingDeveloped.add(developed)
                onProgress?.invoke(pendingDeveloped.size)
            }
            else -> Log.w(TAG, "frame dropped (conversion produced nothing)")
        }
        maybeFinishLocked()
    }

    /**
     * Resume the continuation once every frame has been requested AND every completed capture's
     * join conversion has finished ([burstShouldFinish]) — otherwise the last frame(s) would be
     * silently dropped while still converting. If a join half never arrives, [drainTimeoutMs]
     * bounds the wait (an unbounded wait here was the live on-device wedge).
     */
    private fun maybeFinishLocked() {
        if (continuation == null) return
        if (!burstShouldFinish(remaining, expectedJoins, joined)) {
            if (remaining <= 0) armDrainTimeoutLocked()
            return
        }
        finishLocked()
    }

    /** One-shot per burst: force-finish if outstanding joins don't drain in [drainTimeoutMs]. */
    private fun armDrainTimeoutLocked() {
        if (drainArmed) return
        drainArmed = true
        val gen = generation
        scheduleOnState(drainTimeoutMs) {
            stateLock.withLock {
                if (generation != gen || continuation == null) return@withLock
                Log.w(TAG, "join drain timed out ($joined of $expectedJoins joins) — " +
                    "finishing with ${pendingRawFiles.size + pendingDeveloped.size} frames")
                finishLocked()
            }
        }
    }

    /** Resume the continuation exactly once and reset per-burst state. Must run on stateExecutor. */
    private fun finishLocked() {
        val cont = continuation ?: return
        Log.i(TAG, "finish: raw=${pendingRawFiles.size} dev=${pendingDeveloped.size} joined=$joined/$expectedJoins")
        continuation    = null
        inFlightToken   = NO_TOKEN
        activeRecipe    = null
        isSteadyCheck   = { true }
        onProgress      = null
        sweepPositions  = emptyList()

        val info = burstInfo
        burstInfo = null

        // Drop stranded join halves and release the burst's AE/AWB hold (plain preview resumes
        // the auto loop — iOS re-locks at the next shoot the same way). If the surface was
        // replaced mid-burst, the deferred reconfigure runs now (resumePreviewAfterBurstLocked).
        imageHandler.post { clearJoinState() }
        sessionExecutor.execute { resumePreviewAfterBurstLocked() }

        if (fallbackJPEG) {
            val imgs = pendingDeveloped.toList()
            pendingDeveloped = mutableListOf()
            if (imgs.isEmpty()) cont(Result.failure(CaptureError.NoFramesProduced))
            else                cont(Result.success(CapturedBurst(payload = CapturedBurst.Payload.Developed(imgs), info = info)))
        } else {
            // The RAW payload is a DISK-BACKED lazy list over this burst's spool files — the
            // engine API is unchanged (List<RawSensorFrame>) but no mosaic lives in memory until
            // the pipeline indexes it. Residency contract: BurstSpool.LazyFrameList kdoc.
            val files = pendingRawFiles.toList()
            pendingRawFiles = mutableListOf()
            if (files.isEmpty()) cont(Result.failure(CaptureError.NoFramesProduced))
            else                 cont(Result.success(CapturedBurst(payload = CapturedBurst.Payload.Raw(BurstSpool.LazyFrameList(files)), info = info)))
        }
    }

    // -----------------------------------------------------------------------
    // ImageReader callbacks (on imageHandler; conversion on the shared executor)
    // -----------------------------------------------------------------------

    /**
     * Long-edge pixel cap for JPEG fallback decode. Mirrors iOS [fallbackDecodeLongEdge].
     * 1500 px keeps a 30-frame fallback burst ≈ 0.8 GB instead of ≈ 2 GB at 2400 px.
     */
    private val fallbackDecodeLongEdge = 1500

    private val rawImageListener = ImageReader.OnImageAvailableListener { reader ->
        // imageHandler thread (same Handler as the capture callbacks → join maps need no lock).
        val image: Image? = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            Log.w(TAG, "RAW image acquire failed — frame dropped", e)
            null
        }
        if (image == null) return@OnImageAvailableListener
        val gen = stateLock.withLock { generation }
        val ts  = image.timestamp
        val meta = pendingMeta.remove(ts)
        if (meta != null) {
            submitConversion(gen, image, meta, isJpeg = false)   // result arrived first → join now
            return@OnImageAvailableListener
        }
        pendingImages[ts] = image                                // image arrived first → wait
        while (pendingImages.size > maxPendingImages) {
            val evictTs = pendingImages.keys.first()
            val evicted = pendingImages.remove(evictTs)
            try { evicted?.close() } catch (_: Throwable) {}
            Log.w(TAG, "evicted RAW image still awaiting its result ts=$evictTs — frame dropped")
        }
    }

    private val jpegImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image: Image? = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            Log.w(TAG, "JPEG image acquire failed — frame dropped", e)
            null
        }
        if (image == null) return@OnImageAvailableListener
        val gen = stateLock.withLock { generation }
        submitConversion(gen, image, meta = null, isJpeg = true)   // no result half needed
    }

    /**
     * Convert [image] on the shared [conversionExecutor]. ALWAYS closes the image and ALWAYS
     * reports join completion to the state machine — even on [Throwable] (an uncaught Error/OOM
     * killing a conversion runnable used to strand the burst's outstanding count forever; the
     * watchdog couldn't finish past it, which is the live wedge signature).
     *
     * RAW frames are SPOOLED TO DISK here (sequential on this executor; ~25 MB ≈ tens of ms on
     * UFS — well inside the inter-frame pacing) and only the [File] reaches the state machine,
     * so peak in-memory residency during a burst is ONE mosaic instead of all of them
     * (30 × ~25 MB ≈ 750 MB blew the 512 MB largeHeap ceiling at ~frame 21 — device finding).
     */
    private fun submitConversion(gen: Int, image: Image, meta: FrameColorMetadata?, isJpeg: Boolean) {
        val work = Runnable {
            var rawFile: File? = null
            var developed: PixelImage? = null
            try {
                if (isJpeg) {
                    developed = decodeJpegImage(image)
                } else {
                    val raw = convertRawImage(image, meta)
                    if (raw != null) rawFile = spoolRawFrame(gen, raw)
                    // `raw` goes out of scope here — the mosaic is garbage as soon as the spool
                    // write returns; nothing in-memory accumulates across the burst.
                }
            } catch (t: Throwable) {
                Log.w(TAG, "frame conversion failed — frame dropped", t)
            } finally {
                try { image.close() } catch (_: Throwable) {}
            }
            try {
                stateExecutor.execute {
                    stateLock.withLock { joinDoneLocked(gen, rawFile, developed) }
                }
            } catch (_: RejectedExecutionException) {}
        }
        try {
            conversionExecutor.execute(work)
        } catch (t: Throwable) {
            try { image.close() } catch (_: Throwable) {}
            Log.w(TAG, "conversion executor rejected frame — frame dropped", t)
            try {
                stateExecutor.execute { stateLock.withLock { joinDoneLocked(gen, null, null) } }
            } catch (_: RejectedExecutionException) {}
        }
    }

    /**
     * Write [raw] to this burst's spool dir and return the file, or null when the burst is
     * already stale (generation moved on) or the write fails (frame dropped — a missing frame
     * beats an OOM). Runs on [conversionExecutor].
     */
    private fun spoolRawFrame(gen: Int, raw: RawSensorFrame): File? {
        val dir = stateLock.withLock { if (generation == gen) spoolDir else null } ?: return null
        return try {
            val file = File(dir, "frame-%03d.bin".format(spoolCounter.getAndIncrement()))
            BurstSpool.write(file, raw)
            file
        } catch (t: Throwable) {
            Log.w(TAG, "RAW spool write failed — frame dropped", t)
            null
        }
    }

    /** Decode a JPEG [Image] into a linear [PixelImage] (fallback path). */
    private fun decodeJpegImage(image: Image): PixelImage? {
        val buffer = image.planes[0].buffer
        val bytes  = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val triple = ImageDecoder.rgba8(bytes, fallbackDecodeLongEdge)
        return triple?.let { (rgba, w, h) -> OutputTransform.decodeSRGB8(rgba, w, h) }
    }

    // -----------------------------------------------------------------------
    // RAW frame conversion
    // -----------------------------------------------------------------------

    /**
     * Convert a [ImageFormat.RAW_SENSOR] [Image] to a [RawSensorFrame].
     *
     * iOS spec is `RawFrameConverter.swift` — same semantics, Camera2 metadata sources:
     * - Width/height from [Image.getWidth]/[Image.getHeight].
     * - Pixel data: plane 0, 16-bit little-endian, row-stride-aware copy (rowStride may exceed
     *   width*2 on some hardware — copy only the valid pixel columns). The packed 2-byte case
     *   bulk-copies whole rows through a [java.nio.ShortBuffer] view (one JNI bounds-check per
     *   row instead of two virtual calls per pixel — the scalar loop took ~seconds per 12 MP
     *   frame and was the burst's backpressure source); anything unusual falls back to the
     *   scalar per-pixel loop.
     * - blackLevel: average of [CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]'s four CFA
     *   offsets, probed at configure (iOS averages the per-channel DNG BlackLevel array the same
     *   way). 64 only if the static key was null.
     * - whiteLevel: [CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL] probed at configure.
     * - wbGains: per-frame [CaptureResult.COLOR_CORRECTION_GAINS] joined by SENSOR_TIMESTAMP
     *   (green-relative, see [wbGainsFromRggb]); falls back to
     *   [CaptureResult.SENSOR_NEUTRAL_COLOR_POINT]-derived gains, then 1/1/1; [color] == null
     *   (result never delivered) is the only path to identity gains here.
     * - colorMatrix: per-frame [CaptureResult.COLOR_CORRECTION_TRANSFORM] converted to the
     *   engine's column-major layout; identity when unreported.
     */
    private fun convertRawImage(image: Image, color: FrameColorMetadata?): RawSensorFrame? {
        if (image.format != ImageFormat.RAW_SENSOR) return null
        val w = image.width
        val h = image.height
        val plane     = image.planes[0]
        val buffer    = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride   // bytes per row (may be > w * 2)
        val pixStride = plane.pixelStride // bytes per pixel (should be 2 for RAW_SENSOR)

        val mosaic = ShortArray(w * h)
        if (pixStride == 2 && rowStride % 2 == 0) {
            // Fast path: contiguous 16-bit pixels — bulk-copy each row.
            val shorts = buffer.asShortBuffer()
            val rowShorts = rowStride / 2
            for (row in 0 until h) {
                shorts.position(row * rowShorts)
                shorts.get(mosaic, row * w, w)
            }
        } else {
            // Defensive fallback for exotic strides: scalar per-pixel copy.
            for (row in 0 until h) {
                val rowStart = row * rowStride
                for (col in 0 until w) {
                    buffer.position(rowStart + col * pixStride)
                    mosaic[row * w + col] = buffer.short
                }
            }
        }

        val cfa = _cfaPattern ?: CFAPattern.RGGB   // fall back to RGGB if probe was inconclusive
        val wl  = _whiteLevel.toFloat()

        return RawSensorFrame(
            width       = w,
            height      = h,
            mosaic      = mosaic,
            blackLevel  = _blackLevel,
            whiteLevel  = wl,
            cfa         = cfa,
            wbGains     = color?.wbGains ?: Vec3(1f, 1f, 1f),
            colorMatrix = color?.colorMatrix ?: RawSensorFrame.IDENTITY_3X3.copyOf()
        )
    }

    // -----------------------------------------------------------------------
    // Lazy configure
    // -----------------------------------------------------------------------

    private fun ensurePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private suspend fun ensureConfigured() {
        suspendCancellableCoroutine { cont ->
            sessionExecutor.execute {
                try {
                    ensureConfiguredLocked()
                    cont.resume(Unit)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        }
    }

    /**
     * Configure the camera session. Must run on [sessionExecutor]. Opens the back wide-angle camera,
     * creates an [ImageReader] for [ImageFormat.RAW_SENSOR] (or [ImageFormat.JPEG] as fallback),
     * and probes capabilities. Both async waits are BOUNDED — an unbounded `latch.await()` here
     * used to block the session thread (and with it every queued capture) forever if the HAL
     * never called back.
     *
     * `protected open` only as the Robolectric test seam (PreviewResumeTest overrides it to count
     * configures without a camera HAL); production code must not override.
     */
    protected open fun ensureConfiguredLocked() {
        if (configured) return

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = chooseCameraId(manager)
            ?: throw RuntimeException("No suitable back camera found.")

        val characteristics = manager.getCameraCharacteristics(cameraId)

        // Probe RAW capability.
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

        // Probe Bayer CFA pattern; null = unsupported (ProRAW or unknown).
        val cfaInt   = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val cfaPattern: CFAPattern? = when (cfaInt) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> CFAPattern.RGGB
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> CFAPattern.GRBG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> CFAPattern.GBRG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> CFAPattern.BGGR
            else                                                              -> null
        }
        val rawReady = hasRaw && cfaPattern != null

        _cfaPattern        = cfaPattern
        _supportsRAWCapture = rawReady
        _whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023

        // Black level: static SENSOR_BLACK_LEVEL_PATTERN (per-CFA-site 2×2) averaged into the
        // engine's scalar, mirroring iOS's averaging of the DNG BlackLevel array. 64 stays only
        // as the null-key fallback. Hardcoding 0/wrong black lifts the black point and wrecks
        // shadow color (see RawFrameConverter.swift).
        val blp = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        _blackLevel = if (blp != null) {
            val ints = IntArray(4)
            blp.copyTo(ints, 0)
            blackLevelFromPattern(FloatArray(4) { ints[it].toFloat() })
        } else 64f

        // Manual lens position support (for Depth sweep).
        val minFD = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        _supportsDepthOfField = minFD != null && minFD > 0f
        _minimumFocusDistance = minFD ?: 10f

        // Manual exposure clamp ranges (Pro overrides) + metering coordinate space + AF/AE regions.
        _sensitivityRange  = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        _exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        _sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { rect ->
            _activeArrayWidth  = rect.width()
            _activeArrayHeight = rect.height()
        }
        _maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        _maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

        // Open the camera (Camera2 openCamera is async; bounded latch — see kdoc).
        val deviceRef  = AtomicReference<CameraDevice?>(null)
        val openError  = AtomicReference<Exception?>(null)
        val latch      = java.util.concurrent.CountDownLatch(1)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            // This callback lives for the DEVICE's whole life, not just the open: onDisconnected/
            // onError also fire LATER when the system evicts the camera (app backgrounded — the
            // live "black preview on return" bug 3). During open, counting the latch with
            // deviceRef unset makes the configure fail cleanly; after open, the session is
            // marked dead via invalidateSessionLocked so the next startPreview/ensureConfigured
            // reopens from scratch. (latch.countDown() past zero and invalidating a device that
            // never became cameraDevice are both no-ops, so each path is safe on both timings.)
            override fun onOpened(camera: CameraDevice) {
                deviceRef.set(camera); latch.countDown()
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "camera disconnected — marking session dead")
                camera.close(); latch.countDown()
                try {
                    sessionExecutor.execute { invalidateSessionLocked(camera) }
                } catch (_: RejectedExecutionException) {}
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.w(TAG, "camera error $error — marking session dead")
                openError.set(RuntimeException("Camera open error: $error"))
                camera.close(); latch.countDown()
                try {
                    sessionExecutor.execute { invalidateSessionLocked(camera) }
                } catch (_: RejectedExecutionException) {}
            }
        }, imageHandler)

        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw RuntimeException("Camera open timed out.")
        }
        openError.get()?.let { throw it }
        val device = deviceRef.get() ?: throw RuntimeException("Camera device unavailable.")
        cameraDevice = device

        // Set up ImageReaders.
        if (rawReady) {
            val sizes = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            // Largest RAW size ≤ ~12.6 MP, else the smallest available — iOS 12 MP cap parity
            // (PR #27: 48 MP RAW blew the memory ceiling). See chooseRawSize.
            val rawSize = sizes?.toList()?.let { list ->
                chooseRawSize(list) { it.width.toLong() * it.height.toLong() }
            }
            if (rawSize != null) {
                // maxImages = 3: one in conversion + up to maxPendingImages awaiting their results.
                // (At 2 the reader could fill while a conversion held a buffer, and an
                // un-acquirable image is a permanently lost listener notification.)
                val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, /*maxImages=*/3)
                reader.setOnImageAvailableListener(rawImageListener, imageHandler)
                rawImageReader = reader
            } else {
                _supportsRAWCapture = false   // no suitable RAW output size
            }
        }

        // Always create a JPEG reader (for fallback or preview-linked capture).
        run {
            val sizes = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
            val sz = sizes?.filter { it.width <= 4032 }?.maxByOrNull { it.width * it.height }
                ?: sizes?.minByOrNull { it.width * it.height }
            if (sz != null) {
                val reader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.JPEG, /*maxImages=*/2)
                reader.setOnImageAvailableListener(jpegImageListener, imageHandler)
                jpegImageReader = reader
            }
        }

        if (!_supportsRAWCapture && jpegImageReader == null) {
            throw RuntimeException("No suitable image output format available.")
        }

        // Build the capture session with all surfaces (preview first, then capture readers).
        val surfaces = buildList {
            previewSurface?.let { add(it) }
            rawImageReader?.surface?.let { add(it) }
            jpegImageReader?.surface?.let { add(it) }
        }

        val sessionRef  = AtomicReference<CameraCaptureSession?>(null)
        val sessionError = AtomicReference<Exception?>(null)
        val sessionLatch = java.util.concurrent.CountDownLatch(1)

        device.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    sessionRef.set(session); sessionLatch.countDown()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    sessionError.set(RuntimeException("CaptureSession configuration failed."))
                    sessionLatch.countDown()
                }
            },
            imageHandler
        )

        if (!sessionLatch.await(10, TimeUnit.SECONDS)) {
            throw RuntimeException("CaptureSession configuration timed out.")
        }
        sessionError.get()?.let { throw it }
        captureSession = sessionRef.get() ?: throw RuntimeException("CaptureSession unavailable.")

        configured = true

        // Start a repeating preview request if we have a preview surface.
        startPreviewRequestLocked()
    }

    /**
     * Tear down a dead camera session so the next [startPreview]/[ensureConfigured] reopens from
     * scratch (bug 3: background eviction closed the device, but `configured` stayed true so
     * nothing ever rebuilt — black preview). Must run on [sessionExecutor].
     *
     * [lostDevice] non-null = called from the device StateCallback; ignored when it isn't the
     * CURRENT device (a stale callback from an already-replaced device, or a failure during open
     * before [cameraDevice] was assigned — those paths are handled by the open latch).
     * [previewSurface] is deliberately KEPT: the window's surface usually survives backgrounding
     * and the rebuilt session should reuse it. A DESTROYED surface is cleared by
     * [clearPreviewSurface]; a recreated one arrives via [setPreviewSurface], which owns the
     * preview restart ([previewRequested]).
     *
     * Any in-flight burst is not touched here — its frames simply stop arriving and the
     * per-frame watchdog/drain timeout finish it with the frames gathered so far.
     */
    private fun invalidateSessionLocked(lostDevice: CameraDevice?) {
        if (lostDevice != null && cameraDevice !== lostDevice) return
        if (!configured && cameraDevice == null) return   // nothing to tear down
        Log.w(TAG, "invalidating camera session (configured=$configured)")
        try { captureSession?.close() } catch (_: Throwable) {}
        try { cameraDevice?.close() } catch (_: Throwable) {}
        try { rawImageReader?.close() } catch (_: Throwable) {}
        try { jpegImageReader?.close() } catch (_: Throwable) {}
        captureSession  = null
        cameraDevice    = null
        rawImageReader  = null
        jpegImageReader = null
        configured      = false
        imageHandler.post { clearJoinState() }
    }

    /**
     * Post-burst preview restore — the [finishLocked] tail. Two shapes, on [sessionExecutor]:
     *  - session still alive (the normal case) → swap the burst's AE/AWB hold for the plain
     *    repeating preview, resuming the auto loop;
     *  - the preview surface was REPLACED mid-burst ([setPreviewSurface] invalidated the session
     *    and DEFERRED its restart to here so the burst's remaining frames kept one consistent
     *    exposure) → the old session is dead, so honor the pending replacement with a full
     *    reconfigure (whose tail starts the repeating preview itself). Also covers a session
     *    lost mid-burst to device eviction: the sticky intent reopens at burst end, best-effort.
     */
    private fun resumePreviewAfterBurstLocked() {
        if (configured) { startPreviewRequestLocked(); return }
        if (!previewRequested || previewSurface == null) return
        try {
            ensureConfiguredLocked()
        } catch (e: Exception) {
            Log.w(TAG, "preview restore after burst failed", e)   // preview is best-effort
        }
    }

    /**
     * Start (or restore) the plain repeating preview request — no-op without a preview surface.
     * Also serves as the post-burst unlock: replacing the burst's hold request resumes the
     * AE/AWB auto loop. Reads the CURRENT [previewSurface] field at execution time.
     *
     * `protected open` only as the Robolectric test seam (PreviewResumeTest).
     */
    protected open fun startPreviewRequestLocked() {
        val session = captureSession ?: return
        val device  = cameraDevice  ?: return
        val surface = previewSurface ?: return
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            req.addTarget(surface)
            session.setRepeatingRequest(req.build(), null, imageHandler)
        } catch (e: Exception) {
            Log.w(TAG, "preview restart failed", e)   // preview is best-effort
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Release the camera and stop this service's worker threads (including the shared
     * [conversionExecutor]). Any in-flight burst is finished with the frames gathered so far.
     * The service is unusable afterwards. (MainActivity teardown calls [CaptureService.close].)
     */
    override fun close() {
        try {
            stateExecutor.execute {
                stateLock.withLock {
                    if (continuation != null) {
                        Log.w(TAG, "service closed mid-burst — finishing early")
                        finishLocked()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {}
        sessionExecutor.execute {
            try { captureSession?.close() } catch (_: Throwable) {}
            try { cameraDevice?.close() } catch (_: Throwable) {}
            try { rawImageReader?.close() } catch (_: Throwable) {}
            try { jpegImageReader?.close() } catch (_: Throwable) {}
            captureSession = null
            cameraDevice   = null
            rawImageReader = null
            jpegImageReader = null
            configured = false
            previewRequested = false   // a surface arriving after close must NOT reopen
            previewSurface = null
            imageHandler.post { clearJoinState() }
            scheduler.shutdown()
            conversionExecutor.shutdown()
            stateExecutor.shutdown()
            imageThread.quitSafely()
            sessionExecutor.shutdown()
        }
    }

    // -----------------------------------------------------------------------
    // Camera ID selection
    // -----------------------------------------------------------------------

    /**
     * Choose the back-facing wide-angle camera, preferring one with RAW capability.
     * Falls back to any back-facing camera if none has RAW. Mirrors iOS's "builtInWideAngleCamera".
     */
    private fun chooseCameraId(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        // Prefer a back camera with RAW support.
        for (id in ids) {
            val c = manager.getCameraCharacteristics(id)
            val facing = c.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return id
        }
        // Fall back to any back camera.
        return ids.firstOrNull { id ->
            val c = manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }
}

// ---------------------------------------------------------------------------
// Capture errors for Camera2 path
// ---------------------------------------------------------------------------

sealed class CaptureError(message: String) : Exception(message) {
    object PermissionDenied   : CaptureError("Camera access is off. Enable it in Settings ▸ Privacy ▸ Camera.")
    object NoDevice           : CaptureError("No camera is available on this device.")
    object NoFramesProduced   : CaptureError("Couldn't read the captured frames. Please try again.")
    object Busy               : CaptureError("A capture is already in progress.")
}
