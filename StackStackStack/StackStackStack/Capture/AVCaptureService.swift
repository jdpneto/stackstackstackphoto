import AVFoundation
import StackEngineCore

enum CaptureError: LocalizedError {
    case permissionDenied
    case noDevice
    case noFramesProduced
    case busy

    var errorDescription: String? {
        switch self {
        case .permissionDenied: return "Camera access is off. Enable it in Settings ▸ Privacy ▸ Camera."
        case .noDevice:         return "No camera is available on this device."
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
    // Off the capture path: the per-frame RAW→mosaic copy (12 MP) must NOT run in the AVFoundation
    // delegate callback, or it blocks advancing to the next frame. Hand the photo here instead.
    private let processingQueue = DispatchQueue(label: "com.jdpneto.stackstackstack.capture.processing")

    // Touched only on stateQueue.
    private var burstInfo: CaptureInfo?              // set-once from the first frame; nil = not yet captured
    private var pending: [RawSensorFrame] = []
    private var pendingDeveloped: [PixelImage] = []   // fallback path: HEIC-decoded linear frames
    private var fallbackHEIC = false                  // true when no Bayer RAW format is available
    private var remaining = 0                   // frames still to capture, including the in-flight one
    private var outstanding = 0                 // RAW→frame conversions still running off the capture path
    private var continuation: CheckedContinuation<CapturedBurst, Error>?
    private var generation = 0                  // bumps per burst; stale captures/timeouts are ignored
    private var currentID: Int64?               // settings.uniqueID of the in-flight capture (nil between frames)
    private var rawType: OSType = 0             // Bayer RAW format used to build each frame's settings
    private var pacingInterval = 0.0            // delay between consecutive captures (see captureBurst)
    private var perFrameTimeout = 0.0           // watchdog horizon for a single capture
    private var totalFrames = 0                  // frames requested this burst (to detect the first frame)
    private var isSteadyCheck: () -> Bool = { true }   // steadiness gate; { true } = ungated
    private var onProgress: (@Sendable (Int) -> Void)?  // per-frame progress callback; nil = none
    private var gateAttempts = 0                 // consecutive off-pose rechecks for the current frame
    private let gateRecheckInterval = 0.1        // seconds between steadiness rechecks
    private let maxStartGateAttempts = 50        // ~5s: wait for the first frame to be steady, then fire anyway
    private let maxFrameGateAttempts = 30        // ~3s: if a later frame can't get steady, end the burst
    private var sweepPositions: [Float] = []     // Depth: per-frame lens positions; empty = no sweep
    private var manualLensSupported = true       // probed at configure; optimistic until then
    private var rawSupported = true              // probed at configure; optimistic until then
    private var hevcSupported = false            // probed at configure on sessionQueue; read on stateQueue
    // Touched only on sessionQueue.
    private var configured = false
    // Computed once at configure (sessionQueue), read when building each capture's settings. The
    // largest supported photo size not exceeding ~12 MP (4032x3024) — keeps RAW at the binned
    // readout, never the 48 MP path, even if a device's default differs. (design 2026-06-07 §4)
    private var cappedPhotoDimensions: CMVideoDimensions?

    func captureBurst(recipe: CaptureRecipe, isSteady: @escaping @Sendable () -> Bool,
                      onProgress: (@Sendable (Int) -> Void)?) async throws -> CapturedBurst {
        try await ensureAuthorized()
        try await ensureConfigured()
        try await lockExposureAndFocus(recipe: recipe)   // waits for manual exposure/focus to settle

        let frameCount = recipe.frameCount
        // Delay between consecutive captures: spreads a long-exposure look's frames across the recipe
        // window so motion is sampled over time, and keeps the RAW pipeline from being overrun. (The
        // ~8-frame burst stall was the slow buffer copy holding RAW buffers — fixed in
        // RawFrameConverter — so this floor is small.)
        let pacing = max(recipe.durationSeconds / Double(max(frameCount - 1, 1)), 0.05)
        // Per-frame watchdog: if a single capture never reports completion (interruption, drop) we
        // end the burst with the frames gathered so far, instead of hanging.
        let frameTimeout = max(recipe.manualShutterSeconds ?? 0, 1.0) * 3 + 4.0

        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<CapturedBurst, Error>) in
            self.stateQueue.async {
                guard self.continuation == nil else { cont.resume(throwing: CaptureError.busy); return }
                guard frameCount > 0 else { cont.resume(throwing: CaptureError.noFramesProduced); return }
                let rawTypes = self.output.availableRawPhotoPixelFormatTypes
                // Find a Bayer format the single-plane reader can decode. NOT `?? rawTypes.first`:
                // feeding a ProRAW/other format to AVCapturePhotoSettings(rawPixelFormatType:) can
                // raise an uncatchable NSException, and the converter couldn't read it anyway.
                // When no Bayer format exists, fall back to HEIC capture (non-RAW "Standard quality").
                let bayerType = rawTypes.first(where: { RawFrameConverter.isSupportedBayerFormat($0) })

                self.generation += 1
                self.pending = []
                self.pendingDeveloped = []
                self.burstInfo = nil
                self.outstanding = 0
                self.continuation = cont
                self.remaining = frameCount
                self.totalFrames = frameCount
                self.isSteadyCheck = isSteady
                self.onProgress = onProgress
                self.sweepPositions = recipe.focusSweep?.positions ?? []
                self.gateAttempts = 0
                self.currentID = nil
                self.rawType = bayerType ?? 0
                self.fallbackHEIC = bayerType == nil
                self.pacingInterval = pacing
                self.perFrameTimeout = frameTimeout
                self.startNextFrameLocked(gen: self.generation)
            }
        }
    }

