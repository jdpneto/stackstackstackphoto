import simd

public enum Pipeline {
    /// End-to-end noise reduction over already-developed linear images:
    /// pick sharpest reference → align each frame → sigma-clipped mean.
    public static func noiseReductionImages(_ imgs: [PixelImage],
                                            searchRange: Int = 8,
                                            kappa: Float = 2.0) -> PixelImage {
        precondition(!imgs.isEmpty)
        if imgs.count == 1 { return imgs[0] }
        let refIdx = ReferenceSelection.sharpestIndex(imgs)
        let ref = imgs[refIdx]
        var aligned = [PixelImage]()
        aligned.reserveCapacity(imgs.count)
        for (i, im) in imgs.enumerated() {
            if i == refIdx { aligned.append(im); continue }
            let t = Alignment.estimateTranslation(reference: ref, moving: im, searchRange: searchRange)
            aligned.append(Alignment.warp(im, by: t))
        }
        return StackReducer.sigmaClippedMean(aligned, kappa: kappa)
    }

    /// End-to-end from raw frames: develop each → noise reduction.
    public static func noiseReduction(_ frames: [RawSensorFrame],
                                      searchRange: Int = 8,
                                      kappa: Float = 2.0) -> PixelImage {
        let imgs = frames.map { ColorPipeline.process($0) }
        return noiseReductionImages(imgs, searchRange: searchRange, kappa: kappa)
    }

    /// Convenience for the app + golden harness: raw frames → encoded sRGB RGBA8.
    public static func noiseReductionEncoded(_ frames: [RawSensorFrame]) -> (image: PixelImage, rgba8: [UInt8]) {
        let result = noiseReduction(frames)
        return (result, OutputTransform.encodeSRGB8(result))
    }
}
