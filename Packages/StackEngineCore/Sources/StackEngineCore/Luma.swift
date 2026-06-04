import simd

public enum Luma {
    /// Rec.709 luminance of each pixel.
    public static func luminance(_ img: PixelImage) -> [Float] {
        img.pixels.map { 0.2126 * $0.x + 0.7152 * $0.y + 0.0722 * $0.z }
    }

    /// Sharpness = sum of |Laplacian| over the luminance image (higher = sharper).
    public static func sharpness(_ img: PixelImage) -> Float {
        let l = luminance(img), w = img.width, h = img.height
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
