import Foundation
import StackEngineCore

/// Minimal file-backed library: JPEG results + a JSON index in the given root. All writes are
/// atomic (temp-file + rename) and data-protected, and `loadAll` self-heals (drops records whose
/// file vanished) and preserves a corrupt index rather than letting the next save overwrite it.
///
/// `@unchecked Sendable`: no mutable in-memory state — all stored properties are immutable and every
/// method is stateless file I/O. Reads (loadAll/originalData/adjustments) are safe to call off the
/// main thread. WRITES (save/applyEdit/delete) must stay MainActor-confined: they are read-modify-
/// write on index.json, so two concurrent writers could lose a record (Sendable guarantees memory
/// safety of the reference, not mutation atomicity).
final class LibraryStore: @unchecked Sendable {
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
        var records = (try? loadRaw()) ?? []
        records.insert(StackRecord(id: id, createdAt: now, mode: mode, frameCount: frameCount,
                                   resultFileName: fileName, updatedAt: now), at: 0)
        try persist(records)
        return SavedStack(id: id, resultURL: url)
    }

    /// All persisted records for DISPLAY: self-heals by dropping records whose result file is gone.
    /// Mutations (save/delete/applyEdit) use `loadRaw` instead, so a transient read-time miss can
    /// never feed back into a `persist` and permanently drop a still-recoverable record.
    func loadAll() throws -> [StackRecord] {
        try loadRaw().filter { fm.fileExists(atPath: resultURL(for: $0).path) }
    }

    /// The raw decoded index, unfiltered. On a corrupt/torn index, the bytes are preserved aside
    /// (timestamped, so an earlier recoverable snapshot isn't clobbered) and [] returned — rather
    /// than letting the next save overwrite and permanently drop every prior record.
    private func loadRaw() throws -> [StackRecord] {
        guard let data = try? Data(contentsOf: indexURL) else { return [] }
        do {
            return try JSONDecoder().decode([StackRecord].self, from: data)
        } catch {
            let aside = root.appendingPathComponent("index.\(Int(Date().timeIntervalSince1970)).corrupt")
            try? fm.moveItem(at: indexURL, to: aside)
            return []
        }
    }

    func resultURL(for record: StackRecord) -> URL { record.resultURL(in: root) }

    /// Remove a stack's record and all of its files (result, original, edit sidecar).
    func delete(id: UUID) throws {
        for url in [resultURL(forID: id), originalURL(for: id), editsURL(for: id)] {
            try? fm.removeItem(at: url)
        }
        var records = (try? loadRaw()) ?? []
        records.removeAll { $0.id == id }
        try persist(records)
    }

    /// Delete `<uuid>.*` files that have no matching index record (orphans from failed/partial saves).
    /// MainActor-only: must not interleave with `save` (which writes files before the index entry).
    func reconcileOrphans() {
        let ids = Set(((try? loadRaw()) ?? []).map { $0.id.uuidString })   // raw → keep files for records present
        guard let files = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return }
        for f in files {
            let name = f.lastPathComponent
            // Only consider per-stack files; index.json and index.<epoch>.corrupt end in .json/.corrupt
            // (the former is excluded by name, the latter doesn't match these suffixes).
            guard name.hasSuffix(".jpg") || name.hasSuffix(".json"), name != "index.json" else { continue }
            let uuidPart = String(name.prefix(36))   // "<uuid>.jpg" / "<uuid>.orig.jpg" / "<uuid>.edits.json"
            if !ids.contains(uuidPart) { try? fm.removeItem(at: f) }
        }
    }

    private func resultURL(forID id: UUID) -> URL { root.appendingPathComponent("\(id.uuidString).jpg") }
    private func originalURL(for id: UUID) -> URL { root.appendingPathComponent("\(id.uuidString).orig.jpg") }
    private func editsURL(for id: UUID) -> URL { root.appendingPathComponent("\(id.uuidString).edits.json") }

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
        var records = (try? loadRaw()) ?? []
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
