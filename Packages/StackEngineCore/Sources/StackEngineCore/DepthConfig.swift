/// Operating point for Depth-of-Field focus stacking (design §5, tiered).
public struct DepthConfig: Sendable, Equatable {
    /// Max long-edge in pixels for the working resolution; `nil` = full resolution (no downscale).
    public var workingResolution: Int?
    /// Cap on the number of focus brackets actually stacked (memory/time bound).
    public var maxFrames: Int
    /// Translation-align the brackets to the sharpest reference before stacking. OFF by default:
    /// focus stacking is classically tripod-based, and an SSD fit over focus brackets (whose content
    /// changes with focus) can lodge in a spurious shift/warp that smears detail. Robust handheld
    /// focus-bracket alignment (a focus-invariant estimator) is a documented refinement.
    public var alignFrames: Bool

    public init(workingResolution: Int?, maxFrames: Int, alignFrames: Bool = false) {
        precondition(maxFrames > 0, "maxFrames must be > 0")
        self.workingResolution = workingResolution
        self.maxFrames = maxFrames
        self.alignFrames = alignFrames
    }

    /// Auto (managed): snappy, screen/share-quality — ~1500 px long edge, ~10 brackets.
    public static let auto = DepthConfig(workingResolution: 1500, maxFrames: 10)
    /// Pro (max quality): full sensor resolution, more brackets (slow on CPU until Metal).
    public static let pro = DepthConfig(workingResolution: nil, maxFrames: 24)
}
