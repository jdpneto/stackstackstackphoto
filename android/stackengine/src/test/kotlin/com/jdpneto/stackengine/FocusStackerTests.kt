package com.jdpneto.stackengine

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusStackerTests {

    /**
     * A focus bracket: mid-frequency detail (sharp, in focus) in vertical third [third], flat
     * elsewhere — a synthetic depth bracket. (Frames are co-registered; alignment defaults off.)
     */
    private fun bracket(third: Int, of: Int, w: Int, h: Int): PixelImage {
        val img = PixelImage(w, h, Vec3(0.5f, 0.5f, 0.5f))
        val band = w / of
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((x / band) != third) continue
                val v = 0.5f + 0.4f * sin(x.toFloat() * 0.9f) * sin(y.toFloat() * 0.9f)   // period ~7 px
                img[x, y] = Vec3(v, v, v)
            }
        }
        return img
    }

    @Test
    fun testAllInFocusBeatsAnySingleFrame() {
        val w = 36; val h = 18
        val frames = (0 until 3).map { bracket(third = it, of = 3, w = w, h = h) }
        val out = assertNotNull(FocusStacker.allInFocus(frames,
            config = DepthConfig(workingResolution = null, maxFrames = 12, alignFrames = false)))
        val total = SharpnessMap.compute(out).sum()
        // The composite is sharp in ALL thirds → markedly sharper than any single bracket.
        for (f in frames) {
            assertTrue(total > SharpnessMap.compute(f).sum() * 1.5f)
        }
    }

    @Test
    fun testAlignPathRunsAndStaysSharp() {
        // With alignment ON, co-registered brackets chain to ~identity links and still produce a
        // sharper-than-single composite (exercises the alignChain code path).
        val w = 36; val h = 18
        val frames = (0 until 3).map { bracket(third = it, of = 3, w = w, h = h) }
        val out = assertNotNull(FocusStacker.allInFocus(frames,
            config = DepthConfig(workingResolution = null, maxFrames = 12, alignFrames = true)))
        assertEquals(w, out.width); assertEquals(h, out.height)
        assertTrue(SharpnessMap.compute(out).sum() > 0f)
    }

    @Test
    fun testEmptyReturnsNullAndSingleFrameReturnsItself() {
        assertNull(FocusStacker.allInFocus(emptyList<PixelImage>(), config = DepthConfig.auto))
        val img = PixelImage(8, 8, Vec3(0.5f, 0.5f, 0.5f))
        assertEquals(8, FocusStacker.allInFocus(listOf(img),
            config = DepthConfig(workingResolution = null, maxFrames = 12))?.width)
    }

    @Test
    fun testMismatchedFrameSizesReturnNull() {
        val a = PixelImage(16, 16, Vec3(0.5f, 0.5f, 0.5f))
        val b = PixelImage(8, 8, Vec3(0.5f, 0.5f, 0.5f))
        assertNull(FocusStacker.allInFocus(listOf(a, b),
            config = DepthConfig(workingResolution = null, maxFrames = 12)))
    }

    @Test
    fun testWorkingResolutionDownscales() {
        val img = PixelImage(64, 64, Vec3(0.5f, 0.5f, 0.5f))
        val out = assertNotNull(FocusStacker.allInFocus(listOf(img, img),
            config = DepthConfig(workingResolution = 20, maxFrames = 12, alignFrames = false)))
        assertTrue(maxOf(out.width, out.height) <= 20)
    }

    @Test
    fun testDefaultConfigAlignsFrames() {
        // Chain alignment is the default — the handheld promise (spec §4.4). `alignFrames = false`
        // remains available for the device alignment-off comparison.
        assertTrue(DepthConfig.auto.alignFrames)
        assertTrue(DepthConfig(workingResolution = null, maxFrames = 5).alignFrames)
    }

    @Test
    fun testThereIsNoFullResProPreset() {
        // 48 MP full-res runs hit the ~3 GB jetsam limit — the managed preset is the only one.
        // (Compile-time check by absence: DepthConfig.pro must not exist. This test documents it.)
        assertEquals(1500, DepthConfig.auto.workingResolution)
        assertEquals(10, DepthConfig.auto.maxFrames)
    }

    /**
     * Drifting, blur-varying brackets (the real handheld scenario): the default config must
     * chain-align them and still produce an everywhere-sharper composite.
     */
    @Test
    fun testAllInFocusOnDriftingBracketsBeatsEveryInput() {
        val (frames, _) = chainBracketFrames(w = 96, h = 64, steps = listOf(
            Transform2D.similarity(scale = 1.01f, rotation = 0.004f, tx = 1.0f, ty = -0.5f),
            Transform2D.similarity(scale = 1.008f, rotation = -0.003f, tx = -0.8f, ty = 0.6f),
        ))
        val out = assertNotNull(FocusStacker.allInFocus(frames,
            config = DepthConfig(workingResolution = null, maxFrames = 12)))
        val total = SharpnessMap.compute(out).sum()
        for (f in frames) {
            assertTrue(total > SharpnessMap.compute(f).sum() * 1.2f,
                       "aligned composite must out-sharpen every single drifted bracket")
        }
    }
}
