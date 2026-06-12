import simd

public enum Pipeline {
    /// Align a burst to its sharpest frame (shared by every look). Returns the aligned frames AND
    /// the index of the reference (sharpest) frame so callers can retrieve it without recomputing.
    /// Luminance is computed once per frame and reused for reference selection AND per-frame alignment.
    static func alignedStackWithRefIdx(_ imgs: [PixelImage], searchRange: Int) -> (aligned: [PixelImage], refIdx: Int) {
        precondition(!imgs.isEmpty)
        if imgs.count == 1 { return (imgs, 0) }
        let w = imgs[0].width, h = imgs[0].height
        precondition(imgs.allSatisfy { $0.width == w && $0.height == h }, "all images must be the same size")
        // Luma per frame is reused for reference selection. Alignment is per-frame independent → cores.
        let lumas = parallelMap(imgs) { Luma.luminance($0) }
        let refIdx = ReferenceSelection.sharpestIndex(lumas: lumas, width: w, height: h)
        // Hand shake ROTATES (not just shifts) the frame; a translation-only fit leaves static detail
        // (text) smeared after combining. Estimate a robust SIMILARITY transform (rotation + scale +
        // translation) — cheaply, on a downscaled copy (rotation/scale are resolution-invariant, the
        // translation just scales) — then warp the full-res frame by it. `robustClip` caps per-pixel
        // residuals so the fit locks onto the static background and ignores genuinely-moving regions.
        let refSmall = downscaleOne(imgs[refIdx], maxEdge: alignmentEstimateEdge)
        let factor = Float(w) / Float(refSmall.width)
        let aligned = parallelMap(Array(imgs.indices)) { i -> PixelImage in
            if i == refIdx { return imgs[i] }
            let movSmall = downscaleOne(imgs[i], maxEdge: alignmentEstimateEdge)
            let t = estimateWholeFrameAlignment(referenceSmall: refSmall, movingSmall: movSmall,
                                                factor: factor, searchRange: searchRange)
            return AffineAligner.warp(imgs[i], by: t)
        }
        return (aligned, refIdx)
    }

    /// Align a burst to its sharpest frame. Wrapper over `alignedStackWithRefIdx` for callers that
    /// don't need the reference index.
    static func alignedStack(_ imgs: [PixelImage], searchRange: Int) -> [PixelImage] {
        alignedStackWithRefIdx(imgs, searchRange: searchRange).aligned
    }

