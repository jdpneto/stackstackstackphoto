package com.jdpneto.stackengine

import kotlin.math.max
import kotlin.math.min

/**
 * A dedicated cancellation exception that is NOT a subtype of [kotlinx.coroutines.CancellationException],
 * because coroutines machinery silently swallows that type. Callers catch this explicitly.
 * Mirrors `CancellationError` from Swift (translation rule 6).
 */
class StackCancellationException(message: String = "stack cancelled") : Exception(message)

object Pipeline {

    /**
     * Align a burst to its sharpest frame (shared by every look). Returns the aligned frames AND
     * the index of the reference (sharpest) frame so callers can retrieve it without recomputing.
     * Luminance is computed once per frame and reused for reference selection AND per-frame alignment.
     */
    internal fun alignedStackWithRefIdx(
        imgs: List<PixelImage>, searchRange: Int
    ): Pair<List<PixelImage>, Int> {
        require(imgs.isNotEmpty())
        if (imgs.size == 1) return Pair(imgs, 0)
        val w = imgs[0].width; val h = imgs[0].height
        require(imgs.all { it.width == w && it.height == h }) { "all images must be the same size" }
        // Luma per frame is reused for reference selection. Alignment is per-frame independent → cores.
        val lumas = parallelMap(imgs) { Luma.luminance(it) }
        val refIdx = ReferenceSelection.sharpestIndex(lumas = lumas, width = w, height = h)
        // Hand shake ROTATES (not just shifts) the frame; a translation-only fit leaves static detail
        // (text) smeared after combining. Estimate a robust SIMILARITY transform (rotation + scale +
        // translation) — cheaply, on a downscaled copy (rotation/scale are resolution-invariant, the
        // translation just scales) — then warp the full-res frame by it. `robustClip` caps per-pixel
        // residuals so the fit locks onto the static background and ignores genuinely-moving regions.
        val refSmall = downscaleOne(imgs[refIdx], alignmentEstimateEdge)
        val factor = w.toFloat() / refSmall.width.toFloat()
        val aligned = parallelMap(imgs.indices.toList()) { i ->
            if (i == refIdx) return@parallelMap imgs[i]
            val movSmall = downscaleOne(imgs[i], alignmentEstimateEdge)
            val ts = AffineAligner.estimate(
                reference = refSmall, moving = movSmall,
                translationSearch = searchRange, robustClip = alignmentRobustClip
            )
            val t = Transform2D(a = ts.a, b = ts.b, c = ts.c, d = ts.d, tx = ts.tx * factor, ty = ts.ty * factor)
            AffineAligner.warp(imgs[i], by = t)
        }
        return Pair(aligned, refIdx)
    }

    /**
     * Align a burst to its sharpest frame. Wrapper over [alignedStackWithRefIdx] for callers that
     * don't need the reference index.
     */
    internal fun alignedStack(imgs: List<PixelImage>, searchRange: Int): List<PixelImage> =
        alignedStackWithRefIdx(imgs, searchRange).first

    /**
     * Long-edge (px) the similarity transform is estimated at. Rotation/scale are resolution-
     * invariant and the rotation-step floor gives ~0.02° precision regardless, so estimating small
     * is accurate and far cheaper than fitting at full working resolution.
     */
    internal const val alignmentEstimateEdge = 720

    /**
     * Caps each pixel's squared luma residual during estimation (luma ∈ [0,1]); |Δluma| ≳ 0.14 is
     * treated as "moves differently from the global motion" and down-weighted.
     */
    internal const val alignmentRobustClip: Float = 0.02f

