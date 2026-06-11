package com.jdpneto.stackengine

/**
 * A linear-light RGB image. Pixels are row-major; channels are scene-linear floats.
 *
 * Backed by a flat [FloatArray] of size `width * height * 3` with xyz interleaved
 * (i.e. pixel at (x,y) occupies indices `(y*width+x)*3 .. (y*width+x)*3+2`).
 *
 * PORTING NOTE — Swift struct copy semantics: in Swift, `var out = img` produced a
 * value-type copy. In Kotlin this is a class, so callers that relied on copy-on-assign
 * MUST call `img.copy()` explicitly at every such site.
 *
 * `Sendable` equivalent: pixels is a FloatArray (reference), but the engine never
 * shares a PixelImage across concurrent writes, so the threading contract is upheld
 * by the same discipline as the Swift port.
 */
class PixelImage(
    val width: Int,
    val height: Int,
    val pixels: FloatArray = FloatArray(width * height * 3)
) {
    init {
        require(pixels.size == width * height * 3) {
            "pixel array size mismatch: expected ${width * height * 3}, got ${pixels.size}"
        }
    }

    /** Construct with every pixel filled to [fill]. */
    constructor(width: Int, height: Int, fill: Vec3) : this(
        width, height,
        FloatArray(width * height * 3).also { arr ->
            var i = 0
            val n = width * height
            while (i < n) {
                arr[i * 3]     = fill.x
                arr[i * 3 + 1] = fill.y
                arr[i * 3 + 2] = fill.z
                i++
            }
        }
    )

    @Suppress("NOTHING_TO_INLINE")
    private inline fun index(x: Int, y: Int): Int = (y * width + x) * 3

    /** Get the pixel at (x, y). */
    operator fun get(x: Int, y: Int): Vec3 {
        val i = index(x, y)
        return Vec3(pixels[i], pixels[i + 1], pixels[i + 2])
    }

    /** Set the pixel at (x, y). */
    operator fun set(x: Int, y: Int, v: Vec3) {
        val i = index(x, y)
        pixels[i]     = v.x
        pixels[i + 1] = v.y
        pixels[i + 2] = v.z
    }

    /**
     * Produce an independent copy (value-semantics equivalent of Swift struct assignment).
     * Call this wherever Swift code wrote `var out = img` and then mutated `out`.
     */
    fun copy(): PixelImage = PixelImage(width, height, pixels.copyOf())

    /** Pixel count (width * height). */
    val pixelCount: Int get() = width * height

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PixelImage) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}
