# Blend Strength (α) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Blend strength as a full-quality non-destructive edit: store the working-res aligned reference at capture, lerp result↔reference in the editor.

**Architecture:** Engine: `ImageAdjustments.blendStrength` + `ImageEditor.apply(_:to:reference:)` lerp-first; `Pipeline.*WithReference` variants return the alignment anchor. App: coordinator encodes the reference with the same orientation/format as the result; `LibraryStore` persists `<uuid>.ref.<ext>`; `ResultRenderer`/`EditorView` thread it through. Spec: `docs/superpowers/specs/2026-06-11-blend-strength-design.md`.

**Tech Stack:** pure-Swift engine, SwiftUI app, XCTest. Branch `feat/blend-strength`. Sim: `xcrun simctl list devices available | grep -i iphone | head -3`. Git hygiene: stage exact paths, `CLAUDE.md` stays untracked.

---

### Task 1: Engine — `blendStrength` + `ImageEditor` lerp + Pipeline reference variants

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/ImageAdjustments.swift`, `ImageEditor.swift`, `Pipeline.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/ImageAdjustmentsTests.swift`, `ImageEditorTests.swift`, `PipelineTests.swift`, `PipelineStreamingTests.swift`

- [ ] **Step 1: Failing tests.** READ each test file first; append (adapting to local helpers):

`ImageAdjustmentsTests`:
```swift
    func testBlendStrengthDefaultsToFullLookAndDecodesWhenMissing() throws {
        XCTAssertEqual(ImageAdjustments.identity.blendStrength, 1)
        // Sidecars written before the field existed must decode as full look.
        let legacy = try JSONDecoder().decode(ImageAdjustments.self, from: Data("{}".utf8))
        XCTAssertEqual(legacy.blendStrength, 1)
        XCTAssertTrue(legacy.isIdentity)
    }
```
`ImageEditorTests` (use the file's image-construction helpers; build a 8×8 img filled 0.8 and ref filled 0.2):
```swift
    func testBlendLerpsTowardReferenceInLinearLight() {
        let img = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.8, 0.8, 0.8))
        let ref = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.2, 0.2, 0.2))
        var adj = ImageAdjustments.identity
        adj.blendStrength = 0.5
        let out = ImageEditor.apply(adj, to: img, reference: ref)
        XCTAssertEqual(out[3, 3].x, 0.5, accuracy: 1e-5, "α=0.5 is the linear midpoint")
        adj.blendStrength = 0
        XCTAssertEqual(ImageEditor.apply(adj, to: img, reference: ref)[3, 3].x, 0.2, accuracy: 1e-5)
        adj.blendStrength = 1
        XCTAssertEqual(ImageEditor.apply(adj, to: img, reference: ref)[3, 3].x, 0.8, accuracy: 1e-5)
    }

    func testBlendSkipsOnMissingOrMismatchedReference() {
        let img = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.8, 0.8, 0.8))
        var adj = ImageAdjustments.identity
        adj.blendStrength = 0
        XCTAssertEqual(ImageEditor.apply(adj, to: img, reference: nil)[3, 3].x, 0.8, accuracy: 1e-5)
        let small = PixelImage(width: 4, height: 4, fill: SIMD3<Float>(0.2, 0.2, 0.2))
        XCTAssertEqual(ImageEditor.apply(adj, to: img, reference: small)[3, 3].x, 0.8, accuracy: 1e-5,
                       "dimension mismatch must skip the blend, never trap")
    }

    func testBlendAppliesBeforeTonal() {
        // EV +1 on an α=0 blend must double the REFERENCE, proving lerp-first ordering.
        let img = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.8, 0.8, 0.8))
        let ref = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.2, 0.2, 0.2))
        var adj = ImageAdjustments.identity
        adj.blendStrength = 0
        adj.exposureEV = 1
        XCTAssertEqual(ImageEditor.apply(adj, to: img, reference: ref)[3, 3].x, 0.4, accuracy: 1e-4)
    }
