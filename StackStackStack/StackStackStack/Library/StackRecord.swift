import Foundation

struct StackRecord: Codable, Identifiable, Equatable {
    let id: UUID
    let createdAt: Date
    let mode: String
    let frameCount: Int
    let resultFileName: String

    func resultURL(in dir: URL) -> URL { dir.appendingPathComponent(resultFileName) }
}
