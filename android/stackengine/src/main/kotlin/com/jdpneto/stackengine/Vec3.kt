package com.jdpneto.stackengine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A three-component float vector. Mirrors `SIMD3<Float>` from the Swift engine.
 *
 * Allocation-light is a goal but correctness comes first. Operators match the Swift
 * SIMD3 surface used by the engine: +, -, *, scalar scale, dot, min, max, clamp.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        fun repeating(v: Float) = Vec3(v, v, v)
    }

    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(other: Vec3) = Vec3(x * other.x, y * other.y, z * other.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    /** Component-wise addition with a scalar (adds s to each channel). */
    operator fun plus(s: Float) = Vec3(x + s, y + s, z + s)

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun length(): Float = sqrt(x * x + y * y + z * z)

    /** Component-wise minimum. */
    fun min(other: Vec3) = Vec3(min(x, other.x), min(y, other.y), min(z, other.z))

    /** Component-wise maximum. */
    fun max(other: Vec3) = Vec3(max(x, other.x), max(y, other.y), max(z, other.z))

    /** Component-wise clamp to [lo, hi]. */
    fun clamp(lo: Vec3, hi: Vec3) = Vec3(
        x.coerceIn(lo.x, hi.x),
        y.coerceIn(lo.y, hi.y),
        z.coerceIn(lo.z, hi.z)
    )

    /** Read a channel by index (0=x, 1=y, 2=z). */
    operator fun get(i: Int): Float = when (i) {
        0 -> x; 1 -> y; 2 -> z
        else -> throw IndexOutOfBoundsException("Vec3 index $i")
    }
}

operator fun Float.times(v: Vec3) = Vec3(this * v.x, this * v.y, this * v.z)
