import Foundation
import Combine            // required for ObservableObject / @Published
import QuartzCore         // CALayer for the preview
import StackEngineCore

/// Orchestrates one shot: a fast foreground BURST (the arms-up step), then develop+align+stack+save
/// SERIALIZED in the background. The shutter is gated only by the (short) capture phase, so the user
/// can take the next shot while previous ones are still stacking.
@MainActor
final class StackCaptureCoordinator: ObservableObject {
    /// True only while the camera burst is running — the arms-up phase that gates the shutter.
    @Published private(set) var isCapturing = false
    /// How many stacks are developing/stacking in the background (queued + in-flight).
    @Published private(set) var processingCount = 0
    /// The most recent finished JPEG, published so the UI can show it without re-reading disk.
    @Published private(set) var lastResultJPEG: Data?
    /// The id of the most recent saved stack (for the editor).
    @Published private(set) var lastSavedID: UUID?
    /// The most recent failure message (capture or processing); cleared when a new shot starts.
    @Published private(set) var lastError: String?
    /// The currently selected look. Settable from the capture UI. Switching looks drops the on-screen
    /// result (a new look implies a new shot), so the preview and "Saved ✓" don't go stale.
    @Published var mode: StackMode = .noiseReduction {
        didSet { if mode != oldValue { lastResultJPEG = nil; lastSavedID = nil; lastError = nil } }
    }
    /// Manual Pro overrides (frame count / ISO / shutter / focus). Auto by default.
    @Published var pro: ProControls = .auto
    /// Read-only access to the library for the editor.
    var library: LibraryStore { store }

    private let capture: CaptureService
    private let store: LibraryStore
    /// Tail of the serial background-processing chain; each job awaits the previous so stacks run one
    /// at a time (they already use every core), bounding CPU and memory while the shutter stays free.
    private var processingTail: Task<Void, Never>?

    init(capture: CaptureService, store: LibraryStore = LibraryStore()) {
        self.capture = capture
        self.store = store
    }

    /// Gates the shutter. Capturing during a background stack is unreliable on device — the all-core
    /// develop/align/stack starves the camera and the still-image pipeline bails (-12773) — so the
    /// shutter is disabled while processing too. The (short) capture is what the user holds still for;
    /// once it's done the status invites them to lower the phone while the stack finishes.
    var isBusy: Bool { isCapturing || processingCount > 0 }

    /// Start the live preview and return its layer (nil if unavailable, e.g. the Simulator fake).
    func startPreview() async -> CALayer? { await capture.startPreview() }

    /// Capture a burst (foreground, fast), then queue the heavy processing in the background.
    func shoot() async {
        guard !isBusy else { return }   // reject a rapid double-tap, and a shot during the background stack
        let mode = self.mode                 // capture the selected look/overrides at shutter-press time
        let pro = self.pro
        lastError = nil
        lastResultJPEG = nil                 // drop the previous preview; a new shot is on the way
        isCapturing = true
        let frames: [RawSensorFrame]
        do {
            frames = try await capture.captureBurst(recipe: .recipe(for: mode).applying(pro))
        } catch {
            lastError = error.localizedDescription
            isCapturing = false
            return
        }
        isCapturing = false                  // arms-up done — re-enable the shutter immediately
        guard !frames.isEmpty else { lastError = "No frames were captured."; return }
        enqueueProcessing(frames: frames, mode: mode)
    }

    /// Queue develop→align→stack→encode→save behind any earlier job (serial), running the heavy work
    /// off the MainActor, then publish the result. The shutter stays free meanwhile.
    private func enqueueProcessing(frames: [RawSensorFrame], mode: StackMode) {
        processingCount += 1
        let previous = processingTail
        processingTail = Task { [weak self] in
            await previous?.value                                   // serialize behind earlier jobs
            guard let self else { return }
            defer { self.processingCount -= 1 }                     // always settle the count
            do {
                let jpeg = try await Self.makeJPEG(from: frames, mode: mode)   // heavy work, off the MainActor
                let saved = try self.store.save(resultJPEG: jpeg, mode: mode.rawValue, frameCount: frames.count)
                self.lastResultJPEG = jpeg
                self.lastSavedID = saved.id
            } catch {
                self.lastError = error.localizedDescription
            }
        }
    }

    /// Await all queued/in-flight background processing (tests; also "wait for everything to settle").
    func awaitProcessing() async { await processingTail?.value }

    /// Managed working resolution (long-edge px) the stack is processed at. Full-sensor RAW (~12 MP)
    /// × N frames through a CPU align+stack is minutes-slow on device; downscaling the developed
    /// frames to this before align/stack is the dominant speed + memory win (results stay sharp at
    /// screen/share sizes). A full-resolution Pro tier is a follow-up.
    nonisolated private static let managedWorkingResolution = 2400

    /// DEBUG: dump the developed frames (the exact alignment input) for one capture so the handheld
    /// registration can be debugged offline on real data. Off for release; remove before merge.
    nonisolated private static let dumpFramesForDiagnostics = false

    /// CPU-heavy develop → downscale → align → stack → encode, run off the MainActor.
    nonisolated private static func makeJPEG(from frames: [RawSensorFrame], mode: StackMode) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            // Develop+downscale once, optionally dump for diagnostics, then align+reduce on the result.
            let developed = Pipeline.developedFrames(frames, binnedDevelop: true,
                                                     workingResolution: managedWorkingResolution)
            if dumpFramesForDiagnostics { dumpDevelopedFrames(developed) }
            let result = Pipeline.reduceImages(developed, mode: mode, workingResolution: managedWorkingResolution)
            let rgba = OutputTransform.encodeSRGB8(result)
            return try ImageEncoder.encode(rgba8: rgba, width: result.width, height: result.height,
                                           format: .jpeg, quality: 0.95)
        }.value
    }

    /// Write each developed frame as a JPEG into Documents/diag (clearing any prior dump), so the
    /// most recent capture's alignment input can be pulled off the device.
    nonisolated private static func dumpDevelopedFrames(_ imgs: [PixelImage]) {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("diag", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for (i, img) in imgs.enumerated() {
            let rgba = OutputTransform.encodeSRGB8(img)
            guard let data = try? ImageEncoder.encode(rgba8: rgba, width: img.width, height: img.height,
                                                      format: .jpeg, quality: 0.95) else { continue }
            try? data.write(to: dir.appendingPathComponent(String(format: "frame%02d.jpg", i)))
        }
    }
}
