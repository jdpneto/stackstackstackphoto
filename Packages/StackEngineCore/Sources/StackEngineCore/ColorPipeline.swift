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
