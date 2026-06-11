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

    /// EXIF metadata written into the encoded result.  All fields are optional; nil = omit.
    struct ExifMetadata {
        /// ISO speed (maps to kCGImagePropertyExifISOSpeedRatings).
        var iso: Double?
        /// Exposure time in seconds (maps to kCGImagePropertyExifExposureTime).
        var shutterSeconds: Double?
        /// Capture timestamp (maps to kCGImagePropertyExifDateTimeOriginal).
        var capturedAt: Date?

        init(iso: Double? = nil, shutterSeconds: Double? = nil, capturedAt: Date? = nil) {
            self.iso = iso
            self.shutterSeconds = shutterSeconds
            self.capturedAt = capturedAt
        }
    }

    /// Encode interleaved sRGB RGBA8 bytes into JPEG/HEIC data.
    ///
    /// - Parameters:
    ///   - exif: Optional EXIF metadata to embed. Nil omits the Exif dict entirely.
    static func encode(rgba8: [UInt8], width: Int, height: Int,
                       format: Format, quality: Double, exif: ExifMetadata? = nil) throws -> Data {
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

        // Build the metadata properties dict.  The TIFF Software tag and ICC color profile
        // are written unconditionally; Exif dict only when exif != nil.
        //
        // JPEG TIFF dict note: ImageIO only returns `{TIFF}` from
        // CGImageSourceCopyPropertiesAtIndex for JPEG when the IFD0 contains the standard
        // resolution tags (XResolution/YResolution/ResolutionUnit).  Without those, a
        // Software-only IFD0 is silently swallowed on read-back.  We always emit resolution
        // so the TIFF dict is visible; Software round-trips correctly as a side-effect.
        var imageProps: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: quality,
            kCGImagePropertyTIFFDictionary: [
                "Software":       "Stack Stack Stack",
                "XResolution":    72,
                "YResolution":    72,
                "ResolutionUnit": 2,    // 2 = pixels/inch
            ] as NSDictionary,
            // Tag the output as sRGB so decoders interpret the pixel values correctly.
            // kCGImagePropertyProfileName sets the profile name in the image properties dict
            // (and in JFIF-style colorspace signaling); combined with kCGImagePropertyColorModel
            // it ensures the decoded CGImage's colorSpace.name returns "kCGColorSpaceSRGB"
            // (the Quartz constant for the named sRGB profile — same as real iPhone JPEG output).
            kCGImagePropertyColorModel: kCGImagePropertyColorModelRGB,
            kCGImagePropertyProfileName: "sRGB IEC61966-2.1",
        ]

        // Supply the raw ICC data bytes for formats that embed a full APP2 ICC block (e.g. HEIC).
        // For JPEG, ImageIO uses the profile name tag above; the raw ICC data is passed as a hint
        // but the "ICCProfile" key is not a documented constant.  Both paths produce a decoded
        // CGImage whose colorSpace.name is "kCGColorSpaceSRGB" — matching real iPhone JPEG output.
        // copyICCData() is available iOS 9+.
        if let iccData = cs.copyICCData() as Data? {
            imageProps["ICCProfile" as CFString] = iccData as CFData
        }

        if let exif {
            var exifDict: [CFString: Any] = [:]
            if let iso = exif.iso {
                exifDict[kCGImagePropertyExifISOSpeedRatings] = [Int(iso)]
            }
            if let shutter = exif.shutterSeconds {
                exifDict[kCGImagePropertyExifExposureTime] = shutter
            }
            if let date = exif.capturedAt {
                // EXIF DateTimeOriginal format is "yyyy:MM:dd HH:mm:ss" — uses a FIXED
                // en_US_POSIX locale so the colons in the date portion are never replaced by
                // locale-specific separators on non-Gregorian locales.  Current timezone is
                // correct here: the spec asks for local capture time, not UTC.
                let fmt = DateFormatter()
                fmt.locale = Locale(identifier: "en_US_POSIX")
                fmt.dateFormat = "yyyy:MM:dd HH:mm:ss"
                exifDict[kCGImagePropertyExifDateTimeOriginal] = fmt.string(from: date)
            }
            imageProps[kCGImagePropertyExifDictionary] = exifDict
        }

        CGImageDestinationAddImage(dest, cg, imageProps as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw ImageEncoderError.finalizeFailed }
        return out as Data
    }
}
