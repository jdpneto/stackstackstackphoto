import AVFoundation
import StackEngineCore

enum CaptureError: LocalizedError {
    case permissionDenied
    case noDevice
    case noRawFormat
    case noFramesProduced
    case busy

    var errorDescription: String? {
        switch self {
        case .permissionDenied: return "Camera access is off. Enable it in Settings ▸ Privacy ▸ Camera."
        case .noDevice:         return "No camera is available on this device."
        case .noRawFormat:      return "This device's camera doesn't support RAW capture."
        case .noFramesProduced: return "Couldn't read the captured frames. Please try again."
        case .busy:             return "A capture is already in progress."
        }
    }
}

/// Captures a short Bayer-RAW burst with locked exposure/focus (design §10.4, noise recipe).
///
/// All mutable burst state (`pending`/`remaining`/`continuation`/`generation`/`expectedIDs`) is
/// confined to `stateQueue`, and ALL device/session/output access is confined to `sessionQueue`, so
/// the AVFoundation delegate callbacks and the caller never race and the continuation is resumed
/// exactly once. `@unchecked Sendable`: thread-safe by queue confinement, which the compiler can't
/// verify. (Device path — exercised on hardware; the Simulator uses `FakeCaptureService`.)
final class AVCaptureService: NSObject, CaptureService, @unchecked Sendable {
    private let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private var device: AVCaptureDevice?

    private let stateQueue = DispatchQueue(label: "com.jdpneto.stackstackstack.capture.state")
    private let sessionQueue = DispatchQueue(label: "com.jdpneto.stackstackstack.capture.session")

    // Touched only on stateQueue.
    private var pending: [RawSensorFrame] = []
    private var remaining = 0
    private var continuation: CheckedContinuation<[RawSensorFrame], Error>?
    private var generation = 0                 // bumps per burst; stale captures/timeouts are ignored
    private var expectedIDs: Set<Int64> = []   // settings.uniqueID of the ACTIVE burst's captures
    // Touched only on sessionQueue.
    private var configured = false

    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
        try await ensureAuthorized()
        try await ensureConfigured()
        try await lockExposureAndFocus(recipe: recipe)   // waits for manual exposure/focus to settle

