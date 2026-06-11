package com.jdpneto.stackengine

// ---------------------------------------------------------------------------
// Package-internal helpers (mirror of internal Swift functions)
// ---------------------------------------------------------------------------

/**
 * Linearize every raw site and apply its channel's white-balance gain.
 * Returns a single-channel buffer (still mosaiced), row-major.
 *
 * UInt16 mosaic is stored as ShortArray; read with `(mosaic[i].toInt() and 0xFFFF)`.
 */
internal fun linearizeAndBalance(f: RawSensorFrame): FloatArray {
    val lin = FloatArray(f.width * f.height)
    for (y in 0 until f.height) {
        for (x in 0 until f.width) {
            val i = y * f.width + x
            val raw = f.mosaic[i].toInt() and 0xFFFF
            var v = linearizeSample(raw, f.blackLevel, f.whiteLevel)
            v *= when (cfaColor(f.cfa, x, y)) {
                CFAColor.RED   -> f.wbGains.x
                CFAColor.GREEN -> f.wbGains.y
                CFAColor.BLUE  -> f.wbGains.z
            }
            lin[i] = v
        }
    }
    return lin
}

/**
 * Simple bilinear demosaic of a linear, white-balanced single-channel mosaic.
 * Provisional — replaced by Malvar–He–Cutler in a later plan.
 */
internal fun demosaic(lin: FloatArray, w: Int, h: Int, pattern: CFAPattern): PixelImage {
    fun at(x: Int, y: Int): Float {
        val xx = x.coerceIn(0, w - 1)
        val yy = y.coerceIn(0, h - 1)
        return lin[yy * w + xx]
    }
    val out = PixelImage(w, h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val v = at(x, y)
            var r = 0f; var g = 0f; var b = 0f
            when (cfaColor(pattern, x, y)) {
                CFAColor.RED -> {
                    r = v
                    g = (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1)) / 4f
                    b = (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) / 4f
                }
                CFAColor.BLUE -> {
                    b = v
                    g = (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1)) / 4f
                    r = (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) / 4f
                }
                CFAColor.GREEN -> {
                    g = v
                    // One horizontal neighbor pair is R, the other (vertical) is B — or vice versa.
                    if (cfaColor(pattern, x - 1, y) == CFAColor.RED) {
                        r = (at(x - 1, y) + at(x + 1, y)) / 2f
                        b = (at(x, y - 1) + at(x, y + 1)) / 2f
                    } else {
                        b = (at(x - 1, y) + at(x + 1, y)) / 2f
                        r = (at(x, y - 1) + at(x, y + 1)) / 2f
                    }
                }
            }
            out[x, y] = Vec3(r, g, b)
        }
    }
    return out
}

/**
 * Fast HALF-RESOLUTION develop: combine each 2×2 CFA quad (1 R, 2 G, 1 B) directly into one RGB
 * pixel — no per-pixel neighbour interpolation. Far cheaper than bilinear demosaic (the dominant
 * on-device develop cost) and, since the managed pipeline downscales anyway, loses nothing.
 */
internal fun binDemosaic(lin: FloatArray, w: Int, h: Int, pattern: CFAPattern): PixelImage {
    val ow = w / 2; val oh = h / 2
    val out = PixelImage(ow, oh)
    for (oy in 0 until oh) {
        val y0 = 2 * oy
        for (ox in 0 until ow) {
            val x0 = 2 * ox
            var r = 0f; var g = 0f; var b = 0f; var gn = 0f
            for (dy in 0 until 2) {
                for (dx in 0 until 2) {
                    val v = lin[(y0 + dy) * w + (x0 + dx)]
                    when (cfaColor(pattern, x0 + dx, y0 + dy)) {
                        CFAColor.RED   -> r = v
                        CFAColor.GREEN -> { g += v; gn++ }
                        CFAColor.BLUE  -> b = v
                    }
                }
            }
            out[ox, oy] = Vec3(r, if (gn > 0f) g / gn else g, b)
        }
    }
    return out
}

/**
 * FUSED half-resolution develop: linearize each raw mosaic site INLINE per 2×2 quad and combine
 * directly into one output RGB pixel, so the full-resolution linearized float plane is NEVER
 * materialized. The per-site arithmetic is IDENTICAL to [linearizeAndBalance] followed by
 * [binDemosaic] — same ops, same order — the only difference is that the intermediate full-size
 * FloatArray is eliminated (per-frame transient drops ~50 MB → ~0 on a 12 MP sensor).
 *
 * OOM rationale (Fix 1b): with N parallel develop workers each holding a full-res float plane
 * the default 256 MB Java heap is exhausted. With the fused path only the ~9 MB binned outputs
 * accumulate, keeping total parallel transients within the largeHeap budget.
 */
