import XCTest
@testable import StackStackStack

final class CoordinatorTests: XCTestCase {
    @MainActor
    func testShootProducesADoneStateAndSavesAFile() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: FakeCaptureService(width: 16, height: 16), store: store)
        await coord.shoot(frameCount: 6)
        if case .done = coord.state {} else { XCTFail("expected .done, got \(coord.state)") }
        XCTAssertEqual(try store.loadAll().count, 1)
    }
}
