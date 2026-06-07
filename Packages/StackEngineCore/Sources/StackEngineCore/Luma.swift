import simd

public enum Luma {
    /// Rec.709 luminance of a single linear-RGB pixel.
    @inline(__always) public static func rec709(_ p: SIMD3<Float>) -> Float {
        0.2126 * p.x + 0.7152 * p.y + 0.0722 * p.z
    }

    /// Rec.709 luminance of each pixel.
    public static func luminance(_ img: PixelImage) -> [Float] {
        img.pixels.map { rec709($0) }
    }

    /// Sharpness = sum of |Laplacian| over the luminance image (higher = sharper).
    public static func sharpness(_ img: PixelImage) -> Float {
        sharpness(of: luminance(img), width: img.width, height: img.height)
    }

    /// Sharpness over a precomputed luminance buffer, so callers can reuse the buffer.
    static func sharpness(of l: [Float], width w: Int, height h: Int) -> Float {
        @inline(__always) func at(_ x: Int, _ y: Int) -> Float {
            l[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        var s: Float = 0
        for y in 0..<h { for x in 0..<w {
            let lap = at(x-1, y) + at(x+1, y) + at(x, y-1) + at(x, y+1) - 4 * at(x, y)
            s += abs(lap)
        }}
        return s
    }
}
