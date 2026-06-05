import Foundation
import StackEngineCore

/// Minimal file-backed library: JPEG results + a JSON index in the given root. All writes are
/// atomic (temp-file + rename) and data-protected, and `loadAll` self-heals (drops records whose
/// file vanished) and preserves a corrupt index rather than letting the next save overwrite it.
final class LibraryStore {
    private let root: URL
    private let indexURL: URL
    private let fm = FileManager.default
    /// Atomic so an interrupted write can't truncate the file; complete protection so contents are
    /// unreadable while the device is locked.
    private static let writeOptions: Data.WritingOptions = [.atomic, .completeFileProtection]

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
        let now = Date()
        // Write the files first, then the index last — so a failure never leaves an index entry
        // pointing at a file that isn't there.
        try resultJPEG.write(to: url, options: Self.writeOptions)
        try resultJPEG.write(to: originalURL(for: id), options: Self.writeOptions)   // immutable original
        var records = (try? loadAll()) ?? []
        records.insert(StackRecord(id: id, createdAt: now, mode: mode, frameCount: frameCount,
                                   resultFileName: fileName, updatedAt: now), at: 0)
        try persist(records)
        return SavedStack(id: id, resultURL: url)
    }

    func loadAll() throws -> [StackRecord] {
        guard let data = try? Data(contentsOf: indexURL) else { return [] }
        let records: [StackRecord]
        do {
            records = try JSONDecoder().decode([StackRecord].self, from: data)
        } catch {
            // Corrupt/torn index: preserve the bytes for recovery instead of silently returning []
            // (which would let the next save overwrite and permanently drop every prior record).
            try? fm.removeItem(at: corruptIndexURL)
            try? fm.moveItem(at: indexURL, to: corruptIndexURL)
            return []
        }
        // Self-heal: drop records whose result file is gone (a partial save/delete/external removal).
        return records.filter { fm.fileExists(atPath: resultURL(for: $0).path) }
    }

    func resultURL(for record: StackRecord) -> URL { record.resultURL(in: root) }

    /// Remove a stack's record and all of its files (result, original, edit sidecar).
    func delete(id: UUID) throws {
        for url in [resultURL(forID: id), originalURL(for: id), editsURL(for: id)] {
            try? fm.removeItem(at: url)
        }
        var records = (try? loadAll()) ?? []
        records.removeAll { $0.id == id }
        try persist(records)
    }

    /// Delete `<uuid>.*` files that have no matching index record (orphans from failed/partial saves).
    func reconcileOrphans() {
        let ids = Set(((try? loadAll()) ?? []).map { $0.id.uuidString })
        guard let files = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return }
        for f in files {
            let name = f.lastPathComponent
            guard name.hasSuffix(".jpg") || name.hasSuffix(".json"), name != "index.json",
                  name != corruptIndexURL.lastPathComponent else { continue }
            let uuidPart = String(name.prefix(36))   // "<uuid>.jpg" / "<uuid>.orig.jpg" / "<uuid>.edits.json"
            if !ids.contains(uuidPart) { try? fm.removeItem(at: f) }
        }
    }

    private func resultURL(forID id: UUID) -> URL { root.appendingPathComponent("\(id.uuidString).jpg") }
    private func originalURL(for id: UUID) -> URL { root.appendingPathComponent("\(id.uuidString).orig.jpg") }
    private func editsURL(for id: UUID) -> URL { root.appendingPathComponent("\(id.uuidString).edits.json") }
    private var corruptIndexURL: URL { root.appendingPathComponent("index.json.corrupt") }

    /// The immutable original stacked JPEG, used as the editing source.
    func originalData(for id: UUID) -> Data? {
        try? Data(contentsOf: originalURL(for: id))
    }

    /// The persisted adjustments for a record (identity if none / unreadable).
    func adjustments(for id: UUID) -> ImageAdjustments {
        guard let data = try? Data(contentsOf: editsURL(for: id)),
              let adj = try? JSONDecoder().decode(ImageAdjustments.self, from: data) else { return .identity }
        return adj
    }

    /// Overwrite the displayed JPEG with a rendered result, persist the adjustments, and bump the
    /// record's `updatedAt` so gallery cells reload (their task id includes it).
    func applyEdit(id: UUID, adjustments: ImageAdjustments, renderedJPEG: Data) throws {
        try renderedJPEG.write(to: resultURL(forID: id), options: Self.writeOptions)
        try JSONEncoder().encode(adjustments).write(to: editsURL(for: id), options: Self.writeOptions)
        var records = (try? loadAll()) ?? []
        if let i = records.firstIndex(where: { $0.id == id }) {
            records[i].updatedAt = Date()
            try persist(records)
        }
    }

    private func persist(_ records: [StackRecord]) throws {
        let data = try JSONEncoder().encode(records)
        try data.write(to: indexURL, options: Self.writeOptions)
    }
}
