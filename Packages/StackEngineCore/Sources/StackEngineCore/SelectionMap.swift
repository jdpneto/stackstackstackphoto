/// Turns per-frame sharpness maps into per-frame blend weights for focus stacking (design §13.2):
/// each pixel favours its sharpest frame (winner-biased), the weights are guided-filter-regularized
/// against the reference luma for clean edge-aware boundaries, then renormalized to sum to 1.
public enum SelectionMap {
    public static func weights(sharpness: [[Float]], guide: [Float], width w: Int, height h: Int,
                               radius: Int = 4, eps: Float = 1e-4) -> [[Float]] {
        precondition(!sharpness.isEmpty, "need at least one frame")
        let m = sharpness.count, n = w * h

        // Raw soft weights: normalize across frames, biased to the winner by squaring the sharpness.
        var raw = Array(repeating: [Float](repeating: 0, count: n), count: m)
        for i in 0..<n {
            var sum: Float = 0
            for k in 0..<m { let s = sharpness[k][i]; let wk = s * s; raw[k][i] = wk; sum += wk }
            if sum > 0 { for k in 0..<m { raw[k][i] /= sum } }
            else { for k in 0..<m { raw[k][i] = 1 / Float(m) } }   // no detail anywhere → equal
        }

        // Regularize each mask against the guide, clamp ≥ 0, then renormalize so they sum to 1.
        var reg = raw.map { GuidedFilter.filter(input: $0, guide: guide, width: w, height: h,
                                                radius: radius, eps: eps) }
        for i in 0..<n {
            var sum: Float = 0
            for k in 0..<m {
                // Sanitize non-finite (a NaN/Inf from upstream) to 0 explicitly, rather than relying
                // on max() arg-order to absorb it; sum/renormalize then handles it as "no weight".
                reg[k][i] = reg[k][i].isFinite ? max(reg[k][i], 0) : 0
                sum += reg[k][i]
            }
            if sum > 0 { for k in 0..<m { reg[k][i] /= sum } }
            else { for k in 0..<m { reg[k][i] = 1 / Float(m) } }
        }
        return reg
    }
}
