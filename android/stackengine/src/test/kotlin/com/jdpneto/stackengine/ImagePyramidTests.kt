package com.jdpneto.stackengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImagePyramidTests {

    @Test
    fun testReduceHalvesDimensions() {
        val img = PixelImage(8, 6, Vec3(0.5f, 0.5f, 0.5f))
        val r = ImagePyramid.reduce(img)
        assertEquals(4, r.width)   // ceil(w/2)
        assertEquals(3, r.height)  // ceil(h/2)
    }

    @Test
    fun testReduceOfConstantIsConstant() {
        val img = PixelImage(8, 8, Vec3(0.3f, 0.6f, 0.9f))
        val r = ImagePyramid.reduce(img)
        assertEquals(0.3f, r[1, 1].x, absoluteTolerance = 1e-5f)   // border-renormalized → no darkening
        assertEquals(0.6f, r[1, 1].y, absoluteTolerance = 1e-5f)
        assertEquals(0.9f, r[0, 0].z, absoluteTolerance = 1e-5f)   // even at the corner
    }

    @Test
    fun testExpandOfConstantIsConstantAtTargetSize() {
        val img = PixelImage(4, 4, Vec3(0.4f, 0.4f, 0.4f))
        val e = ImagePyramid.expand(img, 8, 7)
        assertEquals(8, e.width)
        assertEquals(7, e.height)
        assertEquals(0.4f, e[3, 3].x, absoluteTolerance = 1e-5f)
    }

    @Test
    fun testLaplacianCollapseReconstructsConstant() {
        // For a constant image, reduce/expand are exact → collapse(laplacian(img)) == img exactly.
        val img = PixelImage(12, 10, Vec3(0.25f, 0.5f, 0.75f))
        val out = ImagePyramid.collapse(ImagePyramid.laplacian(img))
        assertEquals(12, out.width)
        assertEquals(10, out.height)
        assertTrue(Metrics.maxAbsDiff(out, img) < 1e-4f)
    }
}
