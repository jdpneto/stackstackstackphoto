import simd

/// A linear-light RGB image. Pixels are row-major; channels are scene-linear floats.
public struct PixelImage: Equatable {
    public let width: Int
    public let height: Int
    public var pixels: [SIMD3<Float>]

    public init(width: Int, height: Int, pixels: [SIMD3<Float>]) {
        precondition(pixels.count == width * height, "pixel count mismatch")
        self.width = width
        self.height = height
        self.pixels = pixels
    }

    public init(width: Int, height: Int, fill: SIMD3<Float> = .zero) {
        self.width = width
        self.height = height
        self.pixels = Array(repeating: fill, count: width * height)
    }

    @inline(__always) private func index(_ x: Int, _ y: Int) -> Int { y * width + x }

    public subscript(x: Int, y: Int) -> SIMD3<Float> {
        get { pixels[index(x, y)] }
        set { pixels[index(x, y)] = newValue }
    }
}
