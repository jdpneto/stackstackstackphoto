import StackEngineCore
import simd

/// Deterministic in-memory capture for unit tests and previews (no camera).
struct FakeCaptureService: CaptureService {
    let width: Int
    let height: Int

    func captureBurst(mode: CaptureMode, frameCount: Int) async throws -> [RawSensorFrame] {
        (0..<frameCount).map { k in
            var mosaic = [UInt16](repeating: 0, count: width * height)
            for y in 0..<height { for x in 0..<width {
                let base = 544 + ((x + y) % 2) * 100      // mild pattern
                let noise = (k * 17 + x * 3 + y * 5) % 9 - 4
                mosaic[y * width + x] = UInt16(max(0, min(1023, base + noise)))
            }}
            return RawSensorFrame(width: width, height: height, mosaic: mosaic,
                                  blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
                                  wbGains: SIMD3<Float>(1, 1, 1))
        }
    }
}
