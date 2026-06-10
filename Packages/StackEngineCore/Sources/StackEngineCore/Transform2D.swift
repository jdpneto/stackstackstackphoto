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

    /// The map that applies `other` FIRST, then `self`: result.apply(p) == self.apply(other.apply(p)).
    /// Used to chain per-pair focus-sweep links into a frame's warp-to-reference (spec §4.2).
    public func composed(with other: Transform2D) -> Transform2D {
        Transform2D(a: a * other.a + b * other.c,
                    b: a * other.b + b * other.d,
                    c: c * other.a + d * other.c,
                    d: c * other.b + d * other.d,
                    tx: a * other.tx + b * other.ty + tx,
                    ty: c * other.tx + d * other.ty + ty)
    }

    /// The inverse map. Similarity/affine registration transforms are invertible; a degenerate
    /// (near-zero determinant) matrix would mean the estimator already failed, so trap loudly.
    public var inverse: Transform2D {
        let det = a * d - b * c
        precondition(abs(det) > 1e-12, "non-invertible transform")
        let ia = d / det, ib = -b / det, ic = -c / det, id = a / det
        return Transform2D(a: ia, b: ib, c: ic, d: id,
                           tx: -(ia * tx + ib * ty), ty: -(ic * tx + id * ty))
    }
}
