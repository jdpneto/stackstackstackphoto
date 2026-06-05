import simd

/// Burt–Adelson multiband blend: composite N images by N per-pixel weight masks. Each image's
/// Laplacian pyramid is combined level-by-level using the Gaussian pyramid of its (normalized)
/// weight mask, then collapsed — giving seamless focus-boundary blending without halos (design §13.2).
public enum LaplacianPyramidBlend {
    /// `weights[k]` is a row-major per-pixel weight for frame k (length width*height). Weights need
    /// not be pre-normalized; they are normalized per pixel here (non-finite weights count as 0).
    /// All frames must share `images[0]`'s dimensions; image pixels are assumed finite.
    public static func blend(images: [PixelImage], weights: [[Float]], minSize: Int = 4) -> PixelImage {
        precondition(!images.isEmpty && images.count == weights.count, "images/weights mismatch")
        let w = images[0].width, h = images[0].height, n = w * h
        let m = images.count
        precondition(images.allSatisfy { $0.width == w && $0.height == h }, "all frames must match images[0] size")
        precondition(weights.allSatisfy { $0.count == n }, "each weight array must have width*height entries")

        // Sanitize a weight: non-finite (NaN/Inf) or negative → 0 (no contribution).
        @inline(__always) func clean(_ x: Float) -> Float { x.isFinite ? max(x, 0) : 0 }

        // Per-pixel normalize the weights (so they sum to 1), then carry each as a PixelImage so the
        // single pyramid machinery (SIMD3) handles masks and images alike.
        var maskImgs = [PixelImage]()
        var norm = [Float](repeating: 0, count: n)
        for i in 0..<n { var s: Float = 0; for k in 0..<m { s += clean(weights[k][i]) }; norm[i] = s }
        for k in 0..<m {
            var px = [SIMD3<Float>](repeating: .zero, count: n)
            for i in 0..<n {
                let wgt = norm[i] > 0 ? clean(weights[k][i]) / norm[i] : 1 / Float(m)
                px[i] = SIMD3<Float>(repeating: wgt)
            }
            maskImgs.append(PixelImage(width: w, height: h, pixels: px))
        }

        // Image Laplacian pyramids + mask Gaussian pyramids (same level dimensions for all frames).
        let imgLaps = images.map { ImagePyramid.laplacian($0, minSize: minSize) }
        let maskGaus = maskImgs.map { ImagePyramid.gaussian($0, minSize: minSize) }
        let levels = imgLaps[0].count

        // Blend each level: L_blend = Σ_k maskGauss_k · imgLap_k.
        var blended = [PixelImage]()
        for lvl in 0..<levels {
            let lw = imgLaps[0][lvl].width, lh = imgLaps[0][lvl].height
            var px = [SIMD3<Float>](repeating: .zero, count: lw * lh)
            for k in 0..<m {
                let lap = imgLaps[k][lvl].pixels, mask = maskGaus[k][lvl].pixels
                for j in 0..<px.count { px[j] += lap[j] * mask[j] }
            }
            blended.append(PixelImage(width: lw, height: lh, pixels: px))
        }
        return ImagePyramid.collapse(blended)
    }
}
