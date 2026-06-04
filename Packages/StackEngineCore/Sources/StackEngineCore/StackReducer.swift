import simd

public enum StackReducer {
    /// Per-pixel, per-channel sigma-clipped mean across aligned frames.
    public static func sigmaClippedMean(_ imgs: [PixelImage],
                                        kappa: Float = 2.0,
                                        iterations: Int = 3) -> PixelImage {
        precondition(!imgs.isEmpty)
        let w = imgs[0].width, h = imgs[0].height
        let n = imgs.count
        var out = PixelImage(width: w, height: h)
        for i in 0..<(w * h) {
            for ch in 0..<3 {
                var kept = [Float](); kept.reserveCapacity(n)
                for im in imgs { kept.append(im.pixels[i][ch]) }
                let original = kept
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
                let survivors = kept.count >= 3 ? kept : original
                out.pixels[i][ch] = survivors.reduce(0, +) / Float(survivors.count)
            }
        }
        return out
    }
}
