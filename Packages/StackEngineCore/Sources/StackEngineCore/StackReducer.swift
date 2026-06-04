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
        for i in 0..<(w * h) {
            for ch in 0..<3 {
                var kept = [Float](); kept.reserveCapacity(n)
                for im in imgs { kept.append(im.pixels[i][ch]) }
                var iter = 0
                while iter < iterations && kept.count > 2 {
                    let mean = kept.reduce(0, +) / Float(kept.count)
                    let varc = kept.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Float(kept.count)
                    let sd = varc.squareRoot()
                    if sd == 0 { break }
                    let filtered = kept.filter { abs($0 - mean) <= kappa * sd }
                    if filtered.count < 3 { break }          // keep current set; too few survivors
                    if filtered.count == kept.count { break } // converged
                    kept = filtered
                    iter += 1
                }
                // `kept` is never emptied: the loop breaks before dropping below 3 survivors,
                // and for N <= 2 it never runs — so the mean of `kept` is always well-defined.
                out.pixels[i][ch] = kept.reduce(0, +) / Float(kept.count)
            }
        }
        return out
    }
}
