# Settings + Onboarding (+ Capture-Time HEIC) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the app's third area — a Settings tab whose every entry is backed by a real feature (save-to-Photos, JPEG/HEIC capture format, storage management, capability report, about, replay intro) — plus a skippable first-launch onboarding flow, with the bible/delta docs annotated.

**Architecture:** `AppSettings` (ObservableObject over UserDefaults, 3 keys) injected at the app root. HEIC is a capture-time library format: `StackRecord.format` (optional, nil = JPEG back-compat), format-aware `LibraryStore`, the coordinator snapshots `exportFormat` at shutter press, and edit re-renders use the record's own format. `PhotoLibraryExporter` (PHPhotoLibrary add-only) runs fire-and-forget after save. Onboarding is a full-screen cover driven by `hasSeenOnboarding`, with look-card content as data. Spec: `docs/superpowers/specs/2026-06-10-settings-onboarding-design.md`.

**Tech Stack:** SwiftUI, UserDefaults, Photos (PHPhotoLibrary add-only), ImageIO (already HEIC-capable), XCTest.

**Branch:** `feat/settings-onboarding` (created, spec committed at 2caa4f9).

**Commands:** find a simulator with `xcrun simctl list devices available | grep -i iphone | head -3` (iPhone 17 / iPhone 17 Pro known good), then:
```bash
cd /Users/davidneto/photo-stack-app/StackStackStack
xcodebuild test -scheme StackStackStack -project StackStackStack.xcodeproj \
  -destination 'platform=iOS Simulator,name=<SIM>' -only-testing:StackStackStackTests/<TestClass> -quiet
```
Git hygiene: NEVER `git add -A`/`.` — one deliberately-untracked file exists (`CLAUDE.md`; the delta doc gets committed by Task 8). Stage explicit paths.

---

## File map

| File | Change |
|---|---|
| `StackStackStack/StackStackStack/Settings/AppSettings.swift` | **new** — 3-key UserDefaults wrapper |
| `StackStackStack/StackStackStack/Settings/SettingsView.swift` | **new** — the Form |
| `StackStackStack/StackStackStack/Settings/PhotoLibraryExporter.swift` | **new** — add-only export |
| `StackStackStack/StackStackStack/Onboarding/OnboardingView.swift` | **new** — cover flow + page data |
| `StackStackStack/StackStackStack/StackStackStackApp.swift` | 3rd tab, AppSettings, onboarding cover, format sync |
| `StackStackStack/StackStackStack/ImageEncoder.swift` | `Format` becomes `String`-backed |
| `StackStackStack/StackStackStack/Library/StackRecord.swift` | + `format` field |
| `StackStackStack/StackStackStack/Library/LibraryStore.swift` | format-aware save/URLs/reconcile; + `record(for:)`, `deleteAll()`, `storageUsedBytes()` |
| `StackStackStack/StackStackStack/StackCaptureCoordinator.swift` | `exportFormat` + `makeResult` + JPEG fallback + Photos hook + `supportsRAW` |
| `StackStackStack/StackStackStack/Capture/CaptureService.swift` | + `supportsRAWCapture` protocol req/default |
| `StackStackStack/StackStackStack/Capture/AVCaptureService.swift` | RAW probe at configure |
| `StackStackStack/StackStackStack/ResultRenderer.swift` | + `format` param |
| `StackStackStack/StackStackStack/UI/EditorView.swift`, `UI/PhotoDetailView.swift`, `UI/CaptureView.swift` | thread the record's format into renders |
| Tests: `AppSettingsTests` (new), `LibraryStoreTests`, `CoordinatorTests`, `ResultRendererTests`, `ImageEncoderTests`, `StackFlowUITests` | per task |
| `docs/superpowers/specs/2026-06-04-…design.md` (bible §15.6), `docs/…/2026-06-10-design-implementation-delta.md` | Task 8 annotations |

---

### Task 1: `AppSettings` + String-backed `ImageEncoder.Format`

**Files:**
- Modify: `StackStackStack/StackStackStack/ImageEncoder.swift:9-11`
- Create: `StackStackStack/StackStackStack/Settings/AppSettings.swift`
- Create: `StackStackStack/StackStackStackTests/AppSettingsTests.swift`

- [ ] **Step 1: Write the failing tests** — create `AppSettingsTests.swift`:

```swift
import XCTest
@testable import StackStackStack

final class AppSettingsTests: XCTestCase {
    /// A throwaway suite so tests never touch the app's real defaults.
    private func makeSettings() -> (AppSettings, UserDefaults) {
        let suite = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        return (AppSettings(defaults: suite), suite)
    }

    func testDefaultsAreSafeOutOfTheBox() {
        let (s, _) = makeSettings()
        XCTAssertFalse(s.saveToPhotos, "Photos export is opt-in")
        XCTAssertEqual(s.exportFormat, .jpeg, "JPEG until the user opts into HEIC")
        XCTAssertFalse(s.hasSeenOnboarding, "fresh install shows onboarding")
    }

    func testValuesRoundTripThroughDefaults() {
        let (s, suite) = makeSettings()
        s.saveToPhotos = true
        s.exportFormat = .heic
        s.hasSeenOnboarding = true
        // A second instance over the same suite sees the persisted values.
        let s2 = AppSettings(defaults: suite)
        XCTAssertTrue(s2.saveToPhotos)
        XCTAssertEqual(s2.exportFormat, .heic)
        XCTAssertTrue(s2.hasSeenOnboarding)
    }

    func testUnknownStoredFormatFallsBackToJPEG() {
        let (_, suite) = makeSettings()
        suite.set("avif", forKey: "exportFormat")   // a future/corrupt value
        XCTAssertEqual(AppSettings(defaults: suite).exportFormat, .jpeg)
    }

    func testFormatRawValuesAreStableStorageKeys() {
        XCTAssertEqual(ImageEncoder.Format.jpeg.rawValue, "jpeg")
        XCTAssertEqual(ImageEncoder.Format.heic.rawValue, "heic")
    }
}
```

- [ ] **Step 2: Run to verify failure** — `xcodebuild test … -only-testing:StackStackStackTests/AppSettingsTests -quiet`. Expected: compile error (`AppSettings` undefined; `Format` has no `rawValue`).

