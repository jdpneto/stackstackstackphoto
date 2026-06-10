/// Center-crop aspect presets for the editor.
public enum CropAspect: String, Sendable, Equatable, Codable, CaseIterable {
    case original, square, fourThree, sixteenNine
    /// width:height ratio, or nil for the original (no crop).
    public var ratio: Float? {
        switch self {
        case .original:    return nil
        case .square:      return 1
        case .fourThree:   return 4.0 / 3.0
        case .sixteenNine: return 16.0 / 9.0
        }
    }
}

/// Non-destructive global adjustments applied to a developed result (design §14).
public struct ImageAdjustments: Sendable, Equatable, Codable {
    public var exposureEV: Float        // stops; linear ×2^EV
    public var contrast: Float          // -1...1 around an 18% linear pivot
    public var temperature: Float       // -1...1, warm (+) / cool (-)
    public var tint: Float              // -1...1, magenta (+) / green (-)
    public var shadows: Float           // -1...1, lift (+) / lower (-) dark tones
    public var highlights: Float        // -1...1, lift (+) / lower (-) bright tones
    public var straightenDegrees: Float // rotation about the centre, degrees
    public var cropAspect: CropAspect   // centre-crop aspect
    public var quarterTurns: Int = 0 {  // 90°×k clockwise rotation, kept in 0…3 (gallery rotate)
        didSet { quarterTurns = ((quarterTurns % 4) + 4) % 4 }   // stays canonical on direct mutation (e.g. += 1)
    }
    /// Look strength α: 1 = full look (today's result), 0 = the aligned reference frame; lerp
    /// applied in linear light before geometry/tonal so the reference needs no separate geometry
    /// pass. Missing from legacy sidecars → decoded as 1 (unchanged behaviour). (spec 2026-06-11 §3)
    public var blendStrength: Float     // 0…1; 1 = identity (no blend)

    public init(exposureEV: Float = 0, contrast: Float = 0, temperature: Float = 0, tint: Float = 0,
                shadows: Float = 0, highlights: Float = 0, straightenDegrees: Float = 0,
                cropAspect: CropAspect = .original, quarterTurns: Int = 0, blendStrength: Float = 1) {
        self.exposureEV = exposureEV
        self.contrast = contrast
        self.temperature = temperature
        self.tint = tint
        self.shadows = shadows
        self.highlights = highlights
        self.straightenDegrees = straightenDegrees
        self.cropAspect = cropAspect
        self.quarterTurns = ((quarterTurns % 4) + 4) % 4
        self.blendStrength = blendStrength
    }

    public static let identity = ImageAdjustments()
    public var isIdentity: Bool { self == .identity }

    /// True when any per-pixel tonal control is non-default (lets geometry-only edits skip the tonal pass).
    public var hasTonalAdjustments: Bool {
        exposureEV != 0 || contrast != 0 || temperature != 0 || tint != 0 || shadows != 0 || highlights != 0
    }

    /// True when blendStrength is set below 1 — i.e. the lerp-toward-reference pass is needed.
    public var hasBlend: Bool { blendStrength < 1 }

    // Back-compat: edit sidecars written before the new fields lack those keys — default them.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        exposureEV = try c.decodeIfPresent(Float.self, forKey: .exposureEV) ?? 0
        contrast = try c.decodeIfPresent(Float.self, forKey: .contrast) ?? 0
        temperature = try c.decodeIfPresent(Float.self, forKey: .temperature) ?? 0
        tint = try c.decodeIfPresent(Float.self, forKey: .tint) ?? 0
        shadows = try c.decodeIfPresent(Float.self, forKey: .shadows) ?? 0
        highlights = try c.decodeIfPresent(Float.self, forKey: .highlights) ?? 0
        straightenDegrees = try c.decodeIfPresent(Float.self, forKey: .straightenDegrees) ?? 0
        cropAspect = try c.decodeIfPresent(CropAspect.self, forKey: .cropAspect) ?? .original
        let rawTurns = try c.decodeIfPresent(Int.self, forKey: .quarterTurns) ?? 0
        quarterTurns = ((rawTurns % 4) + 4) % 4
        blendStrength = try c.decodeIfPresent(Float.self, forKey: .blendStrength) ?? 1
    }
}
