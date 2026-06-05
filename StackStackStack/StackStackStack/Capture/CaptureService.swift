import StackEngineCore
import QuartzCore

/// How a burst is captured for a given look (design §10.4), plus optional manual Pro overrides.
/// Long-exposure looks capture more frames over a longer window (a continuous burst); the static
/// looks use a short fast burst.
struct CaptureRecipe: Sendable, Equatable {
    var frameCount: Int
    var durationSeconds: Double
    var manualISO: Float?             // nil = auto/locked exposure gain (device path)
    var manualShutterSeconds: Double? // nil = auto/locked exposure duration (device path)
    var manualFocus: Float?           // nil = auto/locked focus; else lens position 0…1 (device path)

    init(frameCount: Int, durationSeconds: Double,
         manualISO: Float? = nil, manualShutterSeconds: Double? = nil, manualFocus: Float? = nil) {
        precondition(frameCount > 0, "frameCount must be > 0")
        self.frameCount = frameCount
        self.durationSeconds = durationSeconds
        self.manualISO = manualISO
        self.manualShutterSeconds = manualShutterSeconds
        self.manualFocus = manualFocus
    }

    /// Per-look capture policy. Frame counts trade noise/motion-sampling against memory + time.
    /// The device burst is SEQUENTIAL and back-to-back (next frame fires the instant the previous
    /// one completes), so the arms-up capture is as fast as the camera allows; the frame count is
    /// what spreads a long-exposure look across time. `durationSeconds` documents each look's
    /// intended exposure character (and the natural back-to-back span already exceeds these short
    /// windows); it is not used to add inter-frame delay. The unit test only pins relative ordering.
    static func recipe(for mode: StackMode) -> CaptureRecipe {
        switch mode {
        case .noiseReduction: return CaptureRecipe(frameCount: 8,  durationSeconds: 0.5)
        case .lowLightBoost:  return CaptureRecipe(frameCount: 12, durationSeconds: 1.0)
        case .smoothMotion:   return CaptureRecipe(frameCount: 30, durationSeconds: 2.0)
        case .lightTrails:    return CaptureRecipe(frameCount: 30, durationSeconds: 3.0)
        }
    }

    /// Merge manual Pro overrides onto a per-look recipe. Auto (nil) fields leave the look default;
    /// the frame-count override is clamped to ≥ 1 so the recipe stays valid.
    func applying(_ pro: ProControls) -> CaptureRecipe {
        CaptureRecipe(frameCount: max(1, pro.frameCount ?? frameCount),
                      durationSeconds: durationSeconds,
                      manualISO: pro.iso.map(Float.init) ?? manualISO,
                      manualShutterSeconds: pro.shutterSeconds ?? manualShutterSeconds,
                      manualFocus: pro.focus.map(Float.init) ?? manualFocus)
    }
}

protocol CaptureService {
    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame]
    /// Start the live preview session and return a layer showing it (nil if unavailable, e.g. the
    /// Simulator fake). Idempotent — safe to call each time the capture screen appears.
    func startPreview() async -> CALayer?
}
