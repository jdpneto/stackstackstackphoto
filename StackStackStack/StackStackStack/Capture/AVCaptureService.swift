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
    private var remaining = 0                   // frames still to capture, including the in-flight one
    private var continuation: CheckedContinuation<[RawSensorFrame], Error>?
    private var generation = 0                  // bumps per burst; stale captures/timeouts are ignored
    private var currentID: Int64?               // settings.uniqueID of the in-flight capture (nil between frames)
    private var rawType: OSType = 0             // Bayer RAW format used to build each frame's settings
    private var pacingInterval = 0.0            // minimum delay between consecutive frame starts
    private var perFrameTimeout = 0.0           // watchdog horizon for a single capture
    // Touched only on sessionQueue.
    private var configured = false

    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
        try await ensureAuthorized()
        try await ensureConfigured()
        try await lockExposureAndFocus(recipe: recipe)   // waits for manual exposure/focus to settle

        let frameCount = recipe.frameCount
        // Minimum start-to-start spacing so long-exposure looks sample motion over time. With the
        // SEQUENTIAL burst below, the real spacing is at least one capture's own duration, so this
        // is only a floor (small for the fast looks; the frame count drives the long-exposure span).
        let interval = recipe.durationSeconds / Double(max(frameCount - 1, 1))
        // Per-frame watchdog: if a single capture never reports completion (interruption, thermal,
        // drop) we give up on THAT frame and advance, instead of hanging the whole burst.
        let frameTimeout = max(recipe.manualShutterSeconds ?? 0, 1.0) * 3 + 4.0

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
                self.pending = []
                self.continuation = cont
                self.remaining = frameCount
                self.currentID = nil
                self.rawType = rawType
                self.pacingInterval = interval
                self.perFrameTimeout = frameTimeout
                self.startNextFrameLocked(gen: self.generation)
            }
        }
    }

    /// Issue the next capture in the burst, or finish if none remain. Must run on `stateQueue`.
    /// In-flight RAW requests are bounded to exactly one — the still-image (Iris) pipeline bails
    /// (FigCapture err -12773) and wedges the session if requests overlap, so the previous frame
    /// must fully complete (`didFinishCaptureFor`) before the next one is requested.
    private func startNextFrameLocked(gen: Int) {
        guard self.generation == gen, self.continuation != nil else { return }
        guard self.remaining > 0 else { self.finishLocked(); return }

        let settings = AVCapturePhotoSettings(rawPixelFormatType: self.rawType)
        let id = settings.uniqueID
        self.currentID = id

        self.sessionQueue.async {
            // Don't fire for a superseded burst or a stopped session — fail this frame so the burst
            // advances instead of stalling until the watchdog.
            let active = self.stateQueue.sync { self.generation == gen }
            guard active, self.session.isRunning else {
                self.stateQueue.async { self.advanceLocked(completedID: id) }
                return
            }
            self.output.capturePhoto(with: settings, delegate: self)
        }

        self.stateQueue.asyncAfter(deadline: .now() + self.perFrameTimeout) {
            self.advanceLocked(completedID: id)   // no-op if the frame already completed
        }
    }

    /// Mark the in-flight capture `completedID` finished, then pace the next frame or end the burst.
    /// Idempotent per frame: the real callback and the watchdog both call this, but only the first
    /// (while `currentID == completedID`) takes effect. Must run on `stateQueue`.
    private func advanceLocked(completedID: Int64) {
        guard self.continuation != nil, self.currentID == completedID else { return }
        self.currentID = nil
        self.remaining -= 1
        guard self.remaining > 0 else { self.finishLocked(); return }
        let gen = self.generation
        self.stateQueue.asyncAfter(deadline: .now() + self.pacingInterval) {
            self.startNextFrameLocked(gen: gen)
        }
    }

    /// Start the camera (authorize → configure → run) and return a preview layer bound to the
    /// session, so the capture screen shows a live viewfinder. Idempotent (config is cached).
    func startPreview() async -> CALayer? {
        do {
            try await ensureAuthorized()
            try await ensureConfigured()
        } catch {
            return nil   // no permission / no camera → no preview (the UI shows its neutral background)
        }
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        return layer
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
                    // #3 efficient RAW: use the sensor's classic ~12 MP BINNED Bayer readout, NOT
                    // ProRAW (the 48 MP quad-Bayer path) — 4× less data, better low-light stacking,
                    // and the size the CPU develop+stack pipeline can actually handle. ProRAW off.
                    if self.output.isAppleProRAWSupported { self.output.isAppleProRAWEnabled = false }
                    self.output.maxPhotoQualityPrioritization = .balanced   // lower per-frame burst latency
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
        currentID = nil            // ignore any late stragglers / watchdog fires
        let frames = pending
        pending = []
        if frames.isEmpty { cont.resume(throwing: CaptureError.noFramesProduced) }
        else { cont.resume(returning: frames) }
    }
}

extension AVCaptureService: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        // Convert outside the state queue (touches no shared state), then collect it for the burst.
        // Advancing to the next frame happens in `didFinishCaptureFor` (the capture's final callback).
        let frame = error == nil ? RawFrameConverter.make(from: photo) : nil
        let id = photo.resolvedSettings.uniqueID
        stateQueue.async {
            guard self.continuation != nil, id == self.currentID else { return }   // stale/superseded
            if let frame { self.pending.append(frame) }
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        // Last callback for a capture: the still-image pipeline is now free, so it's safe to request
        // the next frame. Advancing only here is what bounds in-flight requests to one.
        let id = resolvedSettings.uniqueID
        stateQueue.async { self.advanceLocked(completedID: id) }
    }
}