    /// Long-edge pixel cap for HEIC fallback decode. Standard-quality ceiling (bible §5.3): 1500 px
    /// keeps a 30-frame fallback burst ≈ 0.8 GB instead of ≈ 2 GB at 2400 px — the fallback serves
    /// exactly the hardware that can least afford the difference. Independent of the RAW path's
    /// managedWorkingResolution by design.
    private static let fallbackDecodeLongEdge = 1500

    /// Issue the next capture in the burst, or finish if none remain. Must run on `stateQueue`.
    /// In-flight RAW requests are bounded to exactly one — the still-image (Iris) pipeline bails
    /// (FigCapture err -12773) and wedges the session if requests overlap, so the previous frame
    /// must fully complete (`didFinishCaptureFor`) before the next one is requested.
    private func startNextFrameLocked(gen: Int) {
        guard self.generation == gen, self.continuation != nil else { return }
        guard self.remaining > 0 else { self.maybeFinishLocked(); return }

        // Steadiness gate (long-exposure looks): don't consume a frame while off-pose.
        if !self.isSteadyCheck() {
            self.gateAttempts += 1
            let isFirst = (self.remaining == self.totalFrames)
            let maxAttempts = isFirst ? self.maxStartGateAttempts : self.maxFrameGateAttempts
            if self.gateAttempts <= maxAttempts {
                self.stateQueue.asyncAfter(deadline: .now() + self.gateRecheckInterval) {
                    self.startNextFrameLocked(gen: gen)        // recheck; don't advance
                }
                return
            }
            if !isFirst {
                // Later frame never steadied within the window → stop requesting frames, stack what we have.
                self.remaining = 0
                self.maybeFinishLocked()
                return
            }
            // First frame timed out → fall through and capture anyway so the shot always yields ≥1 frame.
        }
        self.gateAttempts = 0   // reset for the next frame

        // Settings: Bayer RAW on supported hardware; HEIC/JPEG on the non-RAW fallback path.
        // NOT `?? rawTypes.first`: feeding an unsupported format to rawPixelFormatType raises an
        // uncatchable NSException; the fallback uses HEVC where the hardware lists it (probed once
        // in ensureConfigured on sessionQueue into hevcSupported), otherwise default JPEG settings —
        // never construct settings with a codec the output doesn't advertise. (spec 2026-06-11 §3, Fix 3)
        let settings: AVCapturePhotoSettings
        if self.fallbackHEIC {
            settings = self.hevcSupported
                ? AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.hevc])
                : AVCapturePhotoSettings()
        } else {
            settings = AVCapturePhotoSettings(rawPixelFormatType: self.rawType)
        }
        settings.photoQualityPrioritization = .speed   // minimize per-frame capture latency
        let id = settings.uniqueID
        self.currentID = id

        let frameIndex = self.totalFrames - self.remaining
        let sweepPosition: Float? =
            (frameIndex >= 0 && frameIndex < self.sweepPositions.count) ? self.sweepPositions[frameIndex] : nil

        self.sessionQueue.async {
            // Don't fire for a superseded burst or a stopped session — fail this frame so the burst
            // advances instead of stalling until the watchdog.
            let active = self.stateQueue.sync { self.generation == gen }
            guard active, self.session.isRunning else {
                self.stateQueue.async { self.advanceLocked(completedID: id) }
                return
            }
            if let dims = self.cappedPhotoDimensions { settings.maxPhotoDimensions = dims }
            self.stepSweepFocusThenFire(position: sweepPosition) {
                self.output.capturePhoto(with: settings, delegate: self)
            }
        }

        self.stateQueue.asyncAfter(deadline: .now() + self.perFrameTimeout) {
            self.timeoutFrameLocked(stuckID: id)   // no-op if the frame already completed
        }
    }

    /// Watchdog: a capture that hasn't reported back within `perFrameTimeout` is treated as a stall.
    /// Stop requesting NEW frames (don't advance — firing the next capture while a stalled, maybe
    /// still-in-flight request lingers would re-create the FigCapture -12773 overlap this sequential
    /// design avoids), but still wait for any already-captured frames to finish converting off-queue
    /// before resuming, so we don't drop them. Must run on `stateQueue`.
    private func timeoutFrameLocked(stuckID: Int64) {
        guard self.continuation != nil, self.currentID == stuckID else { return }   // already advanced
        self.currentID = nil
        self.remaining = 0            // request no more frames…
        self.maybeFinishLocked()      // …but finish only once outstanding conversions drain
    }

    /// Mark the in-flight capture `completedID` finished, then request the next frame after
    /// `pacingInterval` — never back-to-back: the RAW pipeline stalls after ~7 rapid captures, so it
    /// needs time to drain between frames. Idempotent per frame: the real callback and the watchdog
    /// both call this, but only the first (while `currentID == completedID`) takes effect. Must run
    /// on `stateQueue`.
    private func advanceLocked(completedID: Int64) {
        guard self.continuation != nil, self.currentID == completedID else { return }
        self.currentID = nil
        self.remaining -= 1
        guard self.remaining > 0 else { self.maybeFinishLocked(); return }
        let gen = self.generation
        self.stateQueue.asyncAfter(deadline: .now() + self.pacingInterval) {
            self.startNextFrameLocked(gen: gen)
        }
    }

    /// Resume the continuation once every capture has been requested AND every off-queue conversion
    /// has finished — otherwise the last frame(s) could be dropped while still converting. Must run
    /// on `stateQueue`.
    private func maybeFinishLocked() {
        guard self.continuation != nil, self.remaining <= 0, self.outstanding == 0 else { return }
        self.finishLocked()
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
                    // We develop the RAW ourselves, so the system's quality processing is wasted work —
                    // prioritize speed to minimize per-frame latency (the arms-up capture must be fast).
                    self.output.maxPhotoQualityPrioritization = .speed
                    // Cap photo dimensions to ~12 MP. Pick the largest supported size within the
                    // target; if every supported size is larger, fall back to the smallest (closest
                    // to 12 MP) so we never silently capture a 48 MP RAW.
                    let target = CMVideoDimensions(width: 4032, height: 3024)
                    let supported = dev.activeFormat.supportedMaxPhotoDimensions
                    let area: (CMVideoDimensions) -> Int = { Int($0.width) * Int($0.height) }
                    self.cappedPhotoDimensions =
                        supported.filter { $0.width <= target.width && $0.height <= target.height }
                                 .max(by: { area($0) < area($1) })
                        ?? supported.min(by: { area($0) < area($1) })
                        ?? target
                    self.session.commitConfiguration()
                    self.session.startRunning()  // off the main thread (sessionQueue)
                    self.device = dev
                    self.configured = true
                    let lensSupported = dev.isLockingFocusWithCustomLensPositionSupported
                    self.stateQueue.async { self.manualLensSupported = lensSupported }
                    let rawOK = self.output.availableRawPhotoPixelFormatTypes
                        .contains(where: { RawFrameConverter.isSupportedBayerFormat($0) })
                    // Probe HEVC availability once here on sessionQueue (the only queue where output
                    // configuration is safe to read). Constructing AVCapturePhotoSettings with a codec
                    // not in availablePhotoCodecTypes raises an uncatchable NSException — guard it here
                    // rather than at each frame's settings build. (Fix 3)
                    let hevcOK = self.output.availablePhotoCodecTypes.contains(.hevc)
                    self.stateQueue.async {
                        self.rawSupported = rawOK
                        self.hevcSupported = hevcOK
                    }
                    // No RAW capability is NOT a configure failure — the burst falls back to HEIC ("Standard quality") and the rawSupported probe reports it.
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

    /// Depth sweep: step the lens to this frame's position and WAIT for the lens to settle before
    /// firing — capturing mid-travel would blur the bracket. No sweep position, no manual-lens
    /// support, or a config-lock failure all degrade to firing at the current focus (a worse
    /// bracket beats a stalled burst; the watchdog never has to save us). Runs on `sessionQueue`.
    /// (spec 2026-06-10 §5.2; same settle pattern as lockExposureAndFocus's manual-focus path)
    private func stepSweepFocusThenFire(position: Float?, fire: @escaping () -> Void) {
        guard let position, let dev = self.device,
              dev.isLockingFocusWithCustomLensPositionSupported else { fire(); return }
        do { try dev.lockForConfiguration() } catch { fire(); return }
        dev.setFocusModeLocked(lensPosition: min(max(position, 0), 1)) { _ in
            self.sessionQueue.async { fire() }
        }
        dev.unlockForConfiguration()
    }

    /// Focus + meter exposure at `point` (normalized device coords). Tap → one-shot autofocus with
    /// continuous exposure metering at the point; long-press (`lock`) → one-shot AF + AE that hold
    /// (AE/AF lock). Runs on `sessionQueue`; every step capability-guarded. (design tap-to-focus §3.2)
    func setFocusExposure(atDevicePoint point: CGPoint, lock: Bool) {
        sessionQueue.async {
            // A stale tap can land here after a burst began (the MainActor gate races the gesture by
            // one runloop turn). Mid-burst it would fight the locked focus — and a Depth sweep's
            // per-frame lens steps — so drop it; tapping is a viewfinder-only affordance.
            guard self.stateQueue.sync(execute: { self.continuation == nil }) else { return }
            guard let dev = self.device else { return }   // session not yet configured → no-op until startPreview completes
            do { try dev.lockForConfiguration() } catch { return }
            if dev.isFocusPointOfInterestSupported {
                dev.focusPointOfInterest = point
                if dev.isFocusModeSupported(.autoFocus) { dev.focusMode = .autoFocus }   // one sweep, then holds
            }
            if dev.isExposurePointOfInterestSupported {
                dev.exposurePointOfInterest = point
                // Lock: meter once and hold (.autoExpose), else freeze (.locked). Tap: keep metering the
                // subject (.continuousAutoExposure), else meter once (.autoExpose).
                let primary: AVCaptureDevice.ExposureMode = lock ? .autoExpose : .continuousAutoExposure
                let fallback: AVCaptureDevice.ExposureMode = lock ? .locked : .autoExpose
                if dev.isExposureModeSupported(primary) { dev.exposureMode = primary }
                else if dev.isExposureModeSupported(fallback) { dev.exposureMode = fallback }
            }
            // White balance is intentionally left as-is — it's locked per-burst by lockExposureAndFocus and
            // re-locked at the next shoot; tap-to-focus only adjusts focus + exposure (design tap-to-focus §2).
            dev.unlockForConfiguration()
        }
    }

    /// Manual-focus capability, probed when the session configures (spec 2026-06-10 §5.4).
    var supportsDepthOfField: Bool { stateQueue.sync { manualLensSupported } }
    /// Bayer RAW capture capability, probed when the session configures.
    var supportsRAWCapture: Bool { stateQueue.sync { rawSupported } }

    // MARK: - Completion (always on stateQueue)

    /// Resume the continuation exactly once and clear per-burst state. Must run on `stateQueue`.
    private func finishLocked() {
        guard let cont = continuation else { return }
        continuation = nil
        currentID = nil            // ignore any late stragglers / watchdog fires
        isSteadyCheck = { true }    // release any captured coordinator/MotionSteadiness reference
        onProgress = nil            // release any captured coordinator reference
        sweepPositions = []
        let info = burstInfo
        burstInfo = nil
        if fallbackHEIC {
            let imgs = pendingDeveloped
            pendingDeveloped = []
            if imgs.isEmpty { cont.resume(throwing: CaptureError.noFramesProduced) }
            else { cont.resume(returning: CapturedBurst(payload: .developed(imgs), info: info)) }
        } else {
            let frames = pending
            pending = []
            if frames.isEmpty { cont.resume(throwing: CaptureError.noFramesProduced) }
            else { cont.resume(returning: CapturedBurst(payload: .raw(frames), info: info)) }
        }
    }
}

