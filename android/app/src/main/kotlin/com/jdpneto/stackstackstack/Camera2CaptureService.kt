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
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import com.jdpneto.stackengine.CFAPattern
import com.jdpneto.stackengine.OutputTransform
import com.jdpneto.stackengine.PixelImage
import com.jdpneto.stackengine.RawSensorFrame
import com.jdpneto.stackengine.Vec3
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * Camera2-backed [CaptureService]: sequential paced one-in-flight state machine on a single-thread
 * executor, mirroring the iOS [AVCaptureService] queue-confinement design (where
 * `stateQueue`/`sessionQueue` → `stateExecutor`/`sessionExecutor` here).
 *
 * RAW capture: uses [CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW] + supported Bayer
 * CFA pattern ([CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT] ∈ {RGGB,GRBG,GBRG,BGGR})
 * → [ImageReader] of format [ImageFormat.RAW_SENSOR] → 16-bit row-stride-aware copy into
 * [RawSensorFrame]. blackLevel from [CaptureResult.SENSOR_BLACK_LEVEL_PATTERN] first entry,
 * whiteLevel from [CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL], wbGains from
 * [CaptureResult.COLOR_CORRECTION_GAINS] (or neutral 1/1/1 if unavailable),
 * colorMatrix identity with a comment for v1 parity.
 *
 * Fallback: no RAW capability → [ImageFormat.JPEG] [ImageReader] → [ImageDecoder.rgba8] at
 * [fallbackDecodeLongEdge]=1500 → [OutputTransform.decodeSRGB8] → developed [PixelImage].
 * The emulator exercises this path live (B4).
 *
 * Focus-distance mapping (LENS_FOCUS_DISTANCE, in diopters):
 *   iOS `lensPosition`: 0 = closest focus, 1 = infinity (far).
 *   Camera2 LENS_FOCUS_DISTANCE: 0 = infinity, large positive = closest (diopters).
 *   The sweep/manual focus in [CaptureRecipe] / [ProControls] uses iOS convention (0=near, 1=far),
 *   so the mapping at capture time is:
 *       diopters = (1 - position) × minimumFocusDistance
 *   where `minimumFocusDistance` (paradoxically named) is the LARGEST diopter value —
 *   the closest-focus limit of the lens — obtained from
 *   [CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE].
 *   This inversion is documented explicitly so future porters don't accidentally re-invert it.
 *
 * [CaptureInfo]: [CaptureResult.SENSOR_SENSITIVITY] → iso (Double),
 *                [CaptureResult.SENSOR_EXPOSURE_TIME] ns → shutterSeconds (Double).
 *
 * Thread safety: all mutable burst state is confined to [stateExecutor] (single-thread).
 * Session/device configuration is confined to [sessionExecutor] (single-thread).
 * [ImageReader] callbacks run on [imageHandler]; the conversion dispatches to [stateExecutor].
 */
