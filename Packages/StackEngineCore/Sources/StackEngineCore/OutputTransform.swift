import Foundation
import simd

public enum OutputTransform {
    @inline(__always) private static func linearToSRGB(_ c: Float) -> Float {
        // NaN-safe: min/max clamp ±Inf correctly (+Inf→1, -Inf→0) but do NOT strip NaN, which would
        // reach UInt8(NaN) → trap. Only NaN needs special-casing → 0.
        let x = c.isNaN ? 0 : min(max(c, 0), 1)
        if x <= 0.0031308 { return x * 12.92 }
        return Float(1.055 * Foundation.pow(Double(x), 1.0 / 2.4) - 0.055)
    }

    @inline(__always) private static func srgbToLinear(_ b: UInt8) -> Float {
        let c = Float(b) / 255
        if c <= 0.04045 { return c / 12.92 }
        return Float(Foundation.pow((Double(c) + 0.055) / 1.055, 2.4))
    }

    /// Decode interleaved sRGB RGBA8 bytes back into a linear image (inverse of `encodeSRGB8`).
    /// The alpha byte (i*4+3) is ignored — developed results are always opaque.
    public static func decodeSRGB8(_ rgba8: [UInt8], width: Int, height: Int) -> PixelImage {
        precondition(rgba8.count == width * height * 4, "rgba8 length mismatch")
        var pixels = [SIMD3<Float>](repeating: .zero, count: width * height)
        for i in 0..<(width * height) {
            pixels[i] = SIMD3<Float>(srgbToLinear(rgba8[i*4]), srgbToLinear(rgba8[i*4+1]), srgbToLinear(rgba8[i*4+2]))
        }
        return PixelImage(width: width, height: height, pixels: pixels)
    }

    /// Encode a linear image to interleaved sRGB RGBA8 bytes (alpha = 255).
    public static func encodeSRGB8(_ img: PixelImage) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: img.pixels.count * 4)
        for i in 0..<img.pixels.count {
            let p = img.pixels[i]
            out[i*4 + 0] = UInt8((linearToSRGB(p.x) * 255).rounded())
            out[i*4 + 1] = UInt8((linearToSRGB(p.y) * 255).rounded())
            out[i*4 + 2] = UInt8((linearToSRGB(p.z) * 255).rounded())
            out[i*4 + 3] = 255
        }
        return out
    }
}
