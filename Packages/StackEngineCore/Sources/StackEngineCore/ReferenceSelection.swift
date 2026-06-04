public enum ReferenceSelection {
    /// Index of the sharpest frame — the geometric anchor for alignment.
    public static func sharpestIndex(_ imgs: [PixelImage]) -> Int {
        precondition(!imgs.isEmpty)
        var best = 0
        var bestScore = -Float.infinity
        for (i, im) in imgs.enumerated() {
            let s = Luma.sharpness(im)
            if s > bestScore { bestScore = s; best = i }
        }
        return best
    }
}
