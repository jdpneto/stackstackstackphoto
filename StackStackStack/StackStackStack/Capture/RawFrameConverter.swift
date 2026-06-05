import AVFoundation
import CoreVideo
import ImageIO
import StackEngineCore
import simd

/// Converts an AVCapturePhoto (Bayer RAW) into our RawSensorFrame. Validates the pixel-buffer
/// format before reading (so a planar / non-16-bit / processed buffer is rejected rather than
/// misread), derives the CFA pattern from the pixel format, and reads the real black/white levels
/// from the DNG metadata (falling back to 14-bit defaults).
enum RawFrameConverter {
    static func make(from photo: AVCapturePhoto) -> RawSensorFrame? {
        guard let px = photo.pixelBuffer else { return nil }
        let fmt = CVPixelBufferGetPixelFormatType(px)
        // Only single-plane 16-bit Bayer RAW is supported; bail (don't misread bytes / overrun the
        // buffer) on anything else — planar, 8-bit, or a processed buffer from a RAW fallback.
        guard isSupportedBayerFormat(fmt), !CVPixelBufferIsPlanar(px) else { return nil }

        CVPixelBufferLockBaseAddress(px, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(px, .readOnly) }
        let w = CVPixelBufferGetWidth(px), h = CVPixelBufferGetHeight(px)
        let rowBytes = CVPixelBufferGetBytesPerRow(px)
        guard w > 0, h > 0, rowBytes >= w * MemoryLayout<UInt16>.stride,
              let base = CVPixelBufferGetBaseAddress(px) else { return nil }

        var mosaic = [UInt16](repeating: 0, count: w * h)
        for y in 0..<h {
            let row = base.advanced(by: y * rowBytes).assumingMemoryBound(to: UInt16.self)
            for x in 0..<w { mosaic[y * w + x] = row[x] }
        }
        let (black, white) = blackWhiteLevels(from: photo.metadata)
        return RawSensorFrame(width: w, height: h, mosaic: mosaic,
                              blackLevel: black, whiteLevel: white, cfa: cfaPattern(for: fmt),
                              wbGains: whiteBalanceGains(from: photo.metadata))
    }

    /// Per-channel white-balance gains from the DNG AsShotNeutral, read from the capture metadata.
    /// Without these the un-balanced Bayer (green has 2× the sites) comes out green-cast.
    static func whiteBalanceGains(from metadata: [String: Any]) -> SIMD3<Float> {
        let dng = metadata[kCGImagePropertyDNGDictionary as String] as? [String: Any]
        guard let neutral = floatArray(dng?[kCGImagePropertyDNGAsShotNeutral as String]) else {
            return SIMD3<Float>(1, 1, 1)
        }
        return gains(fromAsShotNeutral: neutral)
    }

    /// AsShotNeutral is the camera-RGB of a neutral patch; dividing by it white-balances. Normalized
    /// green-relative (green gain = 1) so it neutralizes the cast without changing overall exposure.
    static func gains(fromAsShotNeutral neutral: [Float]) -> SIMD3<Float> {
        guard neutral.count == 3, neutral[0] > 0, neutral[1] > 0, neutral[2] > 0 else {
            return SIMD3<Float>(1, 1, 1)
        }
        let g = neutral[1]
        return SIMD3<Float>(g / neutral[0], 1, g / neutral[2])
    }

    /// DNG numeric metadata can arrive as an NSNumber array or a space-separated string.
    private static func floatArray(_ value: Any?) -> [Float]? {
        if let arr = value as? [NSNumber] { return arr.map { $0.floatValue } }
        if let s = value as? String {
            let parts = s.split(whereSeparator: { $0 == " " || $0 == "," }).compactMap { Float($0) }
            return parts.isEmpty ? nil : parts
        }
        return nil
    }

    /// Bayer RAW pixel formats we can read as a single-plane 16-bit mosaic.
    static func isSupportedBayerFormat(_ fmt: OSType) -> Bool {
        switch fmt {
        case kCVPixelFormatType_14Bayer_GRBG, kCVPixelFormatType_14Bayer_RGGB,
             kCVPixelFormatType_14Bayer_BGGR, kCVPixelFormatType_14Bayer_GBRG:
            return true
        default:
            return false
        }
    }

    /// CFA pattern carried by the pixel format itself (replaces the previous hardcoded RGGB).
    private static func cfaPattern(for fmt: OSType) -> CFAPattern {
        switch fmt {
        case kCVPixelFormatType_14Bayer_BGGR: return .bggr
        case kCVPixelFormatType_14Bayer_GRBG: return .grbg
        case kCVPixelFormatType_14Bayer_GBRG: return .gbrg
        default:                              return .rggb   // includes ..._RGGB
        }
    }

    /// Best-effort black/white level from the DNG metadata dictionary. Hardcoding blackLevel to 0
    /// lifts the black point and wrecks shadow color on real sensors, so we read it when present and
    /// only fall back to conservative defaults. A per-channel black array is averaged (a single
    /// scalar approximation — true per-site subtraction is a later refinement).
    private static func blackWhiteLevels(from metadata: [String: Any]) -> (black: Float, white: Float) {
        let dng = metadata[kCGImagePropertyDNGDictionary as String] as? [String: Any]

        var black: Float = 0
        if let v = dng?[kCGImagePropertyDNGBlackLevel as String] {
            if let arr = v as? [NSNumber], !arr.isEmpty {
                black = arr.map { $0.floatValue }.reduce(0, +) / Float(arr.count)
            } else if let num = v as? NSNumber {
                black = num.floatValue
            }
        }

        var white: Float = 16383  // 14-bit default
        if let v = dng?[kCGImagePropertyDNGWhiteLevel as String] {
            // Apple delivers WhiteLevel as an array (rarely a scalar); accept both, else the wrong
            // denominator silently compresses every highlight.
            if let arr = v as? [NSNumber], let first = arr.first { white = first.floatValue }
            else if let num = v as? NSNumber { white = num.floatValue }
        }
        if white <= black { white = black + 1 }  // never let the linearization denominator hit 0
        return (black, white)
    }
}