    /**
     * Align then apply the look's reducer, ALSO returning the aligned reference (sharpest) frame —
     * the second endpoint of the editor's blend-strength lerp. (spec 2026-06-11 §3)
     */
    fun reduceImagesWithReference(
        imgs: List<PixelImage>, mode: StackMode, searchRange: Int = 8,
        workingResolution: Int? = null
    ): Pair<PixelImage, PixelImage> {
        val (aligned, refIdx) = alignedStackWithRefIdx(downscale(imgs, workingResolution), searchRange)
        val result = reduceAligned(aligned, mode)
        // The blend endpoints must share the look's exposure — α trades noise, not brightness.
        // For lowLightBoost the result is a gain-boosted mean; scale the reference by the same gain
        // so α=0 (reference) and α=1 (result) sit at the same perceptual brightness.
        val rawRef = aligned[refIdx]
        val reference: PixelImage
        if (mode == StackMode.LOW_LIGHT_BOOST) {
            val gain = StackReducer.defaultLowLightGain
            val scaled = rawRef.pixels.copyOf()
            for (i in scaled.indices) { scaled[i] *= gain }
            reference = PixelImage(rawRef.width, rawRef.height, scaled)
        } else {
            reference = rawRef
        }
        return Pair(result, reference)
    }

    /**
     * Align then apply the look's reducer. [workingResolution] (long-edge px, null = full) downscales
     * the developed frames before align/stack — the dominant lever for on-device speed + memory,
     * since alignment and stacking cost scale with pixel count. Use [noiseReductionImages] for an
     * explicit kappa (sigma-clipping threshold) rather than the fixed default.
     */
    fun reduceImages(
        imgs: List<PixelImage>, mode: StackMode, searchRange: Int = 8,
        workingResolution: Int? = null
    ): PixelImage = reduceImagesWithReference(imgs, mode, searchRange, workingResolution).first

    /** Shared reducer dispatch over an already-aligned stack. */
    private fun reduceAligned(aligned: List<PixelImage>, mode: StackMode): PixelImage =
        when (mode) {
            StackMode.NOISE_REDUCTION -> StackReducer.sigmaClippedMean(aligned) // kappa fixed at 2.0
            StackMode.SMOOTH_MOTION   -> StackReducer.mean(aligned)              // aligned mean is sharp where static, blurred where moving
            StackMode.LIGHT_TRAILS    -> {
                // `lighten` (per-pixel max) makes light streaks where things move, but in STATIC regions
                // it amplifies noise and any residual-misalignment edge into a smeared double-image. So
                // composite: a clean aligned mean where nothing moved, the max only where it did.
                val base = StackReducer.mean(aligned)
                val streaks = StackReducer.lighten(aligned)
                val mask = MotionComposite.motionMask(aligned, trailsMotionLo, trailsMotionHi, smoothRadius = 2)
                MotionComposite.blend(staticBase = base, effect = streaks, mask = mask)
            }
            StackMode.LOW_LIGHT_BOOST -> StackReducer.boostedMean(aligned, gain = StackReducer.defaultLowLightGain)
            StackMode.DEPTH_OF_FIELD  -> throw IllegalStateException(
                "Depth of Field is stacked by FocusStacker.allInFocus, not Pipeline.reduce — fix the caller's routing"
            )
        }

    /**
     * Light-trails motion band (temporal luma range across aligned frames). Below [trailsMotionLo] a
     * pixel is treated as static (noise only) and kept as the clean mean; above [trailsMotionHi] it's
     * moving and gets the streak. Tuned for linear luma ∈ [0,1]; a Pro sensitivity control is a follow-up.
     */
    internal const val trailsMotionLo: Float = 0.05f
    internal const val trailsMotionHi: Float = 0.15f

