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

    /// Hard ceiling on burst length. The on-device develop+stack memory/time envelope is sized for
    /// this; beyond it the app risks the ~3 GB jetsam kill. (design 2026-06-07 §2)
    static let maxBurstFrames = 20

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
    /// The device burst is SEQUENTIAL and PACED by `durationSeconds / (frameCount-1)`: frames can't
    /// fire fully back-to-back (the RAW pipeline stalls after ~7), and pacing also spreads a
    /// long-exposure look's frames across its window so motion is sampled over time. The unit test
    /// only pins relative ordering.
    static func recipe(for mode: StackMode) -> CaptureRecipe {
        switch mode {
        case .noiseReduction: return CaptureRecipe(frameCount: 8,  durationSeconds: 0.5)
        case .lowLightBoost:  return CaptureRecipe(frameCount: 12, durationSeconds: 1.0)
        // Long-exposure looks: 15 frames (not 30) — full-res align+stack of 30 frames peaked at ~3 GB
        // and the OS jetsam-killed the app. 15 spread over the same window keeps the streak/blur at
        // ~half the peak memory and CPU. (A streaming/incremental stack would lift this cap later.)
        case .smoothMotion:   return CaptureRecipe(frameCount: 15, durationSeconds: 2.0)
        case .lightTrails:    return CaptureRecipe(frameCount: 15, durationSeconds: 3.0)
        }
    }

    /// Merge manual Pro overrides onto a per-look recipe. Auto (nil) fields leave the look default;
    /// the frame-count override is clamped to ≥ 1 so the recipe stays valid.
    func applying(_ pro: ProControls) -> CaptureRecipe {
        CaptureRecipe(frameCount: min(Self.maxBurstFrames, max(1, pro.frameCount ?? frameCount)),
                      durationSeconds: durationSeconds,
                      manualISO: pro.iso.map(Float.init) ?? manualISO,
                      manualShutterSeconds: pro.shutterSeconds ?? manualShutterSeconds,
                      manualFocus: pro.focus.map(Float.init) ?? manualFocus)
    }
}

protocol CaptureService {
    /// `isSteady` is consulted before each frame; when it returns false the burst waits rather than
    /// capturing (steadiness gating, long-exposure looks). Callers that don't gate use the overload
    /// below. (design 2026-06-07 §8)
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool) async throws -> [RawSensorFrame]
    /// Start the live preview session and return a layer showing it (nil if unavailable, e.g. the
    /// Simulator fake). Idempotent — safe to call each time the capture screen appears.
    func startPreview() async -> CALayer?
    /// Tap-to-focus: focus + meter exposure at a normalized device point of interest (0…1, from
    /// `AVCaptureVideoPreviewLayer.captureDevicePointConverted`). `lock` (long-press) holds AF/AE.
    /// Device-only; the default below makes it a no-op for callers without a camera. (design tap-to-focus §3.2)
    func setFocusExposure(atDevicePoint point: CGPoint, lock: Bool)
}

extension CaptureService {
    /// Ungated capture (static looks, tests): always "steady".
    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
        try await captureBurst(recipe: recipe, isSteady: { true })
    }

    func setFocusExposure(atDevicePoint point: CGPoint, lock: Bool) { }   // no-op unless a device overrides it
}
