import AVFoundation
import ImageIO
import StackEngineCore
import simd

/// Converts an AVCapturePhoto (Bayer RAW) into our RawSensorFrame, reading the real
/// black/white levels from the DNG metadata (falling back to 14-bit defaults).
enum RawFrameConverter {
    static func make(from photo: AVCapturePhoto) -> RawSensorFrame? {
        guard let px = photo.pixelBuffer else { return nil }
        CVPixelBufferLockBaseAddress(px, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(px, .readOnly) }
        let w = CVPixelBufferGetWidth(px), h = CVPixelBufferGetHeight(px)
        guard w > 0, h > 0, let base = CVPixelBufferGetBaseAddress(px) else { return nil }
        let rowBytes = CVPixelBufferGetBytesPerRow(px)
        var mosaic = [UInt16](repeating: 0, count: w * h)
        for y in 0..<h {
            let row = base.advanced(by: y * rowBytes).assumingMemoryBound(to: UInt16.self)
            for x in 0..<w { mosaic[y * w + x] = row[x] }
        }
        let (black, white, cfa) = sensorParameters(from: photo.metadata)
        return RawSensorFrame(width: w, height: h, mosaic: mosaic,
                              blackLevel: black, whiteLevel: white, cfa: cfa,
                              wbGains: SIMD3<Float>(1, 1, 1))
    }

    /// Best-effort black/white level from the DNG metadata dictionary. Hardcoding blackLevel
    /// to 0 (as the first cut did) lifts the black point and wrecks shadow color on real
    /// sensors, so we read it when present and only fall back to conservative defaults.
    private static func sensorParameters(from metadata: [String: Any]) -> (black: Float, white: Float, cfa: CFAPattern) {
        let dng = metadata[kCGImagePropertyDNGDictionary as String] as? [String: Any]

        var black: Float = 0
        if let v = dng?[kCGImagePropertyDNGBlackLevel as String] {
            if let arr = v as? [NSNumber], let first = arr.first { black = first.floatValue }
            else if let num = v as? NSNumber { black = num.floatValue }
        }

        var white: Float = 16383  // 14-bit default
        if let num = dng?[kCGImagePropertyDNGWhiteLevel as String] as? NSNumber { white = num.floatValue }
        if white <= black { white = black + 1 }  // never let the linearization denominator hit 0

        // CFA-pattern detection from DNG metadata is device-specific and not yet validated on
        // hardware; default to RGGB (correct for current Apple back-wide sensors). TODO: read CFA.
        return (black, white, .rggb)
    }
}
