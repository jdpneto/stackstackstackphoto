package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Scale-aware (similarity) registration for focus-breathing frames: warp a frame by a
 * [Transform2D] about its centre, and estimate the transform that aligns a moving frame to a
 * reference by a deterministic intensity pattern search on the luma proxy (spec §3.2).
 */
object AffineAligner {

    /**
     * Min dimension of the COARSEST pyramid level [estimate] optimizes at. Shared with
     * `Pipeline.estimateWholeFrameAlignment`'s robust translation pre-pass: a hint measured at a
     * level built with the same reduce sequence divides back down (`1 shl (levels−1)`) onto the
     * coarsest optimizer level exactly, so the pre-pass and the optimizer agree on the basin.
     */
    internal const val estimatePyramidMinSize = 24

    /**
     * Warp [img] by [t] about the image centre, bilinear + edge-clamped:
     * out[x,y] samples img at t.apply(x − cx, y − cy) + (cx, cy).
     */
    fun warp(img: PixelImage, by: Transform2D): PixelImage {
        val w = img.width; val h = img.height
        val cx = (w - 1).toFloat() / 2f
        val cy = (h - 1).toFloat() / 2f
        val out = PixelImage(w, h)
        // Hot loop (full working resolution × every frame): inline the affine map and the
        // bilinear sample over the flat pixel array — no Pair/Vec3 temporaries ART would have
        // to scalar-replace (it can't; each would be a real allocation per pixel on device).
        // Same float ops in the same order as Transform2D.apply + the old Vec3 sampleRGB,
        // so the result is bit-identical (proven by the identity test).
        val a = by.a; val b = by.b; val c = by.c; val d = by.d
        val ttx = by.tx; val tty = by.ty
        val src = img.pixels
        val dst = out.pixels
        val wf = w.toFloat(); val hf = h.toFloat()
        var di = 0
        for (y in 0 until h) {
            val yr = y.toFloat() - cy
            for (x in 0 until w) {
                val xr = x.toFloat() - cx
                // Transform2D.apply inlined: (a·x + b·y + tx, c·x + d·y + ty), then +centre.
                val fxIn = a * xr + b * yr + ttx + cx
                val fyIn = c * xr + d * yr + tty + cy
                // Bilinear, edge-clamped (same clamp + lerp order as the old sampleRGB).
                val fx = (if (fxIn.isFinite()) fxIn else 0f).coerceIn(-1f, wf)
                val fy = (if (fyIn.isFinite()) fyIn else 0f).coerceIn(-1f, hf)
                val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
                val tx = fx - x0.toFloat(); val ty = fy - y0.toFloat()
                val x0c = x0.coerceIn(0, w - 1); val x1c = (x0 + 1).coerceIn(0, w - 1)
                val y0c = y0.coerceIn(0, h - 1); val y1c = (y0 + 1).coerceIn(0, h - 1)
                val i00 = (y0c * w + x0c) * 3; val i10 = (y0c * w + x1c) * 3
                val i01 = (y1c * w + x0c) * 3; val i11 = (y1c * w + x1c) * 3
                // top = p00 + (p10 − p00)·tx; bot = p01 + (p11 − p01)·tx; out = top + (bot − top)·ty
                val topR = src[i00]     + (src[i10]     - src[i00])     * tx
                val topG = src[i00 + 1] + (src[i10 + 1] - src[i00 + 1]) * tx
                val topB = src[i00 + 2] + (src[i10 + 2] - src[i00 + 2]) * tx
                val botR = src[i01]     + (src[i11]     - src[i01])     * tx
                val botG = src[i01 + 1] + (src[i11 + 1] - src[i01 + 1]) * tx
                val botB = src[i01 + 2] + (src[i11 + 2] - src[i01 + 2]) * tx
                dst[di]     = topR + (botR - topR) * ty
                dst[di + 1] = topG + (botG - topG) * ty
                dst[di + 2] = topB + (botB - topB) * ty
                di += 3
            }
        }
        return out
    }

