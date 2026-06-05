import simd

/// Gaussian/Laplacian image pyramids for multiband blending (Burt–Adelson). `reduce`/`expand` use a
/// separable 5-tap binomial kernel, renormalized at borders so edges aren't darkened. Reconstruction
/// is exact for constant images and a smooth approximation otherwise — sufficient for blending.
public enum ImagePyramid {
    private static let kernel: [Float] = [1.0 / 16, 4.0 / 16, 6.0 / 16, 4.0 / 16, 1.0 / 16]

    /// Blur + downsample by 2 → dimensions ceil(w/2) × ceil(h/2).
    public static func reduce(_ img: PixelImage) -> PixelImage {
        let w = img.width, h = img.height
        let ow = (w + 1) / 2, oh = (h + 1) / 2
        var out = PixelImage(width: ow, height: oh)
        for oy in 0..<oh {
            for ox in 0..<ow {
                var acc = SIMD3<Float>(repeating: 0); var wsum: Float = 0
                for dy in 0..<5 {
                    let sy = 2 * oy + dy - 2
                    guard sy >= 0, sy < h else { continue }
                    for dx in 0..<5 {
                        let sx = 2 * ox + dx - 2
                        guard sx >= 0, sx < w else { continue }
                        let wgt = kernel[dx] * kernel[dy]
                        acc += img[sx, sy] * wgt; wsum += wgt
                    }
                }
                out[ox, oy] = wsum > 0 ? acc / wsum : acc   // renormalize at borders
            }
        }
        return out
    }

    /// Upsample to an exact target size with the binomial kernel (border-renormalized interpolation).
    public static func expand(_ img: PixelImage, toWidth tw: Int, toHeight th: Int) -> PixelImage {
        let w = img.width, h = img.height
        var out = PixelImage(width: tw, height: th)
        for ty in 0..<th {
            for tx in 0..<tw {
                var acc = SIMD3<Float>(repeating: 0); var wsum: Float = 0
                for dy in 0..<5 {
                    let syNum = ty + dy - 2
                    guard syNum % 2 == 0 else { continue }
                    let sy = syNum / 2
                    guard sy >= 0, sy < h else { continue }
                    for dx in 0..<5 {
                        let sxNum = tx + dx - 2
                        guard sxNum % 2 == 0 else { continue }
                        let sx = sxNum / 2
                        guard sx >= 0, sx < w else { continue }
                        let wgt = kernel[dx] * kernel[dy]
                        acc += img[sx, sy] * wgt; wsum += wgt
                    }
                }
                out[tx, ty] = wsum > 0 ? acc / wsum : acc
            }
        }
        return out
    }

    /// Gaussian pyramid, finest first, down to a min dimension of `minSize` (default 4) — but at
    /// least one level beyond the input.
    public static func gaussian(_ img: PixelImage, minSize: Int = 4) -> [PixelImage] {
        var levels = [img]
        while min(levels.last!.width, levels.last!.height) > minSize {
            levels.append(reduce(levels.last!))
        }
        return levels
    }

    /// Laplacian pyramid: L[i] = G[i] − expand(G[i+1] → G[i] size); the coarsest level is G[last].
    public static func laplacian(_ img: PixelImage, minSize: Int = 4) -> [PixelImage] {
        let g = gaussian(img, minSize: minSize)
        var lap = [PixelImage]()
        for i in 0..<(g.count - 1) {
            let up = expand(g[i + 1], toWidth: g[i].width, toHeight: g[i].height)
            var d = g[i]
            for j in 0..<d.pixels.count { d.pixels[j] -= up.pixels[j] }
            lap.append(d)
        }
        lap.append(g.last!)   // coarsest residual
        return lap
    }

    /// Collapse a Laplacian pyramid back to a single image.
    public static func collapse(_ lap: [PixelImage]) -> PixelImage {
        var out = lap.last!
        for i in stride(from: lap.count - 2, through: 0, by: -1) {
            let up = expand(out, toWidth: lap[i].width, toHeight: lap[i].height)
            var sum = lap[i]
            for j in 0..<sum.pixels.count { sum.pixels[j] += up.pixels[j] }
            out = sum
        }
        return out
    }
}
