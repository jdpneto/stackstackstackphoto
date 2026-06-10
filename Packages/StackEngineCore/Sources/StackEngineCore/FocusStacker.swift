import simd

/// End-to-end focus stacking: develop → downscale to the working resolution → chain-align the
/// brackets to the sharpest reference → per-pixel sharpness → selection weights → multiband blend
/// → all-in-focus image (design §13.2, spec 2026-06-10). Returns nil for an empty input.
///
/// Alignment is CHAIN alignment (`AffineAligner.alignChain`, default on): a direct similarity fit
/// of a sharp frame against a defocused one lets the optimizer "explain" blur differences with a
/// spurious warp that smears detail — the failure that originally shelved this look. Adjacent
/// brackets share nearly identical blur, so estimating each link between neighbours and composing
/// to the reference keeps every fit well-conditioned; implausible links degrade to translation-only.
public enum FocusStacker {
    /// All-in-focus composite from already-developed linear frames (all the same dimensions),
    /// in SWEEP ORDER (chain alignment depends on adjacency in focus).
    public static func allInFocus(_ images: [PixelImage], config: DepthConfig) -> PixelImage? {
        guard !images.isEmpty else { return nil }
        let frames = images.prefix(config.maxFrames).map { downscale($0, maxEdge: config.workingResolution) }
        guard frames.count >= 2 else { return frames.first }
        // All brackets must share dimensions for sharpness/selection/blend; reject (nil) rather than trap.
        guard frames.allSatisfy({ $0.width == frames[0].width && $0.height == frames[0].height }) else { return nil }

        let refIdx = ReferenceSelection.sharpestIndex(frames)
        let reference = frames[refIdx]
        let refLuma = Luma.luminance(reference)

        let aligned: [PixelImage]
        if config.alignFrames {
            let transforms = AffineAligner.alignChain(frames, referenceIndex: refIdx)
            aligned = zip(frames, transforms).map { f, t in t == .identity ? f : AffineAligner.warp(f, by: t) }
        } else {
            aligned = frames
        }

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
