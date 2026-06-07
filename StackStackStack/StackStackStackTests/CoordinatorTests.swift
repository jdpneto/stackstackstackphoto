import XCTest
import CoreGraphics
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
    func testCancelDiscardsQueuedStackAndFreesUI() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .smoothMotion
        await coord.shoot()              // capture done on MainActor; processing Task scheduled, not yet run
        coord.cancelProcessing()         // runs synchronously before the processing Task can take the actor
        await coord.awaitProcessing()    // now it runs, sees the token cancelled, bails before saving
        XCTAssertFalse(coord.isBusy, "shutter must be free after cancel")
        XCTAssertNil(coord.lastSavedID)
        XCTAssertNil(coord.lastError, "cancel is not an error")
        XCTAssertEqual(try store.loadAll().count, 0, "a cancelled stack must not be saved")
    }

    @MainActor
    func testLongExposureUsesBurstSettingsFrameCount() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .smoothMotion
        coord.burst = BurstSettings(photoCount: 7, durationSeconds: 4)
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 7)
    }

    @MainActor
    func testStaticLookIgnoresBurstSettings() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .noiseReduction                       // Detail: fixed 8-frame burst
        coord.burst = BurstSettings(photoCount: 3, durationSeconds: 4)
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 8)
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

    @MainActor
    func testTapToFocusEnabledInAutoModeAndFree() {
        let (coord, _) = makeCoordinator()
        XCTAssertTrue(coord.tapToFocusEnabled)
    }

    @MainActor
    func testTapToFocusDisabledWithEachManualOverride() {
        let (coord, _) = makeCoordinator()
        coord.pro = ProControls(focus: 0.5)
        XCTAssertFalse(coord.tapToFocusEnabled, "manual focus disables tap-to-focus")
        coord.pro = ProControls(iso: 800)
        XCTAssertFalse(coord.tapToFocusEnabled, "manual ISO disables tap-to-focus")
        coord.pro = ProControls(shutterSeconds: 0.01)
        XCTAssertFalse(coord.tapToFocusEnabled, "manual shutter disables tap-to-focus")
        coord.pro = .auto
        XCTAssertTrue(coord.tapToFocusEnabled, "back to auto re-enables")
    }

    @MainActor
    func testFocusAndExposeTogglesAeAfLock() {
        let (coord, _) = makeCoordinator()
        coord.focusAndExpose(atDevicePoint: CGPoint(x: 0.5, y: 0.5), lock: true)
        XCTAssertTrue(coord.aeAfLocked)
        coord.focusAndExpose(atDevicePoint: CGPoint(x: 0.3, y: 0.3), lock: false)
        XCTAssertFalse(coord.aeAfLocked, "a normal tap clears the lock")
    }

    @MainActor
    func testEnablingManualClearsAeAfLock() {
        let (coord, _) = makeCoordinator()
        coord.focusAndExpose(atDevicePoint: CGPoint(x: 0.5, y: 0.5), lock: true)
        coord.pro = ProControls(iso: 800)
        XCTAssertFalse(coord.aeAfLocked, "entering manual drops the AE/AF lock")
    }

    @MainActor
    func testChangingLookClearsAeAfLock() {
        let (coord, _) = makeCoordinator()
        coord.focusAndExpose(atDevicePoint: CGPoint(x: 0.5, y: 0.5), lock: true)
        coord.mode = .smoothMotion
        XCTAssertFalse(coord.aeAfLocked, "switching looks drops the AE/AF lock")
    }

    @MainActor
    func testFocusAndExposeIsNoOpWhenDisabled() {
        let (coord, _) = makeCoordinator()
        coord.pro = ProControls(iso: 800)   // manual exposure → tapToFocusEnabled == false
        coord.focusAndExpose(atDevicePoint: CGPoint(x: 0.5, y: 0.5), lock: true)
        XCTAssertFalse(coord.aeAfLocked, "a gesture while disabled (manual mode) must not set the AE/AF lock")
    }

    @MainActor
    func testShootClearsAeAfLock() async throws {
        let (coord, _) = makeCoordinator()
        coord.focusAndExpose(atDevicePoint: CGPoint(x: 0.5, y: 0.5), lock: true)
        XCTAssertTrue(coord.aeAfLocked)
        await coord.shoot()                  // a new shot supersedes the long-press lock
        XCTAssertFalse(coord.aeAfLocked, "shooting clears the AE/AF lock so the banner doesn't linger")
        await coord.awaitProcessing()
    }

    @MainActor
    func testTapToFocusDisabledWhileBusy() async throws {
        let (coord, _) = makeCoordinator()
        await coord.shoot()   // capture done; the stack is queued (processingCount > 0) but its Task hasn't
                              // taken the MainActor yet, so isBusy is deterministically true here
        XCTAssertFalse(coord.tapToFocusEnabled, "tap-to-focus is disabled while a stack is processing")
        await coord.awaitProcessing()
        XCTAssertTrue(coord.tapToFocusEnabled, "re-enabled once processing finishes")
    }
}
