import XCTest
import StackEngineCore
@testable import StackStackStack

final class LibraryStoreTests: XCTestCase {
    func testSaveAndLoadRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xD9]) // minimal stand-in bytes
        let saved = try store.save(resultJPEG: jpeg, mode: "noiseReduction", frameCount: 8)
        let all = try store.loadAll()
        XCTAssertEqual(all.count, 1)
        XCTAssertEqual(all[0].id, saved.id)
        XCTAssertEqual(all[0].frameCount, 8)
        XCTAssertTrue(FileManager.default.fileExists(atPath: saved.resultURL.path))
    }

    func testKeepsOriginalAndAppliesEdit() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let original = Data([0xFF, 0xD8, 0x01, 0xD9])
        let saved = try store.save(resultJPEG: original, mode: "noiseReduction", frameCount: 8)

        // The original is preserved and adjustments default to identity.
        XCTAssertEqual(store.originalData(for: saved.id), original)
        XCTAssertEqual(store.adjustments(for: saved.id), .identity)

        // Applying an edit overwrites the displayed JPEG, persists adjustments, keeps the original.
        let edited = Data([0xFF, 0xD8, 0x02, 0xD9])
        try store.applyEdit(id: saved.id, adjustments: ImageAdjustments(exposureEV: 1), renderedJPEG: edited)
        XCTAssertEqual(try Data(contentsOf: saved.resultURL), edited)        // displayed = edited
        XCTAssertEqual(store.originalData(for: saved.id), original)          // original untouched
        XCTAssertEqual(store.adjustments(for: saved.id).exposureEV, 1)       // recipe persisted
    }

    func testApplyEditUpdatesTheIndexRecord() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let saved = try store.save(resultJPEG: Data([0xFF, 0xD8, 0xFF, 0xD9]), mode: "noiseReduction", frameCount: 8)
        let before = try XCTUnwrap(store.loadAll().first?.updatedAt)
        try store.applyEdit(id: saved.id, adjustments: ImageAdjustments(exposureEV: 1),
                            renderedJPEG: Data([0xFF, 0xD8, 0x02, 0xD9]))
        let after = try XCTUnwrap(store.loadAll().first?.updatedAt)   // index re-persisted (was the bug)
        XCTAssertGreaterThanOrEqual(after, before)
        XCTAssertEqual(try store.loadAll().count, 1)
    }

    func testDeleteRemovesRecordAndFiles() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let saved = try store.save(resultJPEG: Data([0xFF, 0xD8, 0xFF, 0xD9]), mode: "noiseReduction", frameCount: 8)
        try store.delete(id: saved.id)
        XCTAssertEqual(try store.loadAll().count, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: saved.resultURL.path))
        XCTAssertNil(store.originalData(for: saved.id))
    }

    func testLoadAllDropsRecordsWhoseFileVanished() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let saved = try store.save(resultJPEG: Data([0xFF, 0xD8, 0xFF, 0xD9]), mode: "noiseReduction", frameCount: 8)
        try FileManager.default.removeItem(at: saved.resultURL)   // file gone, index still references it
        XCTAssertEqual(try store.loadAll().count, 0)              // self-healed
    }

    func testCorruptIndexIsPreservedNotOverwritten() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        _ = try store.save(resultJPEG: Data([0xFF, 0xD8, 0xFF, 0xD9]), mode: "noiseReduction", frameCount: 8)
        // Corrupt the index, then load: it should return [] but move the bytes aside for recovery.
        try Data("{ not json".utf8).write(to: dir.appendingPathComponent("index.json"))
        XCTAssertEqual(try store.loadAll().count, 0)
        let corruptPreserved = (try? FileManager.default.contentsOfDirectory(atPath: dir.path))?
            .contains { $0.hasSuffix(".corrupt") } ?? false
        XCTAssertTrue(corruptPreserved, "corrupt index bytes should be moved aside, not overwritten")
    }
}
