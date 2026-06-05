# Phase 1 — Global Editor (tonal) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a **non-destructive global editor** for the tonal adjustments from design §14 — **exposure (EV), contrast, white balance (temp/tint)** — applied to a captured result, with a live-preview editor screen and a preserved original.

**Architecture:** A pure-Swift `ImageAdjustments` value + `ImageEditor.apply` (in linear light) and an inverse-sRGB `decodeSRGB8` go in `StackEngineCore` (all TDD-testable). The app keeps the **immutable original** stacked JPEG plus a sidecar `edits.json`; editing decodes the original → linear → applies adjustments → re-encodes the displayed JPEG (the original and the recipe are preserved, so edits are non-destructive and re-editable). An `EditorView` sheet drives it with off-main live preview.

**Tech Stack:** Swift, SPM, XCTest, simd (engine); SwiftUI, ImageIO (app). Builds on Phases 0–1.

**Deferred to a follow-on editor increment (per §14):** crop, straighten, tone-curve, and the proxy blend-strength preview. This plan covers the tonal adjustments only.

---

## File structure (this plan)

```
Packages/StackEngineCore/
  Sources/StackEngineCore/
    ImageAdjustments.swift   # NEW — exposure/contrast/temperature/tint value
    ImageEditor.swift        # NEW — apply(_:to:) in linear light
    OutputTransform.swift    # MODIFY — add decodeSRGB8 (inverse of encodeSRGB8)
  Tests/StackEngineCoreTests/
    ImageEditorTests.swift   # NEW
    OutputTransformTests.swift # MODIFY — decode round-trip
StackStackStack/StackStackStack/
  ImageDecoder.swift         # NEW — JPEG Data → sRGB RGBA8 (ImageIO)
  ResultRenderer.swift       # NEW — original JPEG + adjustments → rendered JPEG
  Library/LibraryStore.swift # MODIFY — keep original, edits sidecar, applyEdit
  UI/EditorView.swift        # NEW — sliders + live preview + save
  UI/CaptureView.swift       # MODIFY — "Edit" affordance when a result is shown
StackStackStack/StackStackStackTests/
  LibraryStoreTests.swift    # MODIFY — original + adjustments round-trip
```

---

## Task 1: `ImageAdjustments`

**Files:**
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/ImageAdjustments.swift`

> Pure value — exercised by `ImageEditor` tests.

- [ ] **Step 1: Create the type**

```swift
/// Non-destructive global tonal adjustments applied to a developed result (design §14).
public struct ImageAdjustments: Sendable, Equatable, Codable {
    public var exposureEV: Float    // stops; linear is multiplied by 2^EV
    public var contrast: Float      // -1...1, around an 18% linear pivot
    public var temperature: Float   // -1...1, warm (+) / cool (-)
    public var tint: Float          // -1...1, magenta (+) / green (-)

    public init(exposureEV: Float = 0, contrast: Float = 0, temperature: Float = 0, tint: Float = 0) {
        self.exposureEV = exposureEV
        self.contrast = contrast
        self.temperature = temperature
        self.tint = tint
    }

    public static let identity = ImageAdjustments()
    public var isIdentity: Bool { self == .identity }
}
```

- [ ] **Step 2: Build**

Run: `cd Packages/StackEngineCore && swift build`
Expected: builds clean.

- [ ] **Step 3: Commit**

```bash
git add Packages/StackEngineCore/Sources/StackEngineCore/ImageAdjustments.swift
git commit -m "feat(core): ImageAdjustments value (exposure/contrast/wb)"
```

---

## Task 2: `decodeSRGB8` (inverse of the output transform)

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/OutputTransform.swift`
- Modify: `Packages/StackEngineCore/Tests/StackEngineCoreTests/OutputTransformTests.swift`

- [ ] **Step 1: Add the failing test**

