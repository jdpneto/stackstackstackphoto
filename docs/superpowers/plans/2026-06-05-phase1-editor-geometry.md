# Phase 1 — Editor: Crop / Straighten / Tone-Curve Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the §14 editor by adding the remaining non-destructive adjustments — **crop** (aspect presets), **straighten** (rotation), and a **tone curve** (shadows / highlights) — on top of the shipped exposure / contrast / white-balance.

**Architecture:** Extend `ImageAdjustments` with the new fields (Codable made back-compatible so existing edit sidecars still decode). `ImageEditor.apply` runs geometry first (straighten → crop) then tonal (exposure/WB/contrast → shadows/highlights). `EditorView` gains the controls; the existing non-destructive recipe + off-main preview pipeline already carries them through.

**Tech Stack:** Swift, simd (engine); SwiftUI (app). Builds on the tonal editor.

---

## File structure (this plan)

```
Packages/StackEngineCore/Sources/StackEngineCore/
  ImageAdjustments.swift   # MODIFY — shadows/highlights/straightenDegrees/cropAspect + CropAspect + back-compat Codable
  ImageEditor.swift        # MODIFY — straighten(), crop(), shadows/highlights in the tonal pass
Packages/StackEngineCore/Tests/StackEngineCoreTests/
  ImageEditorTests.swift   # MODIFY — crop/straighten/tone-curve + Codable back-compat
StackStackStack/StackStackStack/UI/
  EditorView.swift         # MODIFY — crop picker, straighten slider, shadows/highlights sliders
```

---

