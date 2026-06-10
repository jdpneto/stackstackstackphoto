import simd

public struct Translation: Equatable, Sendable {
    public let dx: Int
    public let dy: Int
    public init(dx: Int, dy: Int) { self.dx = dx; self.dy = dy }
}

public enum Alignment {
    /// Integer translation (dx,dy) minimizing mean luma SSD where ref[x,y] ~ moving[x+dx, y+dy].
    /// `robustClip` caps each pixel's squared residual (nil = plain SSD).
    public static func estimateTranslation(reference ref: PixelImage,
                                           moving mov: PixelImage,
                                           searchRange r: Int,
                                           robustClip: Float? = nil) -> Translation {
        precondition(ref.width == mov.width && ref.height == mov.height)
        return estimateTranslation(referenceLuma: Luma.luminance(ref),
                                   movingLuma: Luma.luminance(mov),
                                   width: ref.width, height: ref.height, searchRange: r,
                                   robustClip: robustClip)
    }

    /// Integer translation over precomputed luminance buffers (lets the pipeline reuse luma).
    /// `robustClip` (when set) caps each pixel's squared residual before accumulating, so
    /// focus-blur mismatches (or any outlier region) cannot pull the estimate away from the
    /// common background signal. nil = plain SSD.
    static func estimateTranslation(referenceLuma lr: [Float],
                                    movingLuma lm: [Float],
                                    width w: Int, height h: Int,
                                    searchRange r: Int,
                                    robustClip: Float? = nil) -> Translation {
        precondition(r >= 0, "searchRange must be >= 0")
        var best = Translation(dx: 0, dy: 0)
        var bestCost = Float.infinity
        // Iterate from zero outward (magnitude shells) so equal-cost ties are broken in
        // favour of the SMALLEST displacement.
        for mag in 0...r {
            for dy in -mag...mag {
                for dx in -mag...mag {
                    guard abs(dx) == mag || abs(dy) == mag else { continue } // current shell only
                    var cost: Float = 0
                    var count: Float = 0
                    let yStart = max(0, -dy), yEnd = min(h, h - dy)
                    let xStart = max(0, -dx), xEnd = min(w, w - dx)
                    if yStart >= yEnd || xStart >= xEnd { continue }
                    for y in yStart..<yEnd {
                        for x in xStart..<xEnd {
                            let d = lr[y * w + x] - lm[(y + dy) * w + (x + dx)]
                            let d2 = d * d
                            cost += robustClip.map { Swift.min(d2, $0) } ?? d2
                            count += 1
                        }
                    }
                    let mean = cost / count
                    if mean < bestCost { bestCost = mean; best = Translation(dx: dx, dy: dy) }
                }
            }
        }
        return best
    }

    /// Coarse-to-fine integer translation on a luma pyramid: estimate on a heavily-downscaled level
    /// (cheap, captures large shifts) then refine ±2 per finer level. Cost is ~O(image) instead of
    /// O(image × searchRange²) — the key to making full-resolution alignment fast on device.
    /// Collapses to a single-level `±maxShift` box search when the image is already small (so it
    /// matches `estimateTranslation` for small inputs), and returns identity for `maxShift <= 0`.
    /// Matches the full-resolution search on real content (textured scene + noise); it can diverge
    /// only on pathologically smooth/periodic inputs (pure gradients, sinusoids) where translation
    /// estimation is ill-posed regardless — a non-issue for photos.
    static func estimateTranslationCoarseToFine(referenceLuma lr: [Float], movingLuma lm: [Float],
                                                width w: Int, height h: Int,
                                                maxShift: Int, minDim: Int = 64) -> Translation {
        if maxShift <= 0 { return Translation(dx: 0, dy: 0) }
        // Build matching luma pyramids (finest first), halving until the min dimension hits minDim.
        var refP: [(l: [Float], w: Int, h: Int)] = [(lr, w, h)]
        var movP: [(l: [Float], w: Int, h: Int)] = [(lm, w, h)]
        while min(refP.last!.w, refP.last!.h) > minDim {
            refP.append(halveLuma(refP.last!)); movP.append(halveLuma(movP.last!))
        }
        let levels = refP.count
        var dx = 0, dy = 0
        for lvl in stride(from: levels - 1, through: 0, by: -1) {   // coarsest → finest
            let r = refP[lvl], m = movP[lvl]
            let range = levels == 1 ? maxShift : (lvl == levels - 1 ? max(2, maxShift >> lvl) : 2)
            (dx, dy) = bestShiftAround(r.l, m.l, width: r.w, height: r.h, baseDx: dx, baseDy: dy, range: range)
            if lvl > 0 { dx *= 2; dy *= 2 }   // a coarse-pixel shift is 2 fine-pixels
        }
        return Translation(dx: dx, dy: dy)
    }

    /// 2×2 box-downscale of a luma buffer (edge-clamped on odd dimensions).
    private static func halveLuma(_ p: (l: [Float], w: Int, h: Int)) -> (l: [Float], w: Int, h: Int) {
        let w = p.w, h = p.h, ow = (w + 1) / 2, oh = (h + 1) / 2
        var out = [Float](repeating: 0, count: ow * oh)
        for oy in 0..<oh {
            let y0 = 2 * oy, y1 = min(y0 + 1, h - 1)
            for ox in 0..<ow {
                let x0 = 2 * ox, x1 = min(x0 + 1, w - 1)
                out[oy * ow + ox] = (p.l[y0 * w + x0] + p.l[y0 * w + x1] + p.l[y1 * w + x0] + p.l[y1 * w + x1]) * 0.25
            }
        }
        return (out, ow, oh)
    }

    /// Integer shift (dx,dy) in a box of radius `range` around (baseDx,baseDy) minimizing mean luma
    /// SSD over the overlap, ties broken toward the smaller displacement.
    private static func bestShiftAround(_ lr: [Float], _ lm: [Float], width w: Int, height h: Int,
                                        baseDx: Int, baseDy: Int, range r: Int) -> (Int, Int) {
        var bestDx = baseDx, bestDy = baseDy, bestCost = Float.infinity
        for dy in (baseDy - r)...(baseDy + r) {
            let yStart = max(0, -dy), yEnd = min(h, h - dy)
            if yStart >= yEnd { continue }
            for dx in (baseDx - r)...(baseDx + r) {
                let xStart = max(0, -dx), xEnd = min(w, w - dx)
                if xStart >= xEnd { continue }
                var cost: Float = 0, count: Float = 0
                for y in yStart..<yEnd {
                    let ro = y * w, mo = (y + dy) * w + dx
                    for x in xStart..<xEnd { let d = lr[ro + x] - lm[mo + x]; cost += d * d; count += 1 }
                }
                let mean = cost / count
                let mag = abs(dx) + abs(dy), bestMag = abs(bestDx) + abs(bestDy)
                if mean < bestCost - 1e-9 || (mean < bestCost + 1e-9 && mag < bestMag) {
                    bestCost = mean; bestDx = dx; bestDy = dy
                }
            }
        }
        return (bestDx, bestDy)
    }

    /// Warp by (dx,dy): out[x,y] = img[x+dx, y+dy] (edge-clamped), aligning `img` to the reference.
    public static func warp(_ img: PixelImage, by t: Translation) -> PixelImage {
        let w = img.width, h = img.height
        var out = PixelImage(width: w, height: h)
        for y in 0..<h {
            for x in 0..<w {
                let sx = min(max(x + t.dx, 0), w - 1)
                let sy = min(max(y + t.dy, 0), h - 1)
                out[x, y] = img[sx, sy]
            }
        }
        return out
    }
}
