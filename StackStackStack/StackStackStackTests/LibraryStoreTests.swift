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
}
