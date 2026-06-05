import simd
import Foundation

/// A 2-D affine map used to register a moving frame to a reference. `apply` maps a point;
/// the aligner expresses the warp around the image centre. Built as a similarity (uniform
/// scale + rotation + translation) for focus breathing, but stored as a general 2×3 affine.
public struct Transform2D: Equatable, Sendable {
    public var a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float

    public init(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float) {
        self.a = a; self.b = b; self.c = c; self.d = d; self.tx = tx; self.ty = ty
    }

    public static let identity = Transform2D(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0)

    /// Map a point: (x, y) → (a·x + b·y + tx, c·x + d·y + ty).
    public func apply(_ x: Float, _ y: Float) -> SIMD2<Float> {
        SIMD2<Float>(a * x + b * y + tx, c * x + d * y + ty)
    }

    /// A similarity map: uniform `scale`, `rotation` (radians) about the origin, then translation.
    public static func similarity(scale s: Float, rotation r: Float, tx: Float, ty: Float) -> Transform2D {
        let co = cos(r), si = sin(r)
        return Transform2D(a: s * co, b: -s * si, c: s * si, d: s * co, tx: tx, ty: ty)
    }
}
