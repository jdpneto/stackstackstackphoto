import Foundation
import Combine            // required for ObservableObject / @Published
import StackEngineCore

/// Orchestrates one capture: burst → develop+align+stack → encode → save.
@MainActor
final class StackCaptureCoordinator: ObservableObject {
    enum State: Equatable { case idle, capturing, processing, done(UUID), failed(String) }
    @Published private(set) var state: State = .idle
    /// The finished JPEG, published so the UI can show it without re-reading disk.
    @Published private(set) var lastResultJPEG: Data?

    private let capture: CaptureService
    private let store: LibraryStore

    init(capture: CaptureService, store: LibraryStore = LibraryStore()) {
        self.capture = capture
        self.store = store
    }

    func shoot(frameCount: Int = 8) async {
        do {
            state = .capturing
            let frames = try await capture.captureBurst(mode: .noiseReduction, frameCount: frameCount)
            guard !frames.isEmpty else { state = .failed("No frames were captured."); return }
            state = .processing
            let jpeg = try await Self.makeJPEG(from: frames)   // heavy work, off the main actor
            lastResultJPEG = jpeg
            let saved = try store.save(resultJPEG: jpeg, mode: "noiseReduction", frameCount: frames.count)
            state = .done(saved.id)
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    /// CPU-heavy develop → align → stack → encode, run off the MainActor to keep the UI responsive.
    nonisolated private static func makeJPEG(from frames: [RawSensorFrame]) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            let result = Pipeline.noiseReduction(frames)
            let rgba = OutputTransform.encodeSRGB8(result)
            return try ImageEncoder.encode(rgba8: rgba, width: result.width, height: result.height,
                                           format: .jpeg, quality: 0.95)
        }.value
    }
}
