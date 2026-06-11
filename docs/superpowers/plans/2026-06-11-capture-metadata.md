# Capture Metadata + EXIF/ICC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps.

**Goal:** First-frame ISO/shutter persisted on the record; EXIF + verified ICC in encoded results. Spec: `docs/superpowers/specs/2026-06-11-capture-metadata-design.md`. Branch `feat/capture-metadata`.

### Task 1: Encoder EXIF/ICC + StackRecord fields + store threading

**Files:** `StackStackStack/StackStackStack/ImageEncoder.swift`, `Library/StackRecord.swift`, `Library/LibraryStore.swift`; tests `ImageEncoderTests.swift`, `LibraryStoreTests.swift`.

- [ ] Failing tests:
```swift
    // ImageEncoderTests
    func testEXIFAndICCAreEmbedded() throws {
        let rgba: [UInt8] = Array(repeating: 128, count: 8 * 8 * 4)
        let exif = ImageEncoder.ExifMetadata(iso: 320, shutterSeconds: 0.02, capturedAt: Date(timeIntervalSince1970: 1_750_000_000))
        let data = try ImageEncoder.encode(rgba8: rgba, width: 8, height: 8, format: .jpeg, quality: 0.9, exif: exif)
        let src = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        let props = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any])
        let exifDict = try XCTUnwrap(props[kCGImagePropertyExifDictionary] as? [CFString: Any])
        XCTAssertEqual((exifDict[kCGImagePropertyExifISOSpeedRatings] as? [Int])?.first, 320)
        XCTAssertEqual(exifDict[kCGImagePropertyExifExposureTime] as? Double ?? 0, 0.02, accuracy: 1e-6)
        XCTAssertNotNil(exifDict[kCGImagePropertyExifDateTimeOriginal])
        let tiff = try XCTUnwrap(props[kCGImagePropertyTIFFDictionary] as? [CFString: Any])
        XCTAssertEqual(tiff[kCGImagePropertyTIFFSoftware] as? String, "Stack Stack Stack")
        // ICC: the decoded image's color space must be sRGB (profile embedded, not device-implied).
        let cg = try XCTUnwrap(CGImageSourceCreateImageAtIndex(src, 0, nil))
        let csName = try XCTUnwrap(cg.colorSpace?.name) as String
        XCTAssertTrue(csName.contains("sRGB"), "expected an embedded sRGB profile, got \(csName)")
    }
    func testEncodeWithoutExifStillDecodes() throws { /* encode exif: nil → decodes, no Exif requirement */ }

    // LibraryStoreTests
    func testCaptureInfoPersistsAndLegacyDecodesNil() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xAA]), reference: nil, format: .jpeg,
                                   mode: "noiseReduction", frameCount: 3, iso: 250, shutterSeconds: 0.008)
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertEqual(rec.iso, 250); XCTAssertEqual(rec.shutterSeconds ?? 0, 0.008, accuracy: 1e-9)
        // strip keys from index.json (the legacy-record pattern used by the format test) → nil fields
        // ADAPT: reuse the existing JSON-stripping helper pattern; assert decoded iso/shutterSeconds nil.
        _ = saved
    }
```
- [ ] Implement: `ImageEncoder.ExifMetadata` struct (iso/shutterSeconds/capturedAt all optional); `encode(... exif: ExifMetadata? = nil)` building the properties dict (compression quality + Exif dict + TIFF Software; DateTimeOriginal formatted "yyyy:MM:dd HH:mm:ss" with a FIXED `en_US_POSIX` locale + current timezone — comment why). ICC: if the test's color-space assert fails as-is, attach the profile explicitly (`kCGImageDestinationOptimizeColorForSharing` is NOT it — use the image's colorSpace; if needed add `kCGImagePropertyProfileName`… investigate and make the test pass honestly — the CGImage is created with `CGColorSpace(name: CGColorSpace.sRGB)` which ImageIO normally embeds for JPEG/HEIC; the test will confirm). `StackRecord`: + `iso: Double?`, `shutterSeconds: Double?` (Codable optionals). `LibraryStore.save` gains `iso: Double? = nil, shutterSeconds: Double? = nil` (defaulted — existing call sites compile unchanged) and writes them to the record.
- [ ] Unit suites for the two test classes + full bundle. Commit: `feat(app): EXIF metadata + verified ICC in encoded results; ISO/shutter on StackRecord`.

### Task 2: CaptureInfo extraction + CapturedBurst struct + coordinator threading

**Files:** `Capture/CaptureService.swift`, `Capture/AVCaptureService.swift`, `Capture/FakeCaptureService.swift`, `StackCaptureCoordinator.swift`; tests `CoordinatorTests.swift`, `FakeCaptureServiceTests.swift`.

- [ ] `CapturedBurst` becomes:
```swift
/// One burst's output: the frames plus capture metadata from the first frame (locked-exposure
/// bursts make per-frame metadata redundant — spec 2026-06-11 §1 deviation note).
struct CapturedBurst: Sendable {
    enum Payload: Sendable {
        case raw([RawSensorFrame])
        case developed([PixelImage])
    }
    var payload: Payload
    var info: CaptureInfo?
    var count: Int { switch payload { case .raw(let f): return f.count; case .developed(let i): return i.count } }
    var isEmpty: Bool { count == 0 }
}

/// First-frame capture metadata (EXIF source for the encoded result).
struct CaptureInfo: Sendable, Equatable {
    var iso: Double?
    var shutterSeconds: Double?
}
```
  Update ALL construction/destructuring sites (`grep -rn "CapturedBurst\|case .raw\|case .developed" StackStackStack --include="*.swift"`): fakes wrap `.init(payload: .raw(...), info: nil)`; coordinator switches on `burst.payload`; DevelopedFake in tests likewise.
- [ ] `AVCaptureService`: extract info from the FIRST converted photo on `processingQueue`: `photo.metadata[kCGImagePropertyExifDictionary as String] as? [String: Any]` → ISOSpeedRatings array first + ExposureTime; store into a stateQueue `var burstInfo: CaptureInfo?` (set once, when nil); `finishLocked` packages it. Reset per burst.
- [ ] Coordinator: thread `burst.info` through `enqueueProcessing` → on save: `store.save(..., iso: info?.iso, shutterSeconds: info?.shutterSeconds)`; makeResult's result encode passes `ImageEncoder.ExifMetadata(iso: info?.iso, shutterSeconds: info?.shutterSeconds, capturedAt: Date())`… NOTE makeResult is `nonisolated static` in a detached task — pass the Date from shoot()-time (orientation pattern). The HEIC fallback + reference encodes: reference/fallback re-encode keep the SAME exif (pass through).
- [ ] Tests: `RawFake`-style fake providing `info: CaptureInfo(iso: 640, shutterSeconds: 0.01)` → record fields land (coordinator test); FakeCaptureServiceTests destructuring updated (`burst.payload`).
- [ ] Full unit bundle + StackFlowUITests + engine suite. Commit: `feat(capture): first-frame CaptureInfo through CapturedBurst; EXIF threaded into the result encode`.

### Task 3 (controller): docs (delta #6 done-with-scope note + bible §9.1 status line), /code-review pass, merge.

## Self-review: spec §1 → T1+T2; §2 (nil-safe) → both; §3 tests → T1/T2; deviations recorded in spec + T3 docs.
