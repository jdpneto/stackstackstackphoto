package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bit-exactness gate for the allocation-free hot-loop rewrites (the ART performance work:
 * Pair/Vec3 temporaries are real per-pixel heap allocations on device, so the hot loops were
 * flattened to direct FloatArray arithmetic — same float ops, same order, only the memory
 * shape changed).
 *
 * Each test compares the production function against a straightforward reference
 * implementation KEPT HERE (the old Pair/Vec3-based code, verbatim), following the
 * `fusedBinnedDevelopMatchesUnfusedReference` pattern: max abs diff must be EXACTLY 0 —
 * identical IEEE-754 ops in identical order leave no room for approximation.
 *
 * Inputs are non-trivial on purpose: noise + gradients + hard edges, and transforms with
 * rotation + scale + translation (including out-of-bounds excursions that exercise the
 * edge clamps and the non-finite/coerce guards).
 */
class AllocationFreeIdentityTests {

    // MARK: - Synthetic inputs (deterministic LCG; noise + gradient + hard edges)

    private fun syntheticImage(w: Int, h: Int, seed: Int): PixelImage {
        val img = PixelImage(w, h)
        var state = seed
        fun next(): Float {
            state = state * 1664525 + 1013904223
            return ((state ushr 8) and 0xFFFF).toFloat() / 65535f
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Hard edges every few pixels + smooth gradient + per-channel noise.
                val edge = if ((x / 5 + y / 4) % 2 == 0) 0.4f else 0f
                img[x, y] = Vec3(
                    0.1f + 0.6f * (x.toFloat() / w) + 0.15f * next() + edge,
                    0.2f + 0.5f * (y.toFloat() / h) + 0.15f * next() + edge,
                    0.05f + 0.2f * next() + edge
                )
            }
        }
        return img
    }

    /** Transforms covering rotation+scale+translation, a pure shift past the borders, and a general affine. */
    private val transforms = listOf(
        Transform2D.identity,
        Transform2D.similarity(scale = 1.03f, rotation = 0.05f, tx = 3.7f, ty = -2.3f),
        Transform2D.similarity(scale = 0.97f, rotation = -0.02f, tx = -5.1f, ty = 4.6f),
        Transform2D.similarity(scale = 1f, rotation = 0f, tx = 50.5f, ty = -60.25f),   // far out of bounds → clamps
        Transform2D(a = 1.02f, b = 0.03f, c = -0.04f, d = 0.98f, tx = 1.5f, ty = -0.5f)
    )

    private fun assertExactlyEqual(expected: FloatArray, actual: FloatArray, label: String) {
        assertEquals(expected.size, actual.size, "$label: size mismatch")
        var maxDiff = 0f
        for (i in expected.indices) {
            val d = abs(expected[i] - actual[i])
            if (d > maxDiff) maxDiff = d
        }
        assertEquals(0f, maxDiff, absoluteTolerance = 0f,
            message = "$label diverged from the Pair/Vec3 reference: max abs diff = $maxDiff")
    }

    // MARK: - References: the pre-rewrite Pair/Vec3 implementations, verbatim

    /** Old AffineAligner.sampleRGB (Vec3 ops). */
    private fun referenceSampleRGB(img: PixelImage, fxIn: Float, fyIn: Float): Vec3 {
        val w = img.width; val h = img.height
        val fx = (if (fxIn.isFinite()) fxIn else 0f).coerceIn(-1f, w.toFloat())
        val fy = (if (fyIn.isFinite()) fyIn else 0f).coerceIn(-1f, h.toFloat())
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0.toFloat(); val ty = fy - y0.toFloat()
        fun at(x: Int, y: Int): Vec3 = img[x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)]
        val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        val bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (bot - top) * ty
    }

    /** Old AffineAligner.warp (Pair from Transform2D.apply + Vec3 sampling). */
    private fun referenceWarp(img: PixelImage, by: Transform2D): PixelImage {
        val w = img.width; val h = img.height
        val cx = (w - 1).toFloat() / 2f
        val cy = (h - 1).toFloat() / 2f
        val out = PixelImage(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (px, py) = by.apply(x.toFloat() - cx, y.toFloat() - cy)
                out[x, y] = referenceSampleRGB(img, px + cx, py + cy)
            }
        }
        return out
    }

    /** Old AffineAligner.sampleLuma (unchanged in production, restated here so the reference is self-contained). */
    private fun referenceSampleLuma(l: FloatArray, w: Int, h: Int, fxIn: Float, fyIn: Float): Float {
        val fx = (if (fxIn.isFinite()) fxIn else 0f).coerceIn(-1f, w.toFloat())
        val fy = (if (fyIn.isFinite()) fyIn else 0f).coerceIn(-1f, h.toFloat())
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0.toFloat(); val ty = fy - y0.toFloat()
        fun at(x: Int, y: Int): Float = l[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        val bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (bot - top) * ty
    }

    /** Old AffineAligner.ssdWarped (Pair from Transform2D.apply per pixel). */
    private fun referenceSSDWarped(
        movL: FloatArray, refL: FloatArray, w: Int, h: Int,
        t: Transform2D, robustClip: Float?
    ): Float {
        val cx = (w - 1).toFloat() / 2f
        val cy = (h - 1).toFloat() / 2f
        var sum = 0f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (px, py) = t.apply(x.toFloat() - cx, y.toFloat() - cy)
                val m = referenceSampleLuma(movL, w, h, px + cx, py + cy)
                val d = m - refL[y * w + x]
                val d2 = d * d
                sum += if (robustClip != null) minOf(d2, robustClip) else d2
            }
        }
        return sum / (w * h).toFloat()
    }

    /** Old ImagePyramid.reduce (Vec3 read per tap, Vec3 write per output pixel). */
    private fun referenceReduce(img: PixelImage): PixelImage {
        val kernel = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)
        val w = img.width; val h = img.height
        val ow = (w + 1) / 2; val oh = (h + 1) / 2
        val out = PixelImage(ow, oh)
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                var ax = 0f; var ay = 0f; var az = 0f; var wsum = 0f
                for (dy in 0 until 5) {
                    val sy = 2 * oy + dy - 2
                    if (sy < 0 || sy >= h) continue
                    for (dx in 0 until 5) {
                        val sx = 2 * ox + dx - 2
                        if (sx < 0 || sx >= w) continue
                        val wgt = kernel[dx] * kernel[dy]
                        val p = img[sx, sy]
                        ax += p.x * wgt; ay += p.y * wgt; az += p.z * wgt
                        wsum += wgt
                    }
                }
                if (wsum > 0f) out[ox, oy] = Vec3(ax / wsum, ay / wsum, az / wsum)
            }
        }
        return out
    }

    /** Old ImagePyramid.expand (Vec3 read per tap, Vec3 write per output pixel). */
    private fun referenceExpand(img: PixelImage, toWidth: Int, toHeight: Int): PixelImage {
        val kernel = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)
        val w = img.width; val h = img.height
        val out = PixelImage(toWidth, toHeight)
        for (ty in 0 until toHeight) {
            for (tx in 0 until toWidth) {
                var ax = 0f; var ay = 0f; var az = 0f; var wsum = 0f
                for (dy in 0 until 5) {
                    val syNum = ty + dy - 2
                    if (syNum % 2 != 0) continue
                    val sy = syNum / 2
                    if (sy < 0 || sy >= h) continue
                    for (dx in 0 until 5) {
                        val sxNum = tx + dx - 2
                        if (sxNum % 2 != 0) continue
                        val sx = sxNum / 2
                        if (sx < 0 || sx >= w) continue
                        val wgt = kernel[dx] * kernel[dy]
                        val p = img[sx, sy]
                        ax += p.x * wgt; ay += p.y * wgt; az += p.z * wgt
                        wsum += wgt
                    }
                }
                if (wsum > 0f) out[tx, ty] = Vec3(ax / wsum, ay / wsum, az / wsum)
            }
        }
        return out
    }

    // MARK: - Identity tests (max abs diff EXACTLY 0)

    @Test
    fun warpMatchesPairVec3Reference() {
        val img = syntheticImage(64, 48, seed = 7)
        for ((i, t) in transforms.withIndex()) {
            val flat = AffineAligner.warp(img, by = t)
            val ref = referenceWarp(img, by = t)
            assertExactlyEqual(ref.pixels, flat.pixels, "warp[transform $i]")
        }
    }

    @Test
    fun ssdWarpedMatchesPairReference() {
        val refImg = syntheticImage(48, 36, seed = 3)
        val movImg = syntheticImage(48, 36, seed = 9)
        val refL = Luma.luminance(refImg)
        val movL = Luma.luminance(movImg)
        val w = refImg.width; val h = refImg.height
        for ((i, t) in transforms.withIndex()) {
            for (clip in listOf(null, 0.02f, 0.0001f)) {
                val flat = AffineAligner.ssdWarped(movL, refL, w, h, t, clip)
                val ref = referenceSSDWarped(movL, refL, w, h, t, clip)
                assertEquals(ref, flat, absoluteTolerance = 0f,
                    message = "ssdWarped[transform $i, clip $clip] diverged from the Pair reference")
            }
        }
    }

    @Test
    fun reduceMatchesVec3Reference() {
        // Odd AND even dimensions: borders take the renormalization path.
        for ((w, h) in listOf(Pair(33, 21), Pair(32, 20), Pair(5, 5))) {
            val img = syntheticImage(w, h, seed = w * 31 + h)
            assertExactlyEqual(referenceReduce(img).pixels, ImagePyramid.reduce(img).pixels, "reduce(${w}x$h)")
        }
    }

    @Test
    fun expandMatchesVec3Reference() {
        // Expand back to odd and even targets (both parities of the interleave pattern).
        for ((w, h) in listOf(Pair(33, 21), Pair(32, 20))) {
            val small = ImagePyramid.reduce(syntheticImage(w, h, seed = w + h * 17))
            assertExactlyEqual(
                referenceExpand(small, w, h).pixels,
                ImagePyramid.expand(small, w, h).pixels,
                "expand(→${w}x$h)"
            )
        }
    }

    @Test
    fun developColorMatrixMatchesVec3Reference() {
        // Full-res develop path: reference applies the old per-pixel Vec3 mat3MulVec3.
        val w = 16; val h = 12
        val mosaic = IntArray(w * h) { i -> 150 + (i * 53) % 700 }
        val matrix = floatArrayOf(   // non-trivial, non-symmetric color matrix (column-major)
            0.9f, 0.1f, 0.05f,
            0.2f, 0.8f, 0.1f,
            -0.1f, 0.1f, 0.85f
        )
        val frame = RawSensorFrame.fromIntMosaic(
            w, h, mosaic,
            blackLevel = 64f, whiteLevel = 1024f, cfa = CFAPattern.RGGB,
            wbGains = Vec3(1.7f, 1.0f, 2.2f), colorMatrix = matrix
        )

        // Reference: demosaic, then the old Vec3-based matrix application.
        val ref = demosaic(linearizeAndBalance(frame), w, h, frame.cfa)
        val m = frame.colorMatrix
        for (i in 0 until ref.pixelCount) {
            val base = i * 3
            val v = Vec3(ref.pixels[base], ref.pixels[base + 1], ref.pixels[base + 2])
            val mv = Vec3(
                m[0] * v.x + m[3] * v.y + m[6] * v.z,
                m[1] * v.x + m[4] * v.y + m[7] * v.z,
                m[2] * v.x + m[5] * v.y + m[8] * v.z
            )
            ref.pixels[base] = mv.x; ref.pixels[base + 1] = mv.y; ref.pixels[base + 2] = mv.z
        }

        val flat = ColorPipeline.process(frame)
        assertExactlyEqual(ref.pixels, flat.pixels, "develop color matrix")
    }
}