extension AVCaptureService: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        // Do NOT convert here — `RawFrameConverter.make` copies a 12 MP buffer, and this callback runs
        // BEFORE `didFinishCaptureFor`, so converting inline would stall the next frame. Hand the photo
        // to the processing queue and let capture advance immediately; collect the frame when it's ready.
        let id = photo.resolvedSettings.uniqueID
        stateQueue.async {
            guard self.continuation != nil, id == self.currentID else { return }   // stale/superseded
            let gen = self.generation
            let isFallback = self.fallbackHEIC
            self.outstanding += 1
            self.processingQueue.async {
                // Extract first-frame CaptureInfo from photo.metadata on processingQueue (where
                // RAW conversion also runs), then write it to stateQueue set-once (burstInfo == nil
                // guard). This ensures the EXIF parse doesn't block the AVFoundation callback thread
                // and doesn't race with the stateQueue's mutable burst state.
                let extractedInfo: CaptureInfo? = {
                    guard let exifDict = photo.metadata[kCGImagePropertyExifDictionary as String] as? [String: Any]
                    else { return nil }
                    let iso = (exifDict[kCGImagePropertyExifISOSpeedRatings as String] as? [Double])?.first
                        ?? (exifDict[kCGImagePropertyExifISOSpeedRatings as String] as? [Int]).flatMap { $0.first }.map(Double.init)
                    let shutter = exifDict[kCGImagePropertyExifExposureTime as String] as? Double
                    guard iso != nil || shutter != nil else { return nil }
                    return CaptureInfo(iso: iso, shutterSeconds: shutter)
                }()
                if let extractedInfo {
                    self.stateQueue.async {
                        if self.burstInfo == nil { self.burstInfo = extractedInfo }   // set-once: first frame wins
                    }
                }
                if isFallback {
                    // Non-RAW fallback: decode HEIC to sRGB RGBA8 at working resolution, then
                    // linearise to PixelImage. A failed decode skips the frame (same as a failed
                    // RAW conversion). (spec 2026-06-11 §3 / §4)
                    let decoded: PixelImage? = {
                        guard error == nil,
                              let data = photo.fileDataRepresentation(),
                              let (rgba, w, h) = ImageDecoder.rgba8(from: data, maxPixel: Self.fallbackDecodeLongEdge)
                        else { return nil }
                        return OutputTransform.decodeSRGB8(rgba, width: w, height: h)
                    }()
                    self.stateQueue.async {
                        guard self.continuation != nil, self.generation == gen else { return }
                        if let img = decoded { self.pendingDeveloped.append(img) }
                        if decoded != nil { self.onProgress?(self.pendingDeveloped.count) }
                        self.outstanding -= 1
                        self.maybeFinishLocked()
                    }
                } else {
                    let frame = error == nil ? RawFrameConverter.make(from: photo) : nil
                    self.stateQueue.async {
                        guard self.continuation != nil, self.generation == gen else { return }   // burst ended/superseded
                        if let frame { self.pending.append(frame) }
                        if frame != nil { self.onProgress?(self.pending.count) }
                        self.outstanding -= 1
                        self.maybeFinishLocked()
                    }
                }
            }
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