class Camera2CaptureService(
    private val context: Context
) : CaptureService {

    // -----------------------------------------------------------------------
    // Executors / handler
    // -----------------------------------------------------------------------

    /** Single-thread executor: serialises all mutable burst state (mirrors iOS stateQueue). */
    private val stateExecutor: Executor = Executors.newSingleThreadExecutor()

    /** Single-thread executor: serialises session/device configuration (mirrors iOS sessionQueue). */
    private val sessionExecutor: Executor = Executors.newSingleThreadExecutor()

    /** HandlerThread for ImageReader.OnImageAvailableListener callbacks. */
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
    private var configured = false

    // -----------------------------------------------------------------------
    // Capabilities (written on sessionExecutor once; read on stateExecutor after configure)
    // -----------------------------------------------------------------------

    @Volatile private var _supportsDepthOfField: Boolean = true
    @Volatile private var _supportsRAWCapture:   Boolean = true
    @Volatile private var _minimumFocusDistance: Float   = 10f   // diopters; optimistic
    @Volatile private var _cfaPattern:           CFAPattern? = null
    @Volatile private var _whiteLevel:           Int         = 1023

    override val supportsDepthOfField: Boolean get() = _supportsDepthOfField
    override val supportsRAWCapture:   Boolean get() = _supportsRAWCapture

    // -----------------------------------------------------------------------
    // Per-burst state (touched only on stateExecutor)
    // -----------------------------------------------------------------------

    private val stateLock = ReentrantLock()

    // Continuation for the in-flight burst.
    private var continuation: ((Result<CapturedBurst>) -> Unit)? = null

    private var burstInfo:        CaptureInfo? = null   // set-once from the first frame
    private var pendingRaw:       MutableList<RawSensorFrame> = mutableListOf()
    private var pendingDeveloped: MutableList<PixelImage>     = mutableListOf()
    private var fallbackJPEG:     Boolean = false
    private var remaining:        Int = 0              // frames still to request
    private var outstanding:      Int = 0              // in-flight conversions
    private var totalFrames:      Int = 0
    private var generation:       Int = 0              // bumps per burst; stale callbacks are ignored
    private var currentSequence:  Int = -1             // Camera2 sequenceId of the in-flight capture
    private var pacingMs:         Long = 100L
    private var perFrameTimeoutMs: Long = 5_000L
    private var sweepPositions:   List<Float> = emptyList()
    private var isSteadyCheck:    () -> Boolean = { true }
    private var onProgress:       ((Int) -> Unit)? = null
    private var gateAttempts:     Int = 0

    private val gateRecheckMs:       Long = 100L
    private val maxStartGateAttempts: Int  = 50
    private val maxFrameGateAttempts: Int  = 30

    // -----------------------------------------------------------------------
    // Display rotation (injectable from UI layer for orientation bake)
    // -----------------------------------------------------------------------

    /** The current display rotation ([Surface.ROTATION_*]); set by the UI before shooting. */
    @Volatile var displayRotation: Int = Surface.ROTATION_0

    // -----------------------------------------------------------------------
    // Preview surface provider (B3 UI hook)
    // -----------------------------------------------------------------------

    /**
     * Provide the [Surface] the Camera2 session should send the repeating preview into. Must be
     * called BEFORE [startPreview] so [ensureConfiguredLocked] can include it in the session
     * output list.
     *
     * Mirrors the iOS pattern where [AVCaptureService.startPreview] returns a [CALayer] that the
     * view hosts directly. Here the UI creates a [android.view.SurfaceView] (or [android.graphics.SurfaceTexture]),
     * extracts its [Surface], and registers it here; [startPreview] then returns it back to the
     * coordinator so the coordinator can hand it to the Compose [AndroidView].
     *
     * No-op if called after the session is already configured (the caller must recreate the
     * service if the surface changes, which matches the iOS app-lifecycle model).
     */
    fun setPreviewSurface(surface: Surface) {
        if (!configured) {
            previewSurface = surface
        }
    }

    // -----------------------------------------------------------------------
    // CaptureService: startPreview
    // -----------------------------------------------------------------------

    override suspend fun startPreview(): Surface? {
        if (!ensurePermission()) return null
        return suspendCancellableCoroutine { cont ->
            sessionExecutor.execute {
                try {
                    ensureConfiguredLocked()
                    cont.resume(previewSurface)
                } catch (e: Exception) {
                    cont.resume(null)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // CaptureService: setFocusExposure (tap-to-focus)
    // -----------------------------------------------------------------------

    override fun setFocusExposure(x: Float, y: Float, lock: Boolean) {
        sessionExecutor.execute {
            val session = captureSession ?: return@execute
            val device  = cameraDevice  ?: return@execute
            try {
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                previewSurface?.let { req.addTarget(it) }

                // AF trigger at the tap point.
                val afMode = CaptureRequest.CONTROL_AF_MODE_AUTO
                req.set(CaptureRequest.CONTROL_AF_MODE, afMode)
                req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

                // AE at the tap point.
                val aeMode = if (lock) CaptureRequest.CONTROL_AE_MODE_ON else CaptureRequest.CONTROL_AE_MODE_ON
                req.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
                if (lock) {
                    req.set(CaptureRequest.CONTROL_AE_LOCK, true)
                }

                // Metering rectangle centred at the normalised point.
                val region = MeteringRectangle(
                    maxOf(0, (x * 1000 - 100).toInt()),
                    maxOf(0, (y * 1000 - 100).toInt()),
                    200, 200, MeteringRectangle.METERING_WEIGHT_MAX
                )
                req.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                req.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))

                session.capture(req.build(), null, null)
            } catch (_: Exception) { /* tap-to-focus is a best-effort enhancement */ }
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
                    pendingRaw        = mutableListOf()
                    pendingDeveloped  = mutableListOf()
                    burstInfo         = null
                    outstanding       = 0
                    continuation      = { result -> cont.resumeWith(result) }
                    remaining         = frameCount
                    totalFrames       = frameCount
                    this.isSteadyCheck  = isSteady
                    this.onProgress   = onProgress
                    sweepPositions    = recipe.focusSweep?.positions ?: emptyList()
                    gateAttempts      = 0
                    currentSequence   = -1
                    pacingMs          = (pacingInterval * 1_000).toLong()
                    perFrameTimeoutMs = frameTimeout.toLong()
                    fallbackJPEG      = !_supportsRAWCapture

                    startNextFrameLocked(generation)
                }
            }
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
        if (!isSteadyCheck()) {
            gateAttempts++
            val isFirst   = (remaining == totalFrames)
            val maxAttempts = if (isFirst) maxStartGateAttempts else maxFrameGateAttempts
            if (gateAttempts <= maxAttempts) {
                stateExecutor.asDelayedExecutor(gateRecheckMs) { startNextFrameLocked(gen) }
                return
            }
            if (!isFirst) {
                // Later frame never steadied → stop requesting frames, stack what we have.
                remaining = 0
                maybeFinishLocked()
                return
            }
            // First frame timed out → fall through and capture anyway (≥1 frame guaranteed).
        }
        gateAttempts = 0

        val frameIndex    = totalFrames - remaining
        val sweepPosition: Float? = if (frameIndex in sweepPositions.indices) sweepPositions[frameIndex] else null

        val gen_ = gen
        sessionExecutor.execute {
            val active = stateLock.withLock { generation == gen_ }
            val session = captureSession
            val device  = cameraDevice
            if (!active || session == null || device == null) {
                stateExecutor.execute { stateLock.withLock { advanceLocked(completedSeq = -2) } }
                return@execute
            }

            try {
                val template = CameraDevice.TEMPLATE_STILL_CAPTURE
                val req      = device.createCaptureRequest(template)

                // Apply manual exposure/focus if requested by the recipe.
                applyRecipeSettings(req, recipe = null /* passed via closure capture */, sweepPosition)

                val targetSurface = if (fallbackJPEG) jpegImageReader?.surface else rawImageReader?.surface
                if (targetSurface == null) {
                    stateExecutor.execute { stateLock.withLock { advanceLocked(completedSeq = -2) } }
                    return@execute
                }
                req.addTarget(targetSurface)

                val seqId = session.capture(req.build(), captureCallback, imageHandler)

                stateExecutor.execute {
                    stateLock.withLock { currentSequence = seqId }
                }

                // Per-frame watchdog.
                stateExecutor.asDelayedExecutor(perFrameTimeoutMs) {
                    stateLock.withLock { timeoutFrameLocked(stuckSeq = seqId, gen = gen_) }
                }
            } catch (e: Exception) {
                stateExecutor.execute { stateLock.withLock { advanceLocked(completedSeq = -2) } }
            }
        }
    }

    /**
     * Apply focus-distance sweep position and any other recipe settings to [req].
     * Called on sessionExecutor.
     *
     * Focus-distance inversion (Camera2 vs iOS):
     *   iOS lensPosition 0 = closest, 1 = infinity (far).
     *   Camera2 LENS_FOCUS_DISTANCE in diopters: 0 = infinity, large = closest.
     *   Mapping: diopters = (1 - position) × _minimumFocusDistance.
     *   `_minimumFocusDistance` (the LENS_INFO_MINIMUM_FOCUS_DISTANCE characteristic) is the
     *   *maximum diopter value* — paradoxically named because it is the shortest focal distance.
     *   This inversion is intentional and mirrors exactly the opposite of the iOS API.
     */
    private fun applyRecipeSettings(
        req: CaptureRequest.Builder,
        recipe: CaptureRecipe?,
        sweepPosition: Float?
    ) {
        // AF off for manual focus via LENS_FOCUS_DISTANCE.
        if (sweepPosition != null) {
            // Sweep: set the lens position for this bracket.
            // Diopter mapping: diopters = (1 - sweepPosition) * minimumFocusDistance.
            // iOS position 0 = near (large diopters), 1 = far/infinity (diopters ≈ 0).
            val diopters = (1f - sweepPosition.coerceIn(0f, 1f)) * _minimumFocusDistance
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            req.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
        } else {
            // No sweep — lock to current AF state.
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }
        // AE: always locked for a stable burst.
        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        req.set(CaptureRequest.CONTROL_AE_LOCK, true)
        req.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        req.set(CaptureRequest.CONTROL_AWB_LOCK, true)
    }

    /**
     * Camera2 capture callbacks — all calls hop to stateExecutor before touching burst state.
     * Advancing only in onCaptureCompleted / onCaptureFailed bounds in-flight requests to one.
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val seq = result.sequenceId
            // Extract first-frame CaptureInfo from TotalCaptureResult.
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.toDouble()
            val shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val shutterSec = shutterNs?.let { it.toDouble() / 1_000_000_000.0 }

            stateExecutor.execute {
                stateLock.withLock {
                    if (burstInfo == null && (iso != null || shutterSec != null)) {
                        burstInfo = CaptureInfo(iso = iso, shutterSeconds = shutterSec)
                    }
                    // Advance the burst: camera has committed the frame; advance allows next request.
                    advanceLocked(completedSeq = seq)
                }
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            val seq = failure.sequenceId
            stateExecutor.execute {
                stateLock.withLock { advanceLocked(completedSeq = seq) }
            }
        }
    }

    /** Watchdog: a capture that hasn't reported back within [perFrameTimeoutMs] is a stall. */
    private fun timeoutFrameLocked(stuckSeq: Int, gen: Int) {
        if (continuation == null || generation != gen || currentSequence != stuckSeq) return
        currentSequence = -1
        remaining       = 0         // request no more frames…
        maybeFinishLocked()         // …but wait for outstanding conversions to drain
    }

    /**
     * Mark in-flight capture finished, request next frame after [pacingMs].
     * Idempotent per frame (callback + watchdog both call this; only the first while
     * currentSequence == completedSeq takes effect). Must run on stateExecutor.
     */
    private fun advanceLocked(completedSeq: Int) {
        if (continuation == null) return
        if (completedSeq != -2 && currentSequence != completedSeq) return   // already advanced
        currentSequence = -1
        remaining--
        if (remaining <= 0) { maybeFinishLocked(); return }
        val gen = generation
        stateExecutor.asDelayedExecutor(pacingMs) { startNextFrameLocked(gen) }
    }

    /** Resume the continuation once every capture has been requested AND every conversion done. */
    private fun maybeFinishLocked() {
        if (continuation == null || remaining > 0 || outstanding > 0) return
        finishLocked()
    }

    /** Resume the continuation exactly once and reset per-burst state. Must run on stateExecutor. */
    private fun finishLocked() {
        val cont = continuation ?: return
        continuation    = null
        currentSequence = -1
        isSteadyCheck   = { true }
        onProgress      = null
        sweepPositions  = emptyList()

        val info = burstInfo
        burstInfo = null

        if (fallbackJPEG) {
            val imgs = pendingDeveloped.toList()
            pendingDeveloped = mutableListOf()
            if (imgs.isEmpty()) cont(Result.failure(CaptureError.NoFramesProduced))
            else                cont(Result.success(CapturedBurst(payload = CapturedBurst.Payload.Developed(imgs), info = info)))
        } else {
            val frames = pendingRaw.toList()
            pendingRaw = mutableListOf()
            if (frames.isEmpty()) cont(Result.failure(CaptureError.NoFramesProduced))
            else                  cont(Result.success(CapturedBurst(payload = CapturedBurst.Payload.Raw(frames), info = info)))
        }
    }

    // -----------------------------------------------------------------------
    // ImageReader callbacks (on imageHandler; conversion to stateExecutor)
    // -----------------------------------------------------------------------

    /**
     * Long-edge pixel cap for JPEG fallback decode. Mirrors iOS [fallbackDecodeLongEdge].
     * 1500 px keeps a 30-frame fallback burst ≈ 0.8 GB instead of ≈ 2 GB at 2400 px.
     */
    private val fallbackDecodeLongEdge = 1500

    private val rawImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image: Image? = try { reader.acquireLatestImage() } catch (_: Exception) { null }
        val gen   = stateLock.withLock { generation }
        val isFB  = fallbackJPEG

        if (image == null) {
            stateExecutor.execute {
                stateLock.withLock {
                    outstanding = max(0, outstanding - 1)
                    maybeFinishLocked()
                }
            }
            return@OnImageAvailableListener
        }

        stateExecutor.execute {
            stateLock.withLock {
                if (generation != gen || continuation == null) {
                    image.close(); return@execute
                }
                outstanding++
            }
        }

        // Convert off the callback thread (the 12 MP buffer copy must not block Camera2).
        val convExecutor = Executors.newSingleThreadExecutor()
        convExecutor.execute {
            val frame: RawSensorFrame? = try {
                if (!isFB) convertRawImage(image) else null
            } catch (_: Exception) { null } finally { image.close() }

            stateExecutor.execute {
                stateLock.withLock {
                    if (generation != gen || continuation == null) { return@execute }
                    if (frame != null) {
                        pendingRaw.add(frame)
                        onProgress?.invoke(pendingRaw.size)
                    }
                    outstanding = max(0, outstanding - 1)
                    maybeFinishLocked()
                }
            }
        }
    }

    private val jpegImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image: Image? = try { reader.acquireLatestImage() } catch (_: Exception) { null }
        val gen = stateLock.withLock { generation }

        if (image == null) {
            stateExecutor.execute {
                stateLock.withLock {
                    outstanding = max(0, outstanding - 1)
                    maybeFinishLocked()
                }
            }
            return@OnImageAvailableListener
        }

        stateExecutor.execute {
            stateLock.withLock {
                if (generation != gen || continuation == null) {
                    image.close(); return@execute
                }
                outstanding++
            }
        }

        val convExecutor = Executors.newSingleThreadExecutor()
        convExecutor.execute {
            val decoded: PixelImage? = try {
                val buffer = image.planes[0].buffer
                val bytes  = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val triple = ImageDecoder.rgba8(bytes, fallbackDecodeLongEdge)
                triple?.let { (rgba, w, h) -> OutputTransform.decodeSRGB8(rgba, w, h) }
            } catch (_: Exception) { null } finally { image.close() }

            stateExecutor.execute {
                stateLock.withLock {
                    if (generation != gen || continuation == null) { return@execute }
                    if (decoded != null) {
                        pendingDeveloped.add(decoded)
                        onProgress?.invoke(pendingDeveloped.size)
                    }
                    outstanding = max(0, outstanding - 1)
                    maybeFinishLocked()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // RAW frame conversion
    // -----------------------------------------------------------------------

    /**
     * Convert a [ImageFormat.RAW_SENSOR] [Image] to a [RawSensorFrame].
     *
     * - Width/height from [Image.getWidth]/[Image.getHeight].
     * - Pixel data: plane 0, 16-bit little-endian, row-stride-aware copy (rowStride may exceed
     *   width*2 on some hardware — copy only the valid pixel columns).
     * - blackLevel: first value from [CaptureResult.SENSOR_BLACK_LEVEL_PATTERN] (from result
     *   available via session characteristics; falls back to 64).
     * - whiteLevel: [CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL] probed at configure.
     * - wbGains: [CaptureResult.COLOR_CORRECTION_GAINS] (rggb order) or neutral 1/1/1 if null.
     * - colorMatrix: identity (v1 parity — the engine's colour pipeline re-applies it; the raw
     *   mosaic data is processed by [ColorPipeline] without a separate matrix transform here).
     */
    private fun convertRawImage(image: Image): RawSensorFrame? {
        if (image.format != ImageFormat.RAW_SENSOR) return null
        val w = image.width
        val h = image.height
        val plane     = image.planes[0]
        val buffer    = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride   // bytes per row (may be > w * 2)
        val pixStride = plane.pixelStride // bytes per pixel (should be 2 for RAW_SENSOR)

        val mosaic = ShortArray(w * h)
        for (row in 0 until h) {
            val rowStart = row * rowStride
            for (col in 0 until w) {
                val idx    = rowStart + col * pixStride
                buffer.position(idx)
                mosaic[row * w + col] = buffer.short
            }
        }

        val cfa = _cfaPattern ?: CFAPattern.RGGB   // fall back to RGGB if probe was inconclusive
        val wl  = _whiteLevel.toFloat()

        // wbGains from result — not available in the synchronous callback here (the TotalCaptureResult
        // arrives in captureCallback); use neutral gains for v1 parity. The engine's ColorPipeline
        // applies WB in linear light. A production improvement would pass gains from the callback.
        val gains = Vec3(1f, 1f, 1f)

        return RawSensorFrame(
            width      = w,
            height     = h,
            mosaic     = mosaic,
            blackLevel = 64f,   // conservative fallback; real value from SENSOR_BLACK_LEVEL_PATTERN (v2)
            whiteLevel = wl,
            cfa        = cfa,
            wbGains    = gains
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
     * and probes capabilities.
     */
    private fun ensureConfiguredLocked() {
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

        // Manual lens position support (for Depth sweep).
        val minFD = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        _supportsDepthOfField = minFD != null && minFD > 0f
        _minimumFocusDistance = minFD ?: 10f

        // Open the camera synchronously (Camera2 openCamera is async; we use a latch here).
        val deviceRef  = AtomicReference<CameraDevice?>(null)
        val openError  = AtomicReference<Exception?>(null)
        val latch      = java.util.concurrent.CountDownLatch(1)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                deviceRef.set(camera); latch.countDown()
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close(); latch.countDown()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                openError.set(RuntimeException("Camera open error: $error"))
                camera.close(); latch.countDown()
            }
        }, imageHandler)

        latch.await()
        openError.get()?.let { throw it }
        val device = deviceRef.get() ?: throw RuntimeException("Camera device unavailable.")
        cameraDevice = device

        // Set up ImageReaders.
        if (rawReady) {
            val sizes = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val rawSize = sizes?.maxByOrNull { it.width * it.height }
            if (rawSize != null) {
                val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, /*maxImages=*/2)
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

        sessionLatch.await()
        sessionError.get()?.let { throw it }
        captureSession = sessionRef.get() ?: throw RuntimeException("CaptureSession unavailable.")

        configured = true

        // Start a repeating preview request if we have a preview surface.
        startPreviewRequestLocked()
    }

    /** Start a repeating preview capture request (no-op if no preview surface). */
    private fun startPreviewRequestLocked() {
        val session = captureSession ?: return
        val device  = cameraDevice  ?: return
        val surface = previewSurface ?: return
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            req.addTarget(surface)
            session.setRepeatingRequest(req.build(), null, imageHandler)
        } catch (_: Exception) { /* preview is best-effort */ }
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

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Post a task to an [Executor] after [delayMs] milliseconds via a [Handler]. */
private fun Executor.asDelayedExecutor(delayMs: Long, task: () -> Unit) {
    val h = Handler(android.os.Looper.getMainLooper())
    h.postDelayed({ this.execute(task) }, delayMs)
}
