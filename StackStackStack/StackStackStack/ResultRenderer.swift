import Foundation
import StackEngineCore

/// Renders a developed result through non-destructive adjustments and re-encodes it.
/// Pass `maxPixel` to render a downscaled preview cheaply.
/// Pass `format` to produce HEIC instead of JPEG — renders of an existing record always pass
/// the record's own format so what-you-see-is-what-gets-written (spec §4).
enum ResultRenderer {
    static func render(originalJPEG: Data, adjustments: ImageAdjustments,
                       quality: Double = 0.95, maxPixel: Int? = nil,
                       format: ImageEncoder.Format = .jpeg) -> Data? {
        guard let (rgba, w, h) = ImageDecoder.rgba8(from: originalJPEG, maxPixel: maxPixel) else { return nil }
        let linear = OutputTransform.decodeSRGB8(rgba, width: w, height: h)
        let adjusted = ImageEditor.apply(adjustments, to: linear)
        let outRGBA = OutputTransform.encodeSRGB8(adjusted)
        // Use the ADJUSTED dimensions — crop changes them, so w/h from the decode are stale.
        return try? ImageEncoder.encode(rgba8: outRGBA, width: adjusted.width, height: adjusted.height,
                                        format: format, quality: quality)
    }
}
