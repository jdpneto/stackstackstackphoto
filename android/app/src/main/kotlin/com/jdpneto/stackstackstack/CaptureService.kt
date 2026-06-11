package com.jdpneto.stackstackstack

import android.view.Surface
import com.jdpneto.stackengine.PixelImage
import com.jdpneto.stackengine.RawSensorFrame
import com.jdpneto.stackengine.StackMode

/**
 * First-frame capture metadata (EXIF source for the encoded result). All fields are optional;
 * null = unavailable (fakes, non-RAW path). Mirrors iOS [CaptureInfo].
 *
 * Spec 2026-06-11 §1 deviation: first-frame only — locked-exposure bursts make per-frame
 * metadata redundant.
 */
data class CaptureInfo(
    /** ISO speed from the first frame's EXIF metadata; null if unavailable. */
    val iso: Double? = null,
    /** Exposure time in seconds from the first frame's EXIF metadata; null if unavailable. */
    val shutterSeconds: Double? = null
)

/**
 * One burst's output: the frames plus capture metadata from the first frame.
 * Mirrors iOS [CapturedBurst].
 */
data class CapturedBurst(
    val payload: Payload,
    /** First-frame EXIF metadata; null for fakes and for non-RAW paths that don't vend EXIF. */
    val info: CaptureInfo? = null
) {
    /**
     * What a burst produced: Bayer RAW frames (the quality path) or already-developed images
     * (the non-RAW "Standard quality" fallback; spec 2026-06-11 §3).
     */
    sealed class Payload {
        data class Raw(val frames: List<RawSensorFrame>) : Payload()
        data class Developed(val images: List<PixelImage>) : Payload()
    }

    val count: Int
        get() = when (val p = payload) {
            is Payload.Raw       -> p.frames.size
            is Payload.Developed -> p.images.size
        }

    val isEmpty: Boolean get() = count == 0
}

/**
 * A Depth focus sweep: M evenly-spaced lens positions from [near] to [far] (normalized
 * `lensPosition` space: 0 = closest, 1 = infinity). Frames are captured IN SWEEP ORDER —
 * the chain aligner depends on adjacency in focus. (spec 2026-06-10 §5.1)
 *
 * Normalizes a reversed range and clamps to 0…1 so a wild UI value can't escape — mirrors
 * Swift's custom `init(near:far:steps:)` that performs the same normalization.
 *
 * Mirrors iOS [CaptureRecipe.FocusSweep] 1:1.
 */
class FocusSweep(near: Float, far: Float, steps: Int) {
    val near: Float = minOf(maxOf(minOf(near, far), 0f), 1f)
    val far: Float  = minOf(maxOf(maxOf(near, far), 0f), 1f)
    val steps: Int  = maxOf(steps, 1)

    /**
     * The per-frame lens positions, near → far inclusive. A degenerate single-step sweep
     * shoots at [near] — the documented start of the range, not a surprise midpoint.
     */
    val positions: List<Float>
        get() {
            if (steps <= 1) return listOf(near)
            return (0 until steps).map { k ->
                near + (far - near) * k.toFloat() / (steps - 1).toFloat()
            }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FocusSweep) return false
        return near == other.near && far == other.far && steps == other.steps
    }

    override fun hashCode(): Int = 31 * (31 * near.hashCode() + far.hashCode()) + steps

    override fun toString(): String = "FocusSweep(near=$near, far=$far, steps=$steps)"
}

/**
 * How a burst is captured for a given look (design §10.4), plus optional manual Pro overrides.
 * Mirrors iOS [CaptureRecipe] 1:1.
 */
