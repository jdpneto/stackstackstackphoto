import Foundation
import StackEngineCore

/// Minimal file-backed library: JPEG results + a JSON index in the given root.
final class LibraryStore {
    private let root: URL
    private let indexURL: URL
    private let fm = FileManager.default

    init(rootDirectory: URL = LibraryStore.defaultRoot()) {
        self.root = rootDirectory
        self.indexURL = rootDirectory.appendingPathComponent("index.json")
        try? fm.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
    }

    static func defaultRoot() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Stacks", isDirectory: true)
    }

    struct SavedStack { let id: UUID; let resultURL: URL }

    @discardableResult
    func save(resultJPEG: Data, mode: String, frameCount: Int) throws -> SavedStack {
        let id = UUID()
        let fileName = "\(id.uuidString).jpg"
        let url = root.appendingPathComponent(fileName)
        try resultJPEG.write(to: url)
        try resultJPEG.write(to: originalURL(forFileName: fileName))   // immutable original for re-editing
        var records = (try? loadAll()) ?? []
        records.insert(StackRecord(id: id, createdAt: Date(), mode: mode,
                                   frameCount: frameCount, resultFileName: fileName), at: 0)
        try persist(records)
        return SavedStack(id: id, resultURL: url)
    }

    func loadAll() throws -> [StackRecord] {
        guard let data = try? Data(contentsOf: indexURL) else { return [] }
        return try JSONDecoder().decode([StackRecord].self, from: data)
    }

    func resultURL(for record: StackRecord) -> URL { record.resultURL(in: root) }

    /// The displayed result file URL for an id.
    func resultURL(forID id: UUID) -> URL { root.appendingPathComponent("\(id.uuidString).jpg") }

    private func originalURL(forFileName fileName: String) -> URL {
        root.appendingPathComponent((fileName as NSString).deletingPathExtension + ".orig.jpg")
    }
    private func editsURL(for id: UUID) -> URL {
        root.appendingPathComponent("\(id.uuidString).edits.json")
    }

    /// The immutable original stacked JPEG, used as the editing source.
    func originalData(for id: UUID) -> Data? {
        try? Data(contentsOf: originalURL(forFileName: "\(id.uuidString).jpg"))
    }

    /// The persisted adjustments for a record (identity if none).
    func adjustments(for id: UUID) -> ImageAdjustments {
        guard let data = try? Data(contentsOf: editsURL(for: id)),
              let adj = try? JSONDecoder().decode(ImageAdjustments.self, from: data) else { return .identity }
        return adj
    }

    /// Overwrite the displayed JPEG with a rendered result and persist the adjustments.
    func applyEdit(id: UUID, adjustments: ImageAdjustments, renderedJPEG: Data) throws {
        try renderedJPEG.write(to: root.appendingPathComponent("\(id.uuidString).jpg"))
        try JSONEncoder().encode(adjustments).write(to: editsURL(for: id))
    }

    private func persist(_ records: [StackRecord]) throws {
        let data = try JSONEncoder().encode(records)
        try data.write(to: indexURL)
    }
}
