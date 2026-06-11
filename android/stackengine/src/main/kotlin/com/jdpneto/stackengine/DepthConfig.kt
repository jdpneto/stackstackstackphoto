package com.jdpneto.stackengine

/** Operating point for Depth-of-Field focus stacking (spec 2026-06-10 §4.4, tiered). */
data class DepthConfig(
    /** Max long-edge in pixels for the working resolution; `null` = full resolution (no downscale). */
    val workingResolution: Int?,
    /** Cap on the number of focus brackets actually stacked (memory/time bound). */
    val maxFrames: Int,
    /**
     * Chain-align the brackets to the sharpest reference before stacking (default ON — the
     * handheld promise). Adjacent-pair similarity links sidestep the sharp-vs-defocused SSD trap
     * that made direct-to-reference alignment smear detail; `false` remains for tripod use and the
     * device alignment-off comparison. (spec 2026-06-10 §2, §4.2)
     */
    val alignFrames: Boolean = true
) {
    init {
        require(maxFrames > 0) { "maxFrames must be > 0" }
        require(workingResolution == null || workingResolution >= 1) { "workingResolution must be >= 1 or null" }
    }

    companion object {
        /**
         * The managed operating point: ~1500 px long edge, ~10 brackets. DoF is the one mode that
         * holds ALL frames + their pyramids + weight masks simultaneously — ~700 MB peak at 1500 px
         * for 10 brackets vs ~1.8 GB at 2400 px, which flirts with the ~3 GB jetsam limit. There is
         * deliberately NO full-resolution preset (a 48 MP run hit that limit). (spec 2026-06-10 §3)
         */
        val auto = DepthConfig(workingResolution = 1500, maxFrames = 10)
    }
}
