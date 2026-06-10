import Foundation
import StackEngineCore

/// Library mutation failures surfaced to the editor UI.
enum LibraryError: LocalizedError {
    case recordMissing
    var errorDescription: String? { "This stack no longer exists in the library." }
}

/// Minimal file-backed library: results (JPEG or HEIC) + a JSON index in the given root. All writes
/// are atomic (temp-file + rename) and data-protected, and `loadAll` self-heals (drops records whose
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
    func save(result: Data, reference: Data? = nil, format: ImageEncoder.Format, mode: String, frameCount: Int) throws -> SavedStack {
        let id = UUID()
        let fileName = "\(id.uuidString).\(format.fileExtension)"
        let url = root.appendingPathComponent(fileName)
        let now = Date()
        // Write the files first, then the index last — so a failure never leaves an index entry
        // pointing at a file that isn't there.
        try result.write(to: url, options: Self.writeOptions)
        try result.write(to: originalURL(for: id, format: format), options: Self.writeOptions)   // immutable original
        // The aligned reference frame — the blend-strength lerp's second endpoint; absent for depth
        // and legacy records (the slider is hidden when nil).
        if let reference {
            try reference.write(to: referenceURL(for: id, format: format), options: Self.writeOptions)
        }
        var records = (try? loadRaw()) ?? []
        records.insert(StackRecord(id: id, createdAt: now, mode: mode, frameCount: frameCount,
                                   resultFileName: fileName, updatedAt: now, format: format.rawValue), at: 0)
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

    /// The persisted record for an id (nil if absent).
    func record(for id: UUID) -> StackRecord? {
        ((try? loadRaw()) ?? []).first { $0.id == id }
    }

    /// Remove a stack's record and all of its files (result, original, reference, edit sidecar).
    func delete(id: UUID) throws {
        var records = (try? loadRaw()) ?? []
        let format = records.first(where: { $0.id == id })?.encoderFormat
        var urls = [editsURL(for: id)]
        for f in format.map({ [$0] }) ?? [.jpeg, .heic] {   // unknown record → sweep both extensions
            urls.append(resultURL(for: id, format: f))
            urls.append(originalURL(for: id, format: f))
            urls.append(referenceURL(for: id, format: f))
        }
        for url in urls { try? fm.removeItem(at: url) }
        records.removeAll { $0.id == id }
        try persist(records)
    }

    /// Delete every stack (Settings ▸ Storage). MainActor-only, like all writes.
    func deleteAll() throws {
        let records = (try? loadRaw()) ?? []
        for rec in records {
            var urls = [editsURL(for: rec.id)]
            for f in [rec.encoderFormat] as [ImageEncoder.Format] {
                urls.append(resultURL(for: rec.id, format: f))
                urls.append(originalURL(for: rec.id, format: f))
                urls.append(referenceURL(for: rec.id, format: f))
            }
            for url in urls { try? fm.removeItem(at: url) }
        }
        try persist([])
    }

    /// Total bytes of all library files (results, originals, sidecars, index). Stateless file I/O —
    /// safe to call off the main thread (Settings computes it in a background task).
    func storageUsedBytes() -> Int64 {
        guard let files = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        return files.reduce(0) { sum, url in
            sum + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
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
            guard name.hasSuffix(".jpg") || name.hasSuffix(".heic") || name.hasSuffix(".json"),
                  name != "index.json" else { continue }
            let uuidPart = String(name.prefix(36))   // "<uuid>.jpg" / "<uuid>.orig.jpg" / "<uuid>.edits.json" / "<uuid>.heic"
            // Non-UUID-named files are never per-stack files and must not be swept (e.g. index.corrupt).
            guard UUID(uuidString: uuidPart) != nil else { continue }
            if !ids.contains(uuidPart) { try? fm.removeItem(at: f) }
        }
    }

    private func resultURL(for id: UUID, format: ImageEncoder.Format) -> URL {
        root.appendingPathComponent("\(id.uuidString).\(format.fileExtension)")
    }
    private func originalURL(for id: UUID, format: ImageEncoder.Format) -> URL {
        root.appendingPathComponent("\(id.uuidString).orig.\(format.fileExtension)")
    }
    /// The aligned reference frame used for blend-strength editing (`<uuid>.ref.<ext>`).
    private func referenceURL(for id: UUID, format: ImageEncoder.Format) -> URL {
        root.appendingPathComponent("\(id.uuidString).ref.\(format.fileExtension)")
    }
    private func editsURL(for id: UUID) -> URL { root.appendingPathComponent("\(id.uuidString).edits.json") }

    /// The immutable original stacked image (the record's own format), used as the editing source.
    func originalData(for id: UUID) -> Data? {
        guard let rec = record(for: id) else { return nil }
        return try? Data(contentsOf: originalURL(for: id, format: rec.encoderFormat))
    }

    /// The aligned reference frame (the blend-strength lerp's second endpoint), or nil for depth
    /// records, legacy records, or any record whose ref file is absent or corrupt.
    func referenceData(for id: UUID) -> Data? {
        guard let rec = record(for: id) else { return nil }
        return try? Data(contentsOf: referenceURL(for: id, format: rec.encoderFormat))
    }

    /// The persisted adjustments for a record (identity if none / unreadable).
    func adjustments(for id: UUID) -> ImageAdjustments {
        guard let data = try? Data(contentsOf: editsURL(for: id)),
              let adj = try? JSONDecoder().decode(ImageAdjustments.self, from: data) else { return .identity }
        return adj
    }

    /// Overwrite the displayed result with a rendered image (in the record's own format), persist
    /// the adjustments, and bump `updatedAt` so gallery cells reload (their task id includes it).
    func applyEdit(id: UUID, adjustments: ImageAdjustments, rendered: Data) throws {
        var records = try loadRaw()
        guard let i = records.firstIndex(where: { $0.id == id }) else { throw LibraryError.recordMissing }
        let rec = records[i]
        try rendered.write(to: resultURL(for: id, format: rec.encoderFormat), options: Self.writeOptions)
        try JSONEncoder().encode(adjustments).write(to: editsURL(for: id), options: Self.writeOptions)
        records[i].updatedAt = Date()
        try persist(records)
    }

    private func persist(_ records: [StackRecord]) throws {
        let data = try JSONEncoder().encode(records)
        try data.write(to: indexURL, options: Self.writeOptions)
    }
}