- [ ] **Step 3: Implement.**

`ImageEncoder.swift` — make `Format` String-backed (raw values are persisted preference/record keys — same stability contract as `StackMode`):

```swift
    /// `String`-backed: raw values are persisted (AppSettings + StackRecord.format) — renaming a
    /// case silently breaks stored preferences and library records.
    enum Format: String, Sendable, Equatable {
        case jpeg, heic
        var utType: UTType { self == .jpeg ? .jpeg : .heic }
        /// The file extension library files use for this format.
        var fileExtension: String { self == .jpeg ? "jpg" : "heic" }
    }
```

`Settings/AppSettings.swift` (new):

```swift
import Foundation
import Combine

/// The app's user preferences: a thin observable wrapper over UserDefaults (three keys — no
/// settings framework). Injected once at the app root via `.environmentObject`. (spec §3.1)
final class AppSettings: ObservableObject {
    private let defaults: UserDefaults

    /// Mirror every successful save into the system photo library (add-only). Opt-in.
    @Published var saveToPhotos: Bool { didSet { defaults.set(saveToPhotos, forKey: Keys.saveToPhotos) } }
    /// Library/encode format for NEW captures (existing records keep their own format).
    @Published var exportFormat: ImageEncoder.Format { didSet { defaults.set(exportFormat.rawValue, forKey: Keys.exportFormat) } }
    /// First-launch onboarding gate; "Replay Introduction" presents the flow without resetting this.
    @Published var hasSeenOnboarding: Bool { didSet { defaults.set(hasSeenOnboarding, forKey: Keys.hasSeenOnboarding) } }

    private enum Keys {
        static let saveToPhotos = "saveToPhotos"
        static let exportFormat = "exportFormat"
        static let hasSeenOnboarding = "hasSeenOnboarding"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.saveToPhotos = defaults.bool(forKey: Keys.saveToPhotos)
        self.exportFormat = defaults.string(forKey: Keys.exportFormat)
            .flatMap(ImageEncoder.Format.init(rawValue:)) ?? .jpeg   // unknown/corrupt value → JPEG
        self.hasSeenOnboarding = defaults.bool(forKey: Keys.hasSeenOnboarding)
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS (4 tests). Also run `-only-testing:StackStackStackTests/ImageEncoderTests` (Format change must not break it).

- [ ] **Step 5: Commit**
```bash
cd /Users/davidneto/photo-stack-app
git add StackStackStack/StackStackStack/Settings/AppSettings.swift StackStackStack/StackStackStack/ImageEncoder.swift StackStackStack/StackStackStackTests/AppSettingsTests.swift
git commit -m "feat(settings): AppSettings (UserDefaults-backed preferences); String-backed ImageEncoder.Format"
```

---

### Task 2: `StackRecord.format` + format-aware `LibraryStore` (+ storage helpers)

**Files:**
- Modify: `StackStackStack/StackStackStack/Library/StackRecord.swift`
- Modify: `StackStackStack/StackStackStack/Library/LibraryStore.swift`
- Test: `StackStackStack/StackStackStackTests/LibraryStoreTests.swift`

- [ ] **Step 1: Write the failing tests** — READ `LibraryStoreTests.swift` first and reuse its temp-store helper if one exists; append:

```swift
    func testSaveWithHEICFormatUsesHeicExtensionAndPersistsFormat() throws {
        let store = makeStore()   // adapt to the file's existing temp-dir helper
        let saved = try store.save(result: Data([0xFF]), format: .heic, mode: "noiseReduction", frameCount: 3)
        XCTAssertEqual(saved.resultURL.pathExtension, "heic")
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertEqual(rec.format, "heic")
        XCTAssertEqual(rec.encoderFormat, .heic)
        XCTAssertEqual(store.resultURL(for: rec).pathExtension, "heic")
    }

    func testLegacyRecordWithoutFormatReadsAsJPEG() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF]), format: .jpeg, mode: "smoothMotion", frameCount: 2)
        // Simulate a pre-format index: strip the key from the persisted JSON.
        let indexURL = storeRoot().appendingPathComponent("index.json")   // adapt to helper
        var json = try JSONSerialization.jsonObject(with: Data(contentsOf: indexURL)) as! [[String: Any]]
        json[0].removeValue(forKey: "format")
        try JSONSerialization.data(withJSONObject: json).write(to: indexURL)
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertNil(rec.format)
        XCTAssertEqual(rec.encoderFormat, .jpeg, "nil format = JPEG (back-compat)")
        XCTAssertEqual(store.resultURL(for: rec).lastPathComponent, "\(saved.id.uuidString).jpg")
    }

    func testDeleteRemovesHeicFiles() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF]), format: .heic, mode: "depthOfField", frameCount: 10)
        try store.delete(id: saved.id)
        XCTAssertEqual(try store.loadAll().count, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: saved.resultURL.path))
    }

    func testReconcileOrphansSweepsBothExtensions() throws {
        let store = makeStore()
        let orphanJpg = storeRoot().appendingPathComponent("\(UUID().uuidString).jpg")
        let orphanHeic = storeRoot().appendingPathComponent("\(UUID().uuidString).heic")
        try Data([0x01]).write(to: orphanJpg)
        try Data([0x01]).write(to: orphanHeic)
        store.reconcileOrphans()
        XCTAssertFalse(FileManager.default.fileExists(atPath: orphanJpg.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: orphanHeic.path))
    }

    func testDeleteAllEmptiesTheLibrary() throws {
        let store = makeStore()
        _ = try store.save(result: Data([0xFF]), format: .jpeg, mode: "noiseReduction", frameCount: 1)
        _ = try store.save(result: Data([0xFF]), format: .heic, mode: "lightTrails", frameCount: 1)
        try store.deleteAll()
        XCTAssertEqual(try store.loadAll().count, 0)
    }

    func testStorageUsedBytesCountsLibraryFiles() throws {
        let store = makeStore()
        _ = try store.save(result: Data(repeating: 0, count: 1000), format: .jpeg, mode: "noiseReduction", frameCount: 1)
        XCTAssertGreaterThanOrEqual(store.storageUsedBytes(), 2000, "result + original ≥ 2×1000")
    }

    func testRecordLookupByID() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xFF]), format: .heic, mode: "noiseReduction", frameCount: 1)
        XCTAssertEqual(store.record(for: saved.id)?.encoderFormat, .heic)
        XCTAssertNil(store.record(for: UUID()))
    }
