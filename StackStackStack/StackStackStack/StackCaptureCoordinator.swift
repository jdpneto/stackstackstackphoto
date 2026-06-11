import Foundation
import Combine            // required for ObservableObject / @Published
import QuartzCore         // CALayer for the preview
import UIKit
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
    /// Whether the camera can run a Depth focus sweep (manual-focus hardware). Optimistic `true`
    /// until the preview configures the session; the UI disables the Depth chip when false.
    @Published private(set) var supportsDepth = true
    /// Whether the camera vends Bayer RAW frames the engine can decode. Optimistic `true`
    /// until the preview configures the session; displayed in the Settings capability report.
    @Published private(set) var supportsRAW = true
    /// True while AF/AE are locked via a long-press on the preview (drives the "AE/AF LOCK" banner).
    @Published private(set) var aeAfLocked = false
    /// Live capture progress (drives the on-screen counter + countdown during the burst).
    @Published private(set) var capturedCount = 0
    @Published private(set) var captureTotal = 0
    @Published private(set) var captureRemainingSeconds = 0
    private var countdownTask: Task<Void, Never>?
    /// The currently selected look. Settable from the capture UI. Switching looks drops the on-screen
    /// result (a new look implies a new shot), so the preview and "Saved ✓" don't go stale.
    @Published var mode: StackMode = .noiseReduction {
        didSet { if mode != oldValue { lastResultJPEG = nil; lastSavedID = nil; lastError = nil; aeAfLocked = false } }
    }
    /// Manual Pro overrides (frame count / ISO / shutter / focus). Auto by default.
    @Published var pro: ProControls = .auto {
        // Tapping is disabled in manual mode (see `tapToFocusEnabled`); drop any AE/AF lock so the
        // banner doesn't linger.
        didSet { if pro != oldValue, pro.hasManualFocusOrExposure { aeAfLocked = false } }
    }
    /// User burst length/window for the long-exposure looks (ignored by the static looks).
    @Published var burst: BurstSettings = .default
    /// Library/encode format for new captures. Kept in sync from AppSettings by the app root —
    /// the coordinator stays ignorant of the settings object. Snapshotted at shutter press. (spec §4)
    var exportFormat: ImageEncoder.Format = .jpeg
    /// Mirror saves into the system photo library (Settings toggle; synced by the app root). (spec §5)
    var saveToPhotosEnabled = false
    /// The export function — injectable so tests don't touch the real photo library. (spec §5)
    var photosExporter: @Sendable (Data, ImageEncoder.Format) async throws -> Void = PhotoLibraryExporter.export
    /// Non-blocking note when a Photos export fails (the in-app save already succeeded). (spec §5)
    @Published private(set) var photosExportNote: String?
    /// System-condition advisory shown on the capture screen (thermal warning, low-battery note);
    /// nil when conditions are nominal. Recomputed at each shutter press. (spec 2026-06-11 §2)
    @Published private(set) var environmentNote: String?
    /// System environment probed at shutter press; injectable so tests can simulate thermal/battery/disk
    /// states the simulator can't produce. (spec 2026-06-11 §2)
    var environment: CaptureEnvironment = .live()
    /// Injectable encode — tests can swap this to force an encoder failure and exercise the HEIC→JPEG
    /// fallback path without touching the real ImageEncoder. The last parameter carries optional EXIF
    /// metadata; the default forwards it to ImageEncoder. (spec §8)
    var encodeImage: @Sendable ([UInt8], Int, Int, ImageEncoder.Format, Double, ImageEncoder.ExifMetadata?) throws -> Data =
        { try ImageEncoder.encode(rgba8: $0, width: $1, height: $2, format: $3, quality: $4, exif: $5) }
    /// Handheld-steadiness tracker for the long-exposure looks; observed by the capture overlay and
    /// read by the capture gate. (design 2026-06-07 §8)
    let steadiness = MotionSteadiness()
    /// Read-only access to the library for the editor.
    var library: LibraryStore { store }

    private let capture: CaptureService
    private let store: LibraryStore
    /// Tail of the serial background-processing chain; each job awaits the previous so stacks run one
    /// at a time (they already use every core), bounding CPU and memory while the shutter stays free.
    private var processingTail: Task<Void, Never>?
    /// Live cancellation tokens for queued/in-flight stacks; cancelled together by `cancelProcessing`.
    private var activeTokens: [CancellationToken] = []

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
    /// Also the earliest point the device capability probe is meaningful (session configured).
    func startPreview() async -> CALayer? {
        let layer = await capture.startPreview()
        supportsDepth = capture.supportsDepthOfField
        supportsRAW = capture.supportsRAWCapture
        return layer
    }

    /// Tap-to-focus is available only in full-auto exposure/focus (no manual Pro override) and while
    /// the shutter is free. (design tap-to-focus §3.3)
    var tapToFocusEnabled: Bool { !pro.hasManualFocusOrExposure && !isBusy }

    /// Focus + meter exposure at a normalized device point; `lock` (long-press) holds AF/AE and shows
    /// the banner. (design tap-to-focus §3.3)
    func focusAndExpose(atDevicePoint point: CGPoint, lock: Bool) {
        guard tapToFocusEnabled else { return }   // ignore a stale gesture fired as a burst/manual mode began
        capture.setFocusExposure(atDevicePoint: point, lock: lock)
        aeAfLocked = lock
    }

    /// Capture a burst (foreground, fast), then queue the heavy processing in the background.
    func shoot() async {
        guard !isBusy else { return }   // reject a rapid double-tap, and a shot during the background stack

        // Environment policy (spec 2026-06-11 §2): hard blocks first, then advisory notes.
        // These guards run AFTER the isBusy check and BEFORE the per-shot clears so that a blocked
        // shot sets lastError and returns without touching the rest of the published state.
        let thermal = environment.thermalState()
        if thermal == .critical { environmentNote = nil; lastError = "Too hot — let the phone cool down."; return }
        if environment.freeDiskBytes() < CaptureEnvironment.minimumFreeBytes {
            environmentNote = nil; lastError = "Not enough storage to capture."; return
        }
        let battery = environment.batteryLevel()
        // Thermal wins over battery: .serious halves the burst (the note explains the shortened shot).
        if thermal == .serious {
            environmentNote = "Device is warm — shorter bursts"
        } else if battery >= 0 && battery < CaptureEnvironment.lowBatteryThreshold && !environment.batteryCharging() {
            environmentNote = "Low battery"
        } else {
            environmentNote = nil
        }

        let mode = self.mode                 // capture the selected look/overrides at shutter-press time
        let format = self.exportFormat       // capture the format at shutter-press time
        let exportToPhotos = self.saveToPhotosEnabled   // snapshot the toggle at shutter-press time
        let encode = self.encodeImage        // snapshot the encode hook at shutter-press time
        // UIDevice.current.orientation is main-thread-only; safe here (coordinator is @MainActor, shoot runs on it).
        let orientationTurns = CaptureOrientation.quarterTurns(for: UIDevice.current.orientation)
        // Capture timestamp at shutter-press time, just like orientation — makeResult is nonisolated/detached,
        // so it can't access Date() safely relative to the capture moment; pass it in.
        let capturedAt = Date()
        lastError = nil
        lastResultJPEG = nil                 // drop the previous preview; a new shot is on the way
        photosExportNote = nil               // clear any prior export failure note
        aeAfLocked = false                   // a long-press lock is superseded once a shot begins
        let recipe = makeRecipe(for: mode, thermal: thermal)
        isCapturing = true
        capturedCount = 0
        captureTotal = recipe.frameCount
        captureRemainingSeconds = Int(ceil(recipe.durationSeconds))
        startCaptureCountdown()
        let gating: @Sendable () -> Bool
        if mode.usesSteadinessGate {
            steadiness.start()
            gating = { [steadiness] in steadiness.isSteady }
        } else {
            gating = { true }
        }
        defer {
            if mode.usesSteadinessGate { steadiness.stop() }
            countdownTask?.cancel(); countdownTask = nil
            captureRemainingSeconds = 0   // don't leave a stale value between shots
        }
        let progress: @Sendable (Int) -> Void = { [weak self] n in
            Task { @MainActor in self?.capturedCount = n }
        }
        let burst: CapturedBurst
        do {
            burst = try await capture.captureBurst(recipe: recipe, isSteady: gating, onProgress: progress)
        } catch {
            lastError = error.localizedDescription
            isCapturing = false
            return
        }
        isCapturing = false                  // arms-up done — re-enable the shutter immediately
        guard !burst.isEmpty else { lastError = "No frames were captured."; return }
        enqueueProcessing(burst: burst, mode: mode, format: format,
                          orientationQuarterTurns: orientationTurns, capturedAt: capturedAt,
                          exportToPhotos: exportToPhotos, encode: encode)
    }

    /// Clear the on-screen result preview, returning the capture screen to the live viewfinder.
    func dismissResult() {
        lastResultJPEG = nil
        lastSavedID = nil
        lastError = nil          // symmetric with the look-change clear; no stale "Failed…" after dismiss
    }

    /// One-second ticks decrementing `captureRemainingSeconds` to 0 while the burst runs.
    private func startCaptureCountdown() {
        countdownTask?.cancel()
        countdownTask = Task { [weak self] in
            while !Task.isCancelled, let s = self?.captureRemainingSeconds, s > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard !Task.isCancelled, let self else { break }
                captureRemainingSeconds = max(0, captureRemainingSeconds - 1)
            }
        }
    }

    /// Build the capture recipe for `mode`. Long-exposure looks take their length/window from
    /// `burst` (the edge sliders) plus any manual Pro exposure overrides; static looks use the fixed
    /// per-look recipe with the full Pro overrides. Called synchronously from `shoot()` before any
    /// `await`, so `self.pro`/`self.burst` reflect their shutter-press values without a local snapshot.
    /// `thermal` is passed in from shoot() — already read once there; we never re-read it here.
    /// When the thermal state is `.serious`, the burst is halved (floor, min 2) so the device cools
    /// during shorter stacks; sweep steps track frameCount per the established invariant. (spec 2026-06-11 §2)
    /// (design 2026-06-07 §5)
    private func makeRecipe(for mode: StackMode, thermal: ProcessInfo.ThermalState) -> CaptureRecipe {
        var recipe: CaptureRecipe
        if mode.isLongExposure {
            recipe = CaptureRecipe(frameCount: burst.photoCount,
                                   durationSeconds: burst.durationSeconds,
                                   manualISO: pro.iso.map(Float.init),
                                   manualShutterSeconds: pro.shutterSeconds,
                                   manualFocus: pro.focus.map(Float.init))
        } else {
            recipe = CaptureRecipe.recipe(for: mode).applying(pro)
        }
        // Thermal throttle: halve the burst when the device is seriously hot so it cools faster.
        if thermal == .serious {
            recipe.frameCount = max(2, recipe.frameCount / 2)
            if let s = recipe.focusSweep {
                recipe.focusSweep = .init(near: s.near, far: s.far, steps: recipe.frameCount)
            }
        }
        return recipe
    }

    /// Queue develop→align→stack→encode→save behind any earlier job (serial), running the heavy work
    /// off the MainActor, then publish the result. The shutter stays free meanwhile.
    private func enqueueProcessing(burst: CapturedBurst, mode: StackMode,
                                   format: ImageEncoder.Format, orientationQuarterTurns: Int,
                                   capturedAt: Date,
                                   exportToPhotos: Bool,
                                   encode: @escaping @Sendable ([UInt8], Int, Int, ImageEncoder.Format, Double, ImageEncoder.ExifMetadata?) throws -> Data) {
        processingCount += 1
        let token = CancellationToken()
        activeTokens.append(token)
        let previous = processingTail
        let info = burst.info    // snapshot the CaptureInfo alongside the frames
        processingTail = Task { [weak self] in
            await previous?.value                                   // serialize behind earlier jobs
            guard let self else { return }
            defer {
                self.processingCount -= 1
                self.activeTokens.removeAll { $0 === token }
            }
            if token.isCancelled { return }                        // cancelled while queued
            do {
                // makeResult handles the HEIC→JPEG fallback internally; the develop+align+stack
                // pipeline is never re-run on a fallback, only the encode step. (spec §8)
                let encoded = try await Self.makeResult(from: burst, mode: mode, format: format,
                                                        orientationQuarterTurns: orientationQuarterTurns,
                                                        capturedAt: capturedAt, info: info,
                                                        shouldCancel: { token.isCancelled },
                                                        encode: encode)
                if token.isCancelled { return }                    // cancelled during processing → discard
                let saved = try self.store.save(result: encoded.data, reference: encoded.reference,
                                                format: encoded.format, mode: mode.rawValue, frameCount: burst.count,
                                                iso: info?.iso, shutterSeconds: info?.shutterSeconds)
                // `lastResultJPEG` keeps its historical name; it stores display bytes — ImageIO decodes both JPEG and HEIC.
                self.lastResultJPEG = encoded.data
                self.lastSavedID = saved.id
                if exportToPhotos {
                    let exporter = self.photosExporter
                    let payload = encoded
                    Task { [weak self] in   // fire-and-forget; never blocks or fails the in-app save
                        do { try await exporter(payload.data, payload.format) }
                        catch { await MainActor.run { self?.photosExportNote = "Photos export failed — check Settings ▸ Privacy" } }
                    }
                }
            } catch is CancellationError {
                return                                             // discarded mid-stack — not an error
            } catch {
                self.lastError = error.localizedDescription
            }
        }
    }

    /// Await all queued/in-flight background processing (tests; also "wait for everything to settle").
    func awaitProcessing() async { await processingTail?.value }

    /// Cancel every queued/in-flight background stack. The per-job token (NOT Task cancellation — the
    /// heavy work runs in `Task.detached`, which doesn't inherit it) makes each job discard its partial
    /// work without saving (no error surfaced) and settle its `processingCount` via `defer`, freeing the
    /// shutter. (design 2026-06-07 §7)
    func cancelProcessing() {
        for token in activeTokens { token.cancel() }
        activeTokens.removeAll()
    }

    /// Managed working resolution (long-edge px) the stack is processed at. Full-sensor RAW (~12 MP)
    /// × N frames through a CPU align+stack is minutes-slow on device; downscaling the developed
    /// frames to this before align/stack is the dominant speed + memory win (results stay sharp at
    /// screen/share sizes). A full-resolution Pro tier is a follow-up.
    nonisolated private static let managedWorkingResolution = 2400
    /// Depth working resolution (long-edge px) — the engine's managed preset is the single source
    /// of truth (its comment carries the memory rationale); don't restate the number here.
    nonisolated private static let depthWorkingResolution = DepthConfig.auto.workingResolution

    /// DEBUG: dump the developed frames (the exact alignment input) for one capture so the handheld
    /// registration can be debugged offline on real data. Off for release; remove before merge.
    nonisolated private static let dumpFramesForDiagnostics = false

    /// CPU-heavy develop → downscale → align → stack → encode, run off the MainActor.
    /// Returns `(data, reference, format)` where format may differ from the requested format if HEIC
    /// encoding failed and fell back to JPEG — only the encode step is retried, not the full pipeline.
    /// `reference` is the encoded aligned anchor frame (same orientation + format as the result),
    /// used as the blend-strength lerp's second endpoint; nil for depth stacks (no blend semantics)
    /// and when the reference encode itself fails (safe degradation — never loses the main result).
    /// (spec §8, spec 2026-06-11 §3)
    nonisolated private static func makeResult(from burst: CapturedBurst, mode: StackMode,
                                               format: ImageEncoder.Format,
                                               orientationQuarterTurns: Int,
                                               capturedAt: Date,
                                               info: CaptureInfo?,
                                               shouldCancel: @escaping @Sendable () -> Bool,
                                               encode: @escaping @Sendable ([UInt8], Int, Int, ImageEncoder.Format, Double, ImageEncoder.ExifMetadata?) throws -> Data) async throws -> (data: Data, reference: Data?, format: ImageEncoder.Format) {
        try await Task.detached(priority: .userInitiated) {
            let result: PixelImage
            var referencePixels: PixelImage?   // the aligned anchor — nil when !mode.supportsBlendReference

            switch burst.payload {
            case .raw(let frames):
                // RAW quality path: develop → downscale → align → stack. Verbatim existing logic.
                if mode.isLongExposure {
                    // Streaming: one developed+aligned frame in flight at a time; cancellable between frames.
                    let (res, ref) = try Pipeline.reduceStreamingWithReference(frames, mode: mode,
                                                                               workingResolution: managedWorkingResolution,
                                                                               binnedDevelop: true, shouldCancel: shouldCancel)
                    result = res
                    referencePixels = ref
                } else if !mode.supportsBlendReference {
                    // Depth: develop all brackets at the managed depth resolution, then focus-stack.
                    // No blend-strength reference — frames differ by focus, not by time. (spec 2026-06-11 §4)
                    let developed = Pipeline.developedFrames(frames, binnedDevelop: true,
                                                             workingResolution: depthWorkingResolution)
                    if dumpFramesForDiagnostics { dumpDevelopedFrames(developed) }
                    if shouldCancel() { throw CancellationError() }
                    guard let stacked = FocusStacker.allInFocus(
                        developed,
                        config: DepthConfig(workingResolution: depthWorkingResolution,
                                            maxFrames: max(frames.count, 1)))
                    else { throw ProcessingError.focusStackFailed }
                    result = stacked
                    referencePixels = nil
                } else {
                    let developed = Pipeline.developedFrames(frames, binnedDevelop: true,
                                                             workingResolution: managedWorkingResolution)
                    if shouldCancel() { throw CancellationError() }   // cancel between develop and reduce (static path)
                    if dumpFramesForDiagnostics { dumpDevelopedFrames(developed) }
                    let (res, ref) = Pipeline.reduceImagesWithReference(developed, mode: mode)
                    result = res
                    referencePixels = ref
                }

            case .developed(let images):
                // Non-RAW fallback: frames are already at working resolution — skip the develop step
                // and route every look through the images pipeline. (spec 2026-06-11 §3)
                if mode == .depthOfField {
                    guard let stacked = FocusStacker.allInFocus(
                        images,
                        config: DepthConfig(workingResolution: depthWorkingResolution,
                                            maxFrames: max(images.count, 1)))
                    else { throw ProcessingError.focusStackFailed }
                    result = stacked
                    referencePixels = nil
                } else if mode.isLongExposure {
                    // Streaming: align + fold one frame at a time — peak memory is O(1) warped frames,
                    // not O(N) aligned frames. Cancellable between folds. (Fix: fallback memory bomb)
                    let (res, ref) = try Pipeline.reduceImagesStreamingWithReference(images, mode: mode,
                                                                                     shouldCancel: shouldCancel)
                    result = res
                    referencePixels = ref
                } else {
                    if shouldCancel() { throw CancellationError() }
                    let (res, ref) = Pipeline.reduceImagesWithReference(images, mode: mode,
                                                                        workingResolution: managedWorkingResolution)
                    result = res
                    referencePixels = mode.supportsBlendReference ? ref : nil
                }
            }

            // All cancellation checks precede the encode — a CancellationError here is from the
            // pipeline above, not the encode, so the where-clause below can't swallow one mid-encode.
            let oriented = ImageGeometry.rotated(result, quarterTurns: orientationQuarterTurns)   // bake upright
            // The reference must go through the SAME orientation bake so the lerp endpoints are
            // aligned pixel-to-pixel in the final stored images. (spec 2026-06-11 §4)
            let orientedRef = referencePixels.map { ImageGeometry.rotated($0, quarterTurns: orientationQuarterTurns) }
            let rgba = OutputTransform.encodeSRGB8(oriented)
            // Hoist the reference RGBA encode so it runs once and is shared by both the happy
            // path and the HEIC-fallback branch — eliminates the duplicate work and textual
            // duplication. (spec 2026-06-11 §6)
            let refRGBA = orientedRef.map { OutputTransform.encodeSRGB8($0) }
            // Bind the reference pair once so both the happy-path and the HEIC-fallback branch
            // share it without force-unwrapping. (Fix 6 — no `!` on orientedRef anywhere)
            let refPair: (pixels: PixelImage, rgba: [UInt8])? = orientedRef.flatMap { ref in
                refRGBA.map { (ref, $0) }
            }
            // Build the EXIF metadata for the result encode: ISO/shutter from burst CaptureInfo,
            // timestamp from the shutter-press Date snapshotted on the MainActor (the same pattern
            // as orientationQuarterTurns — makeResult is nonisolated/detached so Date() here would
            // be "encode start", not "capture start"). Reference and fallback encodes share the same
            // EXIF so the pair is consistent. A nil info produces a nil exif → no EXIF dict written.
            let exif: ImageEncoder.ExifMetadata? = {
                guard let info else { return ImageEncoder.ExifMetadata(capturedAt: capturedAt) }
                return ImageEncoder.ExifMetadata(iso: info.iso, shutterSeconds: info.shutterSeconds,
                                                 capturedAt: capturedAt)
            }()
            do {
                let data = try encode(rgba, oriented.width, oriented.height, format, 0.95, exif)
                // Encode the reference in the SAME format the result ended up with; wrap in try? so a
                // reference encode failure never loses the main result. (spec 2026-06-11 §6)
                let refData = try? refPair.flatMap { pair -> Data? in
                    try encode(pair.rgba, pair.pixels.width, pair.pixels.height, format, 0.95, exif)
                }
                return (data, refData, format)
            } catch where format == .heic {
                // HEIC encoder hiccup → re-encode the SAME stacked pixels as JPEG; the pipeline
                // (develop+align+stack) is never re-run. Both result and reference fall back together
                // so the pair always shares one format. (spec §8)
                let data = try encode(rgba, oriented.width, oriented.height, .jpeg, 0.95, exif)
                let refData = try? refPair.flatMap { pair -> Data? in
                    try encode(pair.rgba, pair.pixels.width, pair.pixels.height, .jpeg, 0.95, exif)
                }
                return (data, refData, .jpeg)
            }
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

/// Background-processing failures surfaced to the capture screen's status label.
enum ProcessingError: LocalizedError {
    case focusStackFailed
    var errorDescription: String? {
        switch self {
        case .focusStackFailed: return "Couldn't combine the focus brackets. Please try again."
        }
    }
}
