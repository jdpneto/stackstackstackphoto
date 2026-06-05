import simd

public enum Pipeline {
    /// Align a burst to its sharpest frame (shared by every look). Luminance is computed once
    /// per frame and reused for reference selection AND per-frame alignment.
    static func alignedStack(_ imgs: [PixelImage], searchRange: Int) -> [PixelImage] {
        precondition(!imgs.isEmpty)
        if imgs.count == 1 { return imgs }
        let w = imgs[0].width, h = imgs[0].height
        precondition(imgs.allSatisfy { $0.width == w && $0.height == h }, "all images must be the same size")
        // Luma per frame and the per-frame alignment are independent → run them across cores.
        let lumas = parallelMap(imgs) { Luma.luminance($0) }
        let refIdx = ReferenceSelection.sharpestIndex(lumas: lumas, width: w, height: h)
        return parallelMap(Array(imgs.indices)) { i -> PixelImage in
            if i == refIdx { return imgs[i] }
            // Coarse-to-fine on a luma pyramid: O(image) instead of O(image × searchRange²).
            let t = Alignment.estimateTranslationCoarseToFine(referenceLuma: lumas[refIdx], movingLuma: lumas[i],
                                                              width: w, height: h, maxShift: searchRange)
            return Alignment.warp(imgs[i], by: t)
        }
    }

    /// Align then apply the look's reducer. `workingResolution` (long-edge px, nil = full) downscales
    /// the developed frames before align/stack — the dominant lever for on-device speed + memory,
    /// since alignment and stacking cost scale with pixel count.
    public static func reduceImages(_ imgs: [PixelImage], mode: StackMode, searchRange: Int = 8,
                                    workingResolution: Int? = nil) -> PixelImage {
        let aligned = alignedStack(downscale(imgs, maxEdge: workingResolution), searchRange: searchRange)
        switch mode {
        case .noiseReduction: return StackReducer.sigmaClippedMean(aligned) // kappa fixed at 2.0; use noiseReductionImages for an explicit kappa
        case .smoothMotion:   return StackReducer.mean(aligned)
        case .lightTrails:    return StackReducer.lighten(aligned)
        case .lowLightBoost:  return StackReducer.boostedMean(aligned, gain: StackReducer.defaultLowLightGain)
        }
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
        return parallelMap(imgs) { img in
            var out = img
            while max(out.width, out.height) > maxEdge { out = ImagePyramid.reduce(out) }
            return out
        }
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
