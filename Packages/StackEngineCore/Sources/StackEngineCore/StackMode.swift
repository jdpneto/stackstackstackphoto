/// The processing look applied to an aligned burst (design §13). Every look shares the
/// align step and differs only in the per-pixel reducer.
public enum StackMode: Sendable, Equatable, Hashable, CaseIterable {
    case noiseReduction   // robust (sigma-clipped) mean — clean detail
    case smoothMotion     // plain temporal mean — silky water / clouds
    case lightTrails      // per-channel lighten (max) — light streaks
    case lowLightBoost    // robust mean + exposure gain — brighter night shot
}