Add to `OutputTransformTests.swift`:
```swift
    func testSRGBRoundTripWithinQuantization() {
        // linear → sRGB8 → linear should return ~the original (8-bit quantization tolerance).
        let img = PixelImage(width: 3, height: 1, pixels: [
            SIMD3<Float>(0.0, 0.25, 0.5),
            SIMD3<Float>(0.5, 0.75, 1.0),
            SIMD3<Float>(0.1, 0.2, 0.9),
        ])
        let back = OutputTransform.decodeSRGB8(OutputTransform.encodeSRGB8(img), width: 3, height: 1)
        XCTAssertEqual(back.pixels.count, 3)
        for i in 0..<3 {
            for ch in 0..<3 {
                XCTAssertEqual(back.pixels[i][ch], img.pixels[i][ch], accuracy: 0.01, "pixel \(i) ch \(ch)")
            }
        }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testSRGBRoundTripWithinQuantization`
Expected: FAIL — `type 'OutputTransform' has no member 'decodeSRGB8'`.

- [ ] **Step 3: Implement `decodeSRGB8`**

Add to `OutputTransform.swift` (inside `enum OutputTransform`):
```swift
    @inline(__always) private static func srgbToLinear(_ b: UInt8) -> Float {
        let c = Float(b) / 255
        if c <= 0.04045 { return c / 12.92 }
        return Float(Foundation.pow((Double(c) + 0.055) / 1.055, 2.4))
    }

    /// Decode interleaved sRGB RGBA8 bytes back into a linear image (inverse of `encodeSRGB8`).
    public static func decodeSRGB8(_ rgba8: [UInt8], width: Int, height: Int) -> PixelImage {
        precondition(rgba8.count == width * height * 4, "rgba8 length mismatch")
        var pixels = [SIMD3<Float>](repeating: .zero, count: width * height)
        for i in 0..<(width * height) {
            pixels[i] = SIMD3<Float>(srgbToLinear(rgba8[i*4]), srgbToLinear(rgba8[i*4+1]), srgbToLinear(rgba8[i*4+2]))
        }
        return PixelImage(width: width, height: height, pixels: pixels)
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter testSRGBRoundTripWithinQuantization`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): decodeSRGB8 (inverse output transform) for re-editing"
```

---

## Task 3: `ImageEditor.apply`

**Files:**
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/ImageEditor.swift`
- Create: `Packages/StackEngineCore/Tests/StackEngineCoreTests/ImageEditorTests.swift`

- [ ] **Step 1: Write the failing test**

Create `ImageEditorTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class ImageEditorTests: XCTestCase {
    private func solid(_ c: SIMD3<Float>) -> PixelImage {
        PixelImage(width: 1, height: 1, pixels: [c])
    }

    func testIdentityIsNoOp() {
        let img = solid(SIMD3<Float>(0.2, 0.3, 0.4))
        XCTAssertEqual(ImageEditor.apply(.identity, to: img).pixels[0], img.pixels[0])
    }

    func testExposureDoublesAtPlusOneEV() {
        let out = ImageEditor.apply(ImageAdjustments(exposureEV: 1), to: solid(SIMD3<Float>(0.2, 0.2, 0.2)))
        XCTAssertEqual(out.pixels[0].x, 0.4, accuracy: 1e-4)   // ×2
    }

    func testWhiteBalanceWarmsRedCoolsBlue() {
        let out = ImageEditor.apply(ImageAdjustments(temperature: 1), to: solid(SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertEqual(out.pixels[0].x, 0.5 * 1.3, accuracy: 1e-4)   // R ×1.3
        XCTAssertEqual(out.pixels[0].z, 0.5 * 0.7, accuracy: 1e-4)   // B ×0.7
    }

    func testContrastPushesAwayFromPivot() {
        // value above pivot (0.18) gets brighter with +contrast
        let out = ImageEditor.apply(ImageAdjustments(contrast: 0.5), to: solid(SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertEqual(out.pixels[0].x, (0.5 - 0.18) * 1.5 + 0.18, accuracy: 1e-4)
    }

    func testNegativesAreClampedToZero() {
        // a strong cool WB on a tiny blue can't go below 0
        let out = ImageEditor.apply(ImageAdjustments(exposureEV: -10), to: solid(SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertGreaterThanOrEqual(out.pixels[0].x, 0)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter ImageEditorTests`
Expected: FAIL — `cannot find 'ImageEditor' in scope`.

- [ ] **Step 3: Implement `ImageEditor`**