    /// Long-edge (px) the similarity transform is estimated at. Rotation/scale are resolution-
    /// invariant and the rotation-step floor gives ~0.02° precision regardless, so estimating small
    /// is accurate and far cheaper than fitting at full working resolution.
    static let alignmentEstimateEdge = 720
    /// Bounds for the ADAPTIVE per-pair robust clip used during WHOLE-FRAME registration. The clip
    /// caps each pixel's squared luma residual (luma ∈ [0,1]): residuals above it are treated as
    /// "moves differently from the global motion" and contribute a fixed (saturated) cost.
    ///
    /// WHY ADAPTIVE (investigation 2026-06-12, dim-room-with-TV ghosting on device): the clip is
    /// an ABSOLUTE cap, and no fixed value fits every scene.
    ///  • Too LOOSE in a dim scene: with the old fixed 0.02 (≈ |Δluma| 0.14, calibrated bright),
    ///    a dim static background (luma 0.01–0.1) produces residuals far below the cap — almost
    ///    no cost — while a bright mover (a TV at luma 0.2–0.97) dominates: its scene cuts
    ///    saturate at the cap (still 20–200× the background signal) and its smooth pans stay
    ///    UNDER the cap and are fully trusted, so the optimizer fits the TV's content motion as
    ///    camera motion (43 px misregistration at 1.5 px true drift on device; clip sweep on the
    ///    synthetic repro: 0.02 → 184 px mean error, 1e-4 → 1.2 px).
    ///  • Too TIGHT in a bright scene: a fixed 1e-4 (≈ |Δluma| 0.01) sits BELOW the residual
    ///    floor of bright noisy frames (sensor noise alone exceeds it) and below the sub-pixel
    ///    interpolation residual of any high-contrast texture — every pixel saturates, the cost
    ///    surface goes flat, and both the integer pre-pass and the similarity refinement lose
    ///    their signal (measured 2026-06-12: HandheldAlignmentTests' ±1° shake stopped being
    ///    corrected and PipelineTests' noisy stack stopped converging at a fixed 1e-4).
    /// So the clip is derived per frame pair from the data in TWO stages (see
    /// `estimateWholeFrameAlignment`): a loose clip from the UNALIGNED coarse residual seeds the
    /// robust integer pre-pass, then the clip is re-measured AT the pre-pass shift — the aligned
    /// residual floor — for the similarity refinement. The second stage matters: measuring at
    /// zero shift inflates the median by the burst's own integer drift (texture decorrelation),
    /// which is exactly the signal being estimated, and the resulting over-loose clip let
    /// edge-clamp outlier pixels keep full quadratic influence and drag the optimizer into
    /// micro-rotations (measured 2026-06-12 on PipelineTests' noisy stack: r ≈ 0.003–0.006 rad
    /// spurious vs ≤ 0.001 with the floor measured at the aligned shift).
    /// Only a FLOOR of 1e-4 (the dim-scene-validated value) is applied — deliberately NO ceiling:
    /// capping the clip below the scene's own residual floor recreates the flat-cost failure
    /// (verified 2026-06-12 — a 0.02 ceiling re-broke the ±1° shake test by saturating the
    /// blurred coarse checkerboard's sub-pixel residuals).
    ///
    /// NOTE: the depth-of-field chain deliberately has its OWN fixed clip (`ChainBounds.robustClip`
    /// = 1e-4, field-proven with `ChainBounds.default` on real brackets) — kept separate so
    /// retuning one path can never silently change the other.
    static let alignmentRobustClipFloor: Float = 1e-4
    /// The clip saturates residuals beyond `alignmentClipSigma` robust standard deviations of the
    /// measured coarse residual floor: inliers (noise + sub-pixel interpolation error) stay
    /// un-saturated and keep the cost surface informative, while genuinely-different content
    /// (a bright mover, a scene cut) saturates. 3σ ⇒ ~0.3% false saturation if residuals were
    /// Gaussian — i.e. essentially only true outliers are capped.
    static let alignmentClipSigma: Float = 3

    /// Plausibility bounds for the whole-frame similarity estimate (ChainBounds-style, but sized
    /// for a WHOLE BURST relative to its anchor rather than one focus step). Handheld burst motion
    /// is bounded by the steadiness gate (pitch/roll ≲ 2.9°) and the ~2 s burst duration (yaw
    /// drift ≈ 6% of the long edge at working resolution, investigation 2026-06-12); a similarity
    /// fit outside these bounds is the optimizer explaining scene content motion (the bright-TV
    /// failure) as camera motion, and must not be trusted with scale/rotation.
    static let alignmentMaxScaleDelta: Float = 0.05
    static let alignmentMaxRotationRadians: Float = 0.08        // ~4.6°
    static let alignmentMaxTranslationFraction: Float = 0.25    // of the estimate-image long edge

