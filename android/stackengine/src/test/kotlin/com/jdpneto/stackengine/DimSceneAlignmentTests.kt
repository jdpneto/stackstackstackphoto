package com.jdpneto.stackengine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the dim-scene / bright-mover misregistration found on device (investigation
 * 2026-06-12): a handheld Smooth Motion burst of a DIM living room with a bright TV came out as
 * discrete displaced echoes of the TV frame/subtitles, with static furniture smeared.
 *
 * Mechanism (validated by the investigation's probe suite): the whole-frame robust clip was an
 * ABSOLUTE linear-luma residual cap (0.02 ≈ |Δluma| 0.14). In a dim room the static background
 * (luma 0.01–0.1) only produces residuals ≲ 0.01 — negligible cost — while the bright TV
 * (luma 0.2–0.97) dominates: its scene cuts saturate at the cap (still 20–200× the background
 * signal) and its smooth pans stay UNDER the cap and are fully trusted. The coarse integer seed
 * additionally ran UNclipped SSD. Net effect: the optimizer fits the TV's content motion as
 * camera motion (43 px misregistration at 1.5 px of true drift on device; mean error 184 px on
 * this synthetic repro with clip 0.02 vs 1.2 px with clip 1e-4).
 *
 * This test is the Kotlin port (1:1) of the Swift regression (DimSceneAlignmentTests.swift),
 * itself the investigation's core repro (probe 2), SHRUNK from 1024 px / 20 frames to
 * 512 px / 10 frames so the suite stays fast. The mechanism — dim multi-scale static scene +
 * bright moving "TV" region (~26% of the frame, pans + cuts) + static bright subtitle bar +
 * high-ISO noise + cumulative handheld drift (~27 px @ 512, the same ~129 px at the device's
 * working frame, + 0.7° roll ramp, frame-0 anchor) — is preserved, and red/green was
 * re-verified at this size on Kotlin:
 *   OLD code path (fixed clip 0.02, plain-SSD integer seed, no plausibility check), measured
 *   on Kotlin 2026-06-12: mean error 40.58 px, worst 163.22 px @ 512 — identical to the Swift
 *   red baseline (40.58 / 163.22), confirming the scene-generator port is faithful.
 *   Fixed path (robust pre-pass + scene-adaptive clip + bounded fallback): see the asserts.
 */
class DimSceneAlignmentTests {

    // MARK: - Geometry / poses

    /** Camera pose for one frame: roll [theta] (radians) about the image centre + scene-px translation. */
    private data class Pose(val theta: Float, val tx: Float, val ty: Float)

    private val w = 512; private val h = 384
    private val cx: Float get() = (w - 1).toFloat() / 2f
    private val cy: Float get() = (h - 1).toFloat() / 2f

    /**
     * The scene "furniture" below is authored in the investigation's 1024-px coordinates;
     * [tex] maps a frame pixel onto that texture (512-px frame → ×2).
     */
    private val tex: Float get() = 1024f / w.toFloat()

    /**
     * 10-frame / ~2 s handheld drift: a smooth, mostly-monotonic pan reaching ~27 px at this
     * 512-px frame (~129 px at the device's working frame), gated-magnitude vertical sway, a
     * slight roll ramp to ~0.7°, plus per-frame tremor jitter. Frame 0 is the anchor (identity),
     * matching the streaming paths' contract.
     */
    private fun driftPoses(n: Int = 10): List<Pose> = (0 until n).map { i ->
        if (i == 0) return@map Pose(theta = 0f, tx = 0f, ty = 0f)
        val u = i.toFloat() / (n - 1).toFloat()
        val jx = 1.5f * sin(3.7f * i.toFloat())          // per-frame jitter (tremor), deterministic
        val jy = 1.2f * sin(2.9f * i.toFloat() + 1f)
        Pose(theta = 0.012f * u + 0.0015f * sin(2.1f * i.toFloat()),   // roll ramp to ~0.7°
            tx = 27f * u.pow(1.3f) + jx,
            ty = 7f * sin(PI.toFloat() * u) + jy)
    }

    /** Scene coords of frame-i pixel q: s = R_theta(q − c) + c + T. */
    private fun sceneCoords(p: Pose, x: Float, y: Float): Pair<Float, Float> {
        val vx = x - cx; val vy = y - cy
        val co = cos(p.theta); val si = sin(p.theta)
        return Pair(co * vx - si * vy + cx + p.tx, si * vx + co * vy + cy + p.ty)
    }

    /**
     * Ground-truth registration transform for [p] (what a perfect estimate should return):
     * t.apply(v) = R_{−theta}(v − T) — derived from warp()'s convention
     * out[x,y] = moving.sample(t.apply(q − c) + c).
     */
    private fun groundTruth(p: Pose): Transform2D {
        val co = cos(p.theta); val si = sin(p.theta)
        return Transform2D.similarity(scale = 1f, rotation = -p.theta,
            tx = -(co * p.tx + si * p.ty),
            ty = -(-si * p.tx + co * p.ty))
    }

    /** Max displacement disagreement (px) between two transforms over the frame corners + centre. */
    private fun transformError(est: Transform2D, gt: Transform2D): Float {
        val pts = listOf(
            Pair(0f, 0f), Pair(-cx, -cy), Pair(cx, -cy), Pair(-cx, cy), Pair(cx, cy)
        )
        var worst = 0f
        for ((vx, vy) in pts) {
            val e = est.apply(vx, vy); val g = gt.apply(vx, vy)
            val dx = e.first - g.first; val dy = e.second - g.second
            worst = max(worst, sqrt(dx * dx + dy * dy))
        }
        return worst
    }

    // MARK: - Dim-living-room scene

    private data class RectF(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        fun contains(x: Float, y: Float): Boolean = x >= x0 && x < x1 && y >= y0 && y < y1
        fun inset(d: Float): RectF = RectF(x0 + d, y0 + d, x1 - d, y1 - d)
    }

    // Scene-coordinate furniture (frame 0 == scene coords). All luma is LINEAR light: the dim
    // room sits at ~0.01–0.12; the TV at ~0.2–0.97.
    private val tvOuter = RectF(x0 = 360f, y0 = 200f, x1 = 790f, y1 = 520f)       // bezel (static, dark)
    private val tvContent = RectF(x0 = 374f, y0 = 214f, x1 = 776f, y1 = 506f)     // emissive content (changes per frame)
    private val subtitle = RectF(x0 = 430f, y0 = 455f, x1 = 720f, y1 = 480f)      // STATIC bright bar inside the content

    private fun hash01(ix: Int, iy: Int): Float {
        var n = (ix * 73856093) xor (iy * 19349663)   // Kotlin Int * wraps like Swift &*
        n = n xor (n shr 13); n *= 1274126177; n = n xor (n shr 16)
        return (n and 0xFFFF).toFloat() / 65535f
    }

    private fun hash01(fx: Float, fy: Float): Float = hash01(
        (if (fx.isFinite()) fx.coerceIn(-1e6f, 1e6f) else 0f).toInt(),
        (if (fy.isFinite()) fy.coerceIn(-1e6f, 1e6f) else 0f).toInt()
    )

    /** Static (non-TV-content) scene luma at scene coords — dim room, multi-scale structure. */
    private fun staticLuma(sx: Float, sy: Float): Float {
        var v = 0.018f + 0.012f * sin(sx * 0.013f) * sin(sy * 0.017f)         // walls, slow shading
        v += (hash01(sx / 4f, sy / 4f) - 0.5f) * 0.012f                       // fine wall texture
        // doorway: bright static vertical strip (sharpness probe in the static region)
        if (sx >= 60f && sx < 86f) v = 0.12f + (hash01(sx, sy / 3f) - 0.5f) * 0.01f
        // sofa + shelf: mid-dark blocks
        if (sx >= 820f && sx < 1010f && sy >= 420f && sy < 740f) v = 0.05f + (hash01(sx / 6f, sy / 6f) - 0.5f) * 0.02f
        if (sx >= 120f && sx < 330f && sy >= 80f && sy < 150f) v = 0.06f + (hash01(sx / 5f, sy / 5f) - 0.5f) * 0.025f
        // plant: speckled leaves
        val dx = sx - 150f; val dy = sy - 540f
        if (dx * dx + dy * dy < 110f * 110f) v = 0.035f + (hash01(sx / 3f, sy / 3f) - 0.5f) * 0.05f
        // TV bezel (content interior is overridden by the per-frame renderer)
        if (tvOuter.contains(sx, sy)) v = 0.006f
        return min(max(v, 0.002f), 0.95f)
    }

    /** TV content at frame i: scene cuts every 3 frames + a moving pattern + STATIC subtitle. */
    private fun tvLuma(i: Int, sx: Float, sy: Float): Float {
        if (subtitle.contains(sx, sy)) return 0.85f                           // static subtitles
        val cuts = floatArrayOf(0.18f, 0.50f, 0.28f, 0.62f)
        val lvl = cuts[min(i / 3, cuts.size - 1)]
        val v = lvl + 0.22f * sin(0.045f * sx + 0.08f * sy + 1.9f * i.toFloat())
        return min(max(v, 0.01f), 0.97f)
    }

    private fun sceneLuma(i: Int, sx: Float, sy: Float): Float =
        if (tvContent.contains(sx, sy)) tvLuma(i, sx, sy) else staticLuma(sx, sy)

    /** Deterministic Gaussian noise source (LCG + Box–Muller) — high-ISO sensor noise stand-in. */
    private class Rng(var state: Long) {
        fun next01(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L   // Long wraps like Swift &*/&+
            return (state ushr 40).toFloat() * (1.0f / 16777216.0f)
        }
        fun gaussian(): Float {
            val u1 = max(next01(), 1e-7f); val u2 = next01()
            return sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2)
        }
    }

    private fun renderFrame(i: Int, pose: Pose, noiseSigma: Float = 0.008f): PixelImage {
        val img = PixelImage(w, h)
        val rng = Rng(state = (1000 + i).toLong())
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (sx, sy) = sceneCoords(pose, x.toFloat(), y.toFloat())
                val v = max(sceneLuma(i, sx * tex, sy * tex) + rng.gaussian() * noiseSigma, 0f)
                img[x, y] = Vec3(v, v, v)
            }
        }
        return img
    }

    /** The static scene rendered at the anchor pose (the ideal sharp background for PSNR). */
    private fun staticTruthAtAnchor(): PixelImage {
        val img = PixelImage(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = staticLuma(x.toFloat() * tex, y.toFloat() * tex)
                img[x, y] = Vec3(v, v, v)
            }
        }
        return img
    }

    // MARK: - Metrics

    /**
     * PSNR (peak 1.0) of [img] vs [truth] over the static region: border + dilated TV excluded.
     * The border and the TV exclusion are in texture (1024) coordinates, like the furniture.
     */
    private fun staticRegionPSNR(img: PixelImage, truth: PixelImage): Float {
        val ex = tvOuter.inset(-30f)
        val border = (110f / tex).toInt()
        var sum = 0.0
        var n = 0
        for (y in border until (h - border)) {
            for (x in border until (w - border)) {
                if (ex.contains(x.toFloat() * tex, y.toFloat() * tex)) continue
                val d = (img[x, y].x - truth[x, y].x).toDouble()
                sum += d * d; n++
            }
        }
        return (10.0 * log10(1.0 / (sum / n.toDouble()))).toFloat()
    }

    /**
     * The exact per-frame transform the product paths compute: downscale both frames to the
     * estimate edge, run the shared whole-frame helper, translation scaled back up.
     */
    private fun productAnchorTransform(anchor: PixelImage, moving: PixelImage): Transform2D {
        fun small(img: PixelImage): PixelImage {
            var out = img
            while (maxOf(out.width, out.height) > Pipeline.alignmentEstimateEdge) { out = ImagePyramid.reduce(out) }
            return out
        }
        val refSmall = small(anchor); val movSmall = small(moving)
        val factor = anchor.width.toFloat() / refSmall.width.toFloat()
        return Pipeline.estimateWholeFrameAlignment(
            referenceSmall = refSmall, movingSmall = movSmall,
            factor = factor, searchRange = 8
        )
    }

    // MARK: - Test

    @Test
    fun testDimRoomBrightTvDriftBurstStaysRegistered() {
        val poses = driftPoses()
        val frames = parallelMap(poses.indices.toList()) { renderFrame(it, poses[it]) }

        // (1) Per-frame whole-frame registration accuracy through the shared product helper —
        // the SAME transform every product path (batch + both streaming) computes per frame.
        // Computed once, in parallel, and reused for the quality gate below so this stays a
        // tolerable cost in the routine test loop.
        val transforms = parallelMap((1 until frames.size).toList()) { productAnchorTransform(frames[0], frames[it]) }
        val errs = (1 until frames.size).map { transformError(transforms[it - 1], groundTruth(poses[it])) }
        val meanErr = errs.sum() / errs.size.toFloat()
        val worstErr = errs.max()
        println(String.format("DimScene: per-frame anchor error mean %.2f px, worst %.2f px", meanErr, worstErr))

        assertTrue(meanErr < 3f,
            "whole-frame alignment must lock onto the dim static room, not the bright TV's content motion (mean error $meanErr px)")
        assertTrue(worstErr < 8f,
            "no frame may be hijacked by the TV's pans/cuts (worst error $worstErr px)")

        // (2) Static-region quality gate — the user-visible "smeared furniture / echoed TV"
        // symptom. The aligned mean below is exactly what the streaming Smooth Motion path folds
        // from these same per-frame transforms (the streaming wiring itself is pinned by
        // PipelineStreamingTests; re-running the serial streaming entry point here would just
        // recompute the 19 estimates a second time).
        val truth = staticTruthAtAnchor()
        val alignedFrames = parallelMap(frames.indices.toList()) { i ->
            if (i == 0) frames[0] else AffineAligner.warp(frames[i], by = transforms[i - 1])
        }
        val result = StackReducer.mean(alignedFrames)
        val noAlign = StackReducer.mean(frames)
        val alignedPSNR = staticRegionPSNR(result, truth)
        val baselinePSNR = staticRegionPSNR(noAlign, truth)
        println(String.format("DimScene: static-region PSNR no-align %.1f dB, aligned %.1f dB", baselinePSNR, alignedPSNR))

        assertTrue(alignedPSNR > baselinePSNR + 6f,
            "aligned smooth-motion stack must clearly out-resolve the unaligned mean in the static region " +
                "(no-align $baselinePSNR dB, aligned $alignedPSNR dB)")
    }
}