Create `ImageEditor.swift`:
```swift
import Foundation
import simd

public enum ImageEditor {
    private static let pivot: Float = 0.18   // 18% linear mid-grey

    /// Apply global tonal adjustments to a linear image (design §14).
    /// Order: exposure → white balance → contrast (around the 18% pivot). Output is clamped ≥ 0.
    public static func apply(_ adj: ImageAdjustments, to img: PixelImage) -> PixelImage {
        if adj.isIdentity { return img }
        let expGain = Float(exp2(Double(adj.exposureEV)))
        let contrastFactor = 1 + adj.contrast
        let wb = SIMD3<Float>(1 + adj.temperature * 0.3, 1 + adj.tint * 0.3, 1 - adj.temperature * 0.3)
        let pivotVec = SIMD3<Float>(repeating: pivot)
        let zero = SIMD3<Float>(repeating: 0)
        var out = img
        for i in 0..<out.pixels.count {
            var p = img.pixels[i] * expGain                       // exposure
            p = p * wb                                            // white balance
            p = (p - pivotVec) * contrastFactor + pivotVec        // contrast about the pivot
            out.pixels[i] = simd_max(p, zero)                     // no negative light
        }
        return out
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter ImageEditorTests`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full suite + commit**

Run: `cd Packages/StackEngineCore && swift test` → all green.
```bash
git add Packages/StackEngineCore
git commit -m "feat(core): ImageEditor.apply (exposure/wb/contrast in linear)"
```

---

## Task 4: App image decode helper + result renderer

**Files:**
- Create: `StackStackStack/StackStackStack/ImageDecoder.swift`
- Create: `StackStackStack/StackStackStack/ResultRenderer.swift`
- Test: `StackStackStackTests/ResultRendererTests.swift`

- [ ] **Step 1: Write the failing test**

Create `StackStackStackTests/ResultRendererTests.swift`:
```swift
import XCTest
import StackEngineCore
@testable import StackStackStack

final class ResultRendererTests: XCTestCase {
    func testRenderRoundTripsAndAppliesExposure() throws {
        // Build a small grey JPEG via the engine + encoder.
        let grey = PixelImage(width: 4, height: 4, fill: SIMD3<Float>(0.25, 0.25, 0.25))
        let rgba = OutputTransform.encodeSRGB8(grey)
        let jpeg = try ImageEncoder.encode(rgba8: rgba, width: 4, height: 4, format: .jpeg, quality: 1.0)

        // Identity render returns a valid JPEG of the same dimensions.
        let identity = try XCTUnwrap(ResultRenderer.render(originalJPEG: jpeg, adjustments: .identity))
        let (idRGBA, w, h) = try XCTUnwrap(ImageDecoder.rgba8(from: identity))
        XCTAssertEqual(w, 4); XCTAssertEqual(h, 4); XCTAssertEqual(idRGBA.count, 4 * 4 * 4)

        // +1 EV brightens the decoded result vs the identity render.
        let brighter = try XCTUnwrap(ResultRenderer.render(originalJPEG: jpeg, adjustments: ImageAdjustments(exposureEV: 1)))
        let (brRGBA, _, _) = try XCTUnwrap(ImageDecoder.rgba8(from: brighter))
        XCTAssertGreaterThan(Int(brRGBA[0]), Int(idRGBA[0]))   // pixel got brighter
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Build the test target (⌘U). Expected: FAIL — `cannot find 'ResultRenderer' / 'ImageDecoder' in scope`.

- [ ] **Step 3: Implement the helpers**

Create `StackStackStack/StackStackStack/ImageDecoder.swift`:
```swift
import Foundation
import ImageIO
import CoreGraphics

/// Decodes image Data into interleaved sRGB RGBA8 bytes + dimensions.
enum ImageDecoder {
    static func rgba8(from data: Data) -> (rgba: [UInt8], width: Int, height: Int)? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let cg = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return nil }
        let w = cg.width, h = cg.height
        guard w > 0, h > 0 else { return nil }
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        guard let cs = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: &bytes, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: w * 4, space: cs,
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        return (bytes, w, h)
    }
}
```

Create `StackStackStack/StackStackStack/ResultRenderer.swift`:
```swift
import Foundation
import StackEngineCore

