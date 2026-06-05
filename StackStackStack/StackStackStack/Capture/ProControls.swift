/// Manual capture overrides (design §11, Pro mode). A `nil` field means Auto for that control.
struct ProControls: Sendable, Equatable {
    var frameCount: Int?        // override the look's burst length
    var iso: Double?            // manual sensor gain (ISO units)
    var shutterSeconds: Double? // manual exposure duration (seconds)
    var focus: Double?          // manual lens position, 0 (near) … 1 (far)

    static let auto = ProControls()
}
