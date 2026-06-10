import StackEngineCore
import QuartzCore

/// How a burst is captured for a given look (design §10.4), plus optional manual Pro overrides.
/// Long-exposure looks capture more frames over a longer window (a continuous burst); the static
/// looks use a short fast burst.
struct CaptureRecipe: Sendable, Equatable {
    /// A Depth focus sweep: M evenly-spaced lens positions from `near` to `far` (normalized
    /// `lensPosition` space: 0 = closest, 1 = infinity). Frames are captured IN SWEEP ORDER —
    /// the chain aligner depends on adjacency in focus. (spec 2026-06-10 §5.1)
    struct FocusSweep: Sendable, Equatable {
        let near: Float
        let far: Float
        let steps: Int

        /// Normalizes a reversed range and clamps to 0…1 so a wild UI value can't escape.
        init(near: Float, far: Float, steps: Int) {
            let lo = min(max(min(near, far), 0), 1)
            let hi = min(max(max(near, far), 0), 1)
            self.near = lo
            self.far = hi
            self.steps = max(steps, 1)
        }

        /// The per-frame lens positions, near → far inclusive. A degenerate single-step sweep
        /// shoots at `near` — the documented start of the range, not a surprise midpoint.
        var positions: [Float] {
            guard steps > 1 else { return [near] }
            return (0..<steps).map { near + (far - near) * Float($0) / Float(steps - 1) }
        }
    }

    var frameCount: Int
    var durationSeconds: Double
    var manualISO: Float?             // nil = auto/locked exposure gain (device path)
    var manualShutterSeconds: Double? // nil = auto/locked exposure duration (device path)
    var manualFocus: Float?           // nil = auto/locked focus; else lens position 0…1 (device path)
    var focusSweep: FocusSweep?       // non-nil for .depthOfField; nil for all other looks

    /// Hard ceiling on burst length. The on-device develop+stack memory/time envelope is sized for
    /// this; beyond it the app risks the ~3 GB jetsam kill. (design 2026-06-07 §2)
    static let maxBurstFrames = 20

    init(frameCount: Int, durationSeconds: Double,
         manualISO: Float? = nil, manualShutterSeconds: Double? = nil, manualFocus: Float? = nil,
         focusSweep: FocusSweep? = nil) {
        precondition(frameCount > 0, "frameCount must be > 0")
        self.frameCount = frameCount
        self.durationSeconds = durationSeconds
        self.manualISO = manualISO
        self.manualShutterSeconds = manualShutterSeconds
        self.manualFocus = manualFocus
        self.focusSweep = focusSweep
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
        // Depth: a near→far focus sweep, one RAW bracket per lens position; exposure/WB locked.
        // Step-paced (focus settle per frame), so duration here only sets the pacing floor.
        case .depthOfField:
            return CaptureRecipe(frameCount: 10, durationSeconds: 1.0,
                                 focusSweep: FocusSweep(near: 0, far: 1, steps: 10))
        }
    }

    /// Merge manual Pro overrides onto a per-look recipe. Auto (nil) fields leave the look default;
    /// the frame-count override is clamped to ≥ 1 so the recipe stays valid. For a sweep recipe the
    /// sweep absorbs the Near/Far overrides and tracks the final frame count (steps == frames), and
    /// the single manual-focus override is dropped — the sweep owns lens position.
    func applying(_ pro: ProControls) -> CaptureRecipe {
        let count = min(Self.maxBurstFrames, max(1, pro.frameCount ?? frameCount))
        let sweep = focusSweep.map { s in
            FocusSweep(near: pro.focusSweepNear.map(Float.init) ?? s.near,
                       far: pro.focusSweepFar.map(Float.init) ?? s.far,
                       steps: count)
        }
        return CaptureRecipe(frameCount: count,
                             durationSeconds: durationSeconds,
                             manualISO: pro.iso.map(Float.init) ?? manualISO,
                             manualShutterSeconds: pro.shutterSeconds ?? manualShutterSeconds,
                             manualFocus: sweep != nil ? nil : (pro.focus.map(Float.init) ?? manualFocus),
                             focusSweep: sweep)
    }
}

protocol CaptureService {
    /// `isSteady` is consulted before each frame; when it returns false the burst waits rather than
    /// capturing (steadiness gating, long-exposure looks). `onProgress` is called after each frame
    /// is appended, with the running count (1…n). Callers that don't need these use the overloads
    /// below. (design 2026-06-07 §8)
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool,
                      onProgress: (@Sendable (Int) -> Void)?) async throws -> [RawSensorFrame]
    /// Start the live preview session and return a layer showing it (nil if unavailable, e.g. the
    /// Simulator fake). Idempotent — safe to call each time the capture screen appears.
    func startPreview() async -> CALayer?
    /// Tap-to-focus: focus + meter exposure at a normalized device point of interest (0…1, from
    /// `AVCaptureVideoPreviewLayer.captureDevicePointConverted`). `lock` (long-press) holds AF/AE.
    /// Device-only; the default below makes it a no-op for callers without a camera. (design tap-to-focus §3.2)
    func setFocusExposure(atDevicePoint point: CGPoint, lock: Bool)
    /// Whether the device can step lens position for a Depth focus sweep (manual-focus hardware).
    /// Drives Depth-chip gating in the UI. (spec 2026-06-10 §5.4)
    var supportsDepthOfField: Bool { get }
}

extension CaptureService {
    /// Ungated capture (static looks, tests): always "steady", no progress callback.
    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
        try await captureBurst(recipe: recipe, isSteady: { true }, onProgress: nil)
    }
    /// Gated capture without a progress callback (long-exposure looks that don't need the counter).
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool) async throws -> [RawSensorFrame] {
        try await captureBurst(recipe: recipe, isSteady: isSteady, onProgress: nil)
    }

    func setFocusExposure(atDevicePoint point: CGPoint, lock: Bool) { }   // no-op unless a device overrides it
    var supportsDepthOfField: Bool { true }   // overridden by the device service after configuring
}

extension StackMode {
    /// Looks whose capture quality depends on holding a pose: the long-exposure window looks, and
    /// the Depth focus sweep (per-step handheld motion must stay inside the chain aligner's
    /// `ChainBounds`). Drives the capture gate and the steadiness overlay. (spec 2026-06-10 §5.3)
    var usesSteadinessGate: Bool { isLongExposure || self == .depthOfField }
}