/// Renders a developed result JPEG through non-destructive adjustments and re-encodes it.
enum ResultRenderer {
    static func render(originalJPEG: Data, adjustments: ImageAdjustments, quality: Double = 0.95) -> Data? {
        guard let (rgba, w, h) = ImageDecoder.rgba8(from: originalJPEG) else { return nil }
        let linear = OutputTransform.decodeSRGB8(rgba, width: w, height: h)
        let adjusted = ImageEditor.apply(adjustments, to: linear)
        let outRGBA = OutputTransform.encodeSRGB8(adjusted)
        return try? ImageEncoder.encode(rgba8: outRGBA, width: w, height: h, format: .jpeg, quality: quality)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

⌘U. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add StackStackStack/StackStackStack/ImageDecoder.swift StackStackStack/StackStackStack/ResultRenderer.swift StackStackStack/StackStackStackTests/ResultRendererTests.swift
git commit -m "feat(app): ImageDecoder + ResultRenderer (apply adjustments to a result JPEG)"
```

---

## Task 5: LibraryStore — preserve original + edits sidecar

**Files:**
- Modify: `StackStackStack/StackStackStack/Library/LibraryStore.swift`
- Modify: `StackStackStack/StackStackStackTests/LibraryStoreTests.swift`

Keeps the original immutable (`<id>.orig.jpg`), stores adjustments in `<id>.edits.json`, and lets the editor overwrite the displayed `<id>.jpg`. The index schema is untouched (adjustments live in a sidecar, so old records still decode).

- [ ] **Step 1: Add the failing test**

Add to `LibraryStoreTests.swift`:
```swift
    func testKeepsOriginalAndAppliesEdit() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let original = Data([0xFF, 0xD8, 0x01, 0xD9])
        let saved = try store.save(resultJPEG: original, mode: "noiseReduction", frameCount: 8)

        // The original is preserved and adjustments default to identity.
        XCTAssertEqual(store.originalData(for: saved.id), original)
        XCTAssertEqual(store.adjustments(for: saved.id), .identity)

        // Applying an edit overwrites the displayed JPEG, persists adjustments, keeps the original.
        let edited = Data([0xFF, 0xD8, 0x02, 0xD9])
        try store.applyEdit(id: saved.id, adjustments: ImageAdjustments(exposureEV: 1), renderedJPEG: edited)
        XCTAssertEqual(try Data(contentsOf: saved.resultURL), edited)        // displayed = edited
        XCTAssertEqual(store.originalData(for: saved.id), original)          // original untouched
        XCTAssertEqual(store.adjustments(for: saved.id).exposureEV, 1)       // recipe persisted
    }
```
*(Requires `import StackEngineCore` at the top of `LibraryStoreTests.swift`.)*

- [ ] **Step 2: Run to verify it fails**

⌘U. Expected: FAIL — `value of type 'LibraryStore' has no member 'originalData'`.

- [ ] **Step 3: Extend `LibraryStore`**

In `LibraryStore.swift`:
- Add `import StackEngineCore` at the top.
- In `save(resultJPEG:mode:frameCount:)`, after writing the result file, also write the immutable original. Replace the `try resultJPEG.write(to: url)` line with:
```swift
        try resultJPEG.write(to: url)
        try resultJPEG.write(to: originalURL(forFileName: fileName))   // immutable original for re-editing
```
- Add these methods to `LibraryStore`:
```swift
    private func originalURL(forFileName fileName: String) -> URL {
        root.appendingPathComponent((fileName as NSString).deletingPathExtension + ".orig.jpg")
    }
    private func editsURL(for id: UUID) -> URL {
        root.appendingPathComponent("\(id.uuidString).edits.json")
    }

    /// The immutable original stacked JPEG, used as the editing source.
    func originalData(for id: UUID) -> Data? {
        try? Data(contentsOf: originalURL(forFileName: "\(id.uuidString).jpg"))
    }

    /// The persisted adjustments for a record (identity if none).
    func adjustments(for id: UUID) -> ImageAdjustments {
        guard let data = try? Data(contentsOf: editsURL(for: id)),
              let adj = try? JSONDecoder().decode(ImageAdjustments.self, from: data) else { return .identity }
        return adj
    }