```
NOTE: the existing `save(resultJPEG:mode:frameCount:)` call sites in this test file (and others) will break when the signature changes — update them to `save(result:format: .jpeg, mode:frameCount:)` as part of Step 3.

- [ ] **Step 2: Verify failure** — compile errors (`save(result:format:…)`, `format`, `deleteAll`, `storageUsedBytes`, `record(for:)` undefined).

- [ ] **Step 3: Implement.**

`StackRecord.swift`:

```swift
import Foundation

struct StackRecord: Codable, Identifiable, Equatable {
    let id: UUID
    let createdAt: Date
    let mode: String
    let frameCount: Int
    let resultFileName: String
    /// Bumped whenever the result is re-rendered by an edit, so gallery cells reload. Optional for
    /// back-compat with index.json written before this field existed (synthesized Codable decodes a
    /// missing optional key as nil).
    var updatedAt: Date?
    /// Encoded format of the result/original ("jpeg"/"heic"). Optional for back-compat: records
    /// written before this field existed are JPEG. Stored as the raw string so the index stays a
    /// stable contract (same rule as StackMode raw values).
    var format: String?

    /// The record's encoder format; nil/unknown = JPEG (every pre-format record is a JPEG).
    var encoderFormat: ImageEncoder.Format { format.flatMap(ImageEncoder.Format.init(rawValue:)) ?? .jpeg }

    func resultURL(in dir: URL) -> URL { dir.appendingPathComponent(resultFileName) }
}
```

`LibraryStore.swift` — replace `save` and the private URL helpers; add the new API. The result filename carries the extension, and the original/edit sidecars follow the record's format:

```swift
    @discardableResult
    func save(result: Data, format: ImageEncoder.Format, mode: String, frameCount: Int) throws -> SavedStack {
        let id = UUID()
        let fileName = "\(id.uuidString).\(format.fileExtension)"
        let url = root.appendingPathComponent(fileName)
        let now = Date()
        // Write the files first, then the index last — so a failure never leaves an index entry
        // pointing at a file that isn't there.
        try result.write(to: url, options: Self.writeOptions)
        try result.write(to: originalURL(for: id, format: format), options: Self.writeOptions)   // immutable original
        var records = (try? loadRaw()) ?? []
        records.insert(StackRecord(id: id, createdAt: now, mode: mode, frameCount: frameCount,
                                   resultFileName: fileName, updatedAt: now, format: format.rawValue), at: 0)
        try persist(records)
        return SavedStack(id: id, resultURL: url)
    }
```

URL helpers become record/format-aware (the ID-only forms need the record to know the extension):

```swift
    private func resultURL(for id: UUID, format: ImageEncoder.Format) -> URL {
        root.appendingPathComponent("\(id.uuidString).\(format.fileExtension)")
    }
    private func originalURL(for id: UUID, format: ImageEncoder.Format) -> URL {
        root.appendingPathComponent("\(id.uuidString).orig.\(format.fileExtension)")
    }
    private func editsURL(for id: UUID) -> URL { root.appendingPathComponent("\(id.uuidString).edits.json") }
