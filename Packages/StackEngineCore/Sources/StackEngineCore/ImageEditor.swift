import Foundation
import simd

public enum ImageEditor {
    private static let pivot: Float = 0.18   // 18% linear mid-grey

    /// Apply all adjustments: geometry (straighten → crop) then tonal (exposure → WB → contrast →
    /// shadows/highlights), clamped ≥ 0 (design §14).
    public static func apply(_ adj: ImageAdjustments, to img: PixelImage) -> PixelImage {
        if adj.isIdentity { return img }
        var out = img
        if adj.quarterTurns != 0 { out = ImageGeometry.rotated(out, quarterTurns: adj.quarterTurns) }
        if adj.straightenDegrees != 0 { out = straighten(out, degrees: adj.straightenDegrees) }
        if adj.cropAspect.ratio != nil { out = crop(out, aspect: adj.cropAspect) }
        // Geometry-only edits skip the per-pixel tonal pass entirely.
        return adj.hasTonalAdjustments ? tonal(adj, out) : out
    }

    /// Per-pixel tonal adjustments in linear light: exposure → white balance → contrast about the
    /// pivot → tone curve (shadows/highlights). Output clamped ≥ 0.
    static func tonal(_ adj: ImageAdjustments, _ img: PixelImage) -> PixelImage {
        let expGain = Float(exp2(Double(adj.exposureEV)))            // UI constrains EV to ±2
        let contrastFactor = 1 + max(-0.9, min(1, adj.contrast))     // contrast = -1 must not flat-grey
        // tint is magenta(+)/green(-): positive tint REDUCES green.
        let wb = SIMD3<Float>(1 + adj.temperature * 0.3, 1 - adj.tint * 0.3, 1 - adj.temperature * 0.3)
        let pivotVec = SIMD3<Float>(repeating: pivot)
        let one = SIMD3<Float>(repeating: 1), zero = SIMD3<Float>(repeating: 0)
        var out = img
        for i in 0..<out.pixels.count {
            var p = img.pixels[i] * expGain                          // exposure
            p = p * wb                                               // white balance
            p = (p - pivotVec) * contrastFactor + pivotVec           // contrast about the pivot
            // Tone curve: shadows weighted to the dark end ((1-tone)²), highlights to the bright end (tone²).
            let tone = simd_clamp(p, zero, one)
            p = p + adj.shadows * 0.5 * (one - tone) * (one - tone)
            p = p + adj.highlights * 0.5 * tone * tone
            out.pixels[i] = simd_max(p, zero)                        // no negative light
        }
        return out
    }

    /// Rotate about the centre by `degrees`, keeping dimensions. Auto-zooms to fill so the rotated
    /// frame has no empty / edge-smeared corners (the standard "straighten" behaviour).
    static func straighten(_ img: PixelImage, degrees: Float) -> PixelImage {
        let rad = degrees * .pi / 180
        let cosA = cos(rad), sinA = sin(rad)
        let w = img.width, h = img.height
        let cx = Float(w - 1) / 2, cy = Float(h - 1) / 2
        // Zoom so every output corner back-maps inside the source half-extents (no edge-smeared corners).
        // Derived from the actual extents cx,cy (not w/h) so it's exact for non-square frames too.
        let needX = cx > 0 ? (cx * abs(cosA) + cy * abs(sinA)) / cx : 1
        let needY = cy > 0 ? (cx * abs(sinA) + cy * abs(cosA)) / cy : 1
        let scale = max(needX, needY, 1)
        var out = PixelImage(width: w, height: h)
        for y in 0..<h {
            for x in 0..<w {
                let dx = (Float(x) - cx) / scale, dy = (Float(y) - cy) / scale
                out[x, y] = bilinear(img, cx + dx * cosA + dy * sinA, cy - dx * sinA + dy * cosA)
            }
        }
        return out
    }

    /// Centre-crop to the aspect's ratio (largest fit).
    static func crop(_ img: PixelImage, aspect: CropAspect) -> PixelImage {
        guard let ratio = aspect.ratio else { return img }
        let w = img.width, h = img.height
        var cw = w, ch = h
        // Clamp to [1, source extent]: max(1,…) bars a zero size, min(w/h,…) bars a Float-rounding
        // overshoot that would push the read window out of bounds.
        if Float(w) / Float(h) > ratio { cw = min(w, max(1, Int(Float(h) * ratio))) }
        else { ch = min(h, max(1, Int(Float(w) / ratio))) }
        let x0 = (w - cw) / 2, y0 = (h - ch) / 2
        var out = PixelImage(width: cw, height: ch)
        for y in 0..<ch {
            for x in 0..<cw { out[x, y] = img[x0 + x, y0 + y] }
        }
        return out
    }

    private static func bilinear(_ img: PixelImage, _ fx: Float, _ fy: Float) -> SIMD3<Float> {
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
}
