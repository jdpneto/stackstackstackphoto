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
    /// The currently selected look. Settable from the capture UI.
    @Published var mode: StackMode = .noiseReduction
    /// The id of the most recent saved stack (for the editor).
    @Published private(set) var lastSavedID: UUID?
    /// Read-only access to the library for the editor.
    var library: LibraryStore { store }

    private let capture: CaptureService
    private let store: LibraryStore

    init(capture: CaptureService, store: LibraryStore = LibraryStore()) {
        self.capture = capture
        self.store = store
    }

    /// True while a capture is in flight (capturing or processing). Used to reject re-entrant taps.
    var isBusy: Bool {
        switch state { case .capturing, .processing: return true; default: return false }
    }

    func shoot(frameCount: Int = 8) async {
        guard !isBusy else { return }   // reject a second shoot while one is already running
        let mode = self.mode            // capture the selected look at shutter-press time (before any await)
        do {
            state = .capturing
            let frames = try await capture.captureBurst(mode: .noiseReduction, frameCount: frameCount)
            guard !frames.isEmpty else { state = .failed("No frames were captured."); return }
            state = .processing
            let jpeg = try await Self.makeJPEG(from: frames, mode: mode)   // heavy work, off the main actor
            lastResultJPEG = jpeg
            let saved = try store.save(resultJPEG: jpeg, mode: mode.rawValue, frameCount: frames.count)
            lastSavedID = saved.id
            state = .done(saved.id)
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    /// CPU-heavy develop → align → stack → encode, run off the MainActor to keep the UI responsive.
    nonisolated private static func makeJPEG(from frames: [RawSensorFrame], mode: StackMode) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            let result = Pipeline.reduce(frames, mode: mode)
            let rgba = OutputTransform.encodeSRGB8(result)
            return try ImageEncoder.encode(rgba8: rgba, width: result.width, height: result.height,
                                           format: .jpeg, quality: 0.95)
        }.value
    }
}
