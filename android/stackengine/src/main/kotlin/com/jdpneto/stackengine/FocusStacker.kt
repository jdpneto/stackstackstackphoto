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
     * Peak resident set of [allInFocus], in working-resolution frame-equivalents, for N brackets.
     * At the multiband-blend peak it holds (a luma/weight plane = 1/3 of an RGB frame; a full
     * pyramid totals ≈ 4/3 of its base level):
     *
     *   input frames N + warped aligned copies N        = 2N
     *   + sharpness maps N/3 + selection weights N/3    = 2N/3
     *   + 3-channel mask images N                       = N
     *   + image Laplacian pyramids 4N/3                 = 4N/3
     *   + mask Gaussian pyramids 4N/3                   = 4N/3
     *   ≈ 19N/3, plus ~3 frames of slack for the reference luma, collapse, and transients.
     *
     * Owned by the engine because it encodes THIS file's blend-peak internals — callers (the
     * app's heap-aware working-resolution budget) must not hard-code these coefficients.
     */
    fun peakFrameEquivalents(frameCount: Int): Double = 19.0 * frameCount / 3.0 + 3.0

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
