import XCTest
import StackEngineCore
@testable import StackStackStack

final class LibraryStoreTests: XCTestCase {

    // MARK: - Temp-dir helpers

    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
    }

    private func makeStore() -> LibraryStore { LibraryStore(rootDirectory: tempDir) }
    private func storeRoot() -> URL { tempDir }

    // MARK: - Existing tests (updated call sites)

    func testSaveAndLoadRoundTrip() throws {
        let store = makeStore()
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xD9]) // minimal stand-in bytes
        let saved = try store.save(result: jpeg, format: .jpeg, mode: "noiseReduction", frameCount: 8)
        let all = try store.loadAll()
        XCTAssertEqual(all.count, 1)
        XCTAssertEqual(all[0].id, saved.id)
        XCTAssertEqual(all[0].frameCount, 8)
        XCTAssertTrue(FileManager.default.fileExists(atPath: saved.resultURL.path))
    }

    func testKeepsOriginalAndAppliesEdit() throws {
        let store = makeStore()
        let original = Data([0xFF, 0xD8, 0x01, 0xD9])
        let saved = try store.save(result: original, format: .jpeg, mode: "noiseReduction", frameCount: 8)

        // The original is preserved and adjustments default to identity.
        XCTAssertEqual(store.originalData(for: saved.id), original)
        XCTAssertEqual(store.adjustments(for: saved.id), .identity)

        // Applying an edit overwrites the displayed JPEG, persists adjustments, keeps the original.
        let edited = Data([0xFF, 0xD8, 0x02, 0xD9])
        try store.applyEdit(id: saved.id, adjustments: ImageAdjustments(exposureEV: 1), rendered: edited)
        XCTAssertEqual(try Data(contentsOf: saved.resultURL), edited)        // displayed = edited
        XCTAssertEqual(store.originalData(for: saved.id), original)          // original untouched
        XCTAssertEqual(store.adjustments(for: saved.id).exposureEV, 1)       // recipe persisted
    }

    func testApplyEditUpdatesTheIndexRecord() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF, 0xD8, 0xFF, 0xD9]), format: .jpeg, mode: "noiseReduction", frameCount: 8)
        let before = try XCTUnwrap(store.loadAll().first?.updatedAt)
        try store.applyEdit(id: saved.id, adjustments: ImageAdjustments(exposureEV: 1),
                            rendered: Data([0xFF, 0xD8, 0x02, 0xD9]))
        let after = try XCTUnwrap(store.loadAll().first?.updatedAt)   // index re-persisted (was the bug)
        XCTAssertGreaterThanOrEqual(after, before)
        XCTAssertEqual(try store.loadAll().count, 1)
    }

    func testDeleteRemovesRecordAndFiles() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF, 0xD8, 0xFF, 0xD9]), format: .jpeg, mode: "noiseReduction", frameCount: 8)
        try store.delete(id: saved.id)
        XCTAssertEqual(try store.loadAll().count, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: saved.resultURL.path))
        XCTAssertNil(store.originalData(for: saved.id))
    }

    func testLoadAllDropsRecordsWhoseFileVanished() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF, 0xD8, 0xFF, 0xD9]), format: .jpeg, mode: "noiseReduction", frameCount: 8)
        try FileManager.default.removeItem(at: saved.resultURL)   // file gone, index still references it
        XCTAssertEqual(try store.loadAll().count, 0)              // self-healed
    }

    func testCorruptIndexIsPreservedNotOverwritten() throws {
        let store = makeStore()
        _ = try store.save(result: Data([0xFF, 0xD8, 0xFF, 0xD9]), format: .jpeg, mode: "noiseReduction", frameCount: 8)
        // Corrupt the index, then load: it should return [] but move the bytes aside for recovery.
        try Data("{ not json".utf8).write(to: storeRoot().appendingPathComponent("index.json"))
        XCTAssertEqual(try store.loadAll().count, 0)
        let corruptPreserved = (try? FileManager.default.contentsOfDirectory(atPath: storeRoot().path))?
            .contains { $0.hasSuffix(".corrupt") } ?? false
        XCTAssertTrue(corruptPreserved, "corrupt index bytes should be moved aside, not overwritten")
    }

    // MARK: - New Task 2 tests

    func testSaveWithHEICFormatUsesHeicExtensionAndPersistsFormat() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF]), format: .heic, mode: "noiseReduction", frameCount: 3)
        XCTAssertEqual(saved.resultURL.pathExtension, "heic")
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertEqual(rec.format, "heic")
        XCTAssertEqual(rec.encoderFormat, .heic)
        XCTAssertEqual(store.resultURL(for: rec).pathExtension, "heic")
    }

    func testLegacyRecordWithoutFormatReadsAsJPEG() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF]), format: .jpeg, mode: "smoothMotion", frameCount: 2)
        // Simulate a pre-format index: strip the key from the persisted JSON.
        let indexURL = storeRoot().appendingPathComponent("index.json")
        var json = try JSONSerialization.jsonObject(with: Data(contentsOf: indexURL)) as! [[String: Any]]
        json[0].removeValue(forKey: "format")
        try JSONSerialization.data(withJSONObject: json).write(to: indexURL)
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertNil(rec.format)
        XCTAssertEqual(rec.encoderFormat, .jpeg, "nil format = JPEG (back-compat)")
        XCTAssertEqual(store.resultURL(for: rec).lastPathComponent, "\(saved.id.uuidString).jpg")
    }

    func testDeleteRemovesHeicFiles() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF]), format: .heic, mode: "depthOfField", frameCount: 10)
        try store.delete(id: saved.id)
        XCTAssertEqual(try store.loadAll().count, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: saved.resultURL.path))
    }

    func testReconcileOrphansSweepsBothExtensions() throws {
        let store = makeStore()
        let orphanJpg = storeRoot().appendingPathComponent("\(UUID().uuidString).jpg")
        let orphanHeic = storeRoot().appendingPathComponent("\(UUID().uuidString).heic")
        try Data([0x01]).write(to: orphanJpg)
        try Data([0x01]).write(to: orphanHeic)
        store.reconcileOrphans()
        XCTAssertFalse(FileManager.default.fileExists(atPath: orphanJpg.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: orphanHeic.path))
    }

    func testDeleteAllEmptiesTheLibrary() throws {
        let store = makeStore()
        _ = try store.save(result: Data([0xFF]), format: .jpeg, mode: "noiseReduction", frameCount: 1)
        _ = try store.save(result: Data([0xFF]), format: .heic, mode: "lightTrails", frameCount: 1)
        try store.deleteAll()
        XCTAssertEqual(try store.loadAll().count, 0)
    }

    func testStorageUsedBytesCountsLibraryFiles() throws {
        let store = makeStore()
        _ = try store.save(result: Data(repeating: 0, count: 1000), format: .jpeg, mode: "noiseReduction", frameCount: 1)
        XCTAssertGreaterThanOrEqual(store.storageUsedBytes(), 2000, "result + original ≥ 2×1000")
    }

    func testRecordLookupByID() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF]), format: .heic, mode: "noiseReduction", frameCount: 1)
        XCTAssertEqual(store.record(for: saved.id)?.encoderFormat, .heic)
        XCTAssertNil(store.record(for: UUID()))
    }

    // MARK: - Task 2 (blend-strength) tests

    func testReferenceRoundTripAndDeletion() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xAA]), reference: Data([0xBB]), format: .heic,
                                   mode: "smoothMotion", frameCount: 3)
        XCTAssertEqual(store.referenceData(for: saved.id), Data([0xBB]))
        try store.delete(id: saved.id)
        XCTAssertNil(store.referenceData(for: saved.id))
    }

    func testSaveWithoutReferenceHasNilReferenceData() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xAA]), reference: nil, format: .jpeg,
                                   mode: "depthOfField", frameCount: 10)
        XCTAssertNil(store.referenceData(for: saved.id))
    }

    func testReconcileKeepsLiveReferences() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xAA]), reference: Data([0xBB]), format: .jpeg,
                                   mode: "noiseReduction", frameCount: 3)
        store.reconcileOrphans()
        XCTAssertNotNil(store.referenceData(for: saved.id))
    }

    func testStaleAlphaWithMissingReferenceNormalizesToOne() throws {
        // Save WITHOUT a reference, then applyEdit with blendStrength 0.4 persisted.
        // Re-reading adjustments must return blendStrength == 1 because there's no reference
        // file to blend against — a stale α would silently re-bake at a different look. (Fix 4)
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF, 0xD8, 0xFF, 0xD9]), reference: nil,
                                   format: .jpeg, mode: "noiseReduction", frameCount: 5)
        var adj = ImageAdjustments(blendStrength: 0.4)
        try store.applyEdit(id: saved.id, adjustments: adj, rendered: Data([0xFF, 0xD8, 0x02, 0xD9]))
        // Re-read: the reference file is absent, so blendStrength must be normalized to 1.
        let read = store.adjustments(for: saved.id)
        XCTAssertEqual(read.blendStrength, 1,
                       "stale α without a reference file must normalize to 1 on next read")
        XCTAssertFalse(read.hasBlend, "hasBlend must be false after normalization")
        _ = adj   // suppress unused-variable warning
    }
}
