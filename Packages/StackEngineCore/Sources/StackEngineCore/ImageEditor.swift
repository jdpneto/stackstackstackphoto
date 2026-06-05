import Foundation
import simd

public enum ImageEditor {
    private static let pivot: Float = 0.18   // 18% linear mid-grey

    /// Apply global tonal adjustments to a linear image (design §14).
    /// Order: exposure → white balance → contrast (around the 18% pivot). Output is clamped ≥ 0.
    public static func apply(_ adj: ImageAdjustments, to img: PixelImage) -> PixelImage {
        if adj.isIdentity { return img }
        let expGain = Float(exp2(Double(adj.exposureEV)))
        let contrastFactor = 1 + adj.contrast
        let wb = SIMD3<Float>(1 + adj.temperature * 0.3, 1 + adj.tint * 0.3, 1 - adj.temperature * 0.3)
        let pivotVec = SIMD3<Float>(repeating: pivot)
        let zero = SIMD3<Float>(repeating: 0)
        var out = img
        for i in 0..<out.pixels.count {
            var p = img.pixels[i] * expGain                       // exposure
            p = p * wb                                            // white balance
            p = (p - pivotVec) * contrastFactor + pivotVec        // contrast about the pivot
            out.pixels[i] = simd_max(p, zero)                     // no negative light
        }
        return out
    }
}
