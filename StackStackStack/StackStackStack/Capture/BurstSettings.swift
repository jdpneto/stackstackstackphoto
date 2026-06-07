/// User-controllable burst length + window for the long-exposure looks (Smooth/Trails). Photo count
/// is hard-capped at `CaptureRecipe.maxBurstFrames` and duration at 1...60s — the envelope the
/// on-device streaming stack is sized for. The init clamps, so out-of-range slider values can never
/// escape. (design 2026-06-07 §5)
struct BurstSettings: Equatable, Sendable {
    let photoCount: Int
    let durationSeconds: Double

    init(photoCount: Int, durationSeconds: Double) {
        self.photoCount = min(max(photoCount, 2), CaptureRecipe.maxBurstFrames)
        self.durationSeconds = min(max(durationSeconds, 1), 60)
    }

    /// Default seed for the long-exposure looks.
    static let `default` = BurstSettings(photoCount: 10, durationSeconds: 2)
}