    /**
     * Estimate the similarity transform that best aligns [moving] to [reference], minimising luma
     * SSD. COARSE-TO-FINE over a Gaussian luma pyramid: the coarsest level is smooth (no aliasing →
     * a global basin), then each finer level refines. scale/rotation are resolution-invariant;
     * translation doubles per finer level. Robust on real high-frequency frames.
     * [robustClip] (when set) caps each pixel's squared luma residual, so a region that moves
     * differently from the global motion — a person/video in a handheld scene — can't pull the
     * estimate off the static background it should lock onto. null = plain SSD.
     * [translationHint] seeds the coarsest-level optimizer (before the integer translation search),
     * measured in pixels at the finest (input) resolution; useful when the caller has a robust
     * prior on translation (e.g. from a robust-SSD pre-pass) that defeats a plain-SSD search.
     */
    fun estimate(
        reference: PixelImage, moving: PixelImage,
        translationSearch: Int = 8, robustClip: Float? = null,
        translationHint: Pair<Float, Float>? = null
    ): Transform2D {
        require(reference.width == moving.width && reference.height == moving.height)
        val refPyr = ImagePyramid.gaussian(reference, minSize = estimatePyramidMinSize)
        val movPyr = ImagePyramid.gaussian(moving, minSize = estimatePyramidMinSize)
        val levels = refPyr.size
        var s = 1f; var r = 0f
        var tx = (translationHint?.first ?: 0f) / (1 shl (levels - 1)).toFloat()
        var ty = (translationHint?.second ?: 0f) / (1 shl (levels - 1)).toFloat()
        for (lvl in (levels - 1) downTo 0) {   // coarsest → finest
            val rL = Luma.luminance(refPyr[lvl])
            val mL = Luma.luminance(movPyr[lvl])
            val lw = refPyr[lvl].width; val lh = refPyr[lvl].height
            // Only run the integer translation search when there is no caller-provided hint.
            val tInit = if (translationHint == null && lvl == levels - 1) translationSearch else 0
            val result = refine(rL, mL, lw, lh, s, r, tx, ty, tInit, robustClip)
            s = result.first; r = result.second; tx = result.third; ty = result.fourth
            if (lvl > 0) { tx *= 2f; ty *= 2f }   // propagate translation to the next finer level
        }
        return Transform2D.similarity(scale = s, rotation = r, tx = tx, ty = ty)
    }

    /**
     * One pyramid level of the deterministic Hooke–Jeeves search over scale / rotation / sub-pixel
     * translation, starting from (s,r,tx,ty). [translationInit] > 0 seeds translation by an integer
     * SSD search (only needed at the coarsest level). Scale is clamped to a sane range.
     */
    private fun refine(
        refL: FloatArray, movL: FloatArray, w: Int, h: Int,
        s0: Float, r0: Float, tx0: Float, ty0: Float,
        translationInit: Int, robustClip: Float?
    ): Quadruple<Float, Float, Float, Float> {
        var s = s0; var r = r0; var tx = tx0; var ty = ty0
        if (translationInit > 0) {
            val t0 = Alignment.estimateTranslation(
                referenceLuma = refL, movingLuma = movL,
                width = w, height = h, searchRange = translationInit
            )
            tx = t0.dx.toFloat(); ty = t0.dy.toFloat()
        }
        var best = ssdWarped(movL, refL, w, h, Transform2D.similarity(s, r, tx, ty), robustClip)
        var stepS = 0.05f; var stepR = 0.04f; var stepT = 1.0f
        val minScale = 0.5f; val maxScale = 2.0f
        var guardCount = 0
        while (stepT > 0.01f && guardCount < 1000) {
            guardCount++
            var improved = false
            data class Trial(val dS: Float, val dR: Float, val dTx: Float, val dTy: Float)
            val trials = listOf(
                Trial( stepS, 0f, 0f, 0f), Trial(-stepS, 0f, 0f, 0f),
                Trial(0f,  stepR, 0f, 0f), Trial(0f, -stepR, 0f, 0f),
                Trial(0f, 0f,  stepT, 0f), Trial(0f, 0f, -stepT, 0f),
                Trial(0f, 0f, 0f,  stepT), Trial(0f, 0f, 0f, -stepT),
            )
            for (t in trials) {
                val ns = s + t.dS
                if (ns < minScale || ns > maxScale) continue
                val c = ssdWarped(movL, refL, w, h, Transform2D.similarity(ns, r + t.dR, tx + t.dTx, ty + t.dTy), robustClip)
                if (c < best - 1e-9f) { best = c; s = ns; r += t.dR; tx += t.dTx; ty += t.dTy; improved = true }
            }
            if (!improved) { stepS *= 0.5f; stepR *= 0.5f; stepT *= 0.5f }
        }
        return Quadruple(s, r, tx, ty)
    }

    /** Estimate the registration of [moving] to [reference] and return [moving] warped into the reference frame. */
    fun align(reference: PixelImage, moving: PixelImage): PixelImage =
        warp(moving, by = estimate(reference = reference, moving = moving))