    /// Whole-frame anchor registration shared by `alignedStackWithRefIdx` (batch) and both
    /// streaming paths. `referenceSmall`/`movingSmall` are the estimate-resolution copies (long
    /// edge ≤ `alignmentEstimateEdge`); `factor` scales estimate-space translation back to
    /// working-resolution pixels.
    ///
    /// Recipe (investigation 2026-06-12 — dim-scene/bright-mover ghosting; mirrors the depth
    /// chain's field-proven `boundedLink`):
    ///  0. ADAPTIVE robust clip in two stages (see `alignmentRobustClipFloor`): loose from the
    ///     unaligned coarse residual, then re-measured at the pre-pass shift (the aligned floor).
    ///  1. ROBUST translation pre-pass at the coarsest pyramid level — the plain-SSD integer seed
    ///     is what let a bright mover place the optimizer in the wrong basin in a dim scene.
    ///  2. Similarity refinement with the aligned-floor clip, seeded by the hint (the hint path
    ///     skips the unclipped integer seed inside `AffineAligner.estimate`).
    ///  3. Plausibility check. An implausible fit means the hinted basin was wrong (e.g. the
    ///     coarse search latched onto an accidental texture match); rescue by re-fitting from an
    ///     IDENTITY seed and keeping whichever of {identity-seeded similarity (if plausible),
    ///     robust translation-only} has the lower robust cost — a translation can't smear static
    ///     detail the way a spurious rotation/scale does, and the identity fit wins only when it
    ///     genuinely registers more of the frame.
    static func estimateWholeFrameAlignment(referenceSmall refSmall: PixelImage,
                                            movingSmall movSmall: PixelImage,
                                            factor: Float, searchRange: Int) -> Transform2D {
        // (1) Robust integer pre-pass at the SAME coarsest level `estimate` optimizes at, so the
        // hint (scaled by 2^(levels−1)) divides back exactly onto that level's pixel grid.
        var refCoarse = refSmall, movCoarse = movSmall
        var coarseFactor: Float = 1
        while min(refCoarse.width, refCoarse.height) > AffineAligner.estimatePyramidMinSize {
            refCoarse = ImagePyramid.reduce(refCoarse)
            movCoarse = ImagePyramid.reduce(movCoarse)
            coarseFactor *= 2
        }
        let cw = refCoarse.width, ch = refCoarse.height
        let refCoarseLuma = Luma.luminance(refCoarse)
        let movCoarseLuma = Luma.luminance(movCoarse)

        // (0a) Loose clip from the unaligned residual — it still contains the burst drift, so it
        // only serves to keep true outliers (a bright mover) from steering the integer search.
        let preClip = adaptiveAlignmentClip(refCoarseLuma, movCoarseLuma,
                                            width: cw, height: ch, dx: 0, dy: 0)
        let shift = Alignment.estimateTranslation(referenceLuma: refCoarseLuma, movingLuma: movCoarseLuma,
                                                  width: cw, height: ch,
                                                  searchRange: searchRange, robustClip: preClip)
        // (0b) The refinement clip: the residual floor AT the aligned shift (noise + sub-pixel
        // interpolation error only — the drift component is gone).
        let clip = adaptiveAlignmentClip(refCoarseLuma, movCoarseLuma,
                                         width: cw, height: ch, dx: shift.dx, dy: shift.dy)
        let hint = SIMD2<Float>(Float(shift.dx) * coarseFactor, Float(shift.dy) * coarseFactor)

        // (2) Robust similarity refinement from the hint's basin.
        let tHint = AffineAligner.estimate(reference: refSmall, moving: movSmall,
                                           robustClip: clip, translationHint: hint)

        // (3) Whole-burst plausibility: trust the similarity only inside the handheld envelope.
        let longEdge = Float(max(refSmall.width, refSmall.height))
        if isPlausibleWholeFrameFit(tHint, longEdge: longEdge) {
            return Transform2D(a: tHint.a, b: tHint.b, c: tHint.c, d: tHint.d,
                               tx: tHint.tx * factor, ty: tHint.ty * factor)
        }

        // Rescue: the hinted basin was wrong. Candidate A — similarity re-fit from identity
        // (hint (0,0) skips the integer seed, keeping the optimizer in the identity basin).
        // Candidate B — robust translation-only (cannot smear). Keep the lower robust cost.
        let fallback = Transform2D.similarity(scale: 1, rotation: 0, tx: hint.x, ty: hint.y)
        var best = fallback
        let tZero = AffineAligner.estimate(reference: refSmall, moving: movSmall,
                                           robustClip: clip, translationHint: SIMD2<Float>(0, 0))
        if isPlausibleWholeFrameFit(tZero, longEdge: longEdge) {
            let refLuma = Luma.luminance(refSmall)
            let movLuma = Luma.luminance(movSmall)
            let cZero = AffineAligner.ssdWarped(movLuma, refLuma,
                                                width: refSmall.width, height: refSmall.height,
                                                by: tZero, robustClip: clip)
            let cFallback = AffineAligner.ssdWarped(movLuma, refLuma,
                                                    width: refSmall.width, height: refSmall.height,
                                                    by: fallback, robustClip: clip)
            if cZero < cFallback { best = tZero }
        }
        return Transform2D(a: best.a, b: best.b, c: best.c, d: best.d,
                           tx: best.tx * factor, ty: best.ty * factor)
    }