    /// Overwrite the displayed JPEG with a rendered result and persist the adjustments.
    func applyEdit(id: UUID, adjustments: ImageAdjustments, renderedJPEG: Data) throws {
        try renderedJPEG.write(to: root.appendingPathComponent("\(id.uuidString).jpg"))
        try JSONEncoder().encode(adjustments).write(to: editsURL(for: id))
    }
```

- [ ] **Step 4: Run to verify it passes**

⌘U. Expected: PASS (and the existing `testSaveAndLoadRoundTrip` still passes).

- [ ] **Step 5: Commit**

```bash
git add StackStackStack/StackStackStack/Library/LibraryStore.swift StackStackStack/StackStackStackTests/LibraryStoreTests.swift
git commit -m "feat(app): preserve original + persist edits sidecar for non-destructive editing"
```

---

## Task 6: EditorView + "Edit" entry from the result

**Files:**
- Create: `StackStackStack/StackStackStack/UI/EditorView.swift`
- Modify: `StackStackStack/StackStackStack/StackCaptureCoordinator.swift`
- Modify: `StackStackStack/StackStackStack/UI/CaptureView.swift`

> UI — verified by build + Simulator run.

- [ ] **Step 1: Expose the last-saved id + original on the coordinator**

The editor needs the saved record's id and original JPEG. In `StackCaptureCoordinator.swift`, the store is already held; add a published id and a passthrough to the store. After `@Published private(set) var lastResultJPEG: Data?` add:
```swift
    /// The id of the most recent saved stack (for the editor).
    @Published private(set) var lastSavedID: UUID?
    /// Read-only access to the library for the editor.
    var library: LibraryStore { store }
```
In `shoot(...)`, set it where the save happens — replace the `state = .done(saved.id)` line with:
```swift
            lastSavedID = saved.id
            state = .done(saved.id)
```

- [ ] **Step 2: Create the editor**

Create `StackStackStack/StackStackStack/UI/EditorView.swift`:
```swift
import SwiftUI
import UIKit
import StackEngineCore

struct EditorView: View {
    let originalJPEG: Data
    let recordId: UUID
    let store: LibraryStore
    var onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var adj: ImageAdjustments
    @State private var preview: UIImage?
    @State private var renderTask: Task<Void, Never>?

    init(originalJPEG: Data, recordId: UUID, store: LibraryStore, onSaved: @escaping () -> Void) {
        self.originalJPEG = originalJPEG
        self.recordId = recordId
        self.store = store
        self.onSaved = onSaved
        _adj = State(initialValue: store.adjustments(for: recordId))
    }

    var body: some View {
        NavigationStack {
            VStack {
                Group {
                    if let preview { Image(uiImage: preview).resizable().scaledToFit() }
                    else if let ui = UIImage(data: originalJPEG) { Image(uiImage: ui).resizable().scaledToFit() }
                }
                .padding()
                Spacer()
                slider("Exposure", value: $adj.exposureEV, range: -2...2)
                slider("Contrast", value: $adj.contrast, range: -1...1)
                slider("Warmth", value: $adj.temperature, range: -1...1)
                slider("Tint", value: $adj.tint, range: -1...1)
            }
            .navigationTitle("Edit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { save() } }
            }
            .onAppear { schedulePreview() }
            .onChange(of: adj) { _ in schedulePreview() }
        }
    }

    private func slider(_ label: String, value: Binding<Float>, range: ClosedRange<Float>) -> some View {
        HStack {
            Text(label).frame(width: 80, alignment: .leading)
            Slider(value: value, in: range)
        }.padding(.horizontal)
    }

    /// Render a preview off the main thread, replacing any in-flight render.
    private func schedulePreview() {
        renderTask?.cancel()
        let current = adj
        renderTask = Task {
            let data = await Task.detached(priority: .userInitiated) {
                ResultRenderer.render(originalJPEG: originalJPEG, adjustments: current, quality: 0.85)
            }.value
            if Task.isCancelled { return }
            if let data { preview = UIImage(data: data) }
        }
    }

