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
    func testDismissResultClearsPreview() async throws {
        let (coord, _) = makeCoordinator()
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertNotNil(coord.lastResultJPEG)
        XCTAssertNotNil(coord.lastSavedID)
        coord.dismissResult()
        XCTAssertNil(coord.lastResultJPEG, "dismiss clears the result preview")
        XCTAssertNil(coord.lastSavedID)
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

    @MainActor
    func testCapturePublishesProgress() async throws {
        let (coord, _) = makeCoordinator()
        coord.mode = .noiseReduction          // Detail: fixed 8-frame burst
        await coord.shoot()                   // Fake fires onProgress per synthesized frame
        // The Fake runs off the MainActor, so all 8 `Task { @MainActor in capturedCount = k }` hops are
        // enqueued on the MainActor (FIFO) before shoot()'s continuation resumed the test — one yield puts
        // the test behind them, so they're guaranteed to have run by the asserts (SE-0306 serial executor).
        await Task.yield()
        XCTAssertEqual(coord.captureTotal, 8, "total reflects the recipe frame count")
        XCTAssertEqual(coord.capturedCount, 8, "counter reaches the captured frame count")
        await coord.awaitProcessing()
    }

    @MainActor
    func testDepthShootRoutesToFocusStackerAndSaves() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .depthOfField
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertNil(coord.lastError)
        XCTAssertNotNil(coord.lastResultJPEG)
        let record = try XCTUnwrap(store.loadAll().first)
        XCTAssertEqual(record.mode, "depthOfField")
        XCTAssertEqual(record.frameCount, 10, "Depth default is a 10-bracket sweep")
    }

    @MainActor
    func testDepthHonoursProFrameCount() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .depthOfField
        coord.pro = ProControls(frameCount: 4)
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 4)
    }

    @MainActor
    func testSupportsDepthIsTrueWithTheFake() async {
        let (coord, _) = makeCoordinator()
        _ = await coord.startPreview()
        XCTAssertTrue(coord.supportsDepth, "the fake always supports a focus sweep")
    }

    @MainActor
    func testShootHonoursExportFormat() async throws {
        // Hosts without an HEVC encoder (Intel-Mac simulators) can't produce HEIC at all — the
        // coordinator's JPEG fallback is then correct behavior, not a format bug. Skip there.
        let probe = try? ImageEncoder.encode(rgba8: [0, 0, 0, 255], width: 1, height: 1, format: .heic, quality: 0.9)
        try XCTSkipIf(probe == nil, "host cannot encode HEIC")

        let (coord, store) = makeCoordinator()
        coord.exportFormat = .heic
        await coord.shoot()
        await coord.awaitProcessing()
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertEqual(rec.encoderFormat, .heic)
        XCTAssertEqual(store.resultURL(for: rec).pathExtension, "heic")
        // The bytes really are HEIC: they decode, and re-encoding as HEIC is what produced them.
        XCTAssertNotNil(ImageDecoder.rgba8(from: try Data(contentsOf: store.resultURL(for: rec)), maxPixel: nil))
        let bytes = try Data(contentsOf: store.resultURL(for: rec))
        XCTAssertNotEqual(bytes.prefix(3), Data([0xFF, 0xD8, 0xFF]), "saved bytes must not be JPEG magic when the record says HEIC")
    }

    @MainActor
    func testHEICEncodeFailureFallsBackToJPEG() async throws {
        let (coord, store) = makeCoordinator()
        coord.exportFormat = .heic
        coord.encodeImage = { rgba, w, h, format, quality in
            if format == .heic { throw ImageEncoderError.finalizeFailed }   // simulate an encoder hiccup
            return try ImageEncoder.encode(rgba8: rgba, width: w, height: h, format: format, quality: quality)
        }
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertNil(coord.lastError, "fallback must not surface an error")
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertEqual(rec.encoderFormat, .jpeg, "record stamped with the format actually encoded")
        XCTAssertEqual(try Data(contentsOf: store.resultURL(for: rec)).prefix(3), Data([0xFF, 0xD8, 0xFF]))
    }

    @MainActor
    func testHEICFallbackKeepsResultAndReferencePaired() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .smoothMotion                 // a look that stores a reference
        coord.exportFormat = .heic
        coord.encodeImage = { rgba, w, h, format, quality in
            if format == .heic { throw ImageEncoderError.finalizeFailed }
            return try ImageEncoder.encode(rgba8: rgba, width: w, height: h, format: format, quality: quality)
        }
        await coord.shoot()
        await coord.awaitProcessing()
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertEqual(rec.encoderFormat, .jpeg)
        let ref = try XCTUnwrap(store.referenceData(for: rec.id), "reference must survive the fallback, paired as JPEG")
        XCTAssertEqual(ref.prefix(3), Data([0xFF, 0xD8, 0xFF]), "reference encoded in the shared fallback format")
    }

    @MainActor
    func testDefaultFormatIsJPEG() async throws {
        let (coord, store) = makeCoordinator()
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try XCTUnwrap(store.loadAll().first).encoderFormat, .jpeg)
    }

    // MARK: - Task 5: Photos auto-export

    @MainActor
    func testPhotosExportRunsOnlyWhenEnabled() async throws {
        let exported = ExportLog()
        let (coord, _) = makeCoordinator()
        coord.photosExporter = { data, _ in await exported.record(data.count) }
        await coord.shoot()                       // saveToPhotosEnabled defaults to false
        await coord.awaitProcessing()
        let countDisabled = await exported.count
        XCTAssertEqual(countDisabled, 0, "no export when the toggle is off")
        coord.saveToPhotosEnabled = true
        await coord.shoot()
        await coord.awaitProcessing()
        try await Task.sleep(nanoseconds: 200_000_000)   // export is fire-and-forget
        let countEnabled = await exported.count
        XCTAssertEqual(countEnabled, 1, "one export per save when enabled")
        XCTAssertNil(coord.photosExportNote)
    }

    @MainActor
    func testPhotosExportFailureIsNonBlocking() async throws {
        let (coord, store) = makeCoordinator()
        coord.saveToPhotosEnabled = true
        coord.photosExporter = { _, _ in throw CaptureError.busy }   // any error
        await coord.shoot()
        await coord.awaitProcessing()
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(try store.loadAll().count, 1, "in-app save unaffected")
        XCTAssertNotNil(coord.photosExportNote, "failure surfaces as a note, not an error")
        XCTAssertNil(coord.lastError)
    }

    // MARK: - Task 6: RAW capability probe

    @MainActor
    func testSupportsRAWIsTrueWithTheFake() async {
        let (coord, _) = makeCoordinator()
        _ = await coord.startPreview()
        XCTAssertTrue(coord.supportsRAW)
    }

    // MARK: - Task 2 (blend-strength) tests

    @MainActor
    func testShootSavesAReferenceForBlendableLooks() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .smoothMotion
        await coord.shoot()
        await coord.awaitProcessing()
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertNotNil(store.referenceData(for: rec.id), "long-exposure looks store the blend reference")
    }

    @MainActor
    func testDepthShootSavesNoReference() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .depthOfField
        await coord.shoot()
        await coord.awaitProcessing()
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertNil(store.referenceData(for: rec.id), "no blend semantics for focus stacks")
    }

    // MARK: - Task 1: CaptureEnvironment policy

    @MainActor
    func testCriticalThermalBlocksTheShot() async throws {
        let (coord, store) = makeCoordinator()
        coord.environment = CaptureEnvironment(thermalState: { .critical }, batteryLevel: { 1 },
                                               batteryCharging: { false }, freeDiskBytes: { .max })
        await coord.shoot()
        XCTAssertEqual(coord.lastError, "Too hot — let the phone cool down.")
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().count, 0)
    }

    @MainActor
    func testSeriousThermalHalvesTheBurst() async throws {
        let (coord, store) = makeCoordinator()
        coord.environment = CaptureEnvironment(thermalState: { .serious }, batteryLevel: { 1 },
                                               batteryCharging: { false }, freeDiskBytes: { .max })
        coord.mode = .noiseReduction                      // base recipe = 8 frames
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().first?.frameCount, 4, "serious thermal halves the burst")
        XCTAssertNotNil(coord.environmentNote)
    }

    @MainActor
    func testLowStorageBlocksTheShot() async throws {
        let (coord, store) = makeCoordinator()
        coord.environment = CaptureEnvironment(thermalState: { .nominal }, batteryLevel: { 1 },
                                               batteryCharging: { false }, freeDiskBytes: { 50_000_000 })
        await coord.shoot()
        XCTAssertEqual(coord.lastError, "Not enough storage to capture.")
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().count, 0)
    }

    @MainActor
    func testLowBatteryWarnsButShoots() async throws {
        let (coord, store) = makeCoordinator()
        coord.environment = CaptureEnvironment(thermalState: { .nominal }, batteryLevel: { 0.05 },
                                               batteryCharging: { false }, freeDiskBytes: { .max })
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try store.loadAll().count, 1, "low battery never blocks")
        XCTAssertEqual(coord.environmentNote, "Low battery")
    }

    // MARK: - Task 2 (CapturedBurst / non-RAW fallback) tests

    @MainActor
    func testDevelopedBurstSavesForStaticAndLongExposure() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: DevelopedFake(width: 16, height: 16, count: 20), store: store)
        for mode in [StackMode.noiseReduction, .smoothMotion] {
            coord.mode = mode
            await coord.shoot()
            await coord.awaitProcessing()
            XCTAssertNil(coord.lastError, "\(mode)")
        }
        XCTAssertEqual(try store.loadAll().count, 2)
        // Blendable look on the fallback path still stores a reference.
        let smooth = try XCTUnwrap(store.loadAll().first { $0.mode == "smoothMotion" })
        XCTAssertNotNil(store.referenceData(for: smooth.id))
    }

    @MainActor
    func testDevelopedBurstDepthSaves() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: DevelopedFake(width: 24, height: 16, count: 10), store: store)
        coord.mode = .depthOfField
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertNil(coord.lastError)
        XCTAssertEqual(try store.loadAll().first?.mode, "depthOfField")
    }
}

// MARK: - Helpers

/// Fallback-path fake: returns already-developed frames, as a non-RAW device's HEIC path would.
private struct DevelopedFake: CaptureService {
    let width: Int, height: Int, count: Int
    func startPreview() async -> CALayer? { nil }
    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool,
                      onProgress: (@Sendable (Int) -> Void)?) async throws -> CapturedBurst {
        await Task.yield()
        let n = min(recipe.frameCount, count)
        let imgs = (0..<n).map { k -> PixelImage in
            var img = PixelImage(width: width, height: height, fill: SIMD3<Float>(0.4, 0.4, 0.4))
            img[k % width, 0] = SIMD3<Float>(0.9, 0.9, 0.9)   // per-frame variation
            onProgress?(k + 1)
            return img
        }
        return .developed(imgs)
    }
    var supportsRAWCapture: Bool { false }
}

private actor ExportLog {
    private(set) var count = 0
    func record(_ n: Int) { count += 1 }
}
