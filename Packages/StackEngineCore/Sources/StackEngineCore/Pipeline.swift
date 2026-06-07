import simd

public enum Pipeline {
    /// Align a burst to its sharpest frame (shared by every look). Luminance is computed once
    /// per frame and reused for reference selection AND per-frame alignment.
    static func alignedStack(_ imgs: [PixelImage], searchRange: Int) -> [PixelImage] {
        precondition(!imgs.isEmpty)
        if imgs.count == 1 { return imgs }
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
        return parallelMap(Array(imgs.indices)) { i -> PixelImage in
            if i == refIdx { return imgs[i] }
            let movSmall = downscaleOne(imgs[i], maxEdge: alignmentEstimateEdge)
            let ts = AffineAligner.estimate(reference: refSmall, moving: movSmall,
                                            translationSearch: searchRange, robustClip: alignmentRobustClip)
            let t = Transform2D(a: ts.a, b: ts.b, c: ts.c, d: ts.d, tx: ts.tx * factor, ty: ts.ty * factor)
            return AffineAligner.warp(imgs[i], by: t)
        }
    }

    /// Long-edge (px) the similarity transform is estimated at. Rotation/scale are resolution-
    /// invariant and the rotation-step floor gives ~0.02° precision regardless, so estimating small
    /// is accurate and far cheaper than fitting at full working resolution.
    static let alignmentEstimateEdge = 720
    /// Caps each pixel's squared luma residual during estimation (luma ∈ [0,1]); |Δluma| ≳ 0.14 is
    /// treated as "moves differently from the global motion" and down-weighted.
    static let alignmentRobustClip: Float = 0.02

    /// Align then apply the look's reducer. `workingResolution` (long-edge px, nil = full) downscales
    /// the developed frames before align/stack — the dominant lever for on-device speed + memory,
    /// since alignment and stacking cost scale with pixel count.
    public static func reduceImages(_ imgs: [PixelImage], mode: StackMode, searchRange: Int = 8,
                                    workingResolution: Int? = nil) -> PixelImage {
        let aligned = alignedStack(downscale(imgs, maxEdge: workingResolution), searchRange: searchRange)
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
        }
    }

    /// Light-trails motion band (temporal luma range across aligned frames). Below `lo` a pixel is
    /// treated as static (noise only) and kept as the clean mean; above `hi` it's moving and gets the
    /// streak. Tuned for linear luma ∈ [0,1]; a Pro sensitivity control is a follow-up.
    static let trailsMotionLo: Float = 0.05
    static let trailsMotionHi: Float = 0.15

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