    private func save() {
        let current = adj
        Task {
            let rendered = await Task.detached(priority: .userInitiated) {
                ResultRenderer.render(originalJPEG: originalJPEG, adjustments: current, quality: 0.95)
            }.value
            if let rendered { try? store.applyEdit(id: recordId, adjustments: current, renderedJPEG: rendered) }
            onSaved()
            dismiss()
        }
    }
}
```

- [ ] **Step 3: Add the "Edit" affordance in `CaptureView`**

In `CaptureView.swift`, add an `@State private var showEditor = false` property, and when a result is shown add an Edit button. Replace the result branch:
```swift
                if let img = lastResult {
                    Image(uiImage: img).resizable().scaledToFit().padding()
                } else {
```
with:
```swift
                if let img = lastResult {
                    VStack {
                        Image(uiImage: img).resizable().scaledToFit()
                        if coordinator.lastSavedID != nil {
                            Button("Edit") { showEditor = true }
                                .buttonStyle(.bordered).tint(.white)
                        }
                    }.padding()
                } else {
```
Add `@State private var showEditor = false` near `@State private var lastResult`. Then add a sheet to the `ZStack` (after the `.onReceive` modifiers):
```swift
        .sheet(isPresented: $showEditor) {
            if let id = coordinator.lastSavedID, let original = coordinator.library.originalData(for: id) {
                EditorView(originalJPEG: original, recordId: id, store: coordinator.library) {
                    // Reflect the edit in the on-screen result.
                    if let data = try? Data(contentsOf: coordinator.library.resultURL(forID: id)) {
                        lastResult = UIImage(data: data)
                    }
                }
            }
        }
```

- [ ] **Step 4: Add a `resultURL(forID:)` convenience to `LibraryStore`**

In `LibraryStore.swift` add:
```swift
    /// The displayed result file URL for an id.
    func resultURL(forID id: UUID) -> URL { root.appendingPathComponent("\(id.uuidString).jpg") }
```

- [ ] **Step 5: Build + Simulator run**

Run: `xcodebuild -project StackStackStack/StackStackStack.xcodeproj -scheme StackStackStack -destination 'generic/platform=iOS Simulator' build`
Expected: BUILD SUCCEEDED.
In the Simulator: shoot a stack, tap **Edit**, drag **Exposure/Contrast/Warmth/Tint** → the preview updates live; **Save** → the result reflects the edit and the centre image updates. Re-opening Edit restores the saved slider positions.

- [ ] **Step 6: Run app unit + UI tests**

Run: `xcodebuild test -project StackStackStack/StackStackStack.xcodeproj -scheme StackStackStack -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:StackStackStackTests -only-testing:StackStackStackUITests/StackFlowUITests`
Expected: TEST SUCCEEDED.

- [ ] **Step 7: Commit**

```bash
git add StackStackStack/StackStackStack
git commit -m "feat(app): non-destructive tonal editor (EditorView + Edit entry)"
```

---

## Self-review

**1. Spec coverage (§14 tonal subset):** exposure/contrast/white-balance → Tasks 1–3 (`ImageAdjustments`, `ImageEditor.apply`); non-destructive original + recipe → Task 5 (`originalData`/`adjustments`/`applyEdit`); re-rendered on save → Task 4 (`ResultRenderer`) + Task 6 (editor). **Deferred and noted:** crop, straighten, tone-curve, proxy blend-strength preview (a follow-on editor increment).

**2. Placeholder scan:** every code step is complete; no TBD/TODO.

**3. Type consistency:** `ImageAdjustments(exposureEV:contrast:temperature:tint:)` / `.identity` / `.isIdentity`; `ImageEditor.apply(_:to:)`; `OutputTransform.decodeSRGB8(_:width:height:)`/`encodeSRGB8`; `ImageDecoder.rgba8(from:)`; `ResultRenderer.render(originalJPEG:adjustments:quality:)`; `LibraryStore.originalData(for:)`/`adjustments(for:)`/`applyEdit(id:adjustments:renderedJPEG:)`/`resultURL(forID:)`; `StackCaptureCoordinator.lastSavedID`/`library` — all used identically across tasks.

---

## Definition of done

- `cd Packages/StackEngineCore && swift test` → all green (engine + new editor/decode tests).
- App unit + UI tests green; clean build.
- Simulator: shoot → Edit → live tonal adjustments → Save → result updates; original preserved (re-open restores sliders).
