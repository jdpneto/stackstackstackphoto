package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals

class RawSensorFrameTests {

    // RGGB layout (top-left 2x2 = R G / G B)
    @Test
    fun testCFAColorRGGB() {
        assertEquals(CFAColor.RED,   cfaColor(CFAPattern.RGGB, 0, 0))
        assertEquals(CFAColor.GREEN, cfaColor(CFAPattern.RGGB, 1, 0))
        assertEquals(CFAColor.GREEN, cfaColor(CFAPattern.RGGB, 0, 1))
        assertEquals(CFAColor.BLUE,  cfaColor(CFAPattern.RGGB, 1, 1))
    }

    @Test
    fun testCFAColorHandlesNegativeCoords() {
        // -1 should have the same parity as 1
        assertEquals(cfaColor(CFAPattern.RGGB, -1, 0), cfaColor(CFAPattern.RGGB, 1, 0))
        assertEquals(cfaColor(CFAPattern.RGGB, 0, -1), cfaColor(CFAPattern.RGGB, 0, 1))
    }

    @Test
    fun testCFAColorBGGR() {
        assertEquals(CFAColor.BLUE,  cfaColor(CFAPattern.BGGR, 0, 0))
        assertEquals(CFAColor.GREEN, cfaColor(CFAPattern.BGGR, 1, 0))
        assertEquals(CFAColor.GREEN, cfaColor(CFAPattern.BGGR, 0, 1))
        assertEquals(CFAColor.RED,   cfaColor(CFAPattern.BGGR, 1, 1))
    }

    @Test
    fun testCFAColorGRBG() {
        assertEquals(CFAColor.GREEN, cfaColor(CFAPattern.GRBG, 0, 0))
        assertEquals(CFAColor.RED,   cfaColor(CFAPattern.GRBG, 1, 0))
        assertEquals(CFAColor.BLUE,  cfaColor(CFAPattern.GRBG, 0, 1))
        assertEquals(CFAColor.GREEN, cfaColor(CFAPattern.GRBG, 1, 1))
    }

    @Test
    fun testCFAColorGBRG() {
        assertEquals(CFAColor.GREEN, cfaColor(CFAPattern.GBRG, 0, 0))
        assertEquals(CFAColor.BLUE,  cfaColor(CFAPattern.GBRG, 1, 0))
        assertEquals(CFAColor.RED,   cfaColor(CFAPattern.GBRG, 0, 1))
        assertEquals(CFAColor.GREEN, cfaColor(CFAPattern.GBRG, 1, 1))
    }

    @Test
    fun testLinearizeSample() {
        // (v - black) / (white - black), LOW-clamped only (highlight headroom preserved).
        assertEquals(0.0f, linearizeSample(64, 64f, 1024f), absoluteTolerance = 1e-6f)
        assertEquals(1.0f, linearizeSample(1024, 64f, 1024f), absoluteTolerance = 1e-6f)
        assertEquals(0.5f, linearizeSample(544, 64f, 1024f), absoluteTolerance = 1e-6f)
        assertEquals(0.0f, linearizeSample(0, 64f, 1024f), absoluteTolerance = 1e-6f)    // clamped to 0
        // Above white: NOT clamped — headroom is kept so white balance keeps clipped highlights neutral.
        assertEquals(1984.0f / 960.0f, linearizeSample(2048, 64f, 1024f), absoluteTolerance = 1e-6f)
    }

    @Test
    fun testLinearizeHandlesDegenerateLevels() {
        // white <= black (missing/degenerate metadata) must not divide by zero or return NaN.
        assertEquals(0.0f, linearizeSample(500, 1024f, 1024f))   // white == black
        assertEquals(0.0f, linearizeSample(500, 1024f, 64f))     // white < black
    }
}
