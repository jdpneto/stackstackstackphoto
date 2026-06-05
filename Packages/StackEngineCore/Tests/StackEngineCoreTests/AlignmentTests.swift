import XCTest
import simd
@testable import StackEngineCore

final class AlignmentTests: XCTestCase {
    /// A diagonal gradient gives a unique SSD minimum.
    private func gradient(_ w: Int, _ h: Int) -> PixelImage {
        var img = PixelImage(width: w, height: h)
        for y in 0..<h { for x in 0..<w {
            let v = Float(x + 2 * y) / Float(w + 2 * h)
            img[x, y] = SIMD3<Float>(v, v, v)
        }}
        return img
    }
    /// moving[x,y] = ref[x - sx, y - sy] (content shifted by (sx,sy)).
    private func shifted(_ img: PixelImage, _ sx: Int, _ sy: Int) -> PixelImage {
        let w = img.width, h = img.height
        var out = PixelImage(width: w, height: h)
        for y in 0..<h { for x in 0..<w {
            let cx = min(max(x - sx, 0), w - 1), cy = min(max(y - sy, 0), h - 1)
            out[x, y] = img[cx, cy]
        }}
        return out
    }

    func testEstimateRecoversShift() {
        let ref = gradient(16, 16)
        let mov = shifted(ref, 2, -1) // content moved right 2, up 1
        // ref[x,y] = mov[x+2, y-1], so best (dx,dy) = (2,-1)
        let t = Alignment.estimateTranslation(reference: ref, moving: mov, searchRange: 4)
        XCTAssertEqual(t.dx, 2)
        XCTAssertEqual(t.dy, -1)
    }

    func testWarpAlignsToReference() {
        let ref = gradient(16, 16)
        let mov = shifted(ref, 2, -1)
        let t = Alignment.estimateTranslation(reference: ref, moving: mov, searchRange: 4)
        let warped = Alignment.warp(mov, by: t)
        // Interior must match the reference after warping.
        var maxDiff: Float = 0
        for y in 3..<13 { for x in 3..<13 {
            maxDiff = max(maxDiff, abs(warped[x, y].x - ref[x, y].x))
        }}
        XCTAssertLessThan(maxDiff, 1e-5)
    }

    /// On a larger image the coarse-to-fine pyramid kicks in (>64 px) yet must still recover the
    /// exact integer shift — this is the fast path used for on-device full-resolution alignment.
    func testCoarseToFineRecoversShiftOnLargerImage() {
        let n = 160
        func texel(_ x: Int, _ y: Int) -> Float {
            var h = UInt32(truncatingIfNeeded: x &* 73856093) ^ UInt32(truncatingIfNeeded: y &* 19349663)
            h = h &* 2654435761; h ^= h >> 13; h = h &* 2246822519; h ^= h >> 16
            return 0.15 + 0.7 * Float(h & 0xFFFF) / Float(0xFFFF)
        }
        var ref = PixelImage(width: n, height: n)
        for y in 0..<n { for x in 0..<n { ref[x, y] = SIMD3<Float>(repeating: texel(x, y)) } }
        // moving = content shifted right 5 / up 3 → mov[x,y] = ref[x-5, y+3].
        var mov = PixelImage(width: n, height: n)
        for y in 0..<n { for x in 0..<n {
            mov[x, y] = ref[min(max(x - 5, 0), n - 1), min(max(y + 3, 0), n - 1)]
        }}
        let t = Alignment.estimateTranslationCoarseToFine(referenceLuma: Luma.luminance(ref),
                                                          movingLuma: Luma.luminance(mov),
                                                          width: n, height: n, maxShift: 16)
        XCTAssertEqual(t.dx, 5); XCTAssertEqual(t.dy, -3)
    }

    func testCoarseToFineZeroMaxShiftIsIdentity() {
        let img = gradient(8, 8)
        let l = Luma.luminance(img)
        let t = Alignment.estimateTranslationCoarseToFine(referenceLuma: l, movingLuma: l,
                                                          width: 8, height: 8, maxShift: 0)
        XCTAssertEqual(t.dx, 0); XCTAssertEqual(t.dy, 0)
    }
}
