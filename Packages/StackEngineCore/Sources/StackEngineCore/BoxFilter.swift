/// Separable window mean (edge-clamped, normalized by the true in-image sample count at borders).
/// The smoothing primitive behind the guided filter.
enum BoxFilter {
    static func mean(_ src: [Float], width w: Int, height h: Int, radius r: Int) -> [Float] {
        var tmp = [Float](repeating: 0, count: w * h)   // horizontal pass
        for y in 0..<h {
            for x in 0..<w {
                var s: Float = 0; var n = 0
                for dx in -r...r { let xx = x + dx; if xx >= 0, xx < w { s += src[y * w + xx]; n += 1 } }
                tmp[y * w + x] = s / Float(n)
            }
        }
        var out = [Float](repeating: 0, count: w * h)   // vertical pass
        for y in 0..<h {
            for x in 0..<w {
                var s: Float = 0; var n = 0
                for dy in -r...r { let yy = y + dy; if yy >= 0, yy < h { s += tmp[yy * w + x]; n += 1 } }
                out[y * w + x] = s / Float(n)
            }
        }
        return out
    }
}
