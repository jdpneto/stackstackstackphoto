import simd

public struct Translation: Equatable {
    public let dx: Int
    public let dy: Int
    public init(dx: Int, dy: Int) { self.dx = dx; self.dy = dy }
}

public enum Alignment {
    /// Integer translation (dx,dy) minimizing mean luma SSD where ref[x,y] ~ moving[x+dx, y+dy].
    public static func estimateTranslation(reference ref: PixelImage,
                                           moving mov: PixelImage,
                                           searchRange r: Int) -> Translation {
        precondition(ref.width == mov.width && ref.height == mov.height)
        let lr = Luma.luminance(ref), lm = Luma.luminance(mov)
        let w = ref.width, h = ref.height
        var best = Translation(dx: 0, dy: 0)
        var bestCost = Float.infinity
        // Iterate from zero outward so ties are broken in favour of the smallest displacement.
        for mag in 0...r {
            for dy in -mag...mag {
                for dx in -mag...mag {
                    guard abs(dx) == mag || abs(dy) == mag else { continue } // shell only
                    var cost: Float = 0
                    var count: Float = 0
                    let yStart = max(0, -dy), yEnd = min(h, h - dy)
                    let xStart = max(0, -dx), xEnd = min(w, w - dx)
                    if yStart >= yEnd || xStart >= xEnd { continue }
                    for y in yStart..<yEnd {
                        for x in xStart..<xEnd {
                            let d = lr[y * w + x] - lm[(y + dy) * w + (x + dx)]
                            cost += d * d
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
