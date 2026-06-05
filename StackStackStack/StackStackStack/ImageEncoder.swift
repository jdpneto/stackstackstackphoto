import Foundation
import ImageIO
import UniformTypeIdentifiers
import CoreGraphics

enum ImageEncoderError: Error { case contextFailed, destinationFailed, finalizeFailed }

enum ImageEncoder {
    enum Format { case jpeg, heic
        var utType: UTType { self == .jpeg ? .jpeg : .heic }
    }

    /// Encode interleaved sRGB RGBA8 bytes into JPEG/HEIC data.
    static func encode(rgba8: [UInt8], width: Int, height: Int,
                       format: Format, quality: Double) throws -> Data {
        let cs = CGColorSpace(name: CGColorSpace.sRGB)!
        let bitmapInfo = CGImageAlphaInfo.noneSkipLast.rawValue | CGImageByteOrderInfo.order32Big.rawValue // RGBX, explicit byte order
        var bytes = rgba8
        guard let ctx = CGContext(data: &bytes, width: width, height: height,
                                  bitsPerComponent: 8, bytesPerRow: width * 4,
                                  space: cs, bitmapInfo: bitmapInfo),
              let cg = ctx.makeImage() else { throw ImageEncoderError.contextFailed }

        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(
            out, format.utType.identifier as CFString, 1, nil)
        else { throw ImageEncoderError.destinationFailed }

        CGImageDestinationAddImage(dest, cg, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw ImageEncoderError.finalizeFailed }
        return out as Data
    }
}
