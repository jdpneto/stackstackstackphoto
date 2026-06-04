public enum ReferenceSelection {
    /// Index of the sharpest frame — the geometric anchor for alignment.
    public static func sharpestIndex(_ imgs: [PixelImage]) -> Int {
        precondition(!imgs.isEmpty)
        let lumas = imgs.map { Luma.luminance($0) }
        return sharpestIndex(lumas: lumas, width: imgs[0].width, height: imgs[0].height)
    }

    /// Sharpest frame given precomputed luminance buffers (avoids recomputing luminance).
    static func sharpestIndex(lumas: [[Float]], width: Int, height: Int) -> Int {
        precondition(!lumas.isEmpty)
        var best = 0
        var bestScore = -Float.infinity
        for (i, l) in lumas.enumerated() {
            let s = Luma.sharpness(of: l, width: width, height: height)
            if s > bestScore { bestScore = s; best = i }
        }
        return best
    }
}