    /// True when a similarity fit is inside the handheld whole-burst envelope
    /// (`alignmentMaxScaleDelta` / `alignmentMaxRotationRadians` / `alignmentMaxTranslationFraction`).
    static func isPlausibleWholeFrameFit(_ t: Transform2D, longEdge: Float) -> Bool {
        let scale = (t.a * t.a + t.c * t.c).squareRoot()
        let rotation = atan2(t.c, t.a)
        let translation = (t.tx * t.tx + t.ty * t.ty).squareRoot()
        return abs(scale - 1) <= alignmentMaxScaleDelta
            && abs(rotation) <= alignmentMaxRotationRadians
            && translation <= alignmentMaxTranslationFraction * longEdge
    }

    /// Scene-adaptive robust clip for whole-frame registration (see `alignmentRobustClipFloor`
    /// for the full rationale). Measures the residual floor of THIS frame pair — the median
    /// absolute luma residual at integer offset (dx, dy), over the overlap region — scaled to a
    /// Gaussian-consistent sigma (MAD × 1.4826), and saturates residuals beyond
    /// `alignmentClipSigma` sigmas of it:
    ///   clip = max((alignmentClipSigma · 1.4826 · median|Δluma|)², floor)
    /// The median is robust to up to half the frame being covered by a bright mover.
    /// Deterministic (exact median via sort). Offset convention matches
    /// `Alignment.estimateTranslation`: ref[x, y] is compared with mov[x + dx, y + dy].
    static func adaptiveAlignmentClip(_ refLuma: [Float], _ movLuma: [Float],
                                      width w: Int, height h: Int, dx: Int, dy: Int) -> Float {
        let yStart = max(0, -dy), yEnd = min(h, h - dy)
        let xStart = max(0, -dx), xEnd = min(w, w - dx)
        precondition(refLuma.count == w * h && movLuma.count == w * h, "luma buffer size mismatch")
        precondition(yStart < yEnd && xStart < xEnd, "offset leaves no overlap")
        var absDiff = [Float]()
        absDiff.reserveCapacity((yEnd - yStart) * (xEnd - xStart))
        for y in yStart..<yEnd {
            for x in xStart..<xEnd {
                absDiff.append(abs(refLuma[y * w + x] - movLuma[(y + dy) * w + (x + dx)]))
            }
        }
        absDiff.sort()
        let median = absDiff[absDiff.count / 2]
        let sigma = 1.4826 * median                       // MAD → Gaussian-consistent sigma
        let cap = alignmentClipSigma * sigma
        return max(cap * cap, alignmentRobustClipFloor)
    }

    /// Align then apply the look's reducer, ALSO returning the aligned reference (sharpest) frame —
    /// the second endpoint of the editor's blend-strength lerp. (spec 2026-06-11 §3)
    public static func reduceImagesWithReference(_ imgs: [PixelImage], mode: StackMode, searchRange: Int = 8,
                                                 workingResolution: Int? = nil) -> (result: PixelImage, reference: PixelImage) {
        let (aligned, refIdx) = alignedStackWithRefIdx(downscale(imgs, maxEdge: workingResolution), searchRange: searchRange)
        let result = reduceAligned(aligned, mode: mode)
        // The blend endpoints must share the look's exposure — α trades noise, not brightness.
        // For lowLightBoost the result is a gain-boosted mean; scale the reference by the same gain
        // so α=0 (reference) and α=1 (result) sit at the same perceptual brightness.
        let rawRef = aligned[refIdx]
        let reference: PixelImage
        if mode == .lowLightBoost {
            let gain = StackReducer.defaultLowLightGain
            reference = PixelImage(width: rawRef.width, height: rawRef.height,
                                   pixels: rawRef.pixels.map { $0 * gain })
        } else {
            reference = rawRef
        }
        return (result, reference)
    }

    /// Align then apply the look's reducer. `workingResolution` (long-edge px, nil = full) downscales
    /// the developed frames before align/stack — the dominant lever for on-device speed + memory,
    /// since alignment and stacking cost scale with pixel count.
    public static func reduceImages(_ imgs: [PixelImage], mode: StackMode, searchRange: Int = 8,
                                    workingResolution: Int? = nil) -> PixelImage {
        reduceImagesWithReference(imgs, mode: mode, searchRange: searchRange, workingResolution: workingResolution).result
    }

