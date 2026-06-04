import Foundation
import Combine
import StackEngineCore

/// Orchestrates one capture: burst → develop+align+stack → encode → save.
@MainActor
final class StackCaptureCoordinator: ObservableObject {
    enum State: Equatable { case idle, capturing, processing, done(UUID), failed(String) }
    @Published private(set) var state: State = .idle

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
            state = .processing
            let result = Pipeline.noiseReduction(frames)
            let rgba = OutputTransform.encodeSRGB8(result)
            let jpeg = try ImageEncoder.encode(rgba8: rgba, width: result.width,
                                               height: result.height, format: .jpeg, quality: 0.95)
            let saved = try store.save(resultJPEG: jpeg, mode: "noiseReduction", frameCount: frames.count)
            state = .done(saved.id)
        } catch {
            state = .failed(String(describing: error))
        }
    }
}
