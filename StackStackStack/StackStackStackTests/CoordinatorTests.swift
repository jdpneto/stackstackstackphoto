import XCTest
import StackEngineCore
@testable import StackStackStack

final class CoordinatorTests: XCTestCase {
    @MainActor
    func testShootProducesADoneStateAndSavesAFile() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: FakeCaptureService(width: 16, height: 16), store: store)
        await coord.shoot()
        if case .done = coord.state {} else { XCTFail("expected .done, got \(coord.state)") }
        XCTAssertEqual(try store.loadAll().count, 1)
    }

    @MainActor
    func testSmoothMotionShootProducesAResult() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: FakeCaptureService(width: 16, height: 16), store: store)
        coord.mode = .smoothMotion
        await coord.shoot()
        if case .done = coord.state {} else { XCTFail("expected .done, got \(coord.state)") }
        XCTAssertEqual(try store.loadAll().count, 1)
    }

    @MainActor
    func testProFrameCountOverrideChangesCapturedFrames() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: FakeCaptureService(width: 16, height: 16), store: store)
        coord.pro = ProControls(frameCount: 5)      // override the look default (Detail = 8)
        await coord.shoot()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 5)
    }

    @MainActor
    func testConcurrentShootsAreRejected() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: FakeCaptureService(width: 16, height: 16), store: store)
        async let a: Void = coord.shoot()
        async let b: Void = coord.shoot()
        _ = await (a, b)
        // The re-entrancy guard drops the second concurrent shoot → exactly one save.
        XCTAssertEqual(try store.loadAll().count, 1)
    }
}