data class CaptureRecipe(
    val frameCount: Int,
    val durationSeconds: Double,
    val manualISO: Float? = null,
    val manualShutterSeconds: Double? = null,
    val manualFocus: Float? = null,
    /** Non-null for [StackMode.DEPTH_OF_FIELD]; null for all other looks. */
    val focusSweep: FocusSweep? = null
) {
    init {
        require(frameCount > 0) { "frameCount must be > 0" }
    }

    companion object {
        /**
         * Hard ceiling on burst length. The on-device develop+stack memory/time envelope is
         * sized for this. (design 2026-06-07 §2)
         */
        const val MAX_BURST_FRAMES = 20

        /**
         * Per-look capture policy. Frame counts trade noise/motion-sampling against memory + time.
         * The device burst is SEQUENTIAL and PACED by `durationSeconds / (frameCount-1)`.
         */
        fun recipe(mode: StackMode): CaptureRecipe = when (mode) {
            StackMode.NOISE_REDUCTION -> CaptureRecipe(frameCount = 8,  durationSeconds = 0.5)
            StackMode.LOW_LIGHT_BOOST -> CaptureRecipe(frameCount = 12, durationSeconds = 1.0)
            // Long-exposure looks: 15 frames (not 30) — full-res align+stack of 30 frames peaked
            // at ~3 GB and the OS killed the app. 15 keeps streak/blur at ~half the peak.
            StackMode.SMOOTH_MOTION   -> CaptureRecipe(frameCount = 15, durationSeconds = 2.0)
            StackMode.LIGHT_TRAILS    -> CaptureRecipe(frameCount = 15, durationSeconds = 3.0)
            // Depth: a near→far focus sweep; exposure/WB locked.
            StackMode.DEPTH_OF_FIELD  -> CaptureRecipe(
                frameCount = 10, durationSeconds = 1.0,
                focusSweep = FocusSweep(near = 0f, far = 1f, steps = 10)
            )
        }
    }

    /**
     * Merge manual Pro overrides onto a per-look recipe. Auto (null) fields leave the look
     * default; the frame-count override is clamped to ≥ 1 so the recipe stays valid.
     * For a sweep recipe the sweep absorbs Near/Far overrides and tracks the final frame count
     * (steps == frames), and the single manual-focus override is dropped — the sweep owns
     * lens position. Mirrors iOS `applying(_:)` exactly.
     */
    fun applying(pro: ProControls): CaptureRecipe {
        val count = minOf(MAX_BURST_FRAMES, maxOf(1, pro.frameCount ?: frameCount))
        val sweep = focusSweep?.let { s ->
            FocusSweep(
                near  = pro.focusSweepNear?.toFloat() ?: s.near,
                far   = pro.focusSweepFar?.toFloat() ?: s.far,
                steps = count
            )
        }
        return CaptureRecipe(
            frameCount           = count,
            durationSeconds      = durationSeconds,
            manualISO            = pro.iso?.toFloat() ?: manualISO,
            manualShutterSeconds = pro.shutterSeconds ?: manualShutterSeconds,
            manualFocus          = if (sweep != null) null else (pro.focus?.toFloat() ?: manualFocus),
            focusSweep           = sweep
        )
    }
}

/**
 * Capture service protocol — the boundary between the coordinator and the camera hardware.
 * Mirrors iOS [CaptureService] protocol exactly.
 */
interface CaptureService {
    /**
     * Capture a burst. [isSteady] is consulted before each frame; when it returns false the
     * burst waits (steadiness gating, long-exposure looks). [onProgress] is called after each
     * frame is appended, with the running count (1…n). (design 2026-06-07 §8)
     */
    suspend fun captureBurst(
        recipe: CaptureRecipe,
        isSteady: () -> Boolean = { true },
        onProgress: ((Int) -> Unit)? = null
    ): CapturedBurst

    /**
     * Start the live preview session and return a [Surface] for the preview, or null if
     * unavailable (e.g. the fake service in tests).
     */
    suspend fun startPreview(): Surface?

    /**
     * Tap-to-focus: focus + meter exposure at a normalised point (0…1). [lock] = long-press
     * holds AF/AE. Device-only; the default is a no-op so tests don't need to override it.
     */
    fun setFocusExposure(x: Float, y: Float, lock: Boolean) { /* no-op for fakes */ }

    /** Whether the device can step lens position for a Depth focus sweep. */
    val supportsDepthOfField: Boolean get() = true

    /** Whether the camera vends a Bayer RAW format the converter can decode. */
    val supportsRAWCapture: Boolean get() = true
}

/**
 * Extension: looks whose capture quality depends on holding a pose. Mirrors iOS [StackMode.usesSteadinessGate].
 */
val StackMode.usesSteadinessGate: Boolean
    get() = isLongExposure || this == StackMode.DEPTH_OF_FIELD
