package com.jdpneto.stackengine

/**
 * End-to-end focus stacking: develop → downscale to the working resolution → chain-align the
 * brackets to the sharpest reference → per-pixel sharpness → selection weights → multiband blend
 * → all-in-focus image (design §13.2, spec 2026-06-10). Returns null for an empty input.
 *
 * Alignment is CHAIN alignment ([AffineAligner.alignChain], default on): a direct similarity fit
 * of a sharp frame against a defocused one lets the optimizer "explain" blur differences with a
 * spurious warp that smears detail — the failure that originally shelved this look. Adjacent
 * brackets share nearly identical blur, so estimating each link between neighbours and composing
 * to the reference keeps every fit well-conditioned; implausible links degrade to translation-only.
 */
object FocusStacker {

    /**
     * All-in-focus composite from already-developed linear frames (all the same dimensions),
     * in SWEEP ORDER (chain alignment depends on adjacency in focus).
     */
    fun allInFocus(images: List<PixelImage>, config: DepthConfig): PixelImage? {
        if (images.isEmpty()) return null
        val frames = images.take(config.maxFrames).map { downscale(it, maxEdge = config.workingResolution) }
        if (frames.size < 2) return frames.firstOrNull()
        // All brackets must share dimensions for sharpness/selection/blend; reject (null) rather than trap.
        if (!frames.all { it.width == frames[0].width && it.height == frames[0].height }) return null

        val refIdx = ReferenceSelection.sharpestIndex(frames)
        val reference = frames[refIdx]
        val refLuma = Luma.luminance(reference)

        val aligned: List<PixelImage>
        if (config.alignFrames) {
            val transforms = AffineAligner.alignChain(frames, referenceIndex = refIdx)
            aligned = frames.zip(transforms).map { (f, t) ->
                if (t == Transform2D.identity) f else AffineAligner.warp(f, by = t)
            }
        } else {
            aligned = frames
        }

        val sharp = aligned.map { SharpnessMap.compute(it) }
        val weights = SelectionMap.weights(sharpness = sharp, guide = refLuma,
                                           width = reference.width, height = reference.height)
        return LaplacianPyramidBlend.blend(images = aligned, weights = weights)
    }

    /**
     * All-in-focus composite from raw focus-bracketed frames (develops each first).
     * (`@JvmName` disambiguates the erased `List` overloads on the JVM.)
     */
    @JvmName("allInFocusRaw")
    fun allInFocus(rawFrames: List<RawSensorFrame>, config: DepthConfig): PixelImage? =
        allInFocus(rawFrames.map { ColorPipeline.process(it) }, config)

    /** Halve (Gaussian reduce) until the long edge is within [maxEdge] (null = no downscale). */
    private fun downscale(img: PixelImage, maxEdge: Int?): PixelImage {
        if (maxEdge == null) return img
        var out = img
        while (maxOf(out.width, out.height) > maxEdge) { out = ImagePyramid.reduce(out) }
        return out
    }
}
