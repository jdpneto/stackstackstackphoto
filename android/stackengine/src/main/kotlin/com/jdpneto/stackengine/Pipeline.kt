package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
            val t = estimateWholeFrameAlignment(
                referenceSmall = refSmall, movingSmall = movSmall,
                factor = factor, searchRange = searchRange
            )
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
     * Bounds for the ADAPTIVE per-pair robust clip used during WHOLE-FRAME registration. The clip
     * caps each pixel's squared luma residual (luma ∈ [0,1]): residuals above it are treated as
     * "moves differently from the global motion" and contribute a fixed (saturated) cost.
     *
     * WHY ADAPTIVE (investigation 2026-06-12, dim-room-with-TV ghosting on device): the clip is
     * an ABSOLUTE cap, and no fixed value fits every scene.
     *  • Too LOOSE in a dim scene: with the old fixed 0.02 (≈ |Δluma| 0.14, calibrated bright),
     *    a dim static background (luma 0.01–0.1) produces residuals far below the cap — almost
     *    no cost — while a bright mover (a TV at luma 0.2–0.97) dominates: its scene cuts
     *    saturate at the cap (still 20–200× the background signal) and its smooth pans stay
     *    UNDER the cap and are fully trusted, so the optimizer fits the TV's content motion as
     *    camera motion (43 px misregistration at 1.5 px true drift on device; clip sweep on the
     *    synthetic repro: 0.02 → 184 px mean error, 1e-4 → 1.2 px).
     *  • Too TIGHT in a bright scene: a fixed 1e-4 (≈ |Δluma| 0.01) sits BELOW the residual
     *    floor of bright noisy frames (sensor noise alone exceeds it) and below the sub-pixel
     *    interpolation residual of any high-contrast texture — every pixel saturates, the cost
     *    surface goes flat, and both the integer pre-pass and the similarity refinement lose
     *    their signal (measured 2026-06-12: HandheldAlignmentTests' ±1° shake stopped being
     *    corrected and PipelineTests' noisy stack stopped converging at a fixed 1e-4).
     * So the clip is derived per frame pair from the data in TWO stages (see
     * [estimateWholeFrameAlignment]): a loose clip from the UNALIGNED coarse residual seeds the
     * robust integer pre-pass, then the clip is re-measured AT the pre-pass shift — the aligned
     * residual floor — for the similarity refinement. The second stage matters: measuring at
     * zero shift inflates the median by the burst's own integer drift (texture decorrelation),
     * which is exactly the signal being estimated, and the resulting over-loose clip let
     * edge-clamp outlier pixels keep full quadratic influence and drag the optimizer into
     * micro-rotations (measured 2026-06-12 on PipelineTests' noisy stack: r ≈ 0.003–0.006 rad
     * spurious vs ≤ 0.001 with the floor measured at the aligned shift).
     * Only a FLOOR of 1e-4 (the dim-scene-validated value) is applied — deliberately NO ceiling:
     * capping the clip below the scene's own residual floor recreates the flat-cost failure
     * (verified 2026-06-12 — a 0.02 ceiling re-broke the ±1° shake test by saturating the
     * blurred coarse checkerboard's sub-pixel residuals).
     *
     * NOTE: the depth-of-field chain deliberately has its OWN fixed clip ([ChainBounds.robustClip]
     * = 1e-4, field-proven with `ChainBounds.default` on real brackets) — kept separate so
     * retuning one path can never silently change the other.
     */
    internal const val alignmentRobustClipFloor: Float = 1e-4f

    /**
     * The clip saturates residuals beyond [alignmentClipSigma] robust standard deviations of the
     * measured coarse residual floor: inliers (noise + sub-pixel interpolation error) stay
     * un-saturated and keep the cost surface informative, while genuinely-different content
     * (a bright mover, a scene cut) saturates. 3σ ⇒ ~0.3% false saturation if residuals were
     * Gaussian — i.e. essentially only true outliers are capped.
     */
    internal const val alignmentClipSigma: Float = 3f

    /**
     * Plausibility bounds for the whole-frame similarity estimate (ChainBounds-style, but sized
     * for a WHOLE BURST relative to its anchor rather than one focus step). Handheld burst motion
     * is bounded by the steadiness gate (pitch/roll ≲ 2.9°) and the ~2 s burst duration (yaw
     * drift ≈ 6% of the long edge at working resolution, investigation 2026-06-12); a similarity
     * fit outside these bounds is the optimizer explaining scene content motion (the bright-TV
     * failure) as camera motion, and must not be trusted with scale/rotation.
     */
    internal const val alignmentMaxScaleDelta: Float = 0.05f
    internal const val alignmentMaxRotationRadians: Float = 0.08f        // ~4.6°
    internal const val alignmentMaxTranslationFraction: Float = 0.25f    // of the estimate-image long edge

    /**
     * Whole-frame anchor registration shared by [alignedStackWithRefIdx] (batch) and both
     * streaming paths. [referenceSmall]/[movingSmall] are the estimate-resolution copies (long
     * edge ≤ [alignmentEstimateEdge]); [factor] scales estimate-space translation back to
     * working-resolution pixels.
     *
     * Recipe (investigation 2026-06-12 — dim-scene/bright-mover ghosting; mirrors the depth
     * chain's field-proven `boundedLink`):
     *  0. ADAPTIVE robust clip in two stages (see [alignmentRobustClipFloor]): loose from the
     *     unaligned coarse residual, then re-measured at the pre-pass shift (the aligned floor).
     *  1. ROBUST translation pre-pass at the coarsest pyramid level — the plain-SSD integer seed
     *     is what let a bright mover place the optimizer in the wrong basin in a dim scene.
     *  2. Similarity refinement with the aligned-floor clip, seeded by the hint (the hint path
     *     skips the unclipped integer seed inside [AffineAligner.estimate]).
     *  3. Plausibility check. An implausible fit means the hinted basin was wrong (e.g. the
     *     coarse search latched onto an accidental texture match); rescue by re-fitting from an
     *     IDENTITY seed and keeping whichever of {identity-seeded similarity (if plausible),
     *     robust translation-only} has the lower robust cost — a translation can't smear static
     *     detail the way a spurious rotation/scale does, and the identity fit wins only when it
     *     genuinely registers more of the frame.
     */
    internal fun estimateWholeFrameAlignment(
        referenceSmall: PixelImage, movingSmall: PixelImage,
        factor: Float, searchRange: Int
    ): Transform2D {
        // (1) Robust integer pre-pass at the SAME coarsest level `estimate` optimizes at, so the
        // hint (scaled by 2^(levels−1)) divides back exactly onto that level's pixel grid.
        var refCoarse = referenceSmall
        var movCoarse = movingSmall
        var coarseFactor = 1f
        while (minOf(refCoarse.width, refCoarse.height) > AffineAligner.estimatePyramidMinSize) {
            refCoarse = ImagePyramid.reduce(refCoarse)
            movCoarse = ImagePyramid.reduce(movCoarse)
            coarseFactor *= 2f
        }
        val cw = refCoarse.width; val ch = refCoarse.height
        val refCoarseLuma = Luma.luminance(refCoarse)
        val movCoarseLuma = Luma.luminance(movCoarse)

        // (0a) Loose clip from the unaligned residual — it still contains the burst drift, so it
        // only serves to keep true outliers (a bright mover) from steering the integer search.
        val preClip = adaptiveAlignmentClip(refCoarseLuma, movCoarseLuma,
            width = cw, height = ch, dx = 0, dy = 0)
        val shift = Alignment.estimateTranslation(
            referenceLuma = refCoarseLuma, movingLuma = movCoarseLuma,
            width = cw, height = ch,
            searchRange = searchRange, robustClip = preClip
        )
        // (0b) The refinement clip: the residual floor AT the aligned shift (noise + sub-pixel
        // interpolation error only — the drift component is gone).
        val clip = adaptiveAlignmentClip(refCoarseLuma, movCoarseLuma,
            width = cw, height = ch, dx = shift.dx, dy = shift.dy)
        val hint = Pair(shift.dx.toFloat() * coarseFactor, shift.dy.toFloat() * coarseFactor)

        // (2) Robust similarity refinement from the hint's basin.
        val tHint = AffineAligner.estimate(
            reference = referenceSmall, moving = movingSmall,
            robustClip = clip, translationHint = hint
        )

        // (3) Whole-burst plausibility: trust the similarity only inside the handheld envelope.
        val longEdge = maxOf(referenceSmall.width, referenceSmall.height).toFloat()
        if (isPlausibleWholeFrameFit(tHint, longEdge)) {
            return Transform2D(a = tHint.a, b = tHint.b, c = tHint.c, d = tHint.d,
                tx = tHint.tx * factor, ty = tHint.ty * factor)
        }

        // Rescue: the hinted basin was wrong. Candidate A — similarity re-fit from identity
        // (hint (0,0) skips the integer seed, keeping the optimizer in the identity basin).
        // Candidate B — robust translation-only (cannot smear). Keep the lower robust cost.
        val fallback = Transform2D.similarity(scale = 1f, rotation = 0f, tx = hint.first, ty = hint.second)
        var best = fallback
        val tZero = AffineAligner.estimate(
            reference = referenceSmall, moving = movingSmall,
            robustClip = clip, translationHint = Pair(0f, 0f)
        )
        if (isPlausibleWholeFrameFit(tZero, longEdge)) {
            val refLuma = Luma.luminance(referenceSmall)
            val movLuma = Luma.luminance(movingSmall)
            val cZero = AffineAligner.ssdWarped(movLuma, refLuma,
                referenceSmall.width, referenceSmall.height, tZero, clip)
            val cFallback = AffineAligner.ssdWarped(movLuma, refLuma,
                referenceSmall.width, referenceSmall.height, fallback, clip)
            if (cZero < cFallback) best = tZero
        }
        return Transform2D(a = best.a, b = best.b, c = best.c, d = best.d,
            tx = best.tx * factor, ty = best.ty * factor)
    }

    /**
     * True when a similarity fit is inside the handheld whole-burst envelope
     * ([alignmentMaxScaleDelta] / [alignmentMaxRotationRadians] / [alignmentMaxTranslationFraction]).
     */
    internal fun isPlausibleWholeFrameFit(t: Transform2D, longEdge: Float): Boolean {
        val scale = sqrt(t.a * t.a + t.c * t.c)
        val rotation = atan2(t.c, t.a)
        val translation = sqrt(t.tx * t.tx + t.ty * t.ty)
        return abs(scale - 1f) <= alignmentMaxScaleDelta &&
            abs(rotation) <= alignmentMaxRotationRadians &&
            translation <= alignmentMaxTranslationFraction * longEdge
    }

    /**
     * Scene-adaptive robust clip for whole-frame registration (see [alignmentRobustClipFloor]
     * for the full rationale). Measures the residual floor of THIS frame pair — the median
     * absolute luma residual at integer offset (dx, dy), over the overlap region — scaled to a
     * Gaussian-consistent sigma (MAD × 1.4826), and saturates residuals beyond
     * [alignmentClipSigma] sigmas of it:
     *   clip = max((alignmentClipSigma · 1.4826 · median|Δluma|)², floor)
     * The median is robust to up to half the frame being covered by a bright mover.
     * Deterministic (exact median via sort). Offset convention matches
     * [Alignment.estimateTranslation]: ref[x, y] is compared with mov[x + dx, y + dy].
     *
     * Runs once per frame pair on the COARSE level only (not per-pixel-per-trial), so the one
     * flat scratch buffer + sort below is cheap; the fill is index-addressed FloatArray writes
     * (no per-element boxing for ART to chew on).
     */
    internal fun adaptiveAlignmentClip(
        refLuma: FloatArray, movLuma: FloatArray,
        width: Int, height: Int, dx: Int, dy: Int
    ): Float {
        val w = width; val h = height
        val yStart = max(0, -dy); val yEnd = min(h, h - dy)
        val xStart = max(0, -dx); val xEnd = min(w, w - dx)
        require(refLuma.size == w * h && movLuma.size == w * h) { "luma buffer size mismatch" }
        require(yStart < yEnd && xStart < xEnd) { "offset leaves no overlap" }
        val absDiff = FloatArray((yEnd - yStart) * (xEnd - xStart))
        var i = 0
        for (y in yStart until yEnd) {
            val refRow = y * w
            val movRow = (y + dy) * w + dx
            for (x in xStart until xEnd) {
                absDiff[i++] = abs(refLuma[refRow + x] - movLuma[movRow + x])
            }
        }
        absDiff.sort()
        val median = absDiff[absDiff.size / 2]
        val sigma = 1.4826f * median                      // MAD → Gaussian-consistent sigma
        val cap = alignmentClipSigma * sigma
        return max(cap * cap, alignmentRobustClipFloor)
    }

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
            val t = estimateWholeFrameAlignment(
                referenceSmall = refSmall, movingSmall = movSmall,
                factor = factor, searchRange = searchRange
            )
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
            val t = estimateWholeFrameAlignment(
                referenceSmall = refSmall, movingSmall = movSmall,
                factor = factor, searchRange = searchRange
            )
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