    /**
     * Mean SSD between [reference] luma and [moving] luma warped by [t] (centred, bilinear).
     *
     * THE hottest function in a burst (~57% of estimate, hundreds of millions of pixel visits
     * across the Hooke–Jeeves trials): the affine map is inlined with hoisted a/b/c/d/tx/ty
     * locals instead of `t.apply` — the generic Pair return boxes both Floats, which ART
     * (no real escape analysis) turns into 3 heap allocations per pixel. Same float ops in
     * the same order — bit-identical (identity test). `internal` for that test AND for
     * `Pipeline.estimateWholeFrameAlignment`'s rescue path, which uses it to compare candidate
     * registrations by the exact cost the optimizer minimizes.
     */
    internal fun ssdWarped(
        movL: FloatArray, refL: FloatArray, w: Int, h: Int,
        t: Transform2D, robustClip: Float?
    ): Float {
        val cx = (w - 1).toFloat() / 2f
        val cy = (h - 1).toFloat() / 2f
        val a = t.a; val b = t.b; val c = t.c; val d = t.d
        val ttx = t.tx; val tty = t.ty
        var sum = 0f
        for (y in 0 until h) {
            val yr = y.toFloat() - cy
            val rowBase = y * w
            for (x in 0 until w) {
                val xr = x.toFloat() - cx
                // Transform2D.apply inlined: (a·x + b·y + tx, c·x + d·y + ty), then +centre.
                val m = sampleLuma(movL, w, h, a * xr + b * yr + ttx + cx, c * xr + d * yr + tty + cy)
                val diff = m - refL[rowBase + x]
                val d2 = diff * diff
                sum += if (robustClip != null) minOf(d2, robustClip) else d2  // cap outliers (moving regions)
            }
        }
        return sum / (w * h).toFloat()
    }

    // MARK: - Chain alignment for focus sweeps (spec 2026-06-10 §4.2)

    /**
     * Chain-align a focus sweep: estimate a similarity link between each ADJACENT pair — whose
     * blur is nearly identical, so the SSD cost is valid there, unlike a sharp-vs-defocused
     * direct-to-reference fit (the documented spurious-warp failure) — validate each link against
     * [bounds], and compose links outward from [referenceIndex].
     *
     * Returns one transform per frame mapping reference coords → that frame's coords (identity at
     * the reference); `warp(frames[i], by: result[i])` aligns frame i. Frames MUST be in sweep
     * (focus) order — adjacency is what makes the links well-conditioned.
     */
    fun alignChain(
        frames: List<PixelImage>,
        referenceIndex: Int,
        bounds: ChainBounds = ChainBounds.default
    ): List<Transform2D> {
        require(referenceIndex in frames.indices) { "referenceIndex out of range" }
        require(frames.all { it.width == frames[0].width && it.height == frames[0].height }) {
            "all frames must be the same size"
        }
        val links = MutableList(frames.size) { Transform2D.identity }
        for (i in (referenceIndex + 1) until frames.size) {
            links[i] = boundedLink(reference = frames[i - 1], moving = frames[i], bounds = bounds)
        }
        for (i in (referenceIndex - 1) downTo 0) {
            links[i] = boundedLink(reference = frames[i + 1], moving = frames[i], bounds = bounds)
        }
        return accumulateLinks(links, referenceIndex = referenceIndex)
    }

    /**
     * Accumulate per-adjacent-pair links into per-frame warp-to-reference transforms, composing
     * outward from [referenceIndex]. `links[referenceIndex]` is ignored (the reference maps to
     * identity). This function is separated from link estimation so it can be unit-tested directly
     * with exact algebraically-constructed links, bypassing estimator noise.
     *
     * Composition direction: `transforms[i] = links[i].composed(with: transforms[i-1])` for the
     * up-sweep, so `transforms[i].apply(p)` = `links[i].apply(transforms[i-1].apply(p))` — each
     * link is applied in sequence from the reference outward (reference side applied last).
     */
    internal fun accumulateLinks(links: List<Transform2D>, referenceIndex: Int): List<Transform2D> {
        val transforms = MutableList(links.size) { Transform2D.identity }
        // Up the sweep: link maps frame[i-1] coords → frame[i] coords.
        for (i in (referenceIndex + 1) until links.size) {
            transforms[i] = links[i].composed(with = transforms[i - 1])
        }
        // Down the sweep: link maps frame[i+1] coords → frame[i] coords.
        for (i in (referenceIndex - 1) downTo 0) {
            transforms[i] = links[i].composed(with = transforms[i + 1])
        }
        return transforms
    }

