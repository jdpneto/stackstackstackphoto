package com.jdpneto.stackengine

/** CFA Bayer pattern of the sensor. */
enum class CFAPattern { RGGB, BGGR, GRBG, GBRG }

/** The color at a given CFA site. */
enum class CFAColor { RED, GREEN, BLUE }

/**
 * One captured raw Bayer frame plus the metadata needed to develop it.
 *
 * PORTING NOTE — UInt16 mosaic: Swift uses `[UInt16]`. Kotlin uses [ShortArray] (signed).
 * Read values with `(mosaic[i].toInt() and 0xFFFF)` to recover the unsigned 16-bit value.
 *
 * The [colorMatrix] is stored column-major as a 3×3 flat FloatArray with 9 elements:
 * [col0.x, col0.y, col0.z, col1.x, col1.y, col1.z, col2.x, col2.y, col2.z].
 * Identity default: the 3 diagonal elements are 1, off-diagonals 0.
 *
 * Multiplication `m * v` is:
 *   out.x = col0.x*v.x + col1.x*v.y + col2.x*v.z
 *   out.y = col0.y*v.x + col1.y*v.y + col2.y*v.z
 *   out.z = col0.z*v.x + col1.z*v.y + col2.z*v.z
 */
class RawSensorFrame(
    val width: Int,
    val height: Int,
    /** Row-major Bayer mosaic; read unsigned values with `(mosaic[i].toInt() and 0xFFFF)`. */
    val mosaic: ShortArray,
    val blackLevel: Float,
    val whiteLevel: Float,
    val cfa: CFAPattern,
    /** Per-channel R, G, B white-balance multipliers. Default identity (1,1,1). */
    val wbGains: Vec3 = Vec3(1f, 1f, 1f),
    /**
     * Camera-to-working-space color matrix (column-major, 9 floats).
     * Default: identity matrix.
     * `mat3x3(columns: (col0, col1, col2))` in Swift → `[c0x,c0y,c0z, c1x,c1y,c1z, c2x,c2y,c2z]`.
     */
    val colorMatrix: FloatArray = IDENTITY_3X3.copyOf()
) {
    init {
        require(mosaic.size == width * height) {
            "mosaic count mismatch: expected ${width * height}, got ${mosaic.size}"
        }
        require(colorMatrix.size == 9) { "colorMatrix must have exactly 9 elements" }
    }

    companion object {
        /** Column-major identity 3×3. */
        val IDENTITY_3X3: FloatArray = floatArrayOf(
            1f, 0f, 0f,   // col0
            0f, 1f, 0f,   // col1
            0f, 0f, 1f    // col2
        )

        /**
         * Convenience constructor accepting [IntArray] for the mosaic (for test code that
         * doesn't want to deal with Short literals). Values are truncated to 16 bits.
         */
        fun fromIntMosaic(
            width: Int, height: Int, mosaic: IntArray,
            blackLevel: Float, whiteLevel: Float, cfa: CFAPattern,
            wbGains: Vec3 = Vec3(1f, 1f, 1f),
            colorMatrix: FloatArray = IDENTITY_3X3.copyOf()
        ): RawSensorFrame = RawSensorFrame(
            width, height,
            ShortArray(mosaic.size) { mosaic[it].toShort() },
            blackLevel, whiteLevel, cfa, wbGains, colorMatrix
        )
    }
}

// ---------------------------------------------------------------------------
// CFA helpers (package-internal, ported from RawSensorFrame.swift)
// ---------------------------------------------------------------------------

/** True when n is even — robust to negative values. */
internal fun evenParity(n: Int): Boolean = (((n % 2) + 2) % 2) == 0

/**
 * Returns the color of the CFA site at (x, y). Robust to negative coordinates.
 * Mirrors `cfaColor(_:_:_:)` in Swift.
 */
internal fun cfaColor(pattern: CFAPattern, x: Int, y: Int): CFAColor {
    val ex = evenParity(x)
    val ey = evenParity(y)
    return when (pattern) {
        CFAPattern.RGGB -> if (ey) (if (ex) CFAColor.RED   else CFAColor.GREEN)
                           else    (if (ex) CFAColor.GREEN  else CFAColor.BLUE)
        CFAPattern.BGGR -> if (ey) (if (ex) CFAColor.BLUE  else CFAColor.GREEN)
                           else    (if (ex) CFAColor.GREEN  else CFAColor.RED)
        CFAPattern.GRBG -> if (ey) (if (ex) CFAColor.GREEN else CFAColor.RED)
                           else    (if (ex) CFAColor.BLUE   else CFAColor.GREEN)
        CFAPattern.GBRG -> if (ey) (if (ex) CFAColor.GREEN else CFAColor.BLUE)
                           else    (if (ex) CFAColor.RED    else CFAColor.GREEN)
    }
}

/**
 * Linearize a raw sample from [blackLevel]..[whiteLevel] → [0, +∞).
 *
 * Only the LOW end is clamped (negative → 0); highlight headroom above whiteLevel
 * is preserved so per-channel white balance applied downstream keeps clipped
 * highlights neutral. The high clamp happens at the output transform.
 *
 * Degenerate metadata (whiteLevel ≤ blackLevel) returns 0 without dividing by zero.
 */
internal fun linearizeSample(v: Int, black: Float, white: Float): Float {
    val denom = white - black
    if (denom <= 0f) return 0f
    return maxOf((v.toFloat() - black) / denom, 0f)
}
