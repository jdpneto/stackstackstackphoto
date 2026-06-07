import simd

/// Lossless 90°-multiple rotation of a `PixelImage` — used to bake capture orientation upright and
/// for the editor's quarter-turn rotate. Deterministic (a pure index remap); platform-free.
public enum ImageGeometry {
    /// Rotate clockwise by `quarterTurns × 90°` (normalized mod 4; negatives wrap). 0 → a copy;
    /// 1 and 3 swap width/height.
    public static func rotated(_ img: PixelImage, quarterTurns: Int) -> PixelImage {
        let k = ((quarterTurns % 4) + 4) % 4
        if k == 0 { return img }
        let w = img.width, h = img.height
        let src = img.pixels
        if k == 2 {
            var out = PixelImage(width: w, height: h)
            for y in 0..<h {
                for x in 0..<w {
                    let dst = (h - 1 - y) * w + (w - 1 - x)
                    out.pixels[dst] = src[y * w + x]
                }
            }
            return out
        }
        var out = PixelImage(width: h, height: w)   // 90° (k==1) or 270° (k==3): dimensions swap
        for y in 0..<h {
            for x in 0..<w {
                let nx: Int, ny: Int
                if k == 1 { nx = h - 1 - y; ny = x }           // 90° clockwise
                else      { nx = y;         ny = w - 1 - x }   // 270° clockwise (90° counter-clockwise)
                out.pixels[ny * h + nx] = src[y * w + x]       // out.width == h
            }
        }
        return out
    }
}