    /**
     * One chain link: estimate the moving→reference similarity on a reduced copy (cheap; matches
     * the Pipeline's estimate-small/scale-translation-up pattern), then accept it only if it is
     * physically plausible for one focus step. An implausible fit is a blur difference posing as
     * warp — re-estimate translation-only, which cannot smear detail.
     */
    private fun boundedLink(
        reference: PixelImage, moving: PixelImage, bounds: ChainBounds
    ): Transform2D {
        val (refSmall, factor) = reduceForEstimate(reference)
        val (movSmall, _) = reduceForEstimate(moving)
        // Pre-compute a robust translation so the Hooke–Jeeves optimizer starts at the right basin.
        // Plain-SSD translation search can be pulled toward focal-band alignment (band k+1 aligning
        // with band k) rather than handheld-drift correction; robust SSD suppresses that.
        val robustShift = Alignment.estimateTranslation(
            reference = refSmall, moving = movSmall,
            searchRange = 8, robustClip = bounds.robustClip
        )
        val hint = Pair(robustShift.dx.toFloat(), robustShift.dy.toFloat())
        // Use robust clip + the robust translation hint so the optimizer starts in the right basin.
        val t = estimate(
            reference = refSmall, moving = movSmall,
            robustClip = bounds.robustClip, translationHint = hint
        )
        val scale = sqrt(t.a * t.a + t.c * t.c)
        val rotation = atan2(t.c, t.a)
        val translation = sqrt(t.tx * t.tx + t.ty * t.ty)
        val longEdge = maxOf(refSmall.width, refSmall.height).toFloat()
        if (abs(scale - 1f) <= bounds.maxScaleDelta &&
            abs(rotation) <= bounds.maxRotationRadians &&
            translation <= bounds.maxTranslationFraction * longEdge) {
            return Transform2D(a = t.a, b = t.b, c = t.c, d = t.d, tx = t.tx * factor, ty = t.ty * factor)
        }
        // Scale or rotation is implausible — fall back to robust translation-only (no smearing).
        return Transform2D.similarity(scale = 1f, rotation = 0f,
            tx = robustShift.dx.toFloat() * factor, ty = robustShift.dy.toFloat() * factor)
    }

    /**
     * Halve until the long edge is within [maxEdge]; returns the reduced image and the factor to
     * scale a reduced-space translation back to input pixels (powers of 2 — exact).
     */
    private fun reduceForEstimate(img: PixelImage, maxEdge: Int = 512): Pair<PixelImage, Float> {
        var out = img
        var factor = 1f
        while (maxOf(out.width, out.height) > maxEdge) {
            out = ImagePyramid.reduce(out)
            factor *= 2f
        }
        return Pair(out, factor)
    }

    // MARK: - Private samplers (bilinear, edge-clamped)
    // The RGB sampler lives INLINED in [warp] (flat-array, scalar channels) — a separate
    // Vec3-returning helper would put ~12 Vec3 temporaries per pixel back on the ART heap.

    private fun sampleLuma(l: FloatArray, w: Int, h: Int, fxIn: Float, fyIn: Float): Float {
        val fx = (if (fxIn.isFinite()) fxIn else 0f).coerceIn(-1f, w.toFloat())
        val fy = (if (fyIn.isFinite()) fyIn else 0f).coerceIn(-1f, h.toFloat())
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0.toFloat(); val ty = fy - y0.toFloat()
        fun at(x: Int, y: Int): Float = l[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        val bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (bot - top) * ty
    }
}

/** Tiny helper since Kotlin has no Quadruple in stdlib. */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * Per-link plausibility bounds for [AffineAligner.alignChain]. Focus breathing between ADJACENT
 * brackets is a small, monotonic magnification change, and the steadiness gate bounds handheld
 * per-step motion — so a link estimate outside these is a spurious fit (a blur difference being
 * "explained" by warp) and must not be trusted with scale/rotation. (spec 2026-06-10 §4.2)
 *
 * All fields are `val` (immutable); use `copy(field = newValue)` to derive a variant.
 */
data class ChainBounds(
    /** Max |scale − 1| per step. */
    val maxScaleDelta: Float = 0.02f,
    /** Max |rotation| per step (radians). */
    val maxRotationRadians: Float = (Math.PI / 180).toFloat(),
    /** Max translation magnitude per step, as a fraction of the long edge. */
    val maxTranslationFraction: Float = 0.015f,
    /**
     * Per-pixel squared-residual cap passed to [AffineAligner.estimate] and the robust
     * translation pre-pass, so focus-blur mismatches between adjacent brackets cannot pull the
     * optimizer off the common background signal.
     * Default (0.0001) tuned on the synthetic bracket fixture (band amplitude diff ≈ 0.20) and
     * field-proven on real device brackets (DepthBracketRegressionTests, 2026-06-10). The
     * whole-frame burst paths use a SCENE-ADAPTIVE clip bounded by the same value
     * (`Pipeline.adaptiveAlignmentClip`, dim-scene investigation 2026-06-12) — kept as SEPARATE
     * constants on purpose so retuning one path can never silently change the other.
     * Validate on-device (Task 12); if high-ISO links mis-seed, relax toward 0.001.
     * null = plain SSD (no clipping).
     */
    val robustClip: Float? = 0.0001f
) {
    companion object {
        val default = ChainBounds()
    }
}