```

`delete(id:)`, `originalData(for:)`, `applyEdit(id:adjustments:rendered:)` resolve the record first (via the new `record(for:)`) and use its `encoderFormat`; `delete` falls back to trying BOTH extensions when the record is already gone (defensive sweep):

```swift
    /// The persisted record for an id (nil if absent).
    func record(for id: UUID) -> StackRecord? {
        ((try? loadRaw()) ?? []).first { $0.id == id }
    }

    /// Remove a stack's record and all of its files (result, original, edit sidecar).
    func delete(id: UUID) throws {
        let format = record(for: id)?.encoderFormat
        var urls = [editsURL(for: id)]
        for f in format.map({ [$0] }) ?? [.jpeg, .heic] {   // unknown record → sweep both extensions
            urls.append(resultURL(for: id, format: f))
            urls.append(originalURL(for: id, format: f))
        }
        for url in urls { try? fm.removeItem(at: url) }
        var records = (try? loadRaw()) ?? []
        records.removeAll { $0.id == id }
        try persist(records)
    }

    /// Delete every stack (Settings ▸ Storage). MainActor-only, like all writes.
    func deleteAll() throws {
        for record in (try? loadRaw()) ?? [] { try delete(id: record.id) }
    }

    /// Total bytes of all library files (results, originals, sidecars, index). Stateless file I/O —
    /// safe to call off the main thread (Settings computes it in a background task).
    func storageUsedBytes() -> Int64 {
        guard let files = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        return files.reduce(0) { sum, url in
            sum + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
    }
```

`reconcileOrphans()` — the suffix filter gains `.heic`:

```swift
            guard name.hasSuffix(".jpg") || name.hasSuffix(".heic") || name.hasSuffix(".json"),
                  name != "index.json" else { continue }
```

`originalData(for:)` and `applyEdit` updated to resolve via the record:

```swift
    /// The immutable original stacked image (the record's own format), used as the editing source.
    func originalData(for id: UUID) -> Data? {
        guard let rec = record(for: id) else { return nil }
        return try? Data(contentsOf: originalURL(for: id, format: rec.encoderFormat))
    }

    /// Overwrite the displayed result with a rendered image (in the record's own format), persist
    /// the adjustments, and bump `updatedAt` so gallery cells reload (their task id includes it).
    func applyEdit(id: UUID, adjustments: ImageAdjustments, rendered: Data) throws {
        guard let rec = record(for: id) else { return }
        try rendered.write(to: resultURL(for: id, format: rec.encoderFormat), options: Self.writeOptions)
        try JSONEncoder().encode(adjustments).write(to: editsURL(for: id), options: Self.writeOptions)
        var records = (try? loadRaw()) ?? []
        if let i = records.firstIndex(where: { $0.id == id }) {
            records[i].updatedAt = Date()
            try persist(records)
        }
    }
```

Update ALL existing call sites of the renamed `save`/`applyEdit` across app + tests (`grep -rn "resultJPEG\|renderedJPEG" StackStackStack --include="*.swift"`): coordinator passes `.jpeg` for now (Task 3 threads the real format); `EditorView`/`PhotoDetailView` pass through unchanged data with the new labels.

- [ ] **Step 4: Run** `-only-testing:StackStackStackTests/LibraryStoreTests` then the whole `StackStackStackTests` bundle. PASS expected.

- [ ] **Step 5: Commit**
```bash
git add StackStackStack
git commit -m "feat(library): format-aware store — StackRecord.format (nil=JPEG back-compat), HEIC filenames, deleteAll + storage usage + record lookup"
```

---

### Task 3: Coordinator — `exportFormat` snapshot, `makeResult`, JPEG fallback

**Files:**
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Test: `StackStackStack/StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: Failing tests** — append to `CoordinatorTests`:

```swift
    @MainActor
    func testShootHonoursExportFormat() async throws {
        let (coord, store) = makeCoordinator()
        coord.exportFormat = .heic
        await coord.shoot()
        await coord.awaitProcessing()
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertEqual(rec.encoderFormat, .heic)
        XCTAssertEqual(store.resultURL(for: rec).pathExtension, "heic")
        // The bytes really are HEIC: they decode, and re-encoding as HEIC is what produced them.
        XCTAssertNotNil(ImageDecoder.rgba8(from: try Data(contentsOf: store.resultURL(for: rec)), maxPixel: nil))
    }

    @MainActor
    func testDefaultFormatIsJPEG() async throws {
        let (coord, store) = makeCoordinator()
        await coord.shoot()
        await coord.awaitProcessing()
        XCTAssertEqual(try XCTUnwrap(store.loadAll().first).encoderFormat, .jpeg)
    }
```

- [ ] **Step 2: Verify failure** — compile error (`exportFormat` undefined).

- [ ] **Step 3: Implement** in `StackCaptureCoordinator.swift`:

(a) Property (near `mode`/`pro`/`burst`):
```swift
    /// Library/encode format for new captures. Kept in sync from AppSettings by the app root —
    /// the coordinator stays ignorant of the settings object. Snapshotted at shutter press. (spec §4)
    var exportFormat: ImageEncoder.Format = .jpeg
```

(b) In `shoot()`, snapshot next to `let mode = self.mode`:
```swift
        let format = self.exportFormat            // capture the format at shutter-press time
```
and thread it through `enqueueProcessing(frames:mode:format:orientationQuarterTurns:)` (add the parameter).

(c) In `enqueueProcessing`, the processing task calls the renamed encode and passes the format to the store — with the **JPEG fallback** (spec §8: never lose a stack to an encoder hiccup):
```swift
                var encoded: (data: Data, format: ImageEncoder.Format)
                do {
                    encoded = (try await Self.makeResult(from: frames, mode: mode, format: format,
                                                         orientationQuarterTurns: orientationQuarterTurns,
                                                         shouldCancel: { token.isCancelled }), format)
                } catch is CancellationError {
                    return
                } catch where format == .heic {
                    // HEIC encoder hiccup → fall back to JPEG and stamp the record accordingly.
                    encoded = (try await Self.makeResult(from: frames, mode: mode, format: .jpeg,
                                                         orientationQuarterTurns: orientationQuarterTurns,
                                                         shouldCancel: { token.isCancelled }), .jpeg)
                }
                if token.isCancelled { return }
                let saved = try self.store.save(result: encoded.data, format: encoded.format,
                                                mode: mode.rawValue, frameCount: frames.count)
```
(Keep the surrounding do/catch that surfaces `lastError` — only the encode call gains the inner fallback. Adapt to the file's existing structure; the variable currently named `jpeg` becomes `encoded.data` and `lastResultJPEG` keeps its name and stores `encoded.data` — it's display bytes, ImageIO decodes either format.)

(d) Rename `makeJPEG` → `makeResult` and give it the format parameter; the final encode line becomes:
```swift
            return try ImageEncoder.encode(rgba8: rgba, width: oriented.width, height: oriented.height,
                                           format: format, quality: 0.95)
```
(`dumpDevelopedFrames` keeps encoding JPEG — diagnostics don't need HEIC.)

- [ ] **Step 4: Run** CoordinatorTests + full unit bundle. PASS.

- [ ] **Step 5: Commit**
```bash
git add StackStackStack
git commit -m "feat(app): capture-time export format — coordinator snapshot, makeResult, HEIC→JPEG encode fallback"
```

---

### Task 4: `ResultRenderer` format param; renders use the record's format

**Files:**
- Modify: `StackStackStack/StackStackStack/ResultRenderer.swift`
- Modify: `StackStackStack/StackStackStack/UI/EditorView.swift:89,103`, `UI/PhotoDetailView.swift:95,116`, `UI/CaptureView.swift` (openEditor passes format if it renders)
- Test: `StackStackStack/StackStackStackTests/ResultRendererTests.swift`

- [ ] **Step 1: Failing test** — append to `ResultRendererTests` (reuse its existing fixture JPEG helper):

```swift
    func testRenderInHEICProducesDecodableHEIC() throws {
        let original = makeTestJPEG()   // adapt to the file's existing fixture helper
        let out = try XCTUnwrap(ResultRenderer.render(originalJPEG: original, adjustments: .identity,
                                                      quality: 0.9, format: .heic))
        XCTAssertNotNil(ImageDecoder.rgba8(from: out, maxPixel: nil), "HEIC output must decode")
        XCTAssertNotEqual(out.prefix(3), Data([0xFF, 0xD8, 0xFF]), "must not be JPEG magic bytes")
    }
```

- [ ] **Step 2: Verify failure** — compile error (no `format` param).

- [ ] **Step 3: Implement.** `ResultRenderer.render` gains `format: ImageEncoder.Format = .jpeg` (after `quality`), passed to the encode call. Call sites:
- `EditorView` (lines ~89/~103): it has `recordId` + `store` — compute once near the top of the view: `private var recordFormat: ImageEncoder.Format { store.record(for: recordId)?.encoderFormat ?? .jpeg }` and pass `format: recordFormat` at both render calls (preview AND save — previews of a HEIC record render HEIC so what you see is what gets written).
- `PhotoDetailView` (lines ~95/~116): same — it has `record` already: pass `format: record.encoderFormat`.
- `CaptureView.openEditor` renders a preview (line ~111) — pass the record's format via `lib.record(for: id)?.encoderFormat ?? .jpeg`.
Renders of an existing record use **the record's** format, never the current setting (spec §4).

- [ ] **Step 4: Run** ResultRendererTests + full unit bundle. PASS.

- [ ] **Step 5: Commit**
```bash
git add StackStackStack
git commit -m "feat(app): edit re-renders use the record's own format (never the current setting)"
```

---

### Task 5: `PhotoLibraryExporter` + coordinator hook

**Files:**
- Create: `StackStackStack/StackStackStack/Settings/PhotoLibraryExporter.swift`
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Test: `StackStackStack/StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: Failing tests** — the exporter itself needs the real Photos framework (device-verified in Task 10); unit-test the coordinator's gating via an injected stub. Append to `CoordinatorTests`:

```swift
    @MainActor
    func testPhotosExportRunsOnlyWhenEnabled() async throws {
        let exported = ExportLog()
        let (coord, _) = makeCoordinator()
        coord.photosExporter = { data, _ in await exported.record(data.count) }
        await coord.shoot()                       // saveToPhotosEnabled defaults to false
        await coord.awaitProcessing()
        let countDisabled = await exported.count
        XCTAssertEqual(countDisabled, 0, "no export when the toggle is off")
        coord.saveToPhotosEnabled = true
        await coord.shoot()
        await coord.awaitProcessing()
        try await Task.sleep(nanoseconds: 200_000_000)   // export is fire-and-forget
        let countEnabled = await exported.count
        XCTAssertEqual(countEnabled, 1, "one export per save when enabled")
        XCTAssertNil(coord.photosExportNote)
    }

    @MainActor
    func testPhotosExportFailureIsNonBlocking() async throws {
        let (coord, store) = makeCoordinator()
        coord.saveToPhotosEnabled = true
        coord.photosExporter = { _, _ in throw CaptureError.busy }   // any error
        await coord.shoot()
        await coord.awaitProcessing()
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(try store.loadAll().count, 1, "in-app save unaffected")
        XCTAssertNotNil(coord.photosExportNote, "failure surfaces as a note, not an error")
        XCTAssertNil(coord.lastError)
    }
```
with a file-scope helper actor (if none exists): `private actor ExportLog { private(set) var count = 0; func record(_ n: Int) { count += 1 } }`.

- [ ] **Step 2: Verify failure** — compile errors.

- [ ] **Step 3: Implement.**

`Settings/PhotoLibraryExporter.swift` (new):

```swift
import Photos

/// Writes an encoded image into the system photo library using ADD-ONLY authorization (the
/// lightweight permission — no library read). The system prompt appears contextually on the first
/// export. (spec §5)
enum PhotoLibraryExporter {
    enum ExportError: LocalizedError {
        case notAuthorized
        var errorDescription: String? { "Photos access is off. Enable Add-Only access in Settings ▸ Privacy ▸ Photos." }
    }

    /// Throws on denial or write failure; the caller treats failures as non-blocking.
    static func export(_ data: Data, format: ImageEncoder.Format) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw ExportError.notAuthorized }
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.uniformTypeIdentifier = format.utType.identifier
            request.addResource(with: .photo, data: data, options: options)
        }
    }
}
```

Coordinator additions:

```swift
    /// Mirror saves into the system photo library (Settings toggle; synced by the app root).
    var saveToPhotosEnabled = false
    /// The export function — injectable so tests don't touch the real photo library.
    var photosExporter: @Sendable (Data, ImageEncoder.Format) async throws -> Void = PhotoLibraryExporter.export
    /// Non-blocking note when a Photos export fails (the in-app save already succeeded).
    @Published private(set) var photosExportNote: String?
