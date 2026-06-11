package com.jdpneto.stackengine

import kotlin.math.max

/**
 * Turns per-frame sharpness maps into per-frame blend weights for focus stacking (design §13.2):
 * each pixel favours its sharpest frame (winner-biased), the weights are guided-filter-regularized
 * against the reference luma for clean edge-aware boundaries, then renormalized to sum to 1.
 */
object SelectionMap {

    fun weights(sharpness: List<FloatArray>, guide: FloatArray, width: Int, height: Int,
                radius: Int = 4, eps: Float = 1e-4f): List<FloatArray> {
        require(sharpness.isNotEmpty()) { "need at least one frame" }
        val w = width; val h = height
        val m = sharpness.size; val n = w * h

        // Raw soft weights: normalize across frames, biased to the winner by squaring the sharpness.
        val raw = List(m) { FloatArray(n) }
        for (i in 0 until n) {
            var sum = 0f
            for (k in 0 until m) { val s = sharpness[k][i]; val wk = s * s; raw[k][i] = wk; sum += wk }
            if (sum > 0f) { for (k in 0 until m) { raw[k][i] /= sum } }
            else { for (k in 0 until m) { raw[k][i] = 1f / m.toFloat() } }   // no detail anywhere → equal
        }

        // Regularize each mask against the guide, clamp ≥ 0, then renormalize so they sum to 1.
        val reg = raw.map { GuidedFilter.filter(input = it, guide = guide, width = w, height = h,
                                                radius = radius, eps = eps) }
        for (i in 0 until n) {
            var sum = 0f
            for (k in 0 until m) {
                // Sanitize non-finite (a NaN/Inf from upstream) to 0 explicitly, rather than relying
                // on max() arg-order to absorb it; sum/renormalize then handles it as "no weight".
                reg[k][i] = if (reg[k][i].isFinite()) max(reg[k][i], 0f) else 0f
                sum += reg[k][i]
            }
            if (sum > 0f) { for (k in 0 until m) { reg[k][i] /= sum } }
            else { for (k in 0 until m) { reg[k][i] = 1f / m.toFloat() } }
        }
        return reg
    }
}