    /// Shared reducer dispatch over an already-aligned stack.
    private static func reduceAligned(_ aligned: [PixelImage], mode: StackMode) -> PixelImage {
        switch mode {
        case .noiseReduction: return StackReducer.sigmaClippedMean(aligned) // kappa fixed at 2.0; use noiseReductionImages for an explicit kappa
        case .smoothMotion:   return StackReducer.mean(aligned)              // aligned mean is sharp where static, blurred where moving
        case .lightTrails:
            // `lighten` (per-pixel max) makes light streaks where things move, but in STATIC regions
            // it amplifies noise and any residual-misalignment edge into a smeared double-image. So
            // composite: a clean aligned mean where nothing moved, the max only where it did.
            let base = StackReducer.mean(aligned)
            let streaks = StackReducer.lighten(aligned)
            let mask = MotionComposite.motionMask(aligned, lo: trailsMotionLo, hi: trailsMotionHi, smoothRadius: 2)
            return MotionComposite.blend(staticBase: base, effect: streaks, mask: mask)
        case .lowLightBoost:  return StackReducer.boostedMean(aligned, gain: StackReducer.defaultLowLightGain)
        case .depthOfField:
            preconditionFailure("Depth of Field is stacked by FocusStacker.allInFocus, not Pipeline.reduce — fix the caller's routing")
        }
    }

    /// Light-trails motion band (temporal luma range across aligned frames). Below `lo` a pixel is
    /// treated as static (noise only) and kept as the clean mean; above `hi` it's moving and gets the
    /// streak. Tuned for linear luma ∈ [0,1]; a Pro sensitivity control is a follow-up.
    static let trailsMotionLo: Float = 0.05
    static let trailsMotionHi: Float = 0.15

    /// Streaming/incremental reducer for the long-exposure looks: folds one frame at a time into
    /// per-pixel accumulators and releases it, so peak memory is bounded by ~1-2 frames + a fixed
    /// set of accumulator buffers regardless of frame count (vs. holding all developed + all aligned
    /// frames). `aligned(i)` returns frame i ALREADY aligned to the anchor (frame 0); it is called in
    /// order and the result folded immediately, so only one frame is live at a time. `shouldCancel`
    /// is checked between frames; a true return throws `CancellationError` (no result). Only
    /// smoothMotion / lightTrails are supported. (design 2026-06-07 §6)
    static func streamingReduce(count n: Int, mode: StackMode,
                                shouldCancel: () -> Bool = { false },
                                aligned: (Int) -> PixelImage) throws -> PixelImage {
        precondition(n >= 1, "need at least one frame")
        precondition(mode == .smoothMotion || mode == .lightTrails,
                     "streamingReduce supports the long-exposure looks only")
        let first = aligned(0)
        let w = first.width, h = first.height
        let wantTrails = (mode == .lightTrails)

        var sum = [SIMD3<Float>](repeating: .zero, count: w * h)        // running sum  → mean / base
        var maxRGB = wantTrails ? [SIMD3<Float>](repeating: SIMD3<Float>(repeating: -.greatestFiniteMagnitude), count: w * h) : []  // running max → lighten / streaks
        var lumaMin = wantTrails ? [Float](repeating: .greatestFiniteMagnitude, count: w * h) : []
        var lumaMax = wantTrails ? [Float](repeating: -.greatestFiniteMagnitude, count: w * h) : []
        var count = 0

        func fold(_ img: PixelImage) {
            precondition(img.width == w && img.height == h, "all frames must be the same size")
            for i in 0..<(w * h) {
                let p = img.pixels[i]
                sum[i] += p
                if wantTrails {
                    maxRGB[i] = simd_max(maxRGB[i], p)
                    let l = Luma.rec709(p)
                    lumaMin[i] = Swift.min(lumaMin[i], l)
                    lumaMax[i] = Swift.max(lumaMax[i], l)
                }
            }
            count += 1
        }

        fold(first)                                   // the anchor folds as itself
        for i in 1..<n {
            if shouldCancel() { throw CancellationError() }
            fold(aligned(i))
        }

        let inv = 1 / Float(count)
        var base = PixelImage(width: w, height: h)
        for i in 0..<(w * h) { base.pixels[i] = sum[i] * inv }          // mean
        if !wantTrails { return base }                                  // smoothMotion = mean

        // lightTrails: streaks (running max), motion mask from the temporal luma range, composite.
        let streaks = PixelImage(width: w, height: h, pixels: maxRGB)
        let invSpan = 1 / Swift.max(trailsMotionHi - trailsMotionLo, 1e-6)
        var mask = [Float](repeating: 0, count: w * h)
        for i in 0..<(w * h) {
            let c = Swift.min(Swift.max((lumaMax[i] - lumaMin[i] - trailsMotionLo) * invSpan, 0), 1)
            mask[i] = c * c * (3 - 2 * c)                               // smoothstep
        }
        mask = BoxFilter.mean(mask, width: w, height: h, radius: 2)     // same smoothRadius as motionMask
        return MotionComposite.blend(staticBase: base, effect: streaks, mask: mask)
    }