    /**
     * Streaming/incremental reducer for the long-exposure looks: folds one frame at a time into
     * per-pixel accumulators and releases it, so peak memory is bounded by ~1-2 frames + a fixed
     * set of accumulator buffers regardless of frame count (vs. holding all developed + all aligned
     * frames). [aligned] returns frame i ALREADY aligned to the anchor (frame 0); it is called in
     * order and the result folded immediately, so only one frame is live at a time. [shouldCancel]
     * is checked between frames; a true return throws [StackCancellationException] (no result). Only
     * smoothMotion / lightTrails are supported. (design 2026-06-07 §6)
     */
    @Throws(StackCancellationException::class)
    internal fun streamingReduce(
        count: Int, mode: StackMode,
        shouldCancel: () -> Boolean = { false },
        aligned: (Int) -> PixelImage
    ): PixelImage {
        require(count >= 1) { "need at least one frame" }
        require(mode == StackMode.SMOOTH_MOTION || mode == StackMode.LIGHT_TRAILS) {
            "streamingReduce supports the long-exposure looks only"
        }
        val first = aligned(0)
        val w = first.width; val h = first.height
        val wantTrails = (mode == StackMode.LIGHT_TRAILS)

        val sum = FloatArray(w * h * 3)       // running sum  → mean / base
        val maxRGB = if (wantTrails) FloatArray(w * h * 3) { -Float.MAX_VALUE } else FloatArray(0)
        val lumaMin = if (wantTrails) FloatArray(w * h) {  Float.MAX_VALUE } else FloatArray(0)
        val lumaMax = if (wantTrails) FloatArray(w * h) { -Float.MAX_VALUE } else FloatArray(0)
        var frameCount = 0

        fun fold(img: PixelImage) {
            require(img.width == w && img.height == h) { "all frames must be the same size" }
            for (i in 0 until (w * h)) {
                val base = i * 3
                sum[base]     += img.pixels[base]
                sum[base + 1] += img.pixels[base + 1]
                sum[base + 2] += img.pixels[base + 2]
                if (wantTrails) {
                    if (img.pixels[base]     > maxRGB[base])     maxRGB[base]     = img.pixels[base]
                    if (img.pixels[base + 1] > maxRGB[base + 1]) maxRGB[base + 1] = img.pixels[base + 1]
                    if (img.pixels[base + 2] > maxRGB[base + 2]) maxRGB[base + 2] = img.pixels[base + 2]
                    val l = Luma.rec709(Vec3(img.pixels[base], img.pixels[base + 1], img.pixels[base + 2]))
                    if (l < lumaMin[i]) lumaMin[i] = l
                    if (l > lumaMax[i]) lumaMax[i] = l
                }
            }
            frameCount++
        }

        fold(first)                                   // the anchor folds as itself
        for (i in 1 until count) {
            if (shouldCancel()) throw StackCancellationException()
            fold(aligned(i))
        }

        val inv = 1f / frameCount.toFloat()
        val base = PixelImage(w, h, FloatArray(w * h * 3) { sum[it] * inv })  // mean
        if (!wantTrails) return base                                            // smoothMotion = mean

        // lightTrails: streaks (running max), motion mask from the temporal luma range, composite.
        val streaks = PixelImage(w, h, maxRGB)
        val invSpan = 1f / max(trailsMotionHi - trailsMotionLo, 1e-6f)
        var mask = FloatArray(w * h)
        for (i in 0 until (w * h)) {
            val c = min(max((lumaMax[i] - lumaMin[i] - trailsMotionLo) * invSpan, 0f), 1f)
            mask[i] = c * c * (3f - 2f * c)                               // smoothstep
        }
        mask = BoxFilter.mean(mask, w, h, 2)     // same smoothRadius as motionMask
        return MotionComposite.blend(staticBase = base, effect = streaks, mask = mask)
    }

    /**
     * Streaming reduce over already-developed frames (the non-RAW fallback): aligns each frame to
     * the FIRST frame (anchor) and folds it immediately, so peak memory is the input array plus
     * one warped frame — never a second full aligned array. Long-exposure looks only.
     * (spec 2026-06-11 §3; mirrors reduceStreamingWithReference's anchor semantics)
     */
    @Throws(StackCancellationException::class)
    fun reduceImagesStreamingWithReference(
        imgs: List<PixelImage>, mode: StackMode, searchRange: Int = 8,
        shouldCancel: () -> Boolean = { false }
    ): Pair<PixelImage, PixelImage> {
        require(imgs.isNotEmpty()) { "need at least one frame" }
        require(mode.isLongExposure) { "images streaming supports long-exposure looks only" }
        val reference = imgs[0]
        val refSmall = downscaleOne(reference, alignmentEstimateEdge)
        val factor = reference.width.toFloat() / refSmall.width.toFloat()
        val result = streamingReduce(count = imgs.size, mode = mode, shouldCancel = shouldCancel) { i ->
            if (i == 0) return@streamingReduce reference
            val movSmall = downscaleOne(imgs[i], alignmentEstimateEdge)
            val ts = AffineAligner.estimate(
                reference = refSmall, moving = movSmall,
                translationSearch = searchRange, robustClip = alignmentRobustClip
            )
            val t = Transform2D(a = ts.a, b = ts.b, c = ts.c, d = ts.d, tx = ts.tx * factor, ty = ts.ty * factor)
            AffineAligner.warp(imgs[i], by = t)
        }
        return Pair(result, reference)
    }

