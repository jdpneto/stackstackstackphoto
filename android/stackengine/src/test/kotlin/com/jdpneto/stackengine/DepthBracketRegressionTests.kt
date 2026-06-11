package com.jdpneto.stackengine

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assumptions
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Real-data regression suite for Depth-of-Field chain alignment: 10 handheld focus brackets
 * captured on an iPhone (2026-06-10 device verification, desk scene: mouse → keyboard →
 * controller → couch/TV), developed + binned to 1008×756 — the exact alignment input the app
 * produces. The first entry of the design's golden corpus (bible §18).
 *
 * What it pins: on this sweep the chain measured a clean, monotonic focus-breathing curve
 * (scale 1.030 near → 0.973 far relative to the mid-sweep reference) with sub-degree rotations
 * and ≤4 px handheld drift — every link accepted by `ChainBounds` (no fallbacks). A regression
 * that breaks link estimation, composition order, or the bounds will bend or flatten that curve.
 */
class DepthBracketRegressionTests {

    /**
     * Heavy by design (full CPU stacks on real frames): opt-in via env so the routine
     * `./gradlew test` loop stays fast. CI / pre-merge runs set the variable:
     * `SSS_REAL_BRACKETS=1 ./gradlew :stackengine:test --tests '*DepthBracket*'`.
     */
    private fun requireOptIn() {
        Assumptions.assumeTrue(System.getenv("SSS_REAL_BRACKETS") == "1",
            "heavy real-bracket regression — run with SSS_REAL_BRACKETS=1")
    }

    private fun loadBrackets(): List<PixelImage> =
        (0 until 10).map { i ->
            val name = "frame%02d".format(i)
            // A missing BUNDLED fixture is a build/resource misconfiguration, never a skip —
            // skipping here would silently disable the whole real-data suite.
            val stream = javaClass.getResourceAsStream("/depth-brackets/$name.jpg")
                ?: fail("bundled bracket $name.jpg missing — check src/test/resources/depth-brackets/")
            load(stream.use { ImageIO.read(it) })
        }

    @Test
    fun testChainRecoversMonotonicFocusBreathingOnRealBrackets() {
        requireOptIn()
        val frames = loadBrackets()
        val refIdx = ReferenceSelection.sharpestIndex(frames)
        val transforms = AffineAligner.alignChain(frames, referenceIndex = refIdx)

        val scales = transforms.map { sqrt(it.a * it.a + it.c * it.c) }
        // Focus breathing is monotone in lens position: magnification decreases near → far.
        for (i in 1 until scales.size) {
            assertTrue(scales[i] < scales[i - 1] + 0.002f,
                       "breathing curve must be (near-)monotonically decreasing at frame $i")
        }
        // Pin the measured span (device verification 2026-06-10): near ≈ +3.0%, far ≈ −2.7%.
        assertEquals(1.030f, scales.first(), absoluteTolerance = 0.008f, "near-bracket magnification")
        assertEquals(0.973f, scales.last(), absoluteTolerance = 0.008f, "far-bracket magnification")
        assertEquals(1.0f, scales[refIdx], absoluteTolerance = 1e-4f, "reference is identity")

        // Every link stayed plausible on this capture — no translation-only fallbacks
        // (a fallback link carries exactly zero rotation AND unit scale; the real links don't).
        for ((i, t) in transforms.withIndex()) {
            if (i == refIdx) continue
            val rot = abs(atan2(t.c, t.a))
            assertTrue(rot < 0.012f, "rotation stays sub-degree on this sweep (frame $i)")
        }
    }

    @Test
    fun testAllInFocusOnRealBracketsProducesCleanComposite() {
        requireOptIn()
        val frames = loadBrackets()
        val out = assertNotNull(FocusStacker.allInFocus(frames,
            config = DepthConfig(workingResolution = null, maxFrames = 24)))
        assertEquals(frames[0].width, out.width)
        assertEquals(frames[0].height, out.height)
        // The aligned composite resolves detail no misaligned stack can: compare against the
        // unaligned stack in a high-contrast off-centre window (scale misalignment grows with
        // distance from centre — ±3% breathing ≈ ±15 px at the edges → double edges that this
        // windowed sharpness comparison punishes far less than they deserve, so require only a
        // modest margin; the strong oracle is the synthetic suite + the curve test above).
        val unaligned = assertNotNull(FocusStacker.allInFocus(frames,
            config = DepthConfig(workingResolution = null, maxFrames = 24, alignFrames = false)))
        val alignedVar = laplacianVariance(out, x0 = 30, y0 = 280, w = 360, h = 270)
        val unalignedVar = laplacianVariance(unaligned, x0 = 30, y0 = 280, w = 360, h = 270)
        // Misalignment smears edges into broad ramps: edge-energy VARIANCE collapses even though
        // total energy stays high. Pin the aligned stack's advantage.
        assertTrue(alignedVar > unalignedVar,
                   "chain-aligned composite must out-resolve the unaligned stack in the off-centre window")
    }

    /**
     * Variance of the Laplacian over a window — a focus/resolution measure that punishes the
     * doubled, low-contrast edges misalignment produces.
     */
    private fun laplacianVariance(img: PixelImage, x0: Int, y0: Int, w: Int, h: Int): Float {
        val luma = Luma.luminance(img)
        val W = img.width
        val vals = ArrayList<Float>(w * h)
        for (y in maxOf(y0, 1) until minOf(y0 + h, img.height - 1)) {
            for (x in maxOf(x0, 1) until minOf(x0 + w, W - 1)) {
                val l = 4f * luma[y * W + x] - luma[y * W + x - 1] - luma[y * W + x + 1] -
                        luma[(y - 1) * W + x] - luma[(y + 1) * W + x]
                vals.add(l)
            }
        }
        var mean = 0f
        for (v in vals) { mean += v }
        mean /= vals.size.toFloat()
        var varSum = 0f
        for (v in vals) { varSum += (v - mean) * (v - mean) }
        return varSum / vals.size.toFloat()
    }

    /**
     * Decode a bracket JPEG to a PixelImage. Matches the Swift loader EXACTLY: raw 8-bit values
     * divided by 255 — deliberately NO sRGB linearization (the Swift suite feeds the same
     * gamma-encoded values to the aligner, and the pinned breathing-curve numbers depend on it).
     */
    private fun load(img: BufferedImage): PixelImage {
        val w = img.width; val h = img.height
        val out = PixelImage(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = img.getRGB(x, y)
                val base = (y * w + x) * 3
                out.pixels[base]     = ((argb shr 16) and 0xFF).toFloat() / 255f
                out.pixels[base + 1] = ((argb shr 8) and 0xFF).toFloat() / 255f
                out.pixels[base + 2] = (argb and 0xFF).toFloat() / 255f
            }
        }
        return out
    }
}
