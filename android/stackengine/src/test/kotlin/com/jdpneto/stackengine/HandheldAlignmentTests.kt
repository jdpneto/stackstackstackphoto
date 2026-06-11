package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Handheld capture rotates the frame (not just shifts it). These tests synthesize hand-shake —
 * a small per-frame rotation + translation of a sharp static scene — and verify the similarity
 * aligner registers it, so combining the frames keeps static detail sharp (the tripod-free goal).
 */
class HandheldAlignmentTests {

    /**
     * A sharp, NON-periodic pattern so the aligner has unique features to lock onto (a periodic
     * checkerboard would be ambiguous). 6 px blocks toggled by a spatial hash.
     */
    private fun staticScene(w: Int, h: Int, block: Int = 6): PixelImage {
        val img = PixelImage(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val hash = (x / block * 73856093) xor (y / block * 19349663)
                val v: Float = if (hash and 1 == 0) 0.85f else 0.15f
                img[x, y] = Vec3(v, v, v)
            }
        }
        return img
    }

    /**
     * Mean per-pixel squared spread across frames in the central region (away from warp borders).
     * Low spread ⇒ the frames agree ⇒ combining them keeps that region sharp.
     */
    private fun centralSpread(imgs: List<PixelImage>, margin: Int): Float {
        val w = imgs[0].width; val h = imgs[0].height
        var sum = 0f; var n = 0
        for (y in margin until (h - margin)) {
            for (x in margin until (w - margin)) {
                var mean = 0f
                for (im in imgs) { mean += im[x, y].x }
                mean /= imgs.size.toFloat()
                for (im in imgs) { val d = im[x, y].x - mean; sum += d * d }
                n++
            }
        }
        return sum / n.toFloat()
    }

    @Test
    fun testHandShakeRotationIsAlignedOut() {
        val w = 72; val h = 72
        val scene = staticScene(w, h)
        // Hand shake: small rotations (≈ ±1°) + a couple px of translation per frame.
        data class Shake(val r: Float, val tx: Float, val ty: Float)
        val shakes = listOf(
            Shake(0f, 0f, 0f), Shake(0.018f, 1f, -1f), Shake(-0.020f, -1f, 1f),
            Shake(0.012f, 2f, 0f), Shake(-0.015f, 0f, 2f)
        )
        val frames = shakes.map {
            AffineAligner.warp(scene, by = Transform2D.similarity(1f, it.r, it.tx, it.ty))
        }

        val aligned = Pipeline.alignedStack(frames, searchRange = 6)

        val before = centralSpread(frames, margin = 14)
        val after = centralSpread(aligned, margin = 14)
        // Registration should sharply reduce frame disagreement in the static region — translation
        // alone could not, because the shake includes rotation.
        assertTrue(after < before * 0.5f,
            "similarity alignment should at least halve static-region spread (before $before, after $after)")
    }

    @Test
    fun testMotionMaskBlendsStaticVsMoving() {
        // Compositing logic only (no alignment): static pixels barely vary → take `base`; a pixel
        // that moves across frames → take `effect`.
        val w = 40; val h = 40
        val base = staticScene(w, h, block = 4)
        // a distinct "effect" image
        val effectPixels = base.pixels.copyOf()
        for (i in effectPixels.indices) effectPixels[i] += 0.3f
        val effect = PixelImage(w, h, effectPixels)
        val frames = (0 until 4).map { k ->
            val f = base.copy()
            f[6 + k, 6] = Vec3(1f, 1f, 1f)   // a bright spot moving along y=6
            f
        }.toMutableList<PixelImage>()
        val mask = MotionComposite.motionMask(frames, lo = 0.05f, hi = 0.15f, smoothRadius = 0)
        val out = MotionComposite.blend(staticBase = base, effect = effect, mask = mask)
        assertTrue(abs(out[20, 30].x - base[20, 30].x) <= 0.01f, "static pixel → base")
        assertTrue(out[8, 6].x > base[8, 6].x + 0.1f, "a pixel the spot passed through → toward effect")
    }

    @Test
    fun testLightTrailsStreaksMotionAndKeepsStaticSharp() {
        // End-to-end light-trails: a bright object sweeps the top of an otherwise-static scene.
        val w = 80; val h = 80
        val scene = staticScene(w, h)
        val frames = (0 until 6).map { k ->
            val f = scene.copy()
            val cx = 6 + k * 11
            for (dy in 0 until 6) for (dx in 0 until 6) {
                val x = cx + dx; val y = 5 + dy
                if (x in 0 until w && y in 0 until h) { f[x, y] = Vec3(1f, 1f, 1f) }
            }
            f
        }

        val result = Pipeline.reduceImages(frames, mode = StackMode.LIGHT_TRAILS)
        // (a) Static region (bottom): kept sharp/clean — equal to the scene (mask ≈ 0 → mean).
        var staticDiff = 0f; var nStatic = 0
        for (y in 30 until 76) for (x in 6 until 74) { staticDiff += abs(result[x, y].x - scene[x, y].x); nStatic++ }
        assertTrue(staticDiff / nStatic.toFloat() < 0.03f, "static region should stay sharp (≈ scene)")
        // (b) The object's path streaks bright (mask ≈ 1 → lighten).
        var pathBright = 0f; var nPath = 0
        for (y in 5 until 11) for (x in 6 until 80) { pathBright += result[x, y].x; nPath++ }
        assertTrue(pathBright / nPath.toFloat() > 0.45f, "the moving object's path should streak bright")
    }

    @Test
    fun testStationaryFramesAreUnchangedEnough() {
        // No shake ⇒ already-aligned frames must stay sharp (no regression / over-warping).
        val w = 72; val h = 72
        val scene = staticScene(w, h)
        val frames = List(4) { scene }
        val aligned = Pipeline.alignedStack(frames, searchRange = 6)
        val result = StackReducer.mean(aligned)
        // The combined result should be ~as sharp as a single frame (identity alignment, no blur).
        val single = Luma.sharpness(scene)
        val combined = Luma.sharpness(result)
        assertTrue(combined > single * 0.9f,
            "stationary frames should not be softened by alignment (single $single, combined $combined)")
    }
}
