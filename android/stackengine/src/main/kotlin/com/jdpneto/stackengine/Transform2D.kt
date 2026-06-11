package com.jdpneto.stackengine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

/**
 * A 2-D affine map used to register a moving frame to a reference. [apply] maps a point;
 * the aligner expresses the warp around the image centre. Built as a similarity (uniform
 * scale + rotation + translation) for focus breathing, but stored as a general 2×3 affine.
 */
data class Transform2D(
    val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float
) {
    companion object {
        val identity = Transform2D(a = 1f, b = 0f, c = 0f, d = 1f, tx = 0f, ty = 0f)

        /** A similarity map: uniform [scale], [rotation] (radians) about the origin, then translation. */
        fun similarity(scale: Float, rotation: Float, tx: Float, ty: Float): Transform2D {
            val co = cos(rotation)
            val si = sin(rotation)
            return Transform2D(
                a = scale * co, b = -scale * si,
                c = scale * si, d =  scale * co,
                tx = tx, ty = ty
            )
        }
    }

    /** Map a point: (x, y) → (a·x + b·y + tx, c·x + d·y + ty). Returns a (x, y) pair. */
    fun apply(x: Float, y: Float): Pair<Float, Float> =
        Pair(a * x + b * y + tx, c * x + d * y + ty)

    /**
     * The map that applies [other] FIRST, then [this]: result.apply(p) == this.apply(other.apply(p)).
     * Used to chain per-pair focus-sweep links into a frame's warp-to-reference
     * (handheld DoF spec 2026-06-10 §4.2).
     */
    fun composed(with: Transform2D): Transform2D {
        val o = with
        return Transform2D(
            a  = a * o.a + b * o.c,
            b  = a * o.b + b * o.d,
            c  = c * o.a + d * o.c,
            d  = c * o.b + d * o.d,
            tx = a * o.tx + b * o.ty + tx,
            ty = c * o.tx + d * o.ty + ty
        )
    }

    /**
     * The inverse map. Similarity/affine registration transforms are invertible; a degenerate
     * (near-zero determinant) matrix would mean the estimator already failed, so trap loudly.
     */
    val inverse: Transform2D get() {
        val det = a * d - b * c
        // effectively det == 0 in Float; call sites are similarities with det ≈ 1
        require(abs(det) > 1e-12f) { "non-invertible transform" }
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Transform2D(
            a = ia, b = ib, c = ic, d = id,
            tx = -(ia * tx + ib * ty),
            ty = -(ic * tx + id * ty)
        )
    }
}
