package com.jdpneto.stackengine

/**
 * Lossless 90°-multiple rotation of a [PixelImage] — used to bake capture orientation upright and
 * for the editor's quarter-turn rotate. Deterministic (a pure index remap); platform-free.
 */
object ImageGeometry {

    /**
     * Rotate clockwise by `quarterTurns × 90°` (normalized mod 4; negatives wrap). 0 → the image
     * unchanged; 1 and 3 swap width/height.
     */
    fun rotated(img: PixelImage, quarterTurns: Int): PixelImage {
        val k = ((quarterTurns % 4) + 4) % 4
        if (k == 0) return img
        val w = img.width; val h = img.height
        val src = img.pixels
        if (k == 2) {
            val out = PixelImage(w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dst = ((h - 1 - y) * w + (w - 1 - x)) * 3
                    val s = (y * w + x) * 3
                    out.pixels[dst] = src[s]; out.pixels[dst + 1] = src[s + 1]; out.pixels[dst + 2] = src[s + 2]
                }
            }
            return out
        }
        val out = PixelImage(h, w)   // 90° (k==1) or 270° (k==3): dimensions swap
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx: Int; val ny: Int
                if (k == 1) { nx = h - 1 - y; ny = x }           // 90° clockwise
                else        { nx = y;         ny = w - 1 - x }   // 270° clockwise (90° counter-clockwise)
                val dst = (ny * h + nx) * 3                      // out.width == h
                val s = (y * w + x) * 3
                out.pixels[dst] = src[s]; out.pixels[dst + 1] = src[s + 1]; out.pixels[dst + 2] = src[s + 2]
            }
        }
        return out
    }
}
