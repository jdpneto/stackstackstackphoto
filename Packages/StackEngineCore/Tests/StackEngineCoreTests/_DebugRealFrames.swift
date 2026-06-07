import XCTest
import simd
import ImageIO
import CoreGraphics
@testable import StackEngineCore

/// TEMPORARY offline debug harness: runs the real handheld frames dumped from the device through the
/// pipeline, prints the per-frame estimated transforms, and writes comparison images to /tmp/sss-diag.
/// Delete before merge.
final class _DebugRealFrames: XCTestCase {
    private let dir = "/tmp/sss-diag"

    func testDebugHandheldAlignment() throws {
        let fm = FileManager.default
        let paths = (0..<40).map { "\(dir)/frame\(String(format: "%02d", $0)).jpg" }.filter { fm.fileExists(atPath: $0) }
        try XCTSkipIf(paths.isEmpty, "no diag frames at \(dir)")
        let frames = try paths.map { try load($0) }
        let w = frames[0].width, h = frames[0].height
        print("DEBUG: \(frames.count) frames @ \(w)x\(h)")

        // What motion does the pipeline's (downscaled) estimate find per frame?
        let lumas = frames.map { Luma.luminance($0) }
        let refIdx = ReferenceSelection.sharpestIndex(lumas: lumas, width: w, height: h)
        let estEdge = 720
        let refSmall = downscaleOne(frames[refIdx], maxEdge: estEdge)
        let factor = Float(w) / Float(refSmall.width)
        print("DEBUG: refIdx=\(refIdx)  estSize=\(refSmall.width)x\(refSmall.height) factor=\(factor)")
        for i in frames.indices where i != refIdx {
            let movSmall = downscaleOne(frames[i], maxEdge: estEdge)
            let t = AffineAligner.estimate(reference: refSmall, moving: movSmall, translationSearch: 8, robustClip: 0.02)
            let rot = atan2(t.c, t.a) * 180 / .pi
            let scale = (t.a * t.a + t.c * t.c).squareRoot()
            print(String(format: "DEBUG: frame %d → ref:  rot=%+.3f°  scale=%.4f  tx=%+.1f ty=%+.1f (px@%d)",
                         i, rot, scale, t.tx * factor, t.ty * factor, w))
        }

        // Visual comparison: unaligned vs aligned mean, and the actual look results.
        try save(StackReducer.mean(frames), "\(dir)/_unaligned_mean.png")
        let aligned = Pipeline.alignedStack(frames, searchRange: 8)
        try save(StackReducer.mean(aligned), "\(dir)/_aligned_mean.png")
        try save(StackReducer.lighten(aligned), "\(dir)/_aligned_lighten.png")    // trails WITHOUT the motion mask
        try save(Pipeline.reduceImages(frames, mode: .noiseReduction, workingResolution: 2400), "\(dir)/_result_detail.png")
        try save(Pipeline.reduceImages(frames, mode: .lightTrails, workingResolution: 2400), "\(dir)/_result_trails.png")
        print("DEBUG: wrote comparison PNGs to \(dir)")
    }

    private func downscaleOne(_ img: PixelImage, maxEdge: Int) -> PixelImage {
        var out = img
        while max(out.width, out.height) > maxEdge { out = ImagePyramid.reduce(out) }
        return out
    }

    private enum Err: Error { case load, save }

    private func load(_ path: String) throws -> PixelImage {
        guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
              let cg = CGImageSourceCreateImageAtIndex(src, 0, nil) else { throw Err.load }
        let w = cg.width, h = cg.height
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = CGContext(data: &buf, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var px = [SIMD3<Float>](repeating: .zero, count: w * h)
        for i in 0..<(w * h) {
            px[i] = SIMD3(Float(buf[i * 4]) / 255, Float(buf[i * 4 + 1]) / 255, Float(buf[i * 4 + 2]) / 255)
        }
        return PixelImage(width: w, height: h, pixels: px)
    }

    private func save(_ img: PixelImage, _ path: String) throws {
        var buf = [UInt8](repeating: 255, count: img.width * img.height * 4)
        for i in 0..<(img.width * img.height) {
            let p = img.pixels[i]
            buf[i * 4] = UInt8(min(max(p.x, 0), 1) * 255)
            buf[i * 4 + 1] = UInt8(min(max(p.y, 0), 1) * 255)
            buf[i * 4 + 2] = UInt8(min(max(p.z, 0), 1) * 255)
        }
        let ctx = CGContext(data: &buf, width: img.width, height: img.height, bitsPerComponent: 8,
                            bytesPerRow: img.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        guard let cg = ctx.makeImage(),
              let dst = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, "public.png" as CFString, 1, nil)
        else { throw Err.save }
        CGImageDestinationAddImage(dst, cg, nil)
        CGImageDestinationFinalize(dst)
    }
}
