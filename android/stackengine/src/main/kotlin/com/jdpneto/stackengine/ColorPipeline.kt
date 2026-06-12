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
    // Hot loop (~3M output pixels per 12 MP frame): hoist every loop invariant into locals and
    // write channels straight into the flat pixel array — no Vec3 boxing ART would have to
    // scalar-replace, no repeated field loads.
    val mosaic = f.mosaic
    val cfa = f.cfa
    val black = f.blackLevel; val white = f.whiteLevel
    val gainR = f.wbGains.x; val gainG = f.wbGains.y; val gainB = f.wbGains.z
    val pixels = out.pixels
    for (oy in 0 until oh) {
        val y0 = 2 * oy
        for (ox in 0 until ow) {
            val x0 = 2 * ox
            var r = 0f; var g = 0f; var b = 0f; var gn = 0f
            for (dy in 0 until 2) {
                val cy = y0 + dy
                val rowBase = cy * w
                for (dx in 0 until 2) {
                    val cx = x0 + dx
                    val raw = mosaic[rowBase + cx].toInt() and 0xFFFF
                    // The linearize contract lives in ONE place: linearizeSample (inline, zero-cost).
                    val lin = linearizeSample(raw, black, white)
                    // White-balance gain per CFA color (same math/order as linearizeAndBalance).
                    when (cfaColor(cfa, cx, cy)) {
                        CFAColor.RED   -> r = lin * gainR
                        CFAColor.GREEN -> { g += lin * gainG; gn++ }
                        CFAColor.BLUE  -> b = lin * gainB
                    }
                }
            }
            val base = (oy * ow + ox) * 3
            pixels[base]     = r
            pixels[base + 1] = if (gn > 0f) g / gn else g
            pixels[base + 2] = b
        }
    }
    return out
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
        // Hot loop (every pixel of every developed frame): scalar 3×3 multiply straight on the
        // flat array — the old per-pixel Vec3-in/Vec3-out pair is 2 real heap allocations per
        // pixel under ART. m is column-major: [c0x,c0y,c0z, c1x,c1y,c1z, c2x,c2y,c2z], so
        //   out.r = m[0]·r + m[3]·g + m[6]·b   (and likewise rows 1/2)
        // — the exact ops/order of the old mat3MulVec3 (bit-identical, identity-tested).
        val m0 = m[0]; val m1 = m[1]; val m2 = m[2]
        val m3 = m[3]; val m4 = m[4]; val m5 = m[5]
        val m6 = m[6]; val m7 = m[7]; val m8 = m[8]
        val p = out.pixels
        for (i in 0 until n) {
            val base = i * 3
            val r = p[base]; val g = p[base + 1]; val b = p[base + 2]
            p[base]     = m0 * r + m3 * g + m6 * b
            p[base + 1] = m1 * r + m4 * g + m7 * b
            p[base + 2] = m2 * r + m5 * g + m8 * b
        }
        return out
    }
}
