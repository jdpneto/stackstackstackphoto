import simd

public enum StackReducer {
    /// Per-pixel, per-channel sigma-clipped mean across aligned frames.
    ///
    /// IMPORTANT: a single outlier's maximum z-score is bounded by sqrt(N-1) for N samples,
    /// so with the default `kappa = 2.0` an outlier is only rejectable when N >= 6. For
    /// smaller bursts (N <= 5) at kappa 2.0 this returns the plain mean — no clipping is
    /// mathematically possible. Use a smaller `kappa` (e.g. 1.5) to clip on small bursts.
    public static func sigmaClippedMean(_ imgs: [PixelImage],
                                        kappa: Float = 2.0,
                                        iterations: Int = 3) -> PixelImage {
        precondition(!imgs.isEmpty)
        let w = imgs[0].width, h = imgs[0].height
        precondition(imgs.allSatisfy { $0.width == w && $0.height == h }, "all images must be the same size")
        let n = imgs.count
        var out = PixelImage(width: w, height: h)
        // One reusable scratch buffer for the entire image, refilled per pixel/channel —
        // avoids a heap allocation in the innermost loop (tens of millions on a large frame).
        var kept = [Float](repeating: 0, count: n)
        for i in 0..<(w * h) {
            for ch in 0..<3 {
                for k in 0..<n { kept[k] = imgs[k].pixels[i][ch] }
                var count = n
                var iter = 0
                while iter < iterations && count > 2 {
                    var sum: Float = 0
                    for k in 0..<count { sum += kept[k] }
                    let mean = sum / Float(count)
                    var varSum: Float = 0
                    for k in 0..<count { let d = kept[k] - mean; varSum += d * d }
                    let sd = (varSum / Float(count)).squareRoot()
                    if sd == 0 { break }
                    let threshold = kappa * sd
                    // Compact survivors to the front of `kept` in place (no allocation).
                    var survivors = 0
                    for k in 0..<count where abs(kept[k] - mean) <= threshold {
                        kept[survivors] = kept[k]
                        survivors += 1
                    }
                    if survivors < 3 { break }        // too few survivors — keep the current set
                    if survivors == count { break }   // converged — nothing rejected
                    count = survivors
                    iter += 1
                }
                var sum: Float = 0
                for k in 0..<count { sum += kept[k] }
                out.pixels[i][ch] = sum / Float(count)
            }
        }
        return out
    }
}
