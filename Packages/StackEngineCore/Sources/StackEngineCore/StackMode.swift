/// The processing look applied to an aligned burst (design §13). Every look shares the
/// align step and differs only in the per-pixel reducer.
///
/// `String`-backed so the raw value is a stable, contract-guaranteed storage key (renaming a
/// case won't silently change persisted keys).
public enum StackMode: String, Sendable, Equatable, Hashable, CaseIterable {
    case noiseReduction   // robust (sigma-clipped) mean — clean detail
    case smoothMotion     // plain temporal mean — silky water / clouds
    case lightTrails      // per-channel lighten (max) — light streaks
    case lowLightBoost    // robust mean + exposure gain — brighter night shot
    case depthOfField     // all-in-focus focus sweep — stacked by FocusStacker, not Pipeline.reduce

    /// The looks that capture a continuous burst over a window and use the streaming reducer
    /// (vs. the static fast-burst looks). (design 2026-06-07 §3)
    public var isLongExposure: Bool {
        switch self {
        case .smoothMotion, .lightTrails: return true
        case .noiseReduction, .lowLightBoost, .depthOfField: return false
        }
    }

    /// Looks whose result can be re-blended against the aligned reference (α look-strength).
    /// Depth has no single-reference semantics — its frames differ by focus, not by time.
    public var supportsBlendReference: Bool { self != .depthOfField }
}
