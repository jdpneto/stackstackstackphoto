import Foundation
import simd

public enum OutputTransform {
    @inline(__always) private static func linearToSRGB(_ c: Float) -> Float {
        let x = min(max(c, 0), 1)
        if x <= 0.0031308 { return x * 12.92 }
        return Float(1.055 * Foundation.pow(Double(x), 1.0 / 2.4) - 0.055)
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
