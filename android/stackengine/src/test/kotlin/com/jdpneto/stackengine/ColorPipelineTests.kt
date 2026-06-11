package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorPipelineTests {

    @Test
    fun testLinearizeAndBalanceAppliesPerChannelGain() {
        // 2x2 RGGB, all raw=544 -> linear 0.5 each, with gains R=2, G=1, B=4
        val frame = RawSensorFrame.fromIntMosaic(
            width = 2, height = 2,
            mosaic = intArrayOf(544, 544, 544, 544),
            blackLevel = 64f, whiteLevel = 1024f, cfa = CFAPattern.RGGB,
            wbGains = Vec3(2f, 1f, 4f)
        )
        val lin = linearizeAndBalance(frame)
        // sites: (0,0)=R*2=1.0, (1,0)=G*1=0.5, (0,1)=G*1=0.5, (1,1)=B*4=2.0
        assertEquals(1.0f, lin[0], absoluteTolerance = 1e-6f)
        assertEquals(0.5f, lin[1], absoluteTolerance = 1e-6f)
        assertEquals(0.5f, lin[2], absoluteTolerance = 1e-6f)
        assertEquals(2.0f, lin[3], absoluteTolerance = 1e-6f)
    }

    @Test
    fun testBilinearDemosaicInteriorUniformColor() {
        // 4x4 RGGB. Set every R site=0.8, G site=0.5, B site=0.2 (already linear+balanced).
        val w = 4; val h = 4
        val lin = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                lin[y * w + x] = when (cfaColor(CFAPattern.RGGB, x, y)) {
                    CFAColor.RED   -> 0.8f
                    CFAColor.GREEN -> 0.5f
                    CFAColor.BLUE  -> 0.2f
                }
            }
        }
        val img = demosaic(lin, w, h, CFAPattern.RGGB)
        // Interior pixels (1,1), (2,2) must reconstruct the constant color exactly.
        for ((x, y) in listOf(Pair(1, 1), Pair(2, 2), Pair(2, 1), Pair(1, 2))) {
            assertEquals(0.8f, img[x, y].x, absoluteTolerance = 1e-5f, message = "R at $x,$y")
            assertEquals(0.5f, img[x, y].y, absoluteTolerance = 1e-5f, message = "G at $x,$y")
            assertEquals(0.2f, img[x, y].z, absoluteTolerance = 1e-5f, message = "B at $x,$y")
        }
    }

    @Test
    fun testBilinearDemosaicNonUniformNeighbors() {
        // 4x4 RGGB, deliberately non-linear field so the exact neighbor sets matter.
        val w = 4; val h = 4
        val lin = floatArrayOf(
            10f, 25f, 30f, 12f,
            40f, 60f, 55f, 18f,
            70f, 100f, 90f, 33f,
            22f, 44f, 66f, 88f,
        )
        val img = demosaic(lin, w, h, CFAPattern.RGGB)
        // Green site (1,2): horizontal neighbors are R, vertical neighbors are B.
        assertEquals(80f,  img[1, 2].x, absoluteTolerance = 1e-3f)    // r = (70+90)/2
        assertEquals(100f, img[1, 2].y, absoluteTolerance = 1e-3f)    // g = site value
        assertEquals(52f,  img[1, 2].z, absoluteTolerance = 1e-3f)    // b = (60+44)/2
        // Red site (2,2): g = 4-neighbor avg, b = 4-diagonal avg.
        assertEquals(90f,   img[2, 2].x, absoluteTolerance = 1e-3f)   // r = site
        assertEquals(63.5f, img[2, 2].y, absoluteTolerance = 1e-3f)   // g = (100+33+55+66)/4
        assertEquals(52.5f, img[2, 2].z, absoluteTolerance = 1e-3f)   // b = (60+18+44+88)/4
    }

    @Test
    fun testProcessAppliesColorMatrix() {
        // 4x4 RGGB uniform raw -> linear 0.5 at every site; gains=1.
        // Color matrix swaps R and B channels.
        val w = 4; val h = 4
        val mosaic = IntArray(w * h) { 544 }   // (544-64)/(1024-64)=0.5

        // Swift simd_float3x3(columns: (col0, col1, col2)) where:
        //   col0 = (0,0,1)  -> out.x = col0·in = 0*in.x+0*in.y+1*in.z = in.z
        //   col1 = (0,1,0)  -> out.y = col1·in = 0*in.x+1*in.y+0*in.z = in.y
        //   col2 = (1,0,0)  -> out.z = col2·in = 1*in.x+0*in.y+0*in.z = in.x
        // That is the B↔R swap: out = (in.z, in.y, in.x)
        // Column-major storage: [c0.x,c0.y,c0.z, c1.x,c1.y,c1.z, c2.x,c2.y,c2.z]
        //                     = [0,0,1, 0,1,0, 1,0,0]
        val swapRB = floatArrayOf(
            0f, 0f, 1f,   // col0
            0f, 1f, 0f,   // col1
            1f, 0f, 0f    // col2
        )
        val frame = RawSensorFrame.fromIntMosaic(
            w, h, mosaic, 64f, 1024f, CFAPattern.RGGB,
            Vec3(1f, 1f, 1f), swapRB
        )
        val img = ColorPipeline.process(frame)
        // After demosaic every interior pixel ~ (0.5,0.5,0.5); swap keeps it (0.5,0.5,0.5).
        assertEquals(0.5f, img[2, 2].x, absoluteTolerance = 1e-5f)
        // Now verify the matrix actually runs: use a non-symmetric input via gains.
        val frame2 = RawSensorFrame.fromIntMosaic(
            w, h, mosaic, 64f, 1024f, CFAPattern.RGGB,
            Vec3(0.2f, 0.5f, 0.8f), swapRB
        )
        val img2 = ColorPipeline.process(frame2)
        // Pre-matrix interior ~ (0.2*0.5, 0.5*0.5, 0.8*0.5)=(0.1,0.25,0.4); swapRB -> (0.4,0.25,0.1)
        assertEquals(0.4f,  img2[2, 2].x, absoluteTolerance = 1e-5f)
        assertEquals(0.25f, img2[2, 2].y, absoluteTolerance = 1e-5f)
        assertEquals(0.1f,  img2[2, 2].z, absoluteTolerance = 1e-5f)
    }

    @Test
    fun testBinDemosaicCombines2x2Quad() {
        // RGGB linearized mosaic: R=0.8, G=0.4, G=0.6, B=0.2 → one RGB pixel (0.8, mean(0.4,0.6)=0.5, 0.2).
        val lin = floatArrayOf(0.8f, 0.4f, 0.6f, 0.2f)
        val out = binDemosaic(lin, 2, 2, CFAPattern.RGGB)
        assertEquals(1, out.width)
        assertEquals(1, out.height)
        assertEquals(0.8f, out[0, 0].x, absoluteTolerance = 1e-5f)
        assertEquals(0.5f, out[0, 0].y, absoluteTolerance = 1e-5f)
        assertEquals(0.2f, out[0, 0].z, absoluteTolerance = 1e-5f)
    }

    @Test
    fun testBinDemosaicHandlesNonRGGBPattern() {
        // BGGR: (0,0)=B, (1,0)=G, (0,1)=G, (1,1)=R → still resolves to R=0.8, G=0.5, B=0.2.
        val out = binDemosaic(floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f), 2, 2, CFAPattern.BGGR)
        assertEquals(0.8f, out[0, 0].x, absoluteTolerance = 1e-5f)
        assertEquals(0.5f, out[0, 0].y, absoluteTolerance = 1e-5f)
        assertEquals(0.2f, out[0, 0].z, absoluteTolerance = 1e-5f)
    }

    @Test
    fun testProcessBinnedHalvesResolutionAndDevelops() {
        val w = 8; val h = 8
        val frame = RawSensorFrame.fromIntMosaic(
            w, h, IntArray(w * h) { 600 }, 64f, 1024f, CFAPattern.RGGB
        )
        val out = ColorPipeline.processBinned(frame)
        assertEquals(4, out.width)    // half resolution
        assertEquals(4, out.height)
        assertTrue(out[2, 2].x.isFinite() && out[2, 2].x > 0f)
    }
}