```
In `shoot()`: clear the note with the other per-shot clears (`photosExportNote = nil`) and snapshot `let exportToPhotos = self.saveToPhotosEnabled`. In `enqueueProcessing` (taking the new flag as a parameter), after the successful `store.save` + publishes:
```swift
                if exportToPhotos {
                    let exporter = self.photosExporter
                    let payload = encoded
                    Task { [weak self] in   // fire-and-forget; never blocks or fails the save
                        do { try await exporter(payload.data, payload.format) }
                        catch { await MainActor.run { self?.photosExportNote = "Photos export failed — check Settings ▸ Privacy" } }
                    }
                }
```
And in `CaptureView.statusLabel`, append the note to the Saved state:
```swift
            } else if coordinator.lastResultJPEG != nil {
                Text(coordinator.photosExportNote.map { "Saved ✓ · \($0)" } ?? "Saved ✓")
```

- [ ] **Step 4: Run** CoordinatorTests + bundle. PASS. (The real `PhotoLibraryExporter.export` is compile-verified; behavior is Task 10's device check.)

- [ ] **Step 5: Commit**
```bash
git add StackStackStack
git commit -m "feat(app): add-only Photos auto-export — fire-and-forget after save, non-blocking failure note"
```

---

### Task 6: capability probe (`supportsRAW`) + `SettingsView` + third tab

**Files:**
- Modify: `StackStackStack/StackStackStack/Capture/CaptureService.swift`, `Capture/AVCaptureService.swift`, `StackCaptureCoordinator.swift`
- Create: `StackStackStack/StackStackStack/Settings/SettingsView.swift`
- Modify: `StackStackStack/StackStackStack/StackStackStackApp.swift`
- Test: `StackStackStack/StackStackStackTests/CoordinatorTests.swift`

- [ ] **Step 1: Failing test** — append to `CoordinatorTests`:

```swift
    @MainActor
    func testSupportsRAWIsTrueWithTheFake() async {
        let (coord, _) = makeCoordinator()
        _ = await coord.startPreview()
        XCTAssertTrue(coord.supportsRAW)
    }
```

- [ ] **Step 2: Verify failure** — compile error.

- [ ] **Step 3: Implement.**

`CaptureService.swift` protocol (beside `supportsDepthOfField`) + extension default:
```swift
    /// Whether the camera vends a Bayer RAW format the converter can decode (capability report).
    var supportsRAWCapture: Bool { get }
```
```swift
    var supportsRAWCapture: Bool { true }   // overridden by the device service after configuring
```

`AVCaptureService.swift` — beside `manualLensSupported`: `private var rawSupported = true`; set in `ensureConfigured` right where the existing Bayer guard already computes it (the `availableRawPhotoPixelFormatTypes` check at the end of configuration):
```swift
                    let rawOK = self.output.availableRawPhotoPixelFormatTypes
                        .contains(where: { RawFrameConverter.isSupportedBayerFormat($0) })
                    self.stateQueue.async { self.rawSupported = rawOK }
```
and `var supportsRAWCapture: Bool { stateQueue.sync { rawSupported } }`.

Coordinator — `@Published private(set) var supportsRAW = true`, refreshed in `startPreview()` beside `supportsDepth`.

`Settings/SettingsView.swift` (new):

```swift
import SwiftUI

/// The app's third area (bible §15.1/§15.6): every row is backed by a real feature. (spec §3.2)
struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var coordinator: StackCaptureCoordinator
    let store: LibraryStore
    /// Set by the About section; the app root presents the onboarding cover.
    @Binding var showOnboarding: Bool

    @State private var usedBytes: Int64?
    @State private var stackCount: Int?
    @State private var confirmDeleteAll = false
    @State private var deleteError: String?

    var body: some View {
        Form {
            Section("Capture & Export") {
                Toggle("Save to Photos", isOn: $settings.saveToPhotos)
                Picker("Format", selection: $settings.exportFormat) {
                    Text("JPEG").tag(ImageEncoder.Format.jpeg)
                    Text("HEIC").tag(ImageEncoder.Format.heic)
                }
            }
            Section("Storage") {
                LabeledContent("Stacks", value: stackCount.map(String.init) ?? "…")
                LabeledContent("Space Used", value: usedBytes.map {
                    ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) } ?? "…")
                Button("Delete All Stacks", role: .destructive) { confirmDeleteAll = true }
                    .disabled((stackCount ?? 0) == 0)
                if let deleteError { Text(deleteError).font(.caption).foregroundColor(.red) }
            }
            Section("This Device") {
                LabeledContent("RAW Capture", value: coordinator.supportsRAW ? "Supported" : "Not supported")
                LabeledContent("Depth (Manual Focus)", value: coordinator.supportsDepth ? "Supported" : "Not supported")
            }
            Section("About") {
                LabeledContent("Version", value: Self.versionString)
                Button("Replay Introduction") { showOnboarding = true }
            }
        }
        .navigationTitle("Settings")
        .task { await refreshStorage() }
        .confirmationDialog("Delete all stacks? This cannot be undone.",
                            isPresented: $confirmDeleteAll, titleVisibility: .visible) {
            Button("Delete All", role: .destructive) {
                do { try store.deleteAll(); deleteError = nil } catch { deleteError = error.localizedDescription }
                Task { await refreshStorage() }
            }
        }
    }

    /// Storage accounting is file I/O — compute off-main, render a placeholder until ready. (spec §8)
    private func refreshStorage() async {
        let lib = store
        let (bytes, count) = await Task.detached {
            (lib.storageUsedBytes(), (try? lib.loadAll().count) ?? 0)
        }.value
        usedBytes = bytes
        stackCount = count
    }

    private static var versionString: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }
}
```

`StackStackStackApp.swift` — full new body (AppSettings + third tab + format/photos sync; onboarding cover arrives in Task 7 — wire `showOnboarding` state now):

```swift
import SwiftUI

