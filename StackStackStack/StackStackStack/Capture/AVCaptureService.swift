import AVFoundation
import StackEngineCore

/// Captures a short Bayer-RAW burst with locked exposure/focus (design §10.4, noise recipe).
final class AVCaptureService: NSObject, CaptureService {
    private let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private var device: AVCaptureDevice?

    private var pending: [RawSensorFrame] = []
    private var remaining = 0
    private var continuation: CheckedContinuation<[RawSensorFrame], Error>?

    enum CaptureError: Error { case noDevice, noRawFormat, conversionFailed }

    func configure() throws {
        session.beginConfiguration()
        session.sessionPreset = .photo
        guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
        else { throw CaptureError.noDevice }
        device = dev
        let input = try AVCaptureDeviceInput(device: dev)
        if session.canAddInput(input) { session.addInput(input) }
        if session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()
        session.startRunning()
    }

    func captureBurst(mode: CaptureMode, frameCount: Int) async throws -> [RawSensorFrame] {
        guard !output.availableRawPhotoPixelFormatTypes.isEmpty else { throw CaptureError.noRawFormat }
        try lockExposureAndFocus()
        pending.removeAll(); remaining = frameCount
        return try await withCheckedThrowingContinuation { cont in
            self.continuation = cont
            for _ in 0..<frameCount { captureOneRaw() }
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

    private func captureOneRaw() {
        guard let rawType = output.availableRawPhotoPixelFormatTypes.first else { return }
        let settings = AVCapturePhotoSettings(rawPixelFormatType: rawType)
        output.capturePhoto(with: settings, delegate: self)
    }
}

extension AVCaptureService: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error { continuation?.resume(throwing: error); continuation = nil; return }
        if let frame = RawFrameConverter.make(from: photo) { pending.append(frame) }
        remaining -= 1
        if remaining <= 0 {
            continuation?.resume(returning: pending)
            continuation = nil
        }
    }
}
