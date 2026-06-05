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
/// All mutable burst state (`pending`/`remaining`/`continuation`) is confined to `stateQueue`,
/// so the AVFoundation delegate callbacks and the caller never race, and the continuation is
/// resumed exactly once. Session configuration runs lazily on `sessionQueue` (off the main thread).
/// `@unchecked Sendable`: all mutable state is confined to `stateQueue`/`sessionQueue`, so the
/// type is thread-safe by construction even though the compiler can't verify it.
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
    // Touched only on sessionQueue.
    private var configured = false

    func captureBurst(recipe: CaptureRecipe) async throws -> [RawSensorFrame] {
        try await ensureAuthorized()
        try await ensureConfigured()
        guard !output.availableRawPhotoPixelFormatTypes.isEmpty else { throw CaptureError.noRawFormat }
        try lockExposureAndFocus()

        let frameCount = recipe.frameCount
        // Spread the captures across the recipe's window so long-exposure looks sample motion over
        // time, rather than firing a back-to-back still burst. (Device path — verify on hardware.)
        let interval = recipe.durationSeconds / Double(max(frameCount - 1, 1))

        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<[RawSensorFrame], Error>) in
            stateQueue.async {
                guard self.continuation == nil else { cont.resume(throwing: CaptureError.busy); return }
                guard frameCount > 0, let rawType = self.output.availableRawPhotoPixelFormatTypes.first else {
                    cont.resume(throwing: CaptureError.noFramesProduced); return
                }
                self.pending = []
                self.continuation = cont
                self.remaining = frameCount
                for i in 0..<frameCount {
                    let settings = AVCapturePhotoSettings(rawPixelFormatType: rawType)
                    self.sessionQueue.asyncAfter(deadline: .now() + interval * Double(i)) {
                        self.output.capturePhoto(with: settings, delegate: self)
                    }
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
                    self.session.commitConfiguration()
                    self.session.startRunning()  // off the main thread (sessionQueue)
                    self.device = dev
                    self.configured = true
                    cont.resume()
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
    }

    private func lockExposureAndFocus() throws {
        guard let dev = device else { throw CaptureError.noDevice }
        try dev.lockForConfiguration()
        if dev.isExposureModeSupported(.locked) { dev.exposureMode = .locked }
        if dev.isFocusModeSupported(.locked) { dev.focusMode = .locked }
        if dev.isWhiteBalanceModeSupported(.locked) { dev.whiteBalanceMode = .locked }
        dev.unlockForConfiguration()
    }

    // MARK: - Completion (always on stateQueue)

    /// Resume the continuation exactly once. Must run on `stateQueue`.
    private func finishLocked() {
        guard let cont = continuation else { return }
        continuation = nil
        if pending.isEmpty {
            cont.resume(throwing: CaptureError.noFramesProduced)
        } else {
            cont.resume(returning: pending)
        }
    }
}

extension AVCaptureService: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        // Convert outside the state queue (touches no shared state), then mutate state serially.
        let frame = error == nil ? RawFrameConverter.make(from: photo) : nil
        stateQueue.async {
            if let frame { self.pending.append(frame) }
            self.remaining -= 1
            if self.remaining <= 0 { self.finishLocked() }
        }
    }
}