@main
struct StackStackStackApp: App {
    // Owned once for the app's lifetime — constructing it in `body` would re-build/leak the
    // capture session on every view update.
    @StateObject private var coordinator = StackCaptureCoordinator(capture: StackStackStackApp.makeCaptureService())
    @StateObject private var settings = AppSettings()
    @State private var showOnboarding = false

    var body: some Scene {
        WindowGroup {
            TabView {
                NavigationStack { CaptureView(coordinator: coordinator, steadiness: coordinator.steadiness) }
                    .tabItem { Label("Capture", systemImage: "camera") }
                NavigationStack { GalleryView() }
                    .tabItem { Label("Gallery", systemImage: "photo.on.rectangle") }
                NavigationStack {
                    SettingsView(coordinator: coordinator, store: coordinator.library,
                                 showOnboarding: $showOnboarding)
                }
                .tabItem { Label("Settings", systemImage: "gearshape") }
            }
            .environmentObject(settings)
            // The coordinator stays ignorant of AppSettings: the root mirrors the two prefs in.
            .onAppear {
                coordinator.exportFormat = settings.exportFormat
                coordinator.saveToPhotosEnabled = settings.saveToPhotos
            }
            .onReceive(settings.$exportFormat) { coordinator.exportFormat = $0 }
            .onReceive(settings.$saveToPhotos) { coordinator.saveToPhotosEnabled = $0 }
        }
    }

    private static func makeCaptureService() -> CaptureService {
        #if targetEnvironment(simulator)
        // No camera in the Simulator — use the deterministic fake so the flow is demoable.
        return FakeCaptureService(width: 128, height: 128)
        #else
        // AVCaptureService configures lazily (and off the main thread) on first capture.
        return AVCaptureService()
        #endif
    }
}
```

- [ ] **Step 4: Run** CoordinatorTests + full bundle; build succeeds with the new tab.

- [ ] **Step 5: Commit**
```bash
git add StackStackStack
git commit -m "feat(settings): Settings tab — export prefs, storage management, capability report, about; RAW probe"
```

---

### Task 7: Onboarding

**Files:**
- Create: `StackStackStack/StackStackStack/Onboarding/OnboardingView.swift`
- Modify: `StackStackStack/StackStackStack/StackStackStackApp.swift`
- Test: `StackStackStack/StackStackStackUITests/StackFlowUITests.swift`

- [ ] **Step 1: Failing UI tests** — append to `StackFlowUITests` (mirror its `#if !targetEnvironment(simulator)` skip-guard style — these RUN on the simulator):

