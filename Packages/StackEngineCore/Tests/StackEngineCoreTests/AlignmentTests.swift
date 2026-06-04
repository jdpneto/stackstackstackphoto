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
}