    /// Streaming reduce over already-developed frames (the non-RAW fallback): aligns each frame to
    /// the FIRST frame (anchor) and folds it immediately, so peak memory is the input array plus
    /// one warped frame — never a second full aligned array. Long-exposure looks only.
    /// (spec 2026-06-11 §3; mirrors reduceStreamingWithReference's anchor semantics)
    public static func reduceImagesStreamingWithReference(_ imgs: [PixelImage], mode: StackMode,
                                                          searchRange: Int = 8,
                                                          shouldCancel: () -> Bool = { false }) throws -> (result: PixelImage, reference: PixelImage) {
        precondition(!imgs.isEmpty, "need at least one frame")
        precondition(mode.isLongExposure, "images streaming supports long-exposure looks only")
        let reference = imgs[0]
        let refSmall = downscaleOne(reference, maxEdge: alignmentEstimateEdge)
        let factor = Float(reference.width) / Float(refSmall.width)
        let result = try streamingReduce(count: imgs.count, mode: mode, shouldCancel: shouldCancel) { i in
            if i == 0 { return reference }
            let movSmall = downscaleOne(imgs[i], maxEdge: alignmentEstimateEdge)
            let t = estimateWholeFrameAlignment(referenceSmall: refSmall, movingSmall: movSmall,
                                                factor: factor, searchRange: searchRange)
            return AffineAligner.warp(imgs[i], by: t)
        }
        return (result, reference)
    }

    /// End-to-end streaming stack for the long-exposure looks, ALSO returning the anchor (frame 0 at
    /// working resolution) — it is already held alive for alignment; returning it costs nothing.
    /// (spec 2026-06-11 §3)
    public static func reduceStreamingWithReference(_ frames: [RawSensorFrame], mode: StackMode,
                                                    searchRange: Int = 8, workingResolution: Int? = nil,
                                                    binnedDevelop: Bool = true,
                                                    shouldCancel: () -> Bool = { false }) throws -> (result: PixelImage, reference: PixelImage) {
        precondition(!frames.isEmpty, "need at least one frame")
        if shouldCancel() { throw CancellationError() }
        precondition(mode.isLongExposure, "reduceStreamingWithReference supports long-exposure looks only (.smoothMotion / .lightTrails)")
        func develop(_ i: Int) -> PixelImage {
            let d = binnedDevelop ? ColorPipeline.processBinned(frames[i]) : ColorPipeline.process(frames[i])
            guard let maxEdge = workingResolution else { return d }
            return downscaleOne(d, maxEdge: maxEdge)
        }
        let anchor = develop(0)                                      // anchor, kept for the whole run
        let refSmall = downscaleOne(anchor, maxEdge: alignmentEstimateEdge)
        let factor = Float(anchor.width) / Float(refSmall.width)
        let result = try streamingReduce(count: frames.count, mode: mode, shouldCancel: shouldCancel) { i in
            if i == 0 { return anchor }
            let moving = develop(i)
            let movSmall = downscaleOne(moving, maxEdge: alignmentEstimateEdge)
            let t = estimateWholeFrameAlignment(referenceSmall: refSmall, movingSmall: movSmall,
                                                factor: factor, searchRange: searchRange)
            return AffineAligner.warp(moving, by: t)
        }
        return (result, anchor)
    }

