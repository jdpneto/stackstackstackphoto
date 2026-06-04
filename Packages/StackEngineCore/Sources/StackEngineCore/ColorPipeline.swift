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
