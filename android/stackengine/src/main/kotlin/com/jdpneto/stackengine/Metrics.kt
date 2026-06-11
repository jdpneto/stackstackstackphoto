package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Image quality metrics: PSNR, SSIM, ΔE76. */
object Metrics {

    /**
     * Max absolute per-channel difference between two linear images.
     * Both images must have identical dimensions.
     */
    fun maxAbsDiff(a: PixelImage, b: PixelImage): Float {
        require(a.width == b.width && a.height == b.height) { "image geometry mismatch" }
        var m = 0f
        val n = a.pixels.size
        val ap = a.pixels
        val bp = b.pixels
        for (i in 0 until n) {
            val d = abs(ap[i] - bp[i])
            if (d > m) m = d
        }
        return m
    }

    /**
     * PSNR in dB between two equal-length 8-bit buffers (.infinity if identical).
     *
     * Alpha byte included (constant 255 on both sides) — pin this so cross-platform numbers match.
     */
    fun psnr(a: ByteArray, b: ByteArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        var mse = 0.0
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xFF).toDouble() - (b[i].toInt() and 0xFF).toDouble()
            mse += d * d
        }
        mse /= a.size.toDouble()
        if (mse == 0.0) return Double.POSITIVE_INFINITY
        return 10.0 * log10(255.0 * 255.0 / mse)
    }

    // -------------------------------------------------------------------------
    // SSIM
    // -------------------------------------------------------------------------

    /**
     * Structural Similarity Index between two linear-light images.
     *
     * Implementation follows Wang et al. (2004), computed on Rec.709 luma.
     *
     * Parameters (pinned for Android parity):
     *   - Window: 8×8 box, stride 4
     *   - K1 = 0.01, K2 = 0.03, L = 1  (linear-light [0,1] domain)
     *   - Luma: Rec.709 — 0.2126·R + 0.7152·G + 0.0722·B
     *
     * Edge cases:
     *   - Images with min(width, height) < 8 score 1.0 by convention (no valid windows).
     *   - When (width−8) % 4 ≠ 0 or (height−8) % 4 ≠ 0, up to 3px on right/bottom strips are uncovered.
     *     Android must match both behaviors (this implementation does).
     *
     * Returns mean SSIM over all windows ∈ (−1, 1]; 1.0 for identical images.
     */
    fun ssim(a: PixelImage, b: PixelImage): Double {
        require(a.width == b.width && a.height == b.height) { "image geometry mismatch" }
        val w = a.width
        val h = a.height

        // Build luma planes via the shared Luma helper — no duplication.
        val la = Luma.luminance(a)
        val lb = Luma.luminance(b)

        val winW = 8; val winH = 8; val stride = 4
        val L = 1.0
        val c1 = (0.01 * L) * (0.01 * L)    // K1=0.01
        val c2 = (0.03 * L) * (0.03 * L)    // K2=0.03
        val n = (winW * winH).toDouble()

        var sumSSIM = 0.0
        var count = 0

        var y0 = 0
        while (y0 + winH <= h) {
            var x0 = 0
            while (x0 + winW <= w) {
                // Accumulate window statistics.
                var muA = 0.0; var muB = 0.0
                for (wy in 0 until winH) {
                    for (wx in 0 until winW) {
                        val idx = (y0 + wy) * w + (x0 + wx)
                        muA += la[idx].toDouble()
                        muB += lb[idx].toDouble()
                    }
                }
                muA /= n; muB /= n

                var sigAA = 0.0; var sigBB = 0.0; var sigAB = 0.0
                for (wy in 0 until winH) {
                    for (wx in 0 until winW) {
                        val idx = (y0 + wy) * w + (x0 + wx)
                        val da = la[idx].toDouble() - muA
                        val db = lb[idx].toDouble() - muB
                        sigAA += da * da
                        sigBB += db * db
                        sigAB += da * db
                    }
                }
                // Use population variance (divide by n) — same denominator Android uses.
                sigAA /= n; sigBB /= n; sigAB /= n

                val num = (2.0 * muA * muB + c1) * (2.0 * sigAB + c2)
                val den = (muA * muA + muB * muB + c1) * (sigAA + sigBB + c2)
                sumSSIM += num / den
                count++

                x0 += stride
            }
            y0 += stride
        }

        return if (count == 0) 1.0 else sumSSIM / count.toDouble()
    }

    // -------------------------------------------------------------------------
    // ΔE (CIE76)
    // -------------------------------------------------------------------------

    /**
     * Mean CIE76 ΔE between two linear-sRGB images.
     *
     * Conversion chain (pinned for Android parity):
     *
     *   linear-sRGB → XYZ (D65) via high-precision sRGB D65 derivation (rows sum to the pinned white point):
     *     X = 0.4124564·R + 0.3575761·G + 0.1804375·B
     *     Y = 0.2126729·R + 0.7151522·G + 0.0721750·B
     *     Z = 0.0193339·R + 0.1191920·G + 0.9503041·B
     *   The Android port copies these 7-digit literals exactly, not the IEC 61966-2-1 4-dp table.
     *   (D65 white: Xn=0.95047, Yn=1.00000, Zn=1.08883)
     *
     *   XYZ → Lab via:
     *     f(t) = t^(1/3)          if t > (6/29)^3  (δ³, δ = 6/29)
     *     f(t) = t/(3δ²) + 4/29   otherwise
     *     L* = 116·f(Y/Yn) − 16
     *     a* = 500·(f(X/Xn) − f(Y/Yn))
     *     b* = 200·(f(Y/Yn) − f(Z/Zn))
     *
     *   ΔE76 = sqrt((ΔL*)² + (Δa*)² + (Δb*)²)
     *
     * Returns the mean ΔE76 over all pixels; 0 for identical images.
     */
    fun meanDeltaE(a: PixelImage, b: PixelImage): Double {
        require(a.width == b.width && a.height == b.height) { "image geometry mismatch" }
        val n = a.pixelCount
        if (n == 0) return 0.0
        var sumDE = 0.0
        for (i in 0 until n) {
            val base = i * 3
            val labA = labFromLinearSRGB(a.pixels[base].toDouble(), a.pixels[base + 1].toDouble(), a.pixels[base + 2].toDouble())
            val labB = labFromLinearSRGB(b.pixels[base].toDouble(), b.pixels[base + 1].toDouble(), b.pixels[base + 2].toDouble())
            val dL = labA[0] - labB[0]
            val da = labA[1] - labB[1]
            val db = labA[2] - labB[2]
            sumDE += sqrt(dL * dL + da * da + db * db)
        }
        return sumDE / n.toDouble()
    }
}

