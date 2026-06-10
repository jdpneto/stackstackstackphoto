import Foundation
import ImageIO
import UniformTypeIdentifiers
import CoreGraphics

enum ImageEncoderError: Error { case contextFailed, destinationFailed, finalizeFailed }

enum ImageEncoder {
    /// `String`-backed: raw values are persisted (AppSettings + StackRecord.format) — renaming a
    /// case silently breaks stored preferences and library records.
    enum Format: String, Sendable, Equatable {
        case jpeg, heic
        var utType: UTType { self == .jpeg ? .jpeg : .heic }
        /// The file extension library files use for this format.
        var fileExtension: String { self == .jpeg ? "jpg" : "heic" }
    }

    /// Encode interleaved sRGB RGBA8 bytes into JPEG/HEIC data.
    static func encode(rgba8: [UInt8], width: Int, height: Int,
                       format: Format, quality: Double) throws -> Data {
        // The CGContext below reads width*height*4 bytes from `rgba8`; a mismatch is an OOB read.
        guard width > 0, height > 0, rgba8.count == width * height * 4 else {
            throw ImageEncoderError.contextFailed
        }
        guard let cs = CGColorSpace(name: CGColorSpace.sRGB) else { throw ImageEncoderError.contextFailed }
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
