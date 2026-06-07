/// User-controllable burst length + window for the long-exposure looks (Smooth/Trails). Photo count
/// is hard-capped at `maxPhotoCount` and duration at 1...60s. These looks use the STREAMING stack,
/// whose peak memory is bounded by ~1-2 frames regardless of count, so the cap is set by speed/UX
/// rather than memory — `maxPhotoCount` (30) stays well under a minute to process in Release. (The
/// in-memory batch path used by the static looks / Pro override is capped lower at
/// `CaptureRecipe.maxBurstFrames`.) The init clamps, so out-of-range slider values can never escape.
/// (design 2026-06-07 §5)
struct BurstSettings: Equatable, Sendable {
    /// Hard ceiling on the long-exposure (streaming) burst. Memory is bounded by streaming; this is a
    /// speed/UX bound (≈ single-digit-to-teens of seconds in Release at 30 frames).
    static let maxPhotoCount = 30

    let photoCount: Int
    let durationSeconds: Double

    init(photoCount: Int, durationSeconds: Double) {
        self.photoCount = min(max(photoCount, 2), Self.maxPhotoCount)
        self.durationSeconds = min(max(durationSeconds, 1), 60)
    }

    /// Default seed for the long-exposure looks.
    static let `default` = BurstSettings(photoCount: 10, durationSeconds: 2)
}
