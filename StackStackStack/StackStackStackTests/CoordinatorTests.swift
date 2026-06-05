import XCTest
import StackEngineCore
@testable import StackStackStack

final class CoordinatorTests: XCTestCase {
    @MainActor
    private func makeCoordinator() -> (StackCaptureCoordinator, LibraryStore) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        return (StackCaptureCoordinator(capture: FakeCaptureService(width: 16, height: 16), store: store), store)
    }

    @MainActor
    func testShootCapturesThenStacksAndSavesInBackground() async throws {
        let (coord, store) = makeCoordinator()
        await coord.shoot()
        XCTAssertFalse(coord.isCapturing, "capture should be finished when shoot() returns")
        await coord.awaitProcessing()                       // let the background stack finish
        XCTAssertNil(coord.lastError)
        XCTAssertNotNil(coord.lastSavedID)
        XCTAssertNotNil(coord.lastResultJPEG)
        XCTAssertEqual(coord.processingCount, 0)
        XCTAssertEqual(try store.loadAll().count, 1)
    }

    @MainActor
    func testSmoothMotionShootProducesAResult() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .smoothMotion
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertNil(coord.lastError)
        XCTAssertEqual(try store.loadAll().count, 1)
    }

    @MainActor
    func testProFrameCountOverrideChangesCapturedFrames() async throws {
        let (coord, store) = makeCoordinator()
        coord.pro = ProControls(frameCount: 5)      // override the look default (Detail = 8)
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 5)
    }

    @MainActor
    func testConcurrentShootsAreRejected() async throws {
        let (coord, store) = makeCoordinator()
        async let a: Void = coord.shoot()
        async let b: Void = coord.shoot()
        _ = await (a, b)
        // The re-entrancy guard drops the second shoot fired DURING the first capture → one save.
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().count, 1)
    }

    @MainActor
    func testCaptureFinishesAndShutterClearsAfterProcessing() async throws {
        // Capture (the arms-up phase) finishes when shoot() returns; the shutter is then free only
        // once the background stack completes.
        let (coord, store) = makeCoordinator()
        await coord.shoot()
        XCTAssertFalse(coord.isCapturing, "capture should be finished when shoot() returns")
        await coord.awaitProcessing()
        XCTAssertFalse(coord.isBusy, "shutter is free once the background stack finishes")
        XCTAssertEqual(try store.loadAll().count, 1)
    }

    @MainActor
    func testChangingLookDropsTheStaleResult() async throws {
        let (coord, _) = makeCoordinator()
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertNotNil(coord.lastResultJPEG)
        coord.mode = .smoothMotion                  // switch looks → a new shot is implied
        XCTAssertNil(coord.lastResultJPEG, "switching looks should drop the stale result preview")
        XCTAssertNil(coord.lastSavedID)
    }
}