## Task 1: Extend `ImageAdjustments` (+ `CropAspect`, back-compat Codable)

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/ImageAdjustments.swift`
- Modify: `Packages/StackEngineCore/Tests/StackEngineCoreTests/ImageEditorTests.swift`

- [ ] **Step 1: Add the failing back-compat test**

Add to `ImageEditorTests.swift`:
```swift
    func testDecodesOldAdjustmentsWithoutNewKeys() throws {
        // An edits.json written before this change has only the four tonal keys.
        let oldJSON = #"{"exposureEV":1,"contrast":0,"temperature":0,"tint":0}"#.data(using: .utf8)!
        let adj = try JSONDecoder().decode(ImageAdjustments.self, from: oldJSON)
        XCTAssertEqual(adj.exposureEV, 1, accuracy: 1e-6)
        XCTAssertEqual(adj.shadows, 0)            // defaulted
        XCTAssertEqual(adj.highlights, 0)         // defaulted
        XCTAssertEqual(adj.straightenDegrees, 0)  // defaulted
        XCTAssertEqual(adj.cropAspect, .original) // defaulted
        XCTAssertTrue(ImageAdjustments(exposureEV: 1).isIdentity == false)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testDecodesOldAdjustmentsWithoutNewKeys`
Expected: FAIL — `value of type 'ImageAdjustments' has no member 'shadows'`.

- [ ] **Step 3: Replace `ImageAdjustments.swift`**

```swift
/// Center-crop aspect presets for the editor.
public enum CropAspect: String, Sendable, Equatable, Codable, CaseIterable {
    case original, square, fourThree, sixteenNine
    /// width:height ratio, or nil for the original (no crop).
    public var ratio: Float? {
        switch self {
        case .original:    return nil
        case .square:      return 1
        case .fourThree:   return 4.0 / 3.0
        case .sixteenNine: return 16.0 / 9.0
        }
    }
}

/// Non-destructive global adjustments applied to a developed result (design §14).
public struct ImageAdjustments: Sendable, Equatable, Codable {
    public var exposureEV: Float       // stops; linear ×2^EV
    public var contrast: Float         // -1...1 around an 18% linear pivot
    public var temperature: Float      // -1...1, warm (+) / cool (-)
    public var tint: Float             // -1...1, magenta (+) / green (-)
    public var shadows: Float          // -1...1, lift (+) / lower (-) dark tones
    public var highlights: Float       // -1...1, lift (+) / lower (-) bright tones
    public var straightenDegrees: Float // rotation about the centre, degrees
    public var cropAspect: CropAspect   // centre-crop aspect

    public init(exposureEV: Float = 0, contrast: Float = 0, temperature: Float = 0, tint: Float = 0,
                shadows: Float = 0, highlights: Float = 0, straightenDegrees: Float = 0,
                cropAspect: CropAspect = .original) {
        self.exposureEV = exposureEV; self.contrast = contrast
        self.temperature = temperature; self.tint = tint
        self.shadows = shadows; self.highlights = highlights
        self.straightenDegrees = straightenDegrees; self.cropAspect = cropAspect
    }

    public static let identity = ImageAdjustments()
    public var isIdentity: Bool { self == .identity }

    // Back-compat: edit sidecars written before the new fields lack those keys — default them.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        exposureEV = try c.decodeIfPresent(Float.self, forKey: .exposureEV) ?? 0
        contrast = try c.decodeIfPresent(Float.self, forKey: .contrast) ?? 0
        temperature = try c.decodeIfPresent(Float.self, forKey: .temperature) ?? 0
        tint = try c.decodeIfPresent(Float.self, forKey: .tint) ?? 0
        shadows = try c.decodeIfPresent(Float.self, forKey: .shadows) ?? 0
        highlights = try c.decodeIfPresent(Float.self, forKey: .highlights) ?? 0
        straightenDegrees = try c.decodeIfPresent(Float.self, forKey: .straightenDegrees) ?? 0
        cropAspect = try c.decodeIfPresent(CropAspect.self, forKey: .cropAspect) ?? .original
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter testDecodesOldAdjustmentsWithoutNewKeys`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): ImageAdjustments gains shadows/highlights/straighten/crop (back-compat Codable)"
```

---

## Task 2: `ImageEditor` — straighten, crop, tone curve

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/ImageEditor.swift`
- Modify: `Packages/StackEngineCore/Tests/StackEngineCoreTests/ImageEditorTests.swift`

- [ ] **Step 1: Add the failing tests**

Add to `ImageEditorTests.swift`:
```swift
    func testCropSquareCentersToSmallerSide() {
        let out = ImageEditor.apply(ImageAdjustments(cropAspect: .square),
                                    to: PixelImage(width: 16, height: 8, fill: SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertEqual(out.width, 8)
        XCTAssertEqual(out.height, 8)
    }

    func testStraighten180FlipsRow() {
        let img = PixelImage(width: 4, height: 1, pixels: [
            SIMD3<Float>(1, 1, 1), SIMD3<Float>(0, 0, 0), SIMD3<Float>(0, 0, 0), SIMD3<Float>(0, 0, 0)])
        let r = ImageEditor.straighten(img, degrees: 180)
        XCTAssertEqual(r[3, 0].x, 1, accuracy: 1e-4)   // bright pixel rotated to the far end
        XCTAssertEqual(r[0, 0].x, 0, accuracy: 1e-4)
    }

    func testShadowsLiftBlack() {
        let out = ImageEditor.apply(ImageAdjustments(shadows: 1), to: solid(SIMD3<Float>(0, 0, 0)))
        XCTAssertEqual(out.pixels[0].x, 0.5, accuracy: 1e-4)   // 0 + 1·0.5·(1-0)² = 0.5
    }

    func testHighlightsPullWhite() {
        let out = ImageEditor.apply(ImageAdjustments(highlights: -1), to: solid(SIMD3<Float>(1, 1, 1)))
        XCTAssertEqual(out.pixels[0].x, 0.5, accuracy: 1e-4)   // 1 + (-1)·0.5·1² = 0.5
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter ImageEditorTests`
Expected: FAIL (e.g. `type 'ImageEditor' has no member 'straighten'`).

- [ ] **Step 3: Replace `ImageEditor.swift`**

```swift
import Foundation
import simd

public enum ImageEditor {
    private static let pivot: Float = 0.18   // 18% linear mid-grey

    /// Apply all adjustments: geometry (straighten → crop) then tonal (exposure → WB → contrast →
    /// shadows/highlights), clamped ≥ 0.
    public static func apply(_ adj: ImageAdjustments, to img: PixelImage) -> PixelImage {
        if adj.isIdentity { return img }
        var out = img
        if adj.straightenDegrees != 0 { out = straighten(out, degrees: adj.straightenDegrees) }
        if adj.cropAspect.ratio != nil { out = crop(out, aspect: adj.cropAspect) }
        return tonal(adj, out)
    }

    /// Per-pixel tonal adjustments in linear light.
    static func tonal(_ adj: ImageAdjustments, _ img: PixelImage) -> PixelImage {
        let expGain = Float(exp2(Double(adj.exposureEV)))
        let contrastFactor = 1 + max(-0.9, min(1, adj.contrast))
        let wb = SIMD3<Float>(1 + adj.temperature * 0.3, 1 - adj.tint * 0.3, 1 - adj.temperature * 0.3)
        let pivotVec = SIMD3<Float>(repeating: pivot)
        let one = SIMD3<Float>(repeating: 1), zero = SIMD3<Float>(repeating: 0)
        var out = img
        for i in 0..<out.pixels.count {
            var p = img.pixels[i] * expGain
            p = p * wb
            p = (p - pivotVec) * contrastFactor + pivotVec
            // Tone curve: shadows weighted to the dark end ((1-tone)²), highlights to the bright end (tone²).
            let tone = simd_clamp(p, zero, one)
            p = p + adj.shadows * 0.5 * (one - tone) * (one - tone)
            p = p + adj.highlights * 0.5 * tone * tone
            out.pixels[i] = simd_max(p, zero)
        }
        return out
    }

    /// Rotate about the centre by `degrees`, edge-clamped bilinear sampling (keeps dimensions).
    static func straighten(_ img: PixelImage, degrees: Float) -> PixelImage {
        let rad = degrees * .pi / 180
        let cosA = cos(rad), sinA = sin(rad)
        let w = img.width, h = img.height
        let cx = Float(w - 1) / 2, cy = Float(h - 1) / 2
        var out = PixelImage(width: w, height: h)
        for y in 0..<h { for x in 0..<w {
            let dx = Float(x) - cx, dy = Float(y) - cy
            out[x, y] = bilinear(img, cx + dx * cosA + dy * sinA, cy - dx * sinA + dy * cosA)
        }}
        return out
    }

    /// Centre-crop to the aspect's ratio (largest fit).
    static func crop(_ img: PixelImage, aspect: CropAspect) -> PixelImage {
        guard let ratio = aspect.ratio else { return img }
        let w = img.width, h = img.height
        var cw = w, ch = h
        if Float(w) / Float(h) > ratio { cw = max(1, Int(Float(h) * ratio)) }
        else { ch = max(1, Int(Float(w) / ratio)) }
        let x0 = (w - cw) / 2, y0 = (h - ch) / 2
        var out = PixelImage(width: cw, height: ch)
        for y in 0..<ch { for x in 0..<cw { out[x, y] = img[x0 + x, y0 + y] } }
        return out
    }

    private static func bilinear(_ img: PixelImage, _ fx: Float, _ fy: Float) -> SIMD3<Float> {
        let w = img.width, h = img.height
        let x0 = Int(floor(fx)), y0 = Int(floor(fy))
        let tx = fx - Float(x0), ty = fy - Float(y0)
        @inline(__always) func at(_ x: Int, _ y: Int) -> SIMD3<Float> {
            img.pixels[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        let top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        let bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (bot - top) * ty
    }
}
```

- [ ] **Step 4: Run the editor tests + full suite**

Run: `cd Packages/StackEngineCore && swift test --filter ImageEditorTests` → PASS.
Run: `cd Packages/StackEngineCore && swift test` → ALL green (the existing tonal tests — identity, exposure, WB, contrast, clamp — must still pass; `tonal` keeps the same math).

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): ImageEditor straighten + crop + shadows/highlights tone curve"
```

---

## Task 3: Editor controls (crop / straighten / tone)

**Files:**
- Modify: `StackStackStack/StackStackStack/UI/EditorView.swift`

> UI — verified by build + Simulator run (the existing editor UI test still opens the sheet).

- [ ] **Step 1: Add the new controls**

In `EditorView.swift`, add a crop label helper at the bottom of the file:
```swift
extension CropAspect {
    var shortLabel: String {
        switch self {
        case .original: return "Original"
        case .square: return "Square"
        case .fourThree: return "4:3"
        case .sixteenNine: return "16:9"
        }
    }
}
```
Then in the `VStack` of sliders (after the existing Exposure/Contrast/Warmth/Tint sliders), add:
```swift
                slider("Shadows", value: $adj.shadows, range: -1...1)
                slider("Highlights", value: $adj.highlights, range: -1...1)
                slider("Straighten", value: $adj.straightenDegrees, range: -15...15)
                Picker("Crop", selection: $adj.cropAspect) {
                    ForEach(CropAspect.allCases, id: \.self) { Text($0.shortLabel).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .onChange(of: adj.cropAspect) { _ in schedulePreview() }
```
*(The sliders already call `schedulePreview()` on release via `onEditingChanged`; the crop Picker has no editing-changed callback, so it triggers `schedulePreview()` via `onChange`.)*

- [ ] **Step 2: Build + Simulator run**

Run: `xcodebuild -project StackStackStack/StackStackStack.xcodeproj -scheme StackStackStack -destination 'generic/platform=iOS Simulator' build`
Expected: BUILD SUCCEEDED.
In the Simulator: shoot → **Edit** → drag **Shadows/Highlights/Straighten** (preview updates), tap a **Crop** preset (preview reframes), **Save** → result reflects the geometry + tone. Re-open Edit → the controls restore.

- [ ] **Step 3: Run app unit + UI tests**

Run: `xcodebuild test -project StackStackStack/StackStackStack.xcodeproj -scheme StackStackStack -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:StackStackStackTests -only-testing:StackStackStackUITests/StackFlowUITests`
Expected: TEST SUCCEEDED (editor opens; existing flows unaffected).

- [ ] **Step 4: Commit**

```bash
git add StackStackStack/StackStackStack/UI/EditorView.swift
git commit -m "feat(app): editor crop / straighten / shadows / highlights controls"
```

---

## Self-review

**1. Spec coverage (§14 editor remainder):** crop → `CropAspect` + `ImageEditor.crop` + picker; straighten → `straightenDegrees` + `ImageEditor.straighten` + slider; tone curve → `shadows`/`highlights` + `ImageEditor.tonal` + sliders. With the tonal subset already shipped, the §14 editor is now complete (a full draggable curve / freeform crop rect are richer future polish, not required by §14's list).

**2. Placeholder scan:** every step has complete code; no TBD/TODO.

**3. Type consistency:** `ImageAdjustments(... shadows:highlights:straightenDegrees:cropAspect:)` / `.identity` / `.isIdentity` (now includes the new fields); `CropAspect` (`.original/.square/.fourThree/.sixteenNine`, `.ratio`, `.shortLabel`, `CaseIterable`); `ImageEditor.apply`/`tonal`/`straighten`/`crop`. Back-compat `init(from:)` keeps old sidecars decodable.

---

## Definition of done

- `cd Packages/StackEngineCore && swift test` → all green (existing tonal + new crop/straighten/tone-curve + back-compat).
- App unit + UI tests green; clean build.
- Simulator: crop presets reframe, straighten rotates, shadows/highlights shape tones; all non-destructive and restored on re-open.
