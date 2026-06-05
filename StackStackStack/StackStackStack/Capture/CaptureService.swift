import StackEngineCore

/// How a burst is captured for a given look (design §10.4). Long-exposure looks capture more
/// frames over a longer window (a continuous burst); the static looks use a short fast burst.
struct CaptureRecipe: Sendable, Equatable {
    var frameCount: Int
    var durationSeconds: Double

    init(frameCount: Int, durationSeconds: Double) {
        precondition(frameCount > 0, "frameCount must be > 0")
        self.frameCount = frameCount
        self.durationSeconds = durationSeconds
    }

    /// Per-look capture policy. Frame counts trade noise/motion-sampling against memory + time;
    /// durations are the wall-clock window the device burst is paced over (so long-exposure looks
    /// sample motion across time). Tunable; the unit test only pins the relative ordering.
    static func recipe(for mode: StackMode) -> CaptureRecipe {
        switch mode {
        case .noiseReduction: return CaptureRecipe(frameCount: 8,  durationSeconds: 0.5)
        case .lowLightBoost:  return CaptureRecipe(frameCount: 12, durationSeconds: 1.0)
        case .smoothMotion:   return CaptureRecipe(frameCount: 30, durationSeconds: 2.0)
        case .lightTrails:    return CaptureRecipe(frameCount: 30, durationSeconds: 3.0)
        }
    }
}

protocol CaptureService {
    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame]
}