    /**
     * End-to-end streaming stack for the long-exposure looks, ALSO returning the anchor (frame 0 at
     * working resolution) — it is already held alive for alignment; returning it costs nothing.
     * (spec 2026-06-11 §3)
     */
    @Throws(StackCancellationException::class)
    fun reduceStreamingWithReference(
        frames: List<RawSensorFrame>, mode: StackMode, searchRange: Int = 8,
        workingResolution: Int? = null, binnedDevelop: Boolean = true,
        shouldCancel: () -> Boolean = { false }
    ): Pair<PixelImage, PixelImage> {
        require(frames.isNotEmpty()) { "need at least one frame" }
        if (shouldCancel()) throw StackCancellationException()
        require(mode.isLongExposure) {
            "reduceStreamingWithReference supports long-exposure looks only (.smoothMotion / .lightTrails)"
        }
        fun develop(i: Int): PixelImage {
            val d = if (binnedDevelop) ColorPipeline.processBinned(frames[i]) else ColorPipeline.process(frames[i])
            return if (workingResolution != null) downscaleOne(d, workingResolution) else d
        }
        val anchor = develop(0)                                      // anchor, kept for the whole run
        val refSmall = downscaleOne(anchor, alignmentEstimateEdge)
        val factor = anchor.width.toFloat() / refSmall.width.toFloat()
        val result = streamingReduce(count = frames.size, mode = mode, shouldCancel = shouldCancel) { i ->
            if (i == 0) return@streamingReduce anchor
            val moving = develop(i)
            val movSmall = downscaleOne(moving, alignmentEstimateEdge)
            val ts = AffineAligner.estimate(
                reference = refSmall, moving = movSmall,
                translationSearch = searchRange, robustClip = alignmentRobustClip
            )
            val t = Transform2D(a = ts.a, b = ts.b, c = ts.c, d = ts.d, tx = ts.tx * factor, ty = ts.ty * factor)
            AffineAligner.warp(moving, by = t)
        }
        return Pair(result, anchor)
    }

    /**
     * End-to-end streaming stack for the long-exposure looks. Develops each raw frame on demand,
     * downscales to [workingResolution], aligns it to the FIRST frame (the anchor — streaming can't
     * hold all frames to pick the sharpest, and the steadiness gate keeps the burst near one pose),
     * and folds it via [streamingReduce]. Only one developed/aligned frame is live at a time.
     * (design 2026-06-07 §6)
     */
    @Throws(StackCancellationException::class)
    fun reduceStreaming(
        frames: List<RawSensorFrame>, mode: StackMode, searchRange: Int = 8,
        workingResolution: Int? = null, binnedDevelop: Boolean = true,
        shouldCancel: () -> Boolean = { false }
    ): PixelImage = reduceStreamingWithReference(
        frames, mode, searchRange, workingResolution, binnedDevelop, shouldCancel
    ).first

    /**
     * Peak resident set of the BATCH align+reduce path ([reduceImagesWithReference] and friends),
     * in working-resolution frame-equivalents, for an N-frame stack:
     *
     *   held inputs N (the developed frames stay live through alignment)
     *   + aligned/warped copies N ([alignedStackWithRefIdx] materializes the full aligned list)
     *   + luma planes N/3 (one single-channel float plane per frame, 1/3 of an RGB frame)
     *   + ~3 frames of slack (reference selection, downscaled estimate copies, reducer transients)
     *
     * Owned by the engine because it encodes THIS file's batch internals — callers (the app's
     * heap-aware working-resolution budget) must not hard-code these coefficients, or an engine
     * refactor silently invalidates their memory math.
     */
    fun batchPeakFrameEquivalents(frameCount: Int): Double =
        2.0 * frameCount + frameCount / 3.0 + 3.0

