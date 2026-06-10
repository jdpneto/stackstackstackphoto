/// Manual capture overrides (design §11, Pro mode). A `nil` field means Auto for that control.
struct ProControls: Sendable, Equatable {
    var frameCount: Int?        // override the look's burst length
    var iso: Double?            // manual sensor gain (ISO units)
    var shutterSeconds: Double? // manual exposure duration (seconds)
    var focus: Double?          // manual lens position, 0 (near) … 1 (far)
    var focusSweepNear: Double? // Depth: sweep start lens position (0…1); nil = full range
    var focusSweepFar: Double?  // Depth: sweep end lens position (0…1); nil = full range

    static let auto = ProControls()

    /// True if any focus/exposure override is set (frame count doesn't affect AF/AE). Tap-to-focus
    /// requires full-auto AF/AE, so it's gated off when this is true. The Depth sweep range is NOT
    /// included: the sweep owns lens position at capture regardless, and a tap should still meter
    /// exposure. (spec 2026-06-10 §6)
    var hasManualFocusOrExposure: Bool { focus != nil || iso != nil || shutterSeconds != nil }
}
