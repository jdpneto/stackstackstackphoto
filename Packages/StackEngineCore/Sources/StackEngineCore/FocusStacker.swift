import simd

/// End-to-end focus stacking: develop → downscale to the working resolution → (optionally) align
/// each frame to the sharpest reference → per-pixel sharpness → selection weights → multiband blend
/// → all-in-focus image (design §13.2). Returns nil for an empty input.
///
/// Alignment is **opt-in** (`DepthConfig.alignFrames`, default off) and **translation-only**: focus
/// stacking is classically tripod-based, and a full-DOF SSD fit over focus brackets (whose content
/// changes with focus) can lodge in a spurious warp that smears detail. Robust handheld focus-bracket
/// alignment (a focus-invariant estimator) is a documented refinement before it is safe on real
/// brackets; translation-only keeps the current path from distorting frames.
public enum FocusStacker {
    /// All-in-focus composite from already-developed linear frames (all the same dimensions).
    public static func allInFocus(_ images: [PixelImage], config: DepthConfig) -> PixelImage? {
        guard !images.isEmpty else { return nil }
        let frames = images.prefix(config.maxFrames).map { downscale($0, maxEdge: config.workingResolution) }
        guard frames.count >= 2 else { return frames.first }

        let refIdx = ReferenceSelection.sharpestIndex(frames)
        let reference = frames[refIdx]
        let refLuma = Luma.luminance(reference)

        let aligned: [PixelImage] = config.alignFrames
            ? frames.enumerated().map { i, f in
                i == refIdx ? f : Alignment.warp(f, by: Alignment.estimateTranslation(
                    referenceLuma: refLuma, movingLuma: Luma.luminance(f),
                    width: reference.width, height: reference.height, searchRange: 16))
              }
            : frames

        let sharp = aligned.map { SharpnessMap.compute($0) }
        let weights = SelectionMap.weights(sharpness: sharp, guide: refLuma,
                                           width: reference.width, height: reference.height)
        return LaplacianPyramidBlend.blend(images: aligned, weights: weights)
    }

    /// All-in-focus composite from raw focus-bracketed frames (develops each first).
    public static func allInFocus(rawFrames: [RawSensorFrame], config: DepthConfig) -> PixelImage? {
        allInFocus(rawFrames.map { ColorPipeline.process($0) }, config: config)
    }

    /// Halve (Gaussian reduce) until the long edge is within `maxEdge` (nil = no downscale).
    private static func downscale(_ img: PixelImage, maxEdge: Int?) -> PixelImage {
        guard let maxEdge else { return img }
        var out = img
        while max(out.width, out.height) > maxEdge { out = ImagePyramid.reduce(out) }
        return out
    }
}
