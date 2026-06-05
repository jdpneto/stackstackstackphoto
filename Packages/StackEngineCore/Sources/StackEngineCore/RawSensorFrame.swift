import simd

public enum CFAPattern: Equatable, Sendable { case rggb, bggr, grbg, gbrg }

enum CFAColor: Equatable { case red, green, blue }

/// One captured raw Bayer frame plus the metadata needed to develop it.
public struct RawSensorFrame: Sendable {
    public let width: Int
    public let height: Int
    public let mosaic: [UInt16]            // row-major, length width*height
    public let blackLevel: Float
    public let whiteLevel: Float
    public let cfa: CFAPattern
    public let wbGains: SIMD3<Float>       // per-channel R,G,B multipliers
    public let colorMatrix: simd_float3x3  // camera -> working space

    public init(width: Int, height: Int, mosaic: [UInt16],
                blackLevel: Float, whiteLevel: Float, cfa: CFAPattern,
                wbGains: SIMD3<Float> = SIMD3<Float>(1, 1, 1),
                colorMatrix: simd_float3x3 = matrix_identity_float3x3) {
        precondition(mosaic.count == width * height, "mosaic count mismatch")
        self.width = width
        self.height = height
        self.mosaic = mosaic
        self.blackLevel = blackLevel
        self.whiteLevel = whiteLevel
        self.cfa = cfa
        self.wbGains = wbGains
        self.colorMatrix = colorMatrix
    }
}

@inline(__always) func evenParity(_ n: Int) -> Bool { (((n % 2) + 2) % 2) == 0 }

/// Returns the color of the CFA site at (x, y). Robust to negative coordinates.
func cfaColor(_ pattern: CFAPattern, _ x: Int, _ y: Int) -> CFAColor {
    let ex = evenParity(x), ey = evenParity(y)
    switch pattern {
    case .rggb: return ey ? (ex ? .red : .green) : (ex ? .green : .blue)
    case .bggr: return ey ? (ex ? .blue : .green) : (ex ? .green : .red)
    case .grbg: return ey ? (ex ? .green : .red) : (ex ? .blue : .green)
    case .gbrg: return ey ? (ex ? .green : .blue) : (ex ? .red : .green)
    }
}

@inline(__always) func linearizeSample(_ v: UInt16, black: Float, white: Float) -> Float {
    let denom = white - black
    guard denom > 0 else { return 0 }                 // degenerate metadata (white <= black) → no signal
    // Clamp only the LOW end; preserve highlight headroom (>1) so per-channel white balance applied
    // downstream keeps clipped highlights neutral. The high clamp happens at the output transform.
    return max((Float(v) - black) / denom, 0)
}
