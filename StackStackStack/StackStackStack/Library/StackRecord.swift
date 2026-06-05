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

    func resultURL(in dir: URL) -> URL { dir.appendingPathComponent(resultFileName) }
}
