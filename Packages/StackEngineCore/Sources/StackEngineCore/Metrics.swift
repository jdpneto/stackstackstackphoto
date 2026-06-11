import Foundation
import simd

public enum Metrics {
    /// Max absolute per-channel difference between two linear images.
    public static func maxAbsDiff(_ a: PixelImage, _ b: PixelImage) -> Float {
        precondition(a.width == b.width && a.height == b.height, "image geometry mismatch")
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

    // MARK: - SSIM

    /// Structural Similarity Index between two linear-light images.
    ///
    /// Implementation follows Wang et al. (2004), computed on Rec.709 luma.
    ///
    /// Parameters (pinned for Android parity):
    ///   - Window: 8×8 box, stride 4
    ///   - K1 = 0.01, K2 = 0.03, L = 1  (linear-light [0,1] domain)
    ///   - Luma: Rec.709 — 0.2126·R + 0.7152·G + 0.0722·B
    ///
    /// Returns mean SSIM over all windows ∈ (−1, 1]; 1.0 for identical images.
    public static func ssim(_ a: PixelImage, _ b: PixelImage) -> Double {
        precondition(a.width == b.width && a.height == b.height, "image geometry mismatch")
        let w = a.width, h = a.height

        // Build luma planes via the shared Luma helper — no duplication.
        let la = Luma.luminance(a)
        let lb = Luma.luminance(b)

        let winW = 8, winH = 8, stride = 4
        let L: Double = 1.0
        let c1 = (0.01 * L) * (0.01 * L)   // K1=0.01
        let c2 = (0.03 * L) * (0.03 * L)   // K2=0.03
        let n  = Double(winW * winH)

        var sumSSIM = 0.0
        var count   = 0

        var y0 = 0
        while y0 + winH <= h {
            var x0 = 0
            while x0 + winW <= w {
                // Accumulate window statistics.
                var muA = 0.0, muB = 0.0
                for wy in 0..<winH {
                    for wx in 0..<winW {
                        let idx = (y0 + wy) * w + (x0 + wx)
                        muA += Double(la[idx])
                        muB += Double(lb[idx])
                    }
                }
                muA /= n; muB /= n

                var sigAA = 0.0, sigBB = 0.0, sigAB = 0.0
                for wy in 0..<winH {
                    for wx in 0..<winW {
                        let idx = (y0 + wy) * w + (x0 + wx)
                        let da = Double(la[idx]) - muA
                        let db = Double(lb[idx]) - muB
                        sigAA += da * da
                        sigBB += db * db
                        sigAB += da * db
                    }
                }
                // Use population variance (divide by n) — same denominator Android uses.
                sigAA /= n; sigBB /= n; sigAB /= n

                let num  = (2 * muA * muB + c1) * (2 * sigAB + c2)
                let den  = (muA * muA + muB * muB + c1) * (sigAA + sigBB + c2)
                sumSSIM += num / den
                count   += 1

                x0 += stride
            }
            y0 += stride
        }

        return count == 0 ? 1.0 : sumSSIM / Double(count)
    }

    // MARK: - ΔE (CIE76)

    /// Mean CIE76 ΔE between two linear-sRGB images.
    ///
    /// Conversion chain (pinned for Android parity):
    ///
    ///   linear-sRGB → XYZ (D65) via the IEC 61966-2-1 matrix:
    ///     X = 0.4124564·R + 0.3575761·G + 0.1804375·B
    ///     Y = 0.2126729·R + 0.7151522·G + 0.0721750·B
    ///     Z = 0.0193339·R + 0.1191920·G + 0.9503041·B
    ///   (D65 white: Xn=0.95047, Yn=1.00000, Zn=1.08883)
    ///
    ///   XYZ → Lab via:
    ///     f(t) = t^(1/3)          if t > (6/29)^3  (δ³, δ = 6/29)
    ///     f(t) = t/(3δ²) + 4/29   otherwise
    ///     L* = 116·f(Y/Yn) − 16
    ///     a* = 500·(f(X/Xn) − f(Y/Yn))
    ///     b* = 200·(f(Y/Yn) − f(Z/Zn))
    ///
    ///   ΔE76 = sqrt((ΔL*)² + (Δa*)² + (Δb*)²)
    ///
    /// Returns the mean ΔE76 over all pixels; 0 for identical images.
    public static func meanDeltaE(_ a: PixelImage, _ b: PixelImage) -> Double {
        precondition(a.width == b.width && a.height == b.height, "image geometry mismatch")

        var sumDE = 0.0
        for i in 0..<a.pixels.count {
            let labA = _labFromLinearSRGB(a.pixels[i])
            let labB = _labFromLinearSRGB(b.pixels[i])
            let dL = labA.0 - labB.0
            let da = labA.1 - labB.1
            let db = labA.2 - labB.2
            sumDE += (dL * dL + da * da + db * db).squareRoot()
        }
        return a.pixels.isEmpty ? 0.0 : sumDE / Double(a.pixels.count)
    }
}

// MARK: - Private colour helpers

/// Convert a linear-sRGB pixel to CIE L*a*b* (D65).
///
/// sRGB→XYZ matrix rows (IEC 61966-2-1, D65):
///   [0.4124564, 0.3575761, 0.1804375]   → X
///   [0.2126729, 0.7151522, 0.0721750]   → Y
///   [0.0193339, 0.1191920, 0.9503041]   → Z
///
/// D65 white point: Xn=0.95047, Yn=1.00000, Zn=1.08883
///
/// Lab f(t): δ = 6/29
///   t > δ³  → t^(1/3)
///   else    → t/(3δ²) + 4/29
@inline(__always)
private func _labFromLinearSRGB(_ p: SIMD3<Float>) -> (Double, Double, Double) {
    let r = Double(p.x), g = Double(p.y), b = Double(p.z)

    // linear-sRGB → XYZ (D65)
    let X = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
    let Y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    let Z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b

    // D65 white point
    let Xn = 0.95047, Yn = 1.00000, Zn = 1.08883

    // XYZ → Lab using the standard f(t) piecewise (δ = 6/29)
    @inline(__always) func f(_ t: Double) -> Double {
        // δ = 6/29  →  δ³ ≈ 0.008856,  3δ² = 108/841 ≈ 0.128419,  4/29 ≈ 0.137931
        let delta3 = 0.008856451679035631     // (6/29)^3
        let inv3d2 = 7.787037037037037        // 1/(3·(6/29)^2) = 841/108
        let c      = 0.13793103448275862      // 4/29
        return t > delta3 ? Foundation.pow(t, 1.0 / 3.0) : inv3d2 * t + c
    }

    let fX = f(X / Xn), fY = f(Y / Yn), fZ = f(Z / Zn)
    let L  = 116.0 * fY - 16.0
    let a  = 500.0 * (fX - fY)
    let bL = 200.0 * (fY - fZ)
    return (L, a, bL)
}