    /// End-to-end streaming stack for the long-exposure looks. Develops each raw frame on demand,
    /// downscales to `workingResolution`, aligns it to the FIRST frame (the anchor — streaming can't
    /// hold all frames to pick the sharpest, and the steadiness gate keeps the burst near one pose),
    /// and folds it via `streamingReduce`. Only one developed/aligned frame is live at a time.
    /// (design 2026-06-07 §6)
    public static func reduceStreaming(_ frames: [RawSensorFrame], mode: StackMode,
                                       searchRange: Int = 8, workingResolution: Int? = nil,
                                       binnedDevelop: Bool = true,
                                       shouldCancel: () -> Bool = { false }) throws -> PixelImage {
        try reduceStreamingWithReference(frames, mode: mode, searchRange: searchRange,
                                         workingResolution: workingResolution, binnedDevelop: binnedDevelop,
                                         shouldCancel: shouldCancel).result
    }

    /// Diagnostic: the developed + working-resolution frames that `reduceImages` feeds to alignment.
    /// Lets the app dump the exact alignment input for offline debugging of handheld registration.
    public static func developedFrames(_ frames: [RawSensorFrame], binnedDevelop: Bool,
                                       workingResolution: Int?) -> [PixelImage] {
        let developed = parallelMap(frames) { binnedDevelop ? ColorPipeline.processBinned($0) : ColorPipeline.process($0) }
        return downscale(developed, maxEdge: workingResolution)
    }

    /// End-to-end from raw frames: develop each → (downscale) → align → reduce for the chosen look.
    /// `binnedDevelop` uses the fast half-resolution 2×2-bin develop (managed/on-device path); the
    /// full bilinear demosaic is the dominant develop cost otherwise.
    public static func reduce(_ frames: [RawSensorFrame], mode: StackMode, searchRange: Int = 8,
                              workingResolution: Int? = nil, binnedDevelop: Bool = false) -> PixelImage {
        // Develop is the dominant cost (a full demosaic per frame) and each frame is independent.
        let developed = parallelMap(frames) { binnedDevelop ? ColorPipeline.processBinned($0) : ColorPipeline.process($0) }
        return reduceImages(developed, mode: mode, searchRange: searchRange, workingResolution: workingResolution)
    }

    /// Halve (Gaussian reduce) each frame until its long edge is within `maxEdge` (nil = no downscale).
    /// Frames start equal-size and reduce deterministically, so they stay equal-size.
    private static func downscale(_ imgs: [PixelImage], maxEdge: Int?) -> [PixelImage] {
        guard let maxEdge, maxEdge >= 1 else { return imgs }   // <1 would loop forever (reduce floors at 1)
        return parallelMap(imgs) { downscaleOne($0, maxEdge: maxEdge) }
    }

    /// Halve (Gaussian reduce) one image until its long edge is within `maxEdge` (≥ 1).
    private static func downscaleOne(_ img: PixelImage, maxEdge: Int) -> PixelImage {
        var out = img
        while max(out.width, out.height) > maxEdge { out = ImagePyramid.reduce(out) }
        return out
    }

    // MARK: - Noise-reduction-specific entry points (kept for the golden harness/tests)

    /// Noise reduction with an explicit kappa (used by the golden convergence test).
    public static func noiseReductionImages(_ imgs: [PixelImage],
                                            searchRange: Int = 8,
                                            kappa: Float = 2.0) -> PixelImage {
        StackReducer.sigmaClippedMean(alignedStack(imgs, searchRange: searchRange), kappa: kappa)
    }

    public static func noiseReduction(_ frames: [RawSensorFrame],
                                      searchRange: Int = 8,
                                      kappa: Float = 2.0) -> PixelImage {
        noiseReductionImages(frames.map { ColorPipeline.process($0) }, searchRange: searchRange, kappa: kappa)
    }

    public static func noiseReductionEncoded(_ frames: [RawSensorFrame]) -> (image: PixelImage, rgba8: [UInt8]) {
        let result = noiseReduction(frames)
        return (result, OutputTransform.encodeSRGB8(result))
    }
}