```swift
    func testFreshInstallShowsOnboardingAndSkipLandsOnCapture() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-resetOnboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["onboarding-skip"].waitForExistence(timeout: 10),
                      "fresh install must show the onboarding cover")
        app.buttons["onboarding-skip"].tap()
        XCTAssertTrue(app.buttons["shutter"].waitForExistence(timeout: 10), "skip lands on Capture")
    }

    func testOnboardingDoesNotReappearAfterSkip() throws {
        let app = XCUIApplication()
        app.launch()   // no reset argument — defaults persist from the previous test/launch
        XCTAssertTrue(app.buttons["shutter"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["onboarding-skip"].exists)
    }
```

- [ ] **Step 2: Verify failure** — first test FAILS (no onboarding).

- [ ] **Step 3: Implement.**

`Onboarding/OnboardingView.swift` (new):

```swift
import SwiftUI
import AVFoundation

/// One onboarding page's content — data, not views, so copy/imagery edits never touch flow logic. (spec §6)
struct OnboardingPage: Identifiable {
    let id: String
    let symbol: String
    let tint: Color
    let title: String
    let what: String
    let when: String

    static let looks: [OnboardingPage] = [
        OnboardingPage(id: "detail", symbol: "sparkles", tint: .cyan, title: "Detail",
                       what: "Stacks a burst into one clean, low-noise shot.",
                       when: "Everyday shots, low light, anywhere you want maximum quality."),
        OnboardingPage(id: "smooth", symbol: "water.waves", tint: .blue, title: "Smooth",
                       what: "Averages motion into silky blur while still things stay sharp.",
                       when: "Waterfalls, rivers, clouds, busy crowds."),
        OnboardingPage(id: "trails", symbol: "car.rear.road.lane", tint: .orange, title: "Trails",
                       what: "Keeps the bright paths moving lights leave behind.",
                       when: "Night traffic, fairground rides, sparklers."),
        OnboardingPage(id: "night", symbol: "moon.stars.fill", tint: .indigo, title: "Night",
                       what: "Stacks and brightens a dark scene without the noise.",
                       when: "Dusk, dim rooms, city nights."),
        OnboardingPage(id: "depth", symbol: "camera.macro", tint: .green, title: "Depth",
                       what: "Sweeps focus near→far and keeps the sharpest of each.",
                       when: "Close subjects with a background you also want sharp."),
    ]
}

/// First-launch introduction (bible §15.6): welcome → the five looks → camera pre-prompt.
/// Skippable everywhere; finishing or skipping sets `hasSeenOnboarding`. (spec §6)
struct OnboardingView: View {
    @EnvironmentObject private var settings: AppSettings
    @Binding var isPresented: Bool
    @State private var cameraDenied = AVCaptureDevice.authorizationStatus(for: .video) == .denied

    var body: some View {
        VStack {
            HStack {
                Spacer()
                Button("Skip") { finish() }
                    .padding()
                    .accessibilityIdentifier("onboarding-skip")
            }
            TabView {
                welcome
                ForEach(OnboardingPage.looks) { lookCard($0) }
                cameraPage
            }
            .tabViewStyle(.page)
            .indexViewStyle(.page(backgroundDisplayMode: .always))
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
    }

    private var welcome: some View {
        pageScaffold(symbol: "square.stack.3d.up.fill", tint: .white, title: "Stack Stack Stack",
                     line1: "Shoot a short handheld burst; the app aligns and stacks it into a shot one frame can't make.",
                     line2: "Everything runs on your phone. No cloud, no account.")
    }

    private func lookCard(_ page: OnboardingPage) -> some View {
        pageScaffold(symbol: page.symbol, tint: page.tint, title: page.title,
                     line1: page.what, line2: page.when)
    }

    private var cameraPage: some View {
        VStack(spacing: 16) {
            pageScaffold(symbol: "camera.fill", tint: .yellow, title: "One thing first",
                         line1: "Stack Stack Stack is a camera — it needs camera access to shoot.",
                         line2: "Nothing is captured until you press the shutter.")
            if cameraDenied {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .buttonStyle(.borderedProminent)
            } else {
                Button("Enable Camera") {
                    AVCaptureDevice.requestAccess(for: .video) { _ in
                        Task { @MainActor in finish() }   // continue regardless of the answer
                    }
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("onboarding-enable-camera")
            }
            Button("Done") { finish() }
                .foregroundColor(.secondary)
                .accessibilityIdentifier("onboarding-done")
        }
    }

    private func pageScaffold(symbol: String, tint: Color, title: String,
                              line1: String, line2: String) -> some View {
        VStack(spacing: 20) {
            Spacer()
            ZStack {   // stylized stand-in for a sample shot; a real image can replace it later
                RoundedRectangle(cornerRadius: 24)
                    .fill(LinearGradient(colors: [tint.opacity(0.55), .black],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 180, height: 180)
                Image(systemName: symbol).font(.system(size: 64)).foregroundColor(.white)
            }
            Text(title).font(.title).bold().foregroundColor(.white)
            Text(line1).multilineTextAlignment(.center).foregroundColor(.white.opacity(0.9))
            Text(line2).font(.callout).multilineTextAlignment(.center).foregroundColor(.white.opacity(0.6))
            Spacer()
        }
        .padding(.horizontal, 32)
    }

    private func finish() {
        settings.hasSeenOnboarding = true
        isPresented = false
    }
}
```

`StackStackStackApp.swift` — present the cover and honor the test reset argument. In `init` (add one):
```swift
    init() {
        // UI-test hook: a fresh-install run (defaults are otherwise sticky per simulator install).
        if ProcessInfo.processInfo.arguments.contains("-resetOnboarding") {
            UserDefaults.standard.removeObject(forKey: "hasSeenOnboarding")
        }
    }
```
and on the `TabView` (after the `.onReceive` modifiers):
```swift
            .fullScreenCover(isPresented: $showOnboarding) {
                OnboardingView(isPresented: $showOnboarding)
            }
            .onAppear { if !settings.hasSeenOnboarding { showOnboarding = true } }
```
(The `onAppear` that mirrors prefs already exists — merge into one `onAppear` block.)

