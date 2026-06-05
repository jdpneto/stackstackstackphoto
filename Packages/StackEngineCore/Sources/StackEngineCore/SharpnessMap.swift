import simd

/// Per-pixel focus measure: summed modified-Laplacian energy over a (2·radius+1)² window of luma
/// (design §13.2). Higher = more in-focus. The basis for the focus-stacking selection map.
public enum SharpnessMap {
    public static func compute(_ img: PixelImage, radius: Int = 2) -> [Float] {
        compute(luma: Luma.luminance(img), width: img.width, height: img.height, radius: radius)
    }

    static func compute(luma l: [Float], width w: Int, height h: Int, radius: Int = 2) -> [Float] {
        @inline(__always) func at(_ x: Int, _ y: Int) -> Float {
            l[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        // Modified Laplacian per pixel: |2L − L(x−1) − L(x+1)| + |2L − L(y−1) − L(y+1)|.
        var ml = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                let lx = abs(2 * at(x, y) - at(x - 1, y) - at(x + 1, y))
                let ly = abs(2 * at(x, y) - at(x, y - 1) - at(x, y + 1))
                ml[y * w + x] = lx + ly
            }
        }
        // Sum over the window (edge-clamped).
        @inline(__always) func mlAt(_ x: Int, _ y: Int) -> Float {
            ml[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        var out = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                var s: Float = 0
                for dy in -radius...radius { for dx in -radius...radius { s += mlAt(x + dx, y + dy) } }
                out[y * w + x] = s
            }
        }
        return out
    }
}