internal fun fusedBinDemosaic(f: RawSensorFrame): PixelImage {
    val w = f.width; val h = f.height
    val ow = w / 2; val oh = h / 2
    val out = PixelImage(ow, oh)
    for (oy in 0 until oh) {
        val y0 = 2 * oy
        for (ox in 0 until ow) {
            val x0 = 2 * ox
            var r = 0f; var g = 0f; var b = 0f; var gn = 0f
            for (dy in 0 until 2) {
                for (dx in 0 until 2) {
                    val rawIdx = (y0 + dy) * w + (x0 + dx)
                    val raw = f.mosaic[rawIdx].toInt() and 0xFFFF
                    // Inline linearizeSample (same logic as the standalone function).
                    val denom = f.whiteLevel - f.blackLevel
                    val lin = if (denom <= 0f) 0f else maxOf((raw.toFloat() - f.blackLevel) / denom, 0f)
                    // Inline white-balance gain (same logic as linearizeAndBalance).
                    val cx = x0 + dx; val cy = y0 + dy
                    val v = lin * when (cfaColor(f.cfa, cx, cy)) {
                        CFAColor.RED   -> f.wbGains.x
                        CFAColor.GREEN -> f.wbGains.y
                        CFAColor.BLUE  -> f.wbGains.z
                    }
                    when (cfaColor(f.cfa, cx, cy)) {
                        CFAColor.RED   -> r = v
                        CFAColor.GREEN -> { g += v; gn++ }
                        CFAColor.BLUE  -> b = v
                    }
                }
            }
            out[ox, oy] = Vec3(r, if (gn > 0f) g / gn else g, b)
        }
    }
    return out
}

/** Multiply a column-major 3×3 matrix (9 floats) by a Vec3. */
private fun mat3MulVec3(m: FloatArray, v: Vec3): Vec3 {
    // m is column-major: [c0x,c0y,c0z, c1x,c1y,c1z, c2x,c2y,c2z]
    // out.x = col0.x*v.x + col1.x*v.y + col2.x*v.z  = m[0]*v.x + m[3]*v.y + m[6]*v.z
    // out.y = col0.y*v.x + col1.y*v.y + col2.y*v.z  = m[1]*v.x + m[4]*v.y + m[7]*v.z
    // out.z = col0.z*v.x + col1.z*v.y + col2.z*v.z  = m[2]*v.x + m[5]*v.y + m[8]*v.z
    return Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z
    )
}

/** Color pipeline: linearize → white-balance → demosaic → color-matrix. */
object ColorPipeline {

    /**
     * Develop a raw frame into a linear, working-space RGB image.
     *
     * Order (normative, design §12): linearize → white balance → demosaic → color matrix.
     */
    fun process(frame: RawSensorFrame): PixelImage =
        develop(frame, demosaic(linearizeAndBalance(frame), frame.width, frame.height, frame.cfa))

    /**
     * Fast half-resolution develop (2×2 binning) — same pipeline, cheap demosaic. Used for the
     * managed on-device path (the full-res output is downscaled anyway).
     *
     * Uses [fusedBinDemosaic] so the full-resolution linearized float plane is NEVER materialized
     * (Fix 1b: eliminates ~50 MB per-frame transient, preventing OOM on Android).
     */
    fun processBinned(frame: RawSensorFrame): PixelImage =
        develop(frame, fusedBinDemosaic(frame))

    private fun develop(frame: RawSensorFrame, demosaiced: PixelImage): PixelImage {
        // demosaiced is a fresh image owned by this call — mutate in place, no copy needed.
        val out = demosaiced
        val m = frame.colorMatrix
        val n = out.pixelCount
        for (i in 0 until n) {
            val base = i * 3
            val v = Vec3(out.pixels[base], out.pixels[base + 1], out.pixels[base + 2])
            val mv = mat3MulVec3(m, v)
            out.pixels[base]     = mv.x
            out.pixels[base + 1] = mv.y
            out.pixels[base + 2] = mv.z
        }
        return out
    }
}