```
`PipelineTests`:
```swift
    func testReduceImagesWithReferenceReturnsTheAnchor() {
        // Reuse the file's existing synthetic stack helper; the reference must be the sharpest
        // frame of the aligned stack — for co-registered identical frames, byte-equal to frame 0's content.
        let imgs = makeSyntheticStack()   // ADAPT to the file's existing fixture (any ≥2-frame helper)
        let (result, reference) = Pipeline.reduceImagesWithReference(imgs, mode: .noiseReduction)
        XCTAssertEqual(result.width, reference.width)
        XCTAssertEqual(result.height, reference.height)
        XCTAssertGreaterThan(Luma.sharpness(of: Luma.luminance(reference), width: reference.width, height: reference.height), 0)
    }
```
`PipelineStreamingTests`:
```swift
    func testReduceStreamingWithReferenceReturnsTheAnchorFrame() throws {
        // ADAPT: reuse the file's raw-frame fixture; anchor = frame 0's developed image.
        let frames = makeFrames(count: 3)   // existing helper name may differ — adapt
        let (result, reference) = try Pipeline.reduceStreamingWithReference(frames, mode: .smoothMotion, binnedDevelop: true)
        XCTAssertEqual(result.width, reference.width)
        XCTAssertEqual(result.height, reference.height)
    }
```

- [ ] **Step 2: verify compile failure.** `cd Packages/StackEngineCore && swift test --filter ImageEditorTests` → unknown member errors.

- [ ] **Step 3: Implement.**

`ImageAdjustments.swift`: add `public var blendStrength: Float` ("look strength α: 1 = full look, 0 = the aligned reference frame; lerp applied in linear light before geometry/tonal; spec 2026-06-11") — add to init (default 1), to `init(from:)` (`?? 1`), and `public var hasBlend: Bool { blendStrength < 1 }`. CRITICAL: `identity` must still equal a default-init value (blendStrength 1 in both) and `isIdentity` stays the synthesized ==.

`ImageEditor.swift`:
```swift
    /// Apply adjustments with an optional aligned reference for the blend-strength lerp
    /// (out = α·img + (1−α)·reference, linear light, BEFORE geometry/tonal so the reference
    /// needs no separate geometry pass). Missing/mismatched reference skips the blend. (spec §3)
    public static func apply(_ adj: ImageAdjustments, to img: PixelImage, reference: PixelImage?) -> PixelImage {
        var base = img
        if adj.hasBlend, let ref = reference, ref.width == img.width, ref.height == img.height {
            let a = max(0, min(1, adj.blendStrength))
            for i in 0..<base.pixels.count {
                base.pixels[i] = img.pixels[i] * a + ref.pixels[i] * (1 - a)
            }
        }
        return apply(adj, to: base)
    }