// ---------------------------------------------------------------------------
// Private colour helpers
// ---------------------------------------------------------------------------

/**
 * Convert a linear-sRGB pixel (r, g, b as Double) to CIE L*a*b* (D65).
 *
 * sRGB→XYZ matrix rows (IEC 61966-2-1, D65):
 *   [0.4124564, 0.3575761, 0.1804375]   → X
 *   [0.2126729, 0.7151522, 0.0721750]   → Y
 *   [0.0193339, 0.1191920, 0.9503041]   → Z
 *
 * D65 white point: Xn=0.95047, Yn=1.00000, Zn=1.08883
 *
 * Lab f(t): δ = 6/29
 *   t > δ³  → t^(1/3)
 *   else    → t/(3δ²) + 4/29
 */
private fun labFromLinearSRGB(r: Double, g: Double, b: Double): DoubleArray {
    // linear-sRGB → XYZ (D65)
    val X = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
    val Y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    val Z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b

    // D65 white point
    val Xn = 0.95047; val Yn = 1.00000; val Zn = 1.08883

    // XYZ → Lab using the standard f(t) piecewise (δ = 6/29)
    //   δ³ ≈ 0.008856,  1/(3δ²) = 841/108,  4/29 ≈ 0.137931
    fun f(t: Double): Double {
        val delta3 = 0.008856451679035631     // (6/29)^3
        val inv3d2 = 7.787037037037037        // 1/(3·(6/29)^2) = 841/108
        val c      = 0.13793103448275862      // 4/29
        return if (t > delta3) t.pow(1.0 / 3.0) else inv3d2 * t + c
    }

    val fX = f(X / Xn); val fY = f(Y / Yn); val fZ = f(Z / Zn)
    val L  = 116.0 * fY - 16.0
    val a  = 500.0 * (fX - fY)
    val bL = 200.0 * (fY - fZ)
    return doubleArrayOf(L, a, bL)
}
