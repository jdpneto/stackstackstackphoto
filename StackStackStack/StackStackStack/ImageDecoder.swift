import Foundation
import ImageIO
import CoreGraphics

/// Decodes image Data into interleaved sRGB RGBA8 bytes + dimensions, optionally downscaled.
enum ImageDecoder {
    static func rgba8(from data: Data, maxPixel: Int? = nil) -> (rgba: [UInt8], width: Int, height: Int)? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let cg: CGImage?
        if let maxPixel {
            // Downscaled decode for previews — avoids decoding a full-res frame per slider release.
            let opts: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            ]
            cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
        } else {
            cg = CGImageSourceCreateImageAtIndex(src, 0, nil)
        }
        guard let image = cg else { return nil }
        let w = image.width, h = image.height
        // Bound dimensions so w*h*4 can't overflow Int or trigger an absurd allocation on a
        // malformed/crafted source (30000² ≈ 0.9 GP, far beyond any real phone capture).
        guard w > 0, h > 0, w <= 30_000, h <= 30_000 else { return nil }
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        // Explicit RGBX, big-endian byte order so byte 0 is R (matches OutputTransform's channel order)
        // and the format is fully specified for any source colour space.
        let bitmapInfo = CGImageAlphaInfo.noneSkipLast.rawValue | CGImageByteOrderInfo.order32Big.rawValue
        guard let cs = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: &bytes, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: w * 4, space: cs, bitmapInfo: bitmapInfo) else { return nil }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return (bytes, w, h)
    }
}