    /**
     * Width of the develop fan-out. Each worker transiently holds one FULL-SIZE developed frame
     * (~38 MB for a 12 MP sensor's binned develop) before it is downscaled to the working
     * resolution, so bounding the width bounds the transient memory spike on small-heap devices
     * (Android's Java heap is 512 MB on a Pixel even with largeHeap). Width does not affect
     * results — develop+downscale is a pure per-item op written to order-preserving slots.
     */
    private val developParallelism: Int
        get() = min(Runtime.getRuntime().availableProcessors(), 4)

    /**
     * The developed + working-resolution frames that the batch looks feed to alignment (also the
     * app's diagnostic dump of the exact alignment input for offline registration debugging).
     *
     * Develop and downscale are FUSED per frame: each worker's full-size developed frame is
     * transient and only its downscaled copy is retained — vs. developing ALL frames first and
     * downscaling after, which holds the full-size set and the downscaled set simultaneously
     * (the batch-path OOM on small-heap devices). Bit-identical to the unfused order.
     */
    fun developedFrames(
        frames: List<RawSensorFrame>, binnedDevelop: Boolean, workingResolution: Int?
    ): List<PixelImage> = parallelMap(frames, maxParallel = developParallelism) {
        val developed = if (binnedDevelop) ColorPipeline.processBinned(it) else ColorPipeline.process(it)
        if (workingResolution != null && workingResolution >= 1) {
            downscaleOne(developed, workingResolution)   // full-size frame dies here, per worker
        } else {
            developed
        }
    }

    /**
     * End-to-end from raw frames: develop each → (downscale) → align → reduce for the chosen look.
     * [binnedDevelop] uses the fast half-resolution 2×2-bin develop (managed/on-device path); the
     * full bilinear demosaic is the dominant develop cost otherwise.
     */
    fun reduce(
        frames: List<RawSensorFrame>, mode: StackMode, searchRange: Int = 8,
        workingResolution: Int? = null, binnedDevelop: Boolean = false
    ): PixelImage {
        // Develop is the dominant cost (a full demosaic per frame) and each frame is independent.
        // Fused develop→downscale (see developedFrames) keeps only working-resolution frames
        // resident; the downscale inside reduceImages is then a no-copy pass-through.
        val developed = developedFrames(frames, binnedDevelop, workingResolution)
        return reduceImages(developed, mode, searchRange, workingResolution)
    }

    /**
     * Halve (Gaussian reduce) each frame until its long edge is within [maxEdge] (null = no downscale).
     * Frames start equal-size and reduce deterministically, so they stay equal-size.
     * Already-fitting frames pass through by reference (no pixel copies), so callers that
     * pre-downscaled (e.g. [developedFrames]) pay nothing here.
     */
    private fun downscale(imgs: List<PixelImage>, maxEdge: Int?): List<PixelImage> {
        if (maxEdge == null || maxEdge < 1) return imgs   // <1 would loop forever (reduce floors at 1)
        return parallelMap(imgs) { downscaleOne(it, maxEdge) }
    }

    /**
     * Halve (Gaussian reduce) one image until its long edge is within [maxEdge] (≥ 1).
     * Returns [img] itself (no copy) when it already fits.
     */
    private fun downscaleOne(img: PixelImage, maxEdge: Int): PixelImage {
        var out = img
        while (maxOf(out.width, out.height) > maxEdge) { out = ImagePyramid.reduce(out) }
        return out
    }

    // MARK: - Noise-reduction-specific entry points (kept for the golden harness/tests)

    /** Noise reduction with an explicit kappa (used by the golden convergence test). */
    fun noiseReductionImages(
        imgs: List<PixelImage>, searchRange: Int = 8, kappa: Float = 2.0f
    ): PixelImage = StackReducer.sigmaClippedMean(alignedStack(imgs, searchRange), kappa = kappa)

    fun noiseReduction(
        frames: List<RawSensorFrame>, searchRange: Int = 8, kappa: Float = 2.0f
    ): PixelImage = noiseReductionImages(frames.map { ColorPipeline.process(it) }, searchRange, kappa)

    fun noiseReductionEncoded(frames: List<RawSensorFrame>): Pair<PixelImage, ByteArray> {
        val result = noiseReduction(frames)
        return Pair(result, OutputTransform.encodeSRGB8(result))
    }
}
