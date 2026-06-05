import simd

/// Scale-aware (similarity) registration for focus-breathing frames: warp a frame by a
/// `Transform2D` about its centre, and estimate the transform that aligns a moving frame to a
/// reference by a deterministic intensity pattern search on the luma proxy (spec §3.2).
public enum AffineAligner {
    /// Warp `img` by `t` about the image centre, bilinear + edge-clamped:
    /// out[x,y] samples img at t.apply(x − cx, y − cy) + (cx, cy).
    public static func warp(_ img: PixelImage, by t: Transform2D) -> PixelImage {
        let w = img.width, h = img.height
        let cx = Float(w - 1) / 2, cy = Float(h - 1) / 2
        var out = PixelImage(width: w, height: h)
        for y in 0..<h {
            for x in 0..<w {
                let p = t.apply(Float(x) - cx, Float(y) - cy)
                out[x, y] = sampleRGB(img, p.x + cx, p.y + cy)
            }
        }
        return out
    }

    // MARK: - Private samplers (bilinear, edge-clamped)

    static func sampleRGB(_ img: PixelImage, _ fx: Float, _ fy: Float) -> SIMD3<Float> {
        let w = img.width, h = img.height
        let x0 = Int(floor(fx)), y0 = Int(floor(fy))
        let tx = fx - Float(x0), ty = fy - Float(y0)
        @inline(__always) func at(_ x: Int, _ y: Int) -> SIMD3<Float> {
            img.pixels[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        let top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        let bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (bot - top) * ty
    }

    static func sampleLuma(_ l: [Float], width w: Int, height h: Int, _ fx: Float, _ fy: Float) -> Float {
        let x0 = Int(floor(fx)), y0 = Int(floor(fy))
        let tx = fx - Float(x0), ty = fy - Float(y0)
        @inline(__always) func at(_ x: Int, _ y: Int) -> Float {
            l[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        let top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        let bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (bot - top) * ty
    }
}
