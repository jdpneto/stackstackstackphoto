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
            let ts = AffineAligner.estimate(reference: refSmall, moving: movSmall,
                                            translationSearch: searchRange, robustClip: alignmentRobustClip)
            let t = Transform2D(a: ts.a, b: ts.b, c: ts.c, d: ts.d, tx: ts.tx * factor, ty: ts.ty * factor)
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
    /// Caps each pixel's squared luma residual during estimation (luma ∈ [0,1]); |Δluma| ≳ 0.14 is
    /// treated as "moves differently from the global motion" and down-weighted.
    static let alignmentRobustClip: Float = 0.02

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
            let ts = AffineAligner.estimate(reference: refSmall, moving: movSmall,
                                            translationSearch: searchRange, robustClip: alignmentRobustClip)
            let t = Transform2D(a: ts.a, b: ts.b, c: ts.c, d: ts.d, tx: ts.tx * factor, ty: ts.ty * factor)
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
