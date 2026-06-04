import Foundation

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

    private func persist(_ records: [StackRecord]) throws {
        let data = try JSONEncoder().encode(records)
        try data.write(to: indexURL)
    }
}
