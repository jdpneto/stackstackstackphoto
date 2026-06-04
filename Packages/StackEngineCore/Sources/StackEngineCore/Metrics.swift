import Foundation
import simd

public enum Metrics {
    /// Max absolute per-channel difference between two linear images.
    public static func maxAbsDiff(_ a: PixelImage, _ b: PixelImage) -> Float {
        precondition(a.pixels.count == b.pixels.count)
        var m: Float = 0
        for i in 0..<a.pixels.count {
            let d = a.pixels[i] - b.pixels[i]
            m = max(m, max(abs(d.x), max(abs(d.y), abs(d.z))))
        }
        return m
    }

    /// PSNR in dB between two equal-length 8-bit buffers (.infinity if identical).
    public static func psnr(_ a: [UInt8], _ b: [UInt8]) -> Double {
        precondition(a.count == b.count && !a.isEmpty)
        var mse = 0.0
        for i in 0..<a.count {
            let d = Double(a[i]) - Double(b[i])
            mse += d * d
        }
        mse /= Double(a.count)
        if mse == 0 { return .infinity }
        return 10 * log10(255 * 255 / mse)
    }
}
