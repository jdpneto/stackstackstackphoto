/// Non-destructive global tonal adjustments applied to a developed result (design §14).
public struct ImageAdjustments: Sendable, Equatable, Codable {
    public var exposureEV: Float    // stops; linear is multiplied by 2^EV
    public var contrast: Float      // -1...1, around an 18% linear pivot
    public var temperature: Float   // -1...1, warm (+) / cool (-)
    public var tint: Float          // -1...1, magenta (+) / green (-)

    public init(exposureEV: Float = 0, contrast: Float = 0, temperature: Float = 0, tint: Float = 0) {
        self.exposureEV = exposureEV
        self.contrast = contrast
        self.temperature = temperature
        self.tint = tint
    }

    public static let identity = ImageAdjustments()
    public var isIdentity: Bool { self == .identity }
}