```
NOTE: `apply(adj, to: base)` short-circuits on `isIdentity` — an adjustments value with ONLY blendStrength≠1 is not identity (== fails on blendStrength), so the call-through is safe; verify `isIdentity` uses synthesized Equatable (it does: `self == .identity`).

`Pipeline.swift`:
```swift
    /// Align + reduce, ALSO returning the aligned reference (sharpest) frame — the second endpoint
    /// of the editor's blend-strength lerp. (spec 2026-06-11 §3)
    public static func reduceImagesWithReference(_ imgs: [PixelImage], mode: StackMode, searchRange: Int = 8,
                                                 workingResolution: Int? = nil) -> (result: PixelImage, reference: PixelImage) {
```
— restructure: extract the body of `reduceImages` so both share one implementation: downscale → alignedStack → compute refIdx ONCE (note: `alignedStack` currently computes refIdx internally and doesn't expose it — refactor `alignedStack` to an internal variant returning `(aligned: [PixelImage], refIdx: Int)`, keep the old signature as a wrapper) → switch reducer → return (result, aligned[refIdx]). `reduceImages` becomes `reduceImagesWithReference(...).result`. The `.depthOfField` trap case stays.

```swift
    /// Streaming reduce that also returns the anchor (frame 0 at working resolution) — it is
    /// already held alive for alignment; returning it costs nothing. (spec 2026-06-11 §3)
    public static func reduceStreamingWithReference(_ frames: [RawSensorFrame], mode: StackMode,
                                                    searchRange: Int = 8, workingResolution: Int? = nil,
                                                    binnedDevelop: Bool = true,
                                                    shouldCancel: () -> Bool = { false }) throws -> (result: PixelImage, reference: PixelImage) {
```
— same restructure of `reduceStreaming` (it already binds `let reference = develop(0)`); old name wraps and discards.

- [ ] **Step 4:** `swift test` — FULL engine suite green (127+new; nothing existing may break).

- [ ] **Step 5: Commit** — `git add Packages/StackEngineCore && git commit -m "feat(core): blendStrength adjustment + lerp-first editor blend + Pipeline reference-returning variants"`

---

### Task 2: App — reference capture/persist + renderer/editor threading

**Files:**
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`, `Library/LibraryStore.swift`, `ResultRenderer.swift`, `UI/EditorView.swift`, `UI/CaptureView.swift`, `UI/PhotoDetailView.swift`
- Test: `StackStackStackTests/LibraryStoreTests.swift`, `CoordinatorTests.swift`, `ResultRendererTests.swift`

- [ ] **Step 1: Failing tests** (READ each test file; adapt helpers):

`LibraryStoreTests`:
```swift
    func testReferenceRoundTripAndDeletion() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xAA]), reference: Data([0xBB]), format: .heic,
                                   mode: "smoothMotion", frameCount: 3)
        XCTAssertEqual(store.referenceData(for: saved.id), Data([0xBB]))
        try store.delete(id: saved.id)
        XCTAssertNil(store.referenceData(for: saved.id))
    }

    func testSaveWithoutReferenceHasNilReferenceData() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xAA]), reference: nil, format: .jpeg,
                                   mode: "depthOfField", frameCount: 10)
        XCTAssertNil(store.referenceData(for: saved.id))
    }

    func testReconcileKeepsLiveReferences() throws {
        let store = makeStore()
        let saved = try store.save(result: Data([0xAA]), reference: Data([0xBB]), format: .jpeg,
                                   mode: "noiseReduction", frameCount: 3)
        store.reconcileOrphans()
        XCTAssertNotNil(store.referenceData(for: saved.id))
    }
```
`CoordinatorTests`:
```swift
    @MainActor
    func testShootSavesAReferenceForBlendableLooks() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .smoothMotion
        await coord.shoot()
        await coord.awaitProcessing()
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertNotNil(store.referenceData(for: rec.id), "long-exposure looks store the blend reference")
    }

    @MainActor
    func testDepthShootSavesNoReference() async throws {
        let (coord, store) = makeCoordinator()
        coord.mode = .depthOfField
        await coord.shoot()
        await coord.awaitProcessing()
        let rec = try XCTUnwrap(store.loadAll().first)
        XCTAssertNil(store.referenceData(for: rec.id), "no blend semantics for focus stacks")
    }
```
`ResultRendererTests`:
```swift
    func testRenderAtAlphaZeroMatchesReference() throws {
        // Two flat images: original 0.8 grey, reference 0.2 grey; α=0 must render ≈ the reference.
        let original = encodeFlat(level: 0.8)    // ADAPT: build via the file's PixelImage→encode pattern
        let reference = encodeFlat(level: 0.2)
        var adj = ImageAdjustments.identity
        adj.blendStrength = 0
        let out = try XCTUnwrap(ResultRenderer.render(originalJPEG: original, adjustments: adj,
                                                      quality: 0.95, referenceJPEG: reference))
        let (rgba, w, h) = try XCTUnwrap(ImageDecoder.rgba8(from: out, maxPixel: nil))
        // Centre pixel ≈ the reference's sRGB-encoded 0.2-linear grey (same encode path); tolerance for JPEG.
        let refDecoded = try XCTUnwrap(ImageDecoder.rgba8(from: reference, maxPixel: nil))
        XCTAssertEqual(Int(rgba[(h/2 * w + w/2) * 4]), Int(refDecoded.0[(h/2 * w + w/2) * 4]), accuracy: 6)
    }
```
(If `XCTAssertEqual(Int, Int, accuracy:)` displeases, use abs-diff < 6.)

- [ ] **Step 2: verify compile failure** (`save(result:reference:...)`, `referenceData`, `referenceJPEG` unknown).

- [ ] **Step 3: Implement.**

`LibraryStore.swift`: `save` gains `reference: Data?` (second parameter, after `result`); writes `<uuid>.ref.<ext>` when non-nil ("the aligned reference frame — the blend-strength lerp's second endpoint; absent for depth and legacy records"). Add `private func referenceURL(for id: UUID, format:)`, `func referenceData(for id: UUID) -> Data?` (record-resolved). `delete`/`deleteAll` remove it; `reconcileOrphans`' UUID logic already keeps any `<uuid>.*` file of a live record — VERIFY the suffix filter passes `.ref.jpg`/`.ref.heic` (it checks `.jpg`/`.heic`/`.json` suffixes — yes) and the 36-char prefix logic (yes). UPDATE ALL existing `save(result:format:...)` call sites (coordinator + tests) — add `reference:` (the label is new; pass `nil` in old tests or update where the test gains coverage).

`StackCaptureCoordinator.swift`: `makeResult` returns `(data: Data, reference: Data?, format: ImageEncoder.Format)`. Long-exposure path → `Pipeline.reduceStreamingWithReference`; static path → `Pipeline.developedFrames` + `Pipeline.reduceImagesWithReference` (CHECK: the static path currently calls `reduceImages` on developed frames — switch to the WithReference variant); depth path → reference nil. Orientation: `let orientedRef = referencePixels.map { ImageGeometry.rotated($0, quarterTurns: orientationQuarterTurns) }` — the SAME bake as the result. Encode the reference with the SAME format the result ended up with (inside the HEIC-fallback structure: if the result fell back to JPEG, encode the reference as JPEG too; reference-encode failure → reference = nil, never throw past it: wrap in `try?`). `enqueueProcessing` passes `encoded.reference` into `store.save`.

`ResultRenderer.swift`: `render(originalJPEG:adjustments:quality:maxPixel:format:referenceJPEG: Data? = nil)`: decode reference with the SAME maxPixel, `OutputTransform.decodeSRGB8` it, call `ImageEditor.apply(adjustments, to: linear, reference: refLinear)`.

`EditorView.swift`: add init param `referenceJPEG: Data?`; a "Blend" `optControl`-style row is OVERKILL — use the existing slider row pattern (read the file: sliders are plain labeled rows): add `Slider` row "Blend" (0...1, step 0.05) bound to `current.blendStrength`, shown `if referenceJPEG != nil`. Thread `referenceJPEG` into both render calls. Callers: `CaptureView.openEditor` + `PhotoDetailView.openEditor` load `lib.referenceData(for: id)` in their existing detached blocks and pass through their EditSource structs. `PhotoDetailView.rotate` ALSO renders — pass the reference there too (blend persists through rotation re-render).

- [ ] **Step 4:** full unit bundle green; engine suite green.

- [ ] **Step 5: Commit** — `git add StackStackStack Packages/StackEngineCore && git commit -m "feat(app): blend-strength editing — reference persisted at capture, threaded through renderer and editor"`

---

### Task 3: Docs + final suites

- [ ] Delta doc: TL;DR #3 → `3. **Blend strength — DONE (this PR, as a full-quality edit).** Deviation: no proxy stack — the working-res aligned reference is stored (\`<uuid>.ref.<ext>\`) and α is an ImageAdjustments field applied at full resolution (lerp needs the endpoints, not the reducer). Re-tuning other capture params stays out of scope (§9.3).`; add to "Deliberate deviations" list: `10. Blend strength α = full-quality lerp against a stored aligned reference, not the §9.2 proxy-stack draft preview.`; update §13.3/§14/§9 table rows mentioning α. Bible §9.3: append one line: `> **Status (2026-06-11):** superseded for α — the app stores the aligned reference and applies blend strength at full quality (see 2026-06-11-blend-strength-design.md); the proxy-stack mechanism was not built.`
- [ ] Run all three suites (engine, unit, StackFlowUITests) — green.
- [ ] Commit docs: `git add docs && git commit -m "docs: blend-strength deviation recorded in delta + bible §9.3 status note"`

---

### Task 4: /code-review + merge (controller-level)

High-effort code review of the branch; fix findings; PR; merge to main (authorized). Add device-verify items to the manual test plan memory (visual blend sweep on a real stack).

## Self-review notes
- Spec coverage: §3→T1, §4→T2, §5→T3, §6 rows→T1 (mismatch skip)/T2 (try? reference encode; nil-ref slider hiding), §7→T1+T2 tests.
- Type consistency: `save(result:reference:format:mode:frameCount:)` ordering fixed here and used in T2 tests; `apply(_:to:reference:)`; `reduceImagesWithReference`/`reduceStreamingWithReference` naming consistent.
