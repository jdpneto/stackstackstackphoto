import Foundation
import StackEngineCore

/// Renders a developed result through non-destructive adjustments and re-encodes it.
/// Pass `maxPixel` to render a downscaled preview cheaply.
/// Pass `format` to produce HEIC instead of JPEG — renders of an existing record always pass
/// the record's own format so what-you-see-is-what-gets-written (spec §4).
/// Pass `referenceJPEG` (the stored aligned reference frame) to enable blend-strength: the
/// reference is decoded at the SAME `maxPixel`, linearised, and threaded into
/// `ImageEditor.apply(_:to:reference:)` for the α lerp (spec 2026-06-11 §3).
enum ResultRenderer {
    static func render(originalJPEG: Data, adjustments: ImageAdjustments,
                       quality: Double = 0.95, maxPixel: Int? = nil,
                       format: ImageEncoder.Format = .jpeg,
                       referenceJPEG: Data? = nil) -> Data? {
        guard let (rgba, w, h) = ImageDecoder.rgba8(from: originalJPEG, maxPixel: maxPixel) else { return nil }
        let linear = OutputTransform.decodeSRGB8(rgba, width: w, height: h)
        // Decode the reference at the same scale so dimensions match for the blend-strength lerp.
        // Dimension mismatch (e.g. a JPEG decode that chose a slightly different scale) is caught
        // defensively inside ImageEditor.apply and skips the blend rather than trapping.
        let refLinear: PixelImage? = referenceJPEG.flatMap { refData in
            guard let (refRGBA, rw, rh) = ImageDecoder.rgba8(from: refData, maxPixel: maxPixel) else { return nil }
            return OutputTransform.decodeSRGB8(refRGBA, width: rw, height: rh)
        }
        let adjusted = ImageEditor.apply(adjustments, to: linear, reference: refLinear)
        let outRGBA = OutputTransform.encodeSRGB8(adjusted)
        // Use the ADJUSTED dimensions — crop changes them, so w/h from the decode are stale.
        return try? ImageEncoder.encode(rgba8: outRGBA, width: adjusted.width, height: adjusted.height,
                                        format: format, quality: quality)
    }
}
