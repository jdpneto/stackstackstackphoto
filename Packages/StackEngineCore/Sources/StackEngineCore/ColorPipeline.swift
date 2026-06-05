import simd

/// Linearize every raw site and apply its channel's white-balance gain.
/// Returns a single-channel buffer (still mosaiced), row-major.
func linearizeAndBalance(_ f: RawSensorFrame) -> [Float] {
    var lin = [Float](repeating: 0, count: f.width * f.height)
    for y in 0..<f.height {
        for x in 0..<f.width {
            let i = y * f.width + x
            var v = linearizeSample(f.mosaic[i], black: f.blackLevel, white: f.whiteLevel)
            switch cfaColor(f.cfa, x, y) {
            case .red:   v *= f.wbGains.x
            case .green: v *= f.wbGains.y
            case .blue:  v *= f.wbGains.z
            }
            lin[i] = v
        }
    }
    return lin
}

/// Simple bilinear demosaic of a linear, white-balanced single-channel mosaic.
/// Provisional — replaced by Malvar–He–Cutler in a later plan.
func demosaic(_ lin: [Float], width w: Int, height h: Int, pattern: CFAPattern) -> PixelImage {
    @inline(__always) func at(_ x: Int, _ y: Int) -> Float {
        let xx = min(max(x, 0), w - 1), yy = min(max(y, 0), h - 1)
        return lin[yy * w + xx]
    }
    var out = PixelImage(width: w, height: h)
    for y in 0..<h {
        for x in 0..<w {
            let v = at(x, y)
            var r: Float = 0, g: Float = 0, b: Float = 0
            switch cfaColor(pattern, x, y) {
            case .red:
                r = v
                g = (at(x-1, y) + at(x+1, y) + at(x, y-1) + at(x, y+1)) / 4
                b = (at(x-1, y-1) + at(x+1, y-1) + at(x-1, y+1) + at(x+1, y+1)) / 4
            case .blue:
                b = v
                g = (at(x-1, y) + at(x+1, y) + at(x, y-1) + at(x, y+1)) / 4
                r = (at(x-1, y-1) + at(x+1, y-1) + at(x-1, y+1) + at(x+1, y+1)) / 4
            case .green:
                g = v
                // One horizontal neighbor pair is R, the other (vertical) is B — or vice versa.
                if cfaColor(pattern, x - 1, y) == .red {
                    r = (at(x-1, y) + at(x+1, y)) / 2
                    b = (at(x, y-1) + at(x, y+1)) / 2
                } else {
                    b = (at(x-1, y) + at(x+1, y)) / 2
                    r = (at(x, y-1) + at(x, y+1)) / 2
                }
            }
            out[x, y] = SIMD3<Float>(r, g, b)
        }
    }
    return out
}

/// Fast HALF-RESOLUTION develop: combine each 2×2 CFA quad (1 R, 2 G, 1 B) directly into one RGB
/// pixel — no per-pixel neighbour interpolation. Far cheaper than bilinear demosaic (the dominant
/// on-device develop cost) and, since the managed pipeline downscales anyway, loses nothing.
func binDemosaic(_ lin: [Float], width w: Int, height h: Int, pattern: CFAPattern) -> PixelImage {
    let ow = w / 2, oh = h / 2
    var out = PixelImage(width: ow, height: oh)
    for oy in 0..<oh {
        let y0 = 2 * oy
        for ox in 0..<ow {
            let x0 = 2 * ox
            var r: Float = 0, g: Float = 0, b: Float = 0, gn: Float = 0
            for dy in 0..<2 {
                for dx in 0..<2 {
                    let v = lin[(y0 + dy) * w + (x0 + dx)]
                    switch cfaColor(pattern, x0 + dx, y0 + dy) {
                    case .red:   r = v
                    case .green: g += v; gn += 1
                    case .blue:  b = v
                    }
                }
            }
            out[ox, oy] = SIMD3<Float>(r, gn > 0 ? g / gn : g, b)
        }
    }
    return out
}

public enum ColorPipeline {
    /// Develop a raw frame into a linear, working-space RGB image.
    /// Order (normative, design §12): linearize → white balance → demosaic → color matrix.
    public static func process(_ frame: RawSensorFrame) -> PixelImage {
        develop(frame, demosaiced: demosaic(linearizeAndBalance(frame),
                                            width: frame.width, height: frame.height, pattern: frame.cfa))
    }

    /// Fast half-resolution develop (2×2 binning) — same pipeline, cheap demosaic. Used for the
    /// managed on-device path (the full-res output is downscaled anyway).
    public static func processBinned(_ frame: RawSensorFrame) -> PixelImage {
        develop(frame, demosaiced: binDemosaic(linearizeAndBalance(frame),
                                               width: frame.width, height: frame.height, pattern: frame.cfa))
    }

    private static func develop(_ frame: RawSensorFrame, demosaiced img: PixelImage) -> PixelImage {
        var out = img
        let m = frame.colorMatrix
        for i in 0..<out.pixels.count { out.pixels[i] = m * out.pixels[i] }
        return out
    }
}