        let frameCount = recipe.frameCount
        // Spread the captures across the recipe's window so long-exposure looks sample motion over
        // time — but never faster than a manual exposure can complete (so captures don't overlap).
        let interval = max(recipe.durationSeconds / Double(max(frameCount - 1, 1)),
                           recipe.manualShutterSeconds ?? 0)
        // Watchdog horizon: the whole scheduled span plus generous slack, so a dropped delegate
        // callback (session interruption, thermal, backgrounding) can't hang the burst forever.
        let timeout = interval * Double(frameCount) + max(recipe.manualShutterSeconds ?? 0, 1.0) * 3 + 5.0

        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<[RawSensorFrame], Error>) in
            self.stateQueue.async {
                guard self.continuation == nil else { cont.resume(throwing: CaptureError.busy); return }
                guard frameCount > 0 else { cont.resume(throwing: CaptureError.noFramesProduced); return }
                let rawTypes = self.output.availableRawPhotoPixelFormatTypes
                // Require a Bayer format the single-plane reader can decode. NOT `?? rawTypes.first`:
                // feeding a ProRAW/other format to AVCapturePhotoSettings(rawPixelFormatType:) can
                // raise an uncatchable NSException, and the converter couldn't read it anyway.
                guard let rawType = rawTypes.first(where: { RawFrameConverter.isSupportedBayerFormat($0) }) else {
                    cont.resume(throwing: CaptureError.noRawFormat); return
                }

                self.generation += 1
                let gen = self.generation
                self.pending = []
                self.continuation = cont
                self.remaining = frameCount
                // Build distinct settings up-front so the delegate can tie each callback to THIS
                // burst by uniqueID (and ignore stragglers from an abandoned earlier burst).
                let settingsList = (0..<frameCount).map { _ in AVCapturePhotoSettings(rawPixelFormatType: rawType) }
                self.expectedIDs = Set(settingsList.map { $0.uniqueID })

                for (i, settings) in settingsList.enumerated() {
                    self.sessionQueue.asyncAfter(deadline: .now() + interval * Double(i)) {
                        // Don't fire a capture for a burst that was superseded, or on a stopped session.
                        let active = self.stateQueue.sync { self.generation == gen }
                        guard active, self.session.isRunning else { return }
                        self.output.capturePhoto(with: settings, delegate: self)
                    }
                }
                self.stateQueue.asyncAfter(deadline: .now() + timeout) {
                    guard self.generation == gen, self.continuation != nil else { return }
                    self.finishLocked()   // resume with whatever frames arrived (or noFramesProduced)
                }
            }
        }
    }

    // MARK: - Authorization & lazy configuration

    private func ensureAuthorized() async throws {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return
        case .notDetermined:
            if await AVCaptureDevice.requestAccess(for: .video) == false { throw CaptureError.permissionDenied }
        default:
            throw CaptureError.permissionDenied
        }
    }

    private func ensureConfigured() async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            sessionQueue.async {
                if self.configured { cont.resume(); return }
                do {
                    guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
                    else { throw CaptureError.noDevice }
                    let input = try AVCaptureDeviceInput(device: dev)
                    self.session.beginConfiguration()
                    self.session.sessionPreset = .photo
                    if self.session.canAddInput(input) { self.session.addInput(input) }
                    if self.session.canAddOutput(self.output) { self.session.addOutput(self.output) }
                    self.output.maxPhotoQualityPrioritization = .quality   // best RAW quality
                    self.session.commitConfiguration()
                    self.session.startRunning()  // off the main thread (sessionQueue)
                    self.device = dev
                    self.configured = true
                    // Bayer RAW must be vended by the configured output, else capture can't produce frames.
                    guard !self.output.availableRawPhotoPixelFormatTypes.isEmpty else {
                        throw CaptureError.noRawFormat
                    }
                    cont.resume()
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
    }

    /// Lock exposure/focus/WB for a stable burst. Honour any manual Pro overrides on the recipe;
    /// otherwise lock to the metered auto values. Manual values are clamped to the device's range.
    /// Awaits convergence of any manual exposure/focus so the first frames aren't shot mid-transition.
    /// Runs entirely on `sessionQueue` (device-config confinement); the lock is held only while
    /// issuing settings, not across convergence.
    private func lockExposureAndFocus(recipe: CaptureRecipe) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            self.sessionQueue.async {
                guard let dev = self.device else { cont.resume(throwing: CaptureError.noDevice); return }
                do {
                    try dev.lockForConfiguration()
                } catch {
                    cont.resume(throwing: error); return
                }
                let settle = DispatchGroup()   // fires once any manual exposure/focus has converged

                // Manual exposure (ISO and/or shutter) → custom exposure; else lock the auto value.
                if recipe.manualISO != nil || recipe.manualShutterSeconds != nil,
                   dev.isExposureModeSupported(.custom) {
                    let fmt = dev.activeFormat
                    let duration: CMTime = recipe.manualShutterSeconds
                        .map { CMTime(seconds: $0, preferredTimescale: 1_000_000) }
                        .map { min(max($0, fmt.minExposureDuration), fmt.maxExposureDuration) }
                        ?? AVCaptureDevice.currentExposureDuration
                    let iso: Float = recipe.manualISO
                        .map { min(max($0, fmt.minISO), fmt.maxISO) }
                        ?? AVCaptureDevice.currentISO
                    settle.enter()
                    dev.setExposureModeCustom(duration: duration, iso: iso) { _ in settle.leave() }
                } else if dev.isExposureModeSupported(.locked) {
                    dev.exposureMode = .locked   // also covers manual-requested but .custom unsupported
                }

                // Manual focus → locked lens position; else lock focus.
                if let focus = recipe.manualFocus, dev.isLockingFocusWithCustomLensPositionSupported {
                    settle.enter()
                    dev.setFocusModeLocked(lensPosition: min(max(focus, 0), 1)) { _ in settle.leave() }
                } else if dev.isFocusModeSupported(.locked) {
                    dev.focusMode = .locked   // also covers manual-requested but custom lens unsupported
                }

                if dev.isWhiteBalanceModeSupported(.locked) { dev.whiteBalanceMode = .locked }
                dev.unlockForConfiguration()   // release the lock before awaiting convergence
                settle.notify(queue: self.sessionQueue) { cont.resume() }
            }
        }
    }

    // MARK: - Completion (always on stateQueue)

    /// Resume the continuation exactly once and clear per-burst state. Must run on `stateQueue`.
    private func finishLocked() {
        guard let cont = continuation else { return }
        continuation = nil
        expectedIDs = []           // ignore any late stragglers
        let frames = pending
        pending = []
        if frames.isEmpty { cont.resume(throwing: CaptureError.noFramesProduced) }
        else { cont.resume(returning: frames) }
    }
}

extension AVCaptureService: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        // Convert outside the state queue (touches no shared state), then mutate state serially.
        let frame = error == nil ? RawFrameConverter.make(from: photo) : nil
        let id = photo.resolvedSettings.uniqueID
        stateQueue.async {
            guard self.expectedIDs.remove(id) != nil else { return }   // not from the active burst → ignore
            if let frame { self.pending.append(frame) }
            self.remaining -= 1
            if self.remaining <= 0 { self.finishLocked() }
        }
    }
}
