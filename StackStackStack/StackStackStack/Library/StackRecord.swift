import Foundation

struct StackRecord: Codable, Identifiable, Equatable {
    let id: UUID
    let createdAt: Date
    let mode: String
    let frameCount: Int
    let resultFileName: String
    /// Bumped whenever the result is re-rendered by an edit, so gallery cells reload. Optional for
    /// back-compat with index.json written before this field existed (synthesized Codable decodes a
    /// missing optional key as nil).
    var updatedAt: Date?
    /// Encoded format of the result/original ("jpeg"/"heic"). Optional for back-compat: records
    /// written before this field existed are JPEG. Stored as the raw string so the index stays a
    /// stable contract (same rule as StackMode raw values).
    var format: String?

    /// First-frame ISO speed from the capture burst (nil for legacy records). Optional for back-compat.
    var iso: Double?
    /// First-frame exposure time in seconds from the capture burst (nil for legacy records). Optional for back-compat.
    var shutterSeconds: Double?

    /// The record's encoder format; nil/unknown = JPEG (every pre-format record is a JPEG).
    var encoderFormat: ImageEncoder.Format { format.flatMap(ImageEncoder.Format.init(rawValue:)) ?? .jpeg }

    func resultURL(in dir: URL) -> URL { dir.appendingPathComponent(resultFileName) }
}
