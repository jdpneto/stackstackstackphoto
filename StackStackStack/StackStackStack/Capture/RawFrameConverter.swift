import AVFoundation
import StackEngineCore
import simd

/// Converts an AVCapturePhoto (Bayer RAW) into our RawSensorFrame.
enum RawFrameConverter {
    static func make(from photo: AVCapturePhoto) -> RawSensorFrame? {
        guard let px = photo.pixelBuffer else { return nil }
        CVPixelBufferLockBaseAddress(px, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(px, .readOnly) }
        let w = CVPixelBufferGetWidth(px), h = CVPixelBufferGetHeight(px)
        guard let base = CVPixelBufferGetBaseAddress(px) else { return nil }
        let rowBytes = CVPixelBufferGetBytesPerRow(px)
        var mosaic = [UInt16](repeating: 0, count: w * h)
        for y in 0..<h {
            let row = base.advanced(by: y * rowBytes).assumingMemoryBound(to: UInt16.self)
            for x in 0..<w { mosaic[y * w + x] = row[x] }
        }
        // Metadata: AVFoundation RAW is typically 14-bit packed in 16; use sensible defaults
        // and refine per device in a later plan (capability detection).
        let meta = photo.metadata
        let cfa: CFAPattern = .rggb // refine from kCGImagePropertyDNG keys per device later
        _ = meta
        return RawSensorFrame(width: w, height: h, mosaic: mosaic,
                              blackLevel: 0, whiteLevel: 16383, cfa: cfa,
                              wbGains: SIMD3<Float>(1, 1, 1))
    }
}
