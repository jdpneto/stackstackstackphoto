import StackEngineCore
import QuartzCore
import simd

/// Deterministic in-memory capture that SIMULATES scene motion across the burst, so the
/// long-exposure looks are visibly distinct in the Simulator: a bright object sweeps across
/// the frame, so max-blend "light trails" leaves a bright streak while mean "smooth motion"
/// averages it into a faint blur.
struct FakeCaptureService: CaptureService {
    let width: Int
    let height: Int

    /// No live preview in the Simulator — the capture screen falls back to its neutral background.
    func startPreview() async -> CALayer? { nil }

    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool,
                      onProgress: (@Sendable (Int) -> Void)?) async throws -> [RawSensorFrame] {
        await Task.yield()   // model a non-instant capture so the shutter's re-entrancy guard applies
        let n = max(recipe.frameCount, 1)
        return (0..<n).map { k in
            var mosaic = [UInt16](repeating: 0, count: width * height)
            // Dim, slightly noisy static background.
            for y in 0..<height { for x in 0..<width {
                mosaic[y * width + x] = UInt16(200 + (k * 17 + x * 3 + y * 5) % 11)
            }}
            // A bright object sweeping left→right over the burst.
            let cx = Int((Float(k) / Float(max(n - 1, 1))) * Float(width - 1))
            let cy = height / 2
            for dy in -2...2 { for dx in -2...2 {
                let x = cx + dx, y = cy + dy
                if x >= 0, x < width, y >= 0, y < height { mosaic[y * width + x] = 1000 }
            }}
            let frame = RawSensorFrame(width: width, height: height, mosaic: mosaic,
                                       blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
                                       wbGains: SIMD3<Float>(1, 1, 1))
            onProgress?(k + 1)
            return frame
        }
    }
}
