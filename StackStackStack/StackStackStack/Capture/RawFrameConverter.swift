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
                              wbGains: SIMD3<Float>(1, 1, 1))
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
        if let num = dng?[kCGImagePropertyDNGWhiteLevel as String] as? NSNumber { white = num.floatValue }
        if white <= black { white = black + 1 }  // never let the linearization denominator hit 0
        return (black, white)
    }
}
