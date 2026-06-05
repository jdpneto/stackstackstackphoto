import simd

/// Motion-aware compositing: keep static regions tack-sharp from a clean base, and apply the look's
/// effect only where the scene actually moves. "What moved" is the per-pixel temporal luma range
/// across the ALIGNED frames — once hand shake is registered out, a static pixel barely varies (just
/// sensor noise) while a passing car / person / on-screen video varies a lot. This is what lets a
/// look like light-trails streak the motion while the static text behind it stays readable.
enum MotionComposite {
    /// Per-pixel motion weight in [0,1]: 0 = static, 1 = moving. `lo`/`hi` bound the luma-range band
    /// (below `lo` → static, above `hi` → moving) with a smoothstep, then a box blur removes hard
    /// seams between static and moving regions.
    static func motionMask(_ imgs: [PixelImage], lo: Float, hi: Float, smoothRadius: Int) -> [Float] {
        let w = imgs[0].width, h = imgs[0].height
        var mask = [Float](repeating: 0, count: w * h)
        let invSpan = 1 / max(hi - lo, 1e-6)
        for i in 0..<(w * h) {
            var mn: Float = .greatestFiniteMagnitude
            var mx: Float = -.greatestFiniteMagnitude
            for im in imgs {
                let p = im.pixels[i]
                let l = 0.2126 * p.x + 0.7152 * p.y + 0.0722 * p.z
                mn = min(mn, l)
                mx = max(mx, l)
            }
            let c = min(max((mx - mn - lo) * invSpan, 0), 1)
            mask[i] = c * c * (3 - 2 * c)   // smoothstep
        }
        return smoothRadius > 0 ? BoxFilter.mean(mask, width: w, height: h, radius: smoothRadius) : mask
    }

    /// Per-pixel lerp: `base` where the mask is ~0 (static), `effect` where it's ~1 (moving).
    static func blend(staticBase base: PixelImage, effect: PixelImage, mask: [Float]) -> PixelImage {
        precondition(base.width == effect.width && base.height == effect.height)
        precondition(mask.count == base.width * base.height)
        var out = PixelImage(width: base.width, height: base.height)
        for i in 0..<(base.width * base.height) {
            let b: SIMD3<Float> = base.pixels[i]
            let delta: SIMD3<Float> = effect.pixels[i] - b
            out.pixels[i] = b + delta * mask[i]
        }
        return out
    }
}
