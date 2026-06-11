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
                      onProgress: (@Sendable (Int) -> Void)?) async throws -> CapturedBurst {
        await Task.yield()   // model a non-instant capture so the shutter's re-entrancy guard applies
        if let sweep = recipe.focusSweep {
            return .raw(focusBrackets(steps: sweep.positions.count, onProgress: onProgress))
        }
        let n = max(recipe.frameCount, 1)
        let frames = (0..<n).map { k -> RawSensorFrame in
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
        return .raw(frames)
    }

    /// Focus-bracket fake (spec 2026-06-10 §5.5): frame k carries high-amplitude checker texture
    /// only in vertical band k and a dim texture elsewhere (synthetic defocus), plus a small
    /// per-frame horizontal drift so the chain aligner has real work. Drift is translation-only —
    /// scaling a Bayer mosaic would corrupt the CFA pattern; the engine's unit tests cover scale.
    /// No single frame is sharp in every band; the stacked result must be.
    private func focusBrackets(steps: Int, onProgress: (@Sendable (Int) -> Void)?) -> [RawSensorFrame] {
        (0..<steps).map { k in
            var mosaic = [UInt16](repeating: 0, count: width * height)
            let band = max(width / steps, 1)
            let drift = k                       // px of horizontal drift per frame (handheld jitter)
            for y in 0..<height {
                for x in 0..<width {
                    let sx = x + drift          // shift the PATTERN, not the band, so bands stay put
                    let inBand = min(x / band, steps - 1) == k
                    let amp = inBand ? 350 : 40
                    let checker = ((sx / 2) + (y / 2)) % 2 == 0 ? amp : -amp
                    mosaic[y * width + x] = UInt16(max(64, 500 + checker))
                }
            }
            let frame = RawSensorFrame(width: width, height: height, mosaic: mosaic,
                                       blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
                                       wbGains: SIMD3<Float>(1, 1, 1))
            onProgress?(k + 1)
            return frame
        }
    }
}
