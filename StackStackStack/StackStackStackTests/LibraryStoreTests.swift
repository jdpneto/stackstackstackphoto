import XCTest
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
}