- [ ] **Step 4: Run** the two new UI tests + the full `StackFlowUITests` class (pre-existing flow tests must still pass — they launch WITHOUT the reset argument and the previous test persisted `hasSeenOnboarding`, but ORDER ISN'T GUARANTEED: make every PRE-EXISTING test resilient by adding a tiny helper at the top of the class that skips onboarding if present, called after `app.launch()`:
```swift
    /// Onboarding may cover the UI on a fresh simulator install — dismiss it before flow tests.
    private func dismissOnboardingIfPresent(_ app: XCUIApplication) {
        if app.buttons["onboarding-skip"].waitForExistence(timeout: 2) { app.buttons["onboarding-skip"].tap() }
    }
```
and call it in each pre-existing test after launch). Then the full unit bundle once. ALL PASS.

- [ ] **Step 5: Commit**
```bash
git add StackStackStack
git commit -m "feat(onboarding): first-launch intro — welcome, five look cards (data-driven), camera pre-prompt; replay from Settings"
```

---

### Task 8: Docs — bible §15.6 annotation + delta updates (+ commit the delta doc)

**Files:**
- Modify: `docs/superpowers/specs/2026-06-04-stack-stack-stack-photography-design.md` (§15.6, lines ~384-386)
- Modify: `docs/superpowers/specs/2026-06-10-design-implementation-delta.md`

- [ ] **Step 1: Bible §15.6** — after the existing two bullets ("Onboarding:" and "Settings:"), insert:

```markdown
> **Implementation status (2026-06-10).** Onboarding (permissions pre-prompt + look explainer with
> stylized cards) and Settings shipped with the Settings+Onboarding PR: default export format
> (JPEG/HEIC, wired capture-time), save-to-Photos (add-only auto-export), storage management,
> "What your device supports" (RAW + manual-focus/Depth), replay onboarding, about. Deliberately
> NOT shipped, awaiting their underlying features: **default RAW on/off** (blocked on the YUV/HEIC
> capture fallback, §10.2), **grid/level** (the overlay feature doesn't exist), **max session
> length** (superseded by the capture screen's burst sliders, hard-capped at 60 s).
```

- [ ] **Step 2: Delta doc** — three edits:
1. TL;DR #1: replace the paragraph with `1. **Depth of Field — DONE (PR #30).** Shipped end-to-end: chain alignment (adjacent-bracket links + bounds), lensPosition focus sweep, Depth chip + Near/Far Pro controls, capability gating, real-bracket regression fixture.`
2. TL;DR #2: replace with `2. **Settings + Onboarding — DONE (this PR).** Third tab (save-to-Photos, JPEG/HEIC capture-time format, storage, capability report, replay intro, about) + first-launch onboarding. Still missing from §15.6 by design: RAW toggle, grid/level, max session length (see the bible's §15.6 status note).`
3. §15 row in the status table: change to `| §15 UX screens | Mostly done | Capture + Gallery + detail/editor + Settings + Onboarding; processing/gallery chrome gaps remain |`. Also update the "§13.2 Depth of field" row to `Done (PR #30)` and the §15.6 lines in section 8 accordingly (mark Settings/Onboarding bullets done, keep the gallery/processing gaps).

- [ ] **Step 3: Commit (the delta doc finally enters the repo)**
```bash
git add docs/superpowers/specs/2026-06-04-stack-stack-stack-photography-design.md docs/superpowers/specs/2026-06-10-design-implementation-delta.md
git commit -m "docs: bible §15.6 implementation-status note; delta doc updated (DoF PR #30, Settings+Onboarding) and committed"
```

---

### Task 9: Full-suite verification

- [ ] Engine: `cd /Users/davidneto/photo-stack-app/Packages/StackEngineCore && swift test` — all green (nothing here should have touched it; verify anyway).
- [ ] App unit bundle: `-only-testing:StackStackStackTests` — green.
- [ ] Full UI class: `-only-testing:StackStackStackUITests/StackFlowUITests` — green (incl. both onboarding tests and the pre-existing flows with the dismiss helper). Known infra flake: simulator clone "preflight checks/Busy" — a failure with ZERO failing test cases is infra; boot the sim and retry once.
- [ ] `git status --short` — only `CLAUDE.md` untracked.
- [ ] Commit any stragglers.

---

### Task 10: Device verification + /code-review + PR (manual gate — involve the user)

- [ ] **Photos export round-trip on the iPhone** (mobile-mcp workflow, memory: `mobile-mcp-device-setup`): toggle Save to Photos on → shoot → system permission prompt appears → result lands in Photos.app; deny path → "Saved ✓ · Photos export failed…" note, in-app save intact.
- [ ] **HEIC on device:** switch format to HEIC → shoot → Settings storage shows the smaller file; share sheet exports a `.heic`.
- [ ] **Onboarding on device:** delete + reinstall the app (or use the reset argument via Xcode scheme) → onboarding shows; camera pre-prompt triggers the system dialog.
- [ ] Run `/code-review` (CLAUDE.md: ALWAYS before merging PRs); address findings.
- [ ] Open the PR (`feat/settings-onboarding` → `main`) referencing the spec, with device evidence.

---

## Self-review notes (done at plan-writing time)

- **Spec coverage:** §3.1→T1, §3.2→T6, §4→T1/T2/T3/T4, §5→T5, §6→T7, §7→T8, §8 rows→T3 (HEIC fallback), T5 (non-blocking note), T6 (delete-all confirm + off-main storage), T7 (denied → Open Settings), §9→every task's tests + T10 device checks.
- **Type consistency:** `save(result:format:mode:frameCount:)` and `applyEdit(id:adjustments:rendered:)` renamed in T2 and used in T3/T4; `ImageEncoder.Format` String-backed from T1; `encoderFormat` defined in T2, used in T2/T3/T4/T6 tests.
- **Judgment calls encoded:** previews render in the record's format (WYSIWYG); `lastResultJPEG` keeps its (now slightly stale) name to avoid a noisy rename — flag to the quality reviewer rather than churn; diagnostics dumps stay JPEG.
