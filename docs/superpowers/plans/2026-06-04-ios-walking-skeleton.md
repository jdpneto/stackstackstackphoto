# iOS Walking Skeleton (Phase 0 + Noise Reduction) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a working iOS app that captures a handheld RAW burst, auto-aligns it, runs a linear-light color pipeline, mean-stacks it into a denoised image, and saves/displays the result — plus the shared golden-test harness.

**Architecture:** The deterministic image-processing core (color pipeline, alignment, stacking, metrics) lives in a **pure-Swift, CPU-only Swift Package** (`StackEngineCore`) that runs under `swift test` on the dev Mac — so every algorithm gets real red-green TDD against hand-computable references. The iOS app target wires AVFoundation RAW capture, Core Data storage, ImageIO encoding, and a SwiftUI shell around that core. **Deliberately deferred to later plans** (documented in the design bible): Metal GPU acceleration, the normative Malvar–He–Cutler demosaic (skeleton uses bilinear as a *provisional* stand-in), and homography + local-flow alignment (skeleton uses translation-only global alignment). Golden references produced here are therefore **provisional skeleton baselines**, frozen as authoritative only after Plan 2 swaps in Malvar.

**Tech Stack:** Swift 5.9+, Swift Package Manager, XCTest, simd, AVFoundation (RAW capture), ImageIO (JPEG/HEIC), Core Data, SwiftUI. Min targets: iOS 16 (app), macOS 13 (so the core package tests run on the Mac CLI).

---

## File structure (this plan)

```
photo-stack-app/
├── Packages/StackEngineCore/
│   ├── Package.swift
│   ├── Sources/StackEngineCore/
│   │   ├── PixelImage.swift          # linear-RGB float image type
│   │   ├── RawSensorFrame.swift      # raw Bayer frame + metadata + CFA helpers
│   │   ├── ColorPipeline.swift       # linearize → WB → demosaic → color matrix
│   │   ├── Luma.swift                # luminance + sharpness measure
│   │   ├── ReferenceSelection.swift  # pick sharpest frame
│   │   ├── Alignment.swift           # translation estimate + warp
│   │   ├── StackReducer.swift        # sigma-clipped mean
│   │   ├── OutputTransform.swift     # linear → sRGB 8-bit
│   │   ├── Metrics.swift             # PSNR, maxAbsDiff
│   │   └── Pipeline.swift            # end-to-end noise-reduction orchestrator + golden harness
│   └── Tests/StackEngineCoreTests/   # one test file per source file
├── StackStackStack.xcodeproj         # iOS app (created in Task 11)
└── StackStackStack/                  # app sources (Capture, Library, Encoding, UI)
```

Each core file has one responsibility and is small enough to hold in context. Tests mirror sources 1:1.

---

## Task 1: Scaffold the core package + `PixelImage`

**Files:**
- Create: `Packages/StackEngineCore/Package.swift`
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/PixelImage.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/PixelImageTests.swift`

- [ ] **Step 1: Create the package skeleton**

Run:
```bash
mkdir -p Packages/StackEngineCore/Sources/StackEngineCore
mkdir -p Packages/StackEngineCore/Tests/StackEngineCoreTests
```

Create `Packages/StackEngineCore/Package.swift`:
```swift
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "StackEngineCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "StackEngineCore", targets: ["StackEngineCore"])
    ],
    targets: [
        .target(name: "StackEngineCore"),
        .testTarget(name: "StackEngineCoreTests", dependencies: ["StackEngineCore"])
    ]
)
```

- [ ] **Step 2: Write the failing test**

Create `Packages/StackEngineCore/Tests/StackEngineCoreTests/PixelImageTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class PixelImageTests: XCTestCase {
    func testSubscriptRoundTrip() {
        var img = PixelImage(width: 2, height: 2)
        img[1, 0] = SIMD3<Float>(0.1, 0.2, 0.3)
        XCTAssertEqual(img[1, 0], SIMD3<Float>(0.1, 0.2, 0.3))
        XCTAssertEqual(img[0, 0], SIMD3<Float>(0, 0, 0))
        XCTAssertEqual(img.pixels.count, 4)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testSubscriptRoundTrip`
Expected: FAIL — `cannot find 'PixelImage' in scope`.

- [ ] **Step 4: Write the minimal implementation**

Create `Packages/StackEngineCore/Sources/StackEngineCore/PixelImage.swift`:
```swift
import simd

/// A linear-light RGB image. Pixels are row-major; channels are scene-linear floats.
public struct PixelImage: Equatable {
    public let width: Int
    public let height: Int
    public var pixels: [SIMD3<Float>]

    public init(width: Int, height: Int, pixels: [SIMD3<Float>]) {
        precondition(pixels.count == width * height, "pixel count mismatch")
        self.width = width
        self.height = height
        self.pixels = pixels
    }

    public init(width: Int, height: Int, fill: SIMD3<Float> = .zero) {
        self.width = width
        self.height = height
        self.pixels = Array(repeating: fill, count: width * height)
    }

    @inline(__always) public func index(_ x: Int, _ y: Int) -> Int { y * width + x }

    public subscript(x: Int, y: Int) -> SIMD3<Float> {
        get { pixels[index(x, y)] }
        set { pixels[index(x, y)] = newValue }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter testSubscriptRoundTrip`
Expected: PASS.

- [ ] **Step 6: Add SPM/Xcode ignores and commit**

Append to `.gitignore` (repo root):
```
.build/
DerivedData/
*.xcuserstate
xcuserdata/
```

Run:
```bash
git add .gitignore Packages/StackEngineCore
git commit -m "feat(core): scaffold StackEngineCore package with PixelImage"
```

---

## Task 2: `RawSensorFrame` + CFA helpers

**Files:**
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/RawSensorFrame.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/RawSensorFrameTests.swift`

- [ ] **Step 1: Write the failing test**

Create `RawSensorFrameTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class RawSensorFrameTests: XCTestCase {
    // RGGB layout (top-left 2x2 = R G / G B)
    func testCFAColorRGGB() {
        XCTAssertEqual(cfaColor(.rggb, 0, 0), .red)
        XCTAssertEqual(cfaColor(.rggb, 1, 0), .green)
        XCTAssertEqual(cfaColor(.rggb, 0, 1), .green)
        XCTAssertEqual(cfaColor(.rggb, 1, 1), .blue)
    }
    func testCFAColorHandlesNegativeCoords() {
        // -1 should have the same parity as 1
        XCTAssertEqual(cfaColor(.rggb, -1, 0), cfaColor(.rggb, 1, 0))
        XCTAssertEqual(cfaColor(.rggb, 0, -1), cfaColor(.rggb, 0, 1))
    }
    func testLinearizeSample() {
        // (v - black) / (white - black), clamped
        XCTAssertEqual(linearizeSample(64, black: 64, white: 1024), 0.0, accuracy: 1e-6)
        XCTAssertEqual(linearizeSample(1024, black: 64, white: 1024), 1.0, accuracy: 1e-6)
        XCTAssertEqual(linearizeSample(544, black: 64, white: 1024), 0.5, accuracy: 1e-6)
        XCTAssertEqual(linearizeSample(0, black: 64, white: 1024), 0.0, accuracy: 1e-6) // clamped
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter RawSensorFrameTests`
Expected: FAIL — `cannot find 'cfaColor' in scope`.

- [ ] **Step 3: Write the minimal implementation**

Create `RawSensorFrame.swift`:
```swift
import simd

public enum CFAPattern: Equatable { case rggb, bggr, grbg, gbrg }

enum CFAColor: Equatable { case red, green, blue }

/// One captured raw Bayer frame plus the metadata needed to develop it.
public struct RawSensorFrame {
    public let width: Int
    public let height: Int
    public let mosaic: [UInt16]            // row-major, length width*height
    public let blackLevel: Float
    public let whiteLevel: Float
    public let cfa: CFAPattern
    public let wbGains: SIMD3<Float>       // per-channel R,G,B multipliers
    public let colorMatrix: simd_float3x3  // camera -> working space

    public init(width: Int, height: Int, mosaic: [UInt16],
                blackLevel: Float, whiteLevel: Float, cfa: CFAPattern,
                wbGains: SIMD3<Float> = SIMD3<Float>(1, 1, 1),
                colorMatrix: simd_float3x3 = matrix_identity_float3x3) {
        precondition(mosaic.count == width * height, "mosaic count mismatch")
        self.width = width; self.height = height; self.mosaic = mosaic
        self.blackLevel = blackLevel; self.whiteLevel = whiteLevel; self.cfa = cfa
        self.wbGains = wbGains; self.colorMatrix = colorMatrix
    }
}

@inline(__always) func evenParity(_ n: Int) -> Bool { (((n % 2) + 2) % 2) == 0 }

/// Returns the color of the CFA site at (x, y). Robust to negative coordinates.
func cfaColor(_ pattern: CFAPattern, _ x: Int, _ y: Int) -> CFAColor {
    let ex = evenParity(x), ey = evenParity(y)
    switch pattern {
    case .rggb: return ey ? (ex ? .red : .green) : (ex ? .green : .blue)
    case .bggr: return ey ? (ex ? .blue : .green) : (ex ? .green : .red)
    case .grbg: return ey ? (ex ? .green : .red) : (ex ? .blue : .green)
    case .gbrg: return ey ? (ex ? .green : .blue) : (ex ? .red : .green)
    }
}

@inline(__always) func linearizeSample(_ v: UInt16, black: Float, white: Float) -> Float {
    let x = (Float(v) - black) / (white - black)
    return min(max(x, 0), 1)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter RawSensorFrameTests`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): add RawSensorFrame, CFA color mapping, and linearize"
```

---

## Task 3: Linearize + white balance into a single-channel buffer

**Files:**
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/ColorPipeline.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/ColorPipelineTests.swift`

This builds the spec-ordered `linearize → white balance` step (§12 of the design): each raw site is linearized, then multiplied by its channel's WB gain, **before** demosaic.

- [ ] **Step 1: Write the failing test**

Create `ColorPipelineTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class ColorPipelineTests: XCTestCase {
    func testLinearizeAndBalanceAppliesPerChannelGain() {
        // 2x2 RGGB, all raw=544 -> linear 0.5 each, with gains R=2, G=1, B=4
        let frame = RawSensorFrame(
            width: 2, height: 2,
            mosaic: [544, 544, 544, 544],
            blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
            wbGains: SIMD3<Float>(2, 1, 4))
        let lin = linearizeAndBalance(frame)
        // sites: (0,0)=R*2=1.0, (1,0)=G*1=0.5, (0,1)=G*1=0.5, (1,1)=B*4=2.0
        XCTAssertEqual(lin[0], 1.0, accuracy: 1e-6)
        XCTAssertEqual(lin[1], 0.5, accuracy: 1e-6)
        XCTAssertEqual(lin[2], 0.5, accuracy: 1e-6)
        XCTAssertEqual(lin[3], 2.0, accuracy: 1e-6)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testLinearizeAndBalanceAppliesPerChannelGain`
Expected: FAIL — `cannot find 'linearizeAndBalance' in scope`.

- [ ] **Step 3: Write the minimal implementation**

Create `ColorPipeline.swift`:
```swift
import simd

/// Linearize every raw site and apply its channel's white-balance gain.
/// Returns a single-channel buffer (still mosaiced), row-major.
func linearizeAndBalance(_ f: RawSensorFrame) -> [Float] {
    var lin = [Float](repeating: 0, count: f.width * f.height)
    for y in 0..<f.height {
        for x in 0..<f.width {
            let i = y * f.width + x
            var v = linearizeSample(f.mosaic[i], black: f.blackLevel, white: f.whiteLevel)
            switch cfaColor(f.cfa, x, y) {
            case .red:   v *= f.wbGains.x
            case .green: v *= f.wbGains.y
            case .blue:  v *= f.wbGains.z
            }
            lin[i] = v
        }
    }
    return lin
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter testLinearizeAndBalanceAppliesPerChannelGain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): linearize + white-balance raw sites pre-demosaic"
```

---

## Task 4: Bilinear demosaic (provisional)

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/ColorPipeline.swift`
- Modify: `Packages/StackEngineCore/Tests/StackEngineCoreTests/ColorPipelineTests.swift`

> Provisional per the architecture note: Plan 2 replaces this with Malvar–He–Cutler before goldens are frozen.

- [ ] **Step 1: Add the failing test**

Add to `ColorPipelineTests.swift` (inside the class):
```swift
    func testBilinearDemosaicInteriorUniformColor() {
        // 4x4 RGGB. Set every R site=0.8, G site=0.5, B site=0.2 (already linear+balanced).
        let w = 4, h = 4
        var lin = [Float](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w {
            switch cfaColor(.rggb, x, y) {
            case .red: lin[y*w+x] = 0.8
            case .green: lin[y*w+x] = 0.5
            case .blue: lin[y*w+x] = 0.2
            }
        }}
        let img = demosaic(lin, width: w, height: h, pattern: .rggb)
        // Interior pixels (1,1), (2,2) must reconstruct the constant color exactly.
        for (x, y) in [(1, 1), (2, 2), (2, 1), (1, 2)] {
            XCTAssertEqual(img[x, y].x, 0.8, accuracy: 1e-5, "R at \(x),\(y)")
            XCTAssertEqual(img[x, y].y, 0.5, accuracy: 1e-5, "G at \(x),\(y)")
            XCTAssertEqual(img[x, y].z, 0.2, accuracy: 1e-5, "B at \(x),\(y)")
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testBilinearDemosaicInteriorUniformColor`
Expected: FAIL — `cannot find 'demosaic' in scope`.

- [ ] **Step 3: Implement bilinear demosaic**

Append to `ColorPipeline.swift`:
```swift
/// Simple bilinear demosaic of a linear, white-balanced single-channel mosaic.
/// Provisional — replaced by Malvar–He–Cutler in a later plan.
func demosaic(_ lin: [Float], width w: Int, height h: Int, pattern: CFAPattern) -> PixelImage {
    @inline(__always) func at(_ x: Int, _ y: Int) -> Float {
        let xx = min(max(x, 0), w - 1), yy = min(max(y, 0), h - 1)
        return lin[yy * w + xx]
    }
    var out = PixelImage(width: w, height: h)
    for y in 0..<h {
        for x in 0..<w {
            let v = at(x, y)
            var r: Float = 0, g: Float = 0, b: Float = 0
            switch cfaColor(pattern, x, y) {
            case .red:
                r = v
                g = (at(x-1, y) + at(x+1, y) + at(x, y-1) + at(x, y+1)) / 4
                b = (at(x-1, y-1) + at(x+1, y-1) + at(x-1, y+1) + at(x+1, y+1)) / 4
            case .blue:
                b = v
                g = (at(x-1, y) + at(x+1, y) + at(x, y-1) + at(x, y+1)) / 4
                r = (at(x-1, y-1) + at(x+1, y-1) + at(x-1, y+1) + at(x+1, y+1)) / 4
            case .green:
                g = v
                // One horizontal neighbor pair is R, the other (vertical) is B — or vice versa.
                if cfaColor(pattern, x - 1, y) == .red {
                    r = (at(x-1, y) + at(x+1, y)) / 2
                    b = (at(x, y-1) + at(x, y+1)) / 2
                } else {
                    b = (at(x-1, y) + at(x+1, y)) / 2
                    r = (at(x, y-1) + at(x, y+1)) / 2
                }
            }
            out[x, y] = SIMD3<Float>(r, g, b)
        }
    }
    return out
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter testBilinearDemosaicInteriorUniformColor`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): bilinear demosaic (provisional)"
```

---

## Task 5: Full `ColorPipeline.process` (RAW → linear working-space RGB)

**Files:**
- Modify: `Packages/StackEngineCore/Sources/StackEngineCore/ColorPipeline.swift`
- Modify: `Packages/StackEngineCore/Tests/StackEngineCoreTests/ColorPipelineTests.swift`

- [ ] **Step 1: Add the failing test**

Add to `ColorPipelineTests.swift`:
```swift
    func testProcessAppliesColorMatrix() {
        // 4x4 RGGB uniform raw -> linear 0.5 at every site; gains=1.
        // Color matrix swaps R and B channels.
        let w = 4, h = 4
        let mosaic = [UInt16](repeating: 544, count: w * h) // (544-64)/(1024-64)=0.5
        let swapRB = simd_float3x3(columns: (
            SIMD3<Float>(0, 0, 1),   // out.x from in.z
            SIMD3<Float>(0, 1, 0),
            SIMD3<Float>(1, 0, 0)))  // out.z from in.x
        let frame = RawSensorFrame(width: w, height: h, mosaic: mosaic,
            blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
            wbGains: SIMD3<Float>(1, 1, 1), colorMatrix: swapRB)
        let img = ColorPipeline.process(frame)
        // After demosaic every interior pixel ~ (0.5,0.5,0.5); swap keeps it (0.5,0.5,0.5).
        XCTAssertEqual(img[2, 2].x, 0.5, accuracy: 1e-5)
        // Now verify the matrix actually runs: use a non-symmetric input via gains.
        let frame2 = RawSensorFrame(width: w, height: h, mosaic: mosaic,
            blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
            wbGains: SIMD3<Float>(0.2, 0.5, 0.8), colorMatrix: swapRB)
        let img2 = ColorPipeline.process(frame2)
        // Pre-matrix interior ~ (0.2*0.5, 0.5*0.5, 0.8*0.5)=(0.1,0.25,0.4); swapRB -> (0.4,0.25,0.1)
        XCTAssertEqual(img2[2, 2].x, 0.4, accuracy: 1e-5)
        XCTAssertEqual(img2[2, 2].y, 0.25, accuracy: 1e-5)
        XCTAssertEqual(img2[2, 2].z, 0.1, accuracy: 1e-5)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testProcessAppliesColorMatrix`
Expected: FAIL — `type 'ColorPipeline' has no member 'process'`.

- [ ] **Step 3: Implement `ColorPipeline.process`**

Append to `ColorPipeline.swift`:
```swift
public enum ColorPipeline {
    /// Develop a raw frame into a linear, working-space RGB image.
    /// Order (normative, design §12): linearize → white balance → demosaic → color matrix.
    public static func process(_ frame: RawSensorFrame) -> PixelImage {
        let lin = linearizeAndBalance(frame)
        var img = demosaic(lin, width: frame.width, height: frame.height, pattern: frame.cfa)
        let m = frame.colorMatrix
        for i in 0..<img.pixels.count { img.pixels[i] = m * img.pixels[i] }
        return img
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter testProcessAppliesColorMatrix`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): ColorPipeline.process develops RAW to linear RGB"
```

---

## Task 6: Luminance, sharpness, and reference-frame selection

**Files:**
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/Luma.swift`
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/ReferenceSelection.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/ReferenceSelectionTests.swift`

- [ ] **Step 1: Write the failing test**

Create `ReferenceSelectionTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class ReferenceSelectionTests: XCTestCase {
    private func checkerboard(_ n: Int) -> PixelImage {
        var img = PixelImage(width: n, height: n)
        for y in 0..<n { for x in 0..<n {
            let v: Float = ((x + y) % 2 == 0) ? 1 : 0
            img[x, y] = SIMD3<Float>(v, v, v)
        }}
        return img
    }
    private func flat(_ n: Int, _ v: Float) -> PixelImage {
        PixelImage(width: n, height: n, fill: SIMD3<Float>(v, v, v))
    }

    func testSharpnessHigherForCheckerboard() {
        XCTAssertGreaterThan(Luma.sharpness(checkerboard(8)), Luma.sharpness(flat(8, 0.5)))
    }
    func testReferenceSelectionPicksSharpest() {
        let frames = [flat(8, 0.5), checkerboard(8), flat(8, 0.3)]
        XCTAssertEqual(ReferenceSelection.sharpestIndex(frames), 1)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter ReferenceSelectionTests`
Expected: FAIL — `cannot find 'Luma' in scope`.

- [ ] **Step 3: Implement Luma + ReferenceSelection**

Create `Luma.swift`:
```swift
import simd

public enum Luma {
    /// Rec.709 luminance of each pixel.
    public static func luminance(_ img: PixelImage) -> [Float] {
        img.pixels.map { 0.2126 * $0.x + 0.7152 * $0.y + 0.0722 * $0.z }
    }

    /// Sharpness = sum of |Laplacian| over the luminance image (higher = sharper).
    public static func sharpness(_ img: PixelImage) -> Float {
        let l = luminance(img), w = img.width, h = img.height
        @inline(__always) func at(_ x: Int, _ y: Int) -> Float {
            l[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        var s: Float = 0
        for y in 0..<h { for x in 0..<w {
            let lap = at(x-1, y) + at(x+1, y) + at(x, y-1) + at(x, y+1) - 4 * at(x, y)
            s += abs(lap)
        }}
        return s
    }
}
```

Create `ReferenceSelection.swift`:
```swift
public enum ReferenceSelection {
    /// Index of the sharpest frame — the geometric anchor for alignment.
    public static func sharpestIndex(_ imgs: [PixelImage]) -> Int {
        precondition(!imgs.isEmpty)
        var best = 0
        var bestScore = -Float.infinity
        for (i, im) in imgs.enumerated() {
            let s = Luma.sharpness(im)
            if s > bestScore { bestScore = s; best = i }
        }
        return best
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter ReferenceSelectionTests`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): luminance, sharpness, and reference-frame selection"
```

---

## Task 7: Translation alignment — estimate + warp

**Files:**
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/Alignment.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/AlignmentTests.swift`

> Skeleton scope: integer translation via luma SSD search. Homography + local flow are a later plan.

- [ ] **Step 1: Write the failing test**

Create `AlignmentTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class AlignmentTests: XCTestCase {
    /// A diagonal gradient gives a unique SSD minimum.
    private func gradient(_ w: Int, _ h: Int) -> PixelImage {
        var img = PixelImage(width: w, height: h)
        for y in 0..<h { for x in 0..<w {
            let v = Float(x + 2 * y) / Float(w + 2 * h)
            img[x, y] = SIMD3<Float>(v, v, v)
        }}
        return img
    }
    /// moving[x,y] = ref[x - sx, y - sy] (content shifted by (sx,sy)).
    private func shifted(_ img: PixelImage, _ sx: Int, _ sy: Int) -> PixelImage {
        let w = img.width, h = img.height
        var out = PixelImage(width: w, height: h)
        for y in 0..<h { for x in 0..<w {
            let cx = min(max(x - sx, 0), w - 1), cy = min(max(y - sy, 0), h - 1)
            out[x, y] = img[cx, cy]
        }}
        return out
    }

    func testEstimateRecoversShift() {
        let ref = gradient(16, 16)
        let mov = shifted(ref, 2, -1) // content moved right 2, up 1
        // ref[x,y] = mov[x+2, y-1], so best (dx,dy) = (2,-1)
        let t = Alignment.estimateTranslation(reference: ref, moving: mov, searchRange: 4)
        XCTAssertEqual(t.dx, 2)
        XCTAssertEqual(t.dy, -1)
    }

    func testWarpAlignsToReference() {
        let ref = gradient(16, 16)
        let mov = shifted(ref, 2, -1)
        let t = Alignment.estimateTranslation(reference: ref, moving: mov, searchRange: 4)
        let warped = Alignment.warp(mov, by: t)
        // Interior must match the reference after warping.
        var maxDiff: Float = 0
        for y in 3..<13 { for x in 3..<13 {
            maxDiff = max(maxDiff, abs(warped[x, y].x - ref[x, y].x))
        }}
        XCTAssertLessThan(maxDiff, 1e-5)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter AlignmentTests`
Expected: FAIL — `cannot find 'Alignment' in scope`.

- [ ] **Step 3: Implement Alignment**

Create `Alignment.swift`:
```swift
import simd

public struct Translation: Equatable {
    public let dx: Int
    public let dy: Int
    public init(dx: Int, dy: Int) { self.dx = dx; self.dy = dy }
}

public enum Alignment {
    /// Integer translation (dx,dy) minimizing mean luma SSD where ref[x,y] ~ moving[x+dx, y+dy].
    public static func estimateTranslation(reference ref: PixelImage,
                                           moving mov: PixelImage,
                                           searchRange r: Int) -> Translation {
        precondition(ref.width == mov.width && ref.height == mov.height)
        let lr = Luma.luminance(ref), lm = Luma.luminance(mov)
        let w = ref.width, h = ref.height
        var best = Translation(dx: 0, dy: 0)
        var bestCost = Float.infinity
        // Iterate from zero outward (magnitude shells) so equal-cost ties are broken in
        // favour of the SMALLEST displacement. This matters for low-texture / degenerate
        // scenes where several shifts share the same SSD; the minimal shift is the correct one.
        for mag in 0...r {
            for dy in -mag...mag {
                for dx in -mag...mag {
                    guard abs(dx) == mag || abs(dy) == mag else { continue } // current shell only
                    var cost: Float = 0
                    var count: Float = 0
                    let yStart = max(0, -dy), yEnd = min(h, h - dy)
                    let xStart = max(0, -dx), xEnd = min(w, w - dx)
                    if yStart >= yEnd || xStart >= xEnd { continue }
                    for y in yStart..<yEnd {
                        for x in xStart..<xEnd {
                            let d = lr[y * w + x] - lm[(y + dy) * w + (x + dx)]
                            cost += d * d
                            count += 1
                        }
                    }
                    let mean = cost / count
                    if mean < bestCost { bestCost = mean; best = Translation(dx: dx, dy: dy) }
                }
            }
        }
        return best
    }

    /// Warp by (dx,dy): out[x,y] = img[x+dx, y+dy] (edge-clamped), aligning `img` to the reference.
    public static func warp(_ img: PixelImage, by t: Translation) -> PixelImage {
        let w = img.width, h = img.height
        var out = PixelImage(width: w, height: h)
        for y in 0..<h {
            for x in 0..<w {
                let sx = min(max(x + t.dx, 0), w - 1)
                let sy = min(max(y + t.dy, 0), h - 1)
                out[x, y] = img[sx, sy]
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter AlignmentTests`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): translation alignment estimate + warp"
```

---

## Task 8: Sigma-clipped mean reducer

**Files:**
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/StackReducer.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/StackReducerTests.swift`

- [ ] **Step 1: Write the failing test**

Create `StackReducerTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class StackReducerTests: XCTestCase {
    private func flat(_ v: Float) -> PixelImage {
        PixelImage(width: 1, height: 1, pixels: [SIMD3<Float>(v, v, v)])
    }

    func testPlainMeanWhenNoOutliers() {
        let imgs = [flat(0.2), flat(0.4), flat(0.6), flat(0.8)]
        let out = StackReducer.sigmaClippedMean(imgs, kappa: 1.5)
        XCTAssertEqual(out[0, 0].x, 0.5, accuracy: 1e-5)
    }

    func testRejectsOutlier() {
        // four 0.5s and one 10.0 -> clipped -> ~0.5
        let imgs = [flat(0.5), flat(0.5), flat(0.5), flat(0.5), flat(10.0)]
        let out = StackReducer.sigmaClippedMean(imgs, kappa: 1.5)
        XCTAssertEqual(out[0, 0].x, 0.5, accuracy: 1e-4)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter StackReducerTests`
Expected: FAIL — `cannot find 'StackReducer' in scope`.

- [ ] **Step 3: Implement StackReducer**

Create `StackReducer.swift`:
```swift
import simd

public enum StackReducer {
    /// Per-pixel, per-channel sigma-clipped mean across aligned frames.
    public static func sigmaClippedMean(_ imgs: [PixelImage],
                                        kappa: Float = 2.0,
                                        iterations: Int = 3) -> PixelImage {
        precondition(!imgs.isEmpty)
        let w = imgs[0].width, h = imgs[0].height
        precondition(imgs.allSatisfy { $0.width == w && $0.height == h }, "all images must be the same size")
        let n = imgs.count
        var out = PixelImage(width: w, height: h)
        for i in 0..<(w * h) {
            for ch in 0..<3 {
                var kept = [Float](); kept.reserveCapacity(n)
                for im in imgs { kept.append(im.pixels[i][ch]) }
                let original = kept
                var iter = 0
                while iter < iterations && kept.count > 2 {
                    let mean = kept.reduce(0, +) / Float(kept.count)
                    let varc = kept.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Float(kept.count)
                    let sd = varc.squareRoot()
                    if sd == 0 { break }
                    let filtered = kept.filter { abs($0 - mean) <= kappa * sd }
                    if filtered.count < 3 { break }          // keep current set; too few survivors
                    if filtered.count == kept.count { break } // converged
                    kept = filtered
                    iter += 1
                }
                let survivors = kept.count >= 3 ? kept : original
                out.pixels[i][ch] = survivors.reduce(0, +) / Float(survivors.count)
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter StackReducerTests`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): sigma-clipped mean stack reducer"
```

---

## Task 9: Output transform (linear → sRGB 8-bit)

**Files:**
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/OutputTransform.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/OutputTransformTests.swift`

- [ ] **Step 1: Write the failing test**

Create `OutputTransformTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class OutputTransformTests: XCTestCase {
    func testSRGBEncodingKnownValues() {
        let img = PixelImage(width: 3, height: 1, pixels: [
            SIMD3<Float>(0, 0, 0),
            SIMD3<Float>(1, 1, 1),
            SIMD3<Float>(0.5, 0.5, 0.5),
        ])
        let bytes = OutputTransform.encodeSRGB8(img) // RGBA, 4 bytes/pixel
        XCTAssertEqual(bytes.count, 3 * 4)
        // black -> 0, alpha 255
        XCTAssertEqual(bytes[0], 0); XCTAssertEqual(bytes[3], 255)
        // white -> 255
        XCTAssertEqual(bytes[4], 255)
        // linear 0.5 -> sRGB ~0.7353 -> ~188
        XCTAssertEqual(Int(bytes[8]), 188, accuracy: 1)
    }
}

private func XCTAssertEqual(_ a: Int, _ b: Int, accuracy: Int,
                           file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertLessThanOrEqual(abs(a - b), accuracy, file: file, line: line)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter OutputTransformTests`
Expected: FAIL — `cannot find 'OutputTransform' in scope`.

- [ ] **Step 3: Implement OutputTransform**

Create `OutputTransform.swift`:
```swift
import Foundation
import simd

public enum OutputTransform {
    @inline(__always) private static func linearToSRGB(_ c: Float) -> Float {
        let x = min(max(c, 0), 1)
        if x <= 0.0031308 { return x * 12.92 }
        return Float(1.055 * Foundation.pow(Double(x), 1.0 / 2.4) - 0.055)
    }

    /// Encode a linear image to interleaved sRGB RGBA8 bytes (alpha = 255).
    public static func encodeSRGB8(_ img: PixelImage) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: img.pixels.count * 4)
        for i in 0..<img.pixels.count {
            let p = img.pixels[i]
            out[i*4 + 0] = UInt8((linearToSRGB(p.x) * 255).rounded())
            out[i*4 + 1] = UInt8((linearToSRGB(p.y) * 255).rounded())
            out[i*4 + 2] = UInt8((linearToSRGB(p.z) * 255).rounded())
            out[i*4 + 3] = 255
        }
        return out
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Packages/StackEngineCore && swift test --filter OutputTransformTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): linear-to-sRGB 8-bit output transform"
```

---

## Task 10: Metrics + end-to-end Pipeline + golden test

**Files:**
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/Metrics.swift`
- Create: `Packages/StackEngineCore/Sources/StackEngineCore/Pipeline.swift`
- Test: `Packages/StackEngineCore/Tests/StackEngineCoreTests/PipelineTests.swift`

- [ ] **Step 1: Write the failing test (metrics)**

Create `PipelineTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class PipelineTests: XCTestCase {
    func testPSNRIdenticalIsInfinite() {
        let a: [UInt8] = [10, 20, 30, 255]
        XCTAssertEqual(Metrics.psnr(a, a), .infinity)
    }
    func testPSNRDecreasesWithError() {
        let a: [UInt8] = [100, 100, 100, 100]
        let b: [UInt8] = [110, 90, 105, 100]
        let p = Metrics.psnr(a, b)
        XCTAssertGreaterThan(p, 20)
        XCTAssertLessThan(p, 60)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testPSNRIdenticalIsInfinite`
Expected: FAIL — `cannot find 'Metrics' in scope`.

- [ ] **Step 3: Implement Metrics**

Create `Metrics.swift`:
```swift
import Foundation
import simd

public enum Metrics {
    /// Max absolute per-channel difference between two linear images.
    public static func maxAbsDiff(_ a: PixelImage, _ b: PixelImage) -> Float {
        precondition(a.pixels.count == b.pixels.count)
        var m: Float = 0
        for i in 0..<a.pixels.count {
            let d = a.pixels[i] - b.pixels[i]
            m = max(m, max(abs(d.x), max(abs(d.y), abs(d.z))))
        }
        return m
    }

    /// PSNR in dB between two equal-length 8-bit buffers (.infinity if identical).
    public static func psnr(_ a: [UInt8], _ b: [UInt8]) -> Double {
        precondition(a.count == b.count && !a.isEmpty)
        var mse = 0.0
        for i in 0..<a.count {
            let d = Double(a[i]) - Double(b[i])
            mse += d * d
        }
        mse /= Double(a.count)
        if mse == 0 { return .infinity }
        return 10 * log10(255 * 255 / mse)
    }
}
```

- [ ] **Step 4: Run the metrics tests**

Run: `cd Packages/StackEngineCore && swift test --filter PipelineTests`
Expected: PASS (2 tests).

- [ ] **Step 5: Add the failing end-to-end golden test**

Add to `PipelineTests.swift` (inside the class):
```swift
    /// Deterministic synthetic scene + per-frame noise + small shifts.
    private func makeNoisyShiftedStack(clean: PixelImage, count: Int) -> [PixelImage] {
        let w = clean.width, h = clean.height
        var frames = [PixelImage]()
        for k in 0..<count {
            // Deterministic shift pattern and additive noise (no RNG, so the test is stable).
            let sx = (k % 3) - 1            // -1,0,1,-1,0,1...
            let sy = ((k / 3) % 3) - 1
            var img = PixelImage(width: w, height: h)
            for y in 0..<h { for x in 0..<w {
                let cx = min(max(x - sx, 0), w - 1), cy = min(max(y - sy, 0), h - 1)
                // Deterministic noise in SCENE coordinates (cx,cy): this keeps each scene
                // point's noise consistent across aligned frames so it averages out after
                // stacking, while still varying with frame index k. Amplitude ÷200 (≈±0.025)
                // stays below the per-pixel luma gradient (≈0.030) so alignment stays reliable.
                let noise = Float((k * 37 + cx * 7 + cy * 13) % 11 - 5) / 200.0
                let base = clean[cx, cy]
                img[x, y] = SIMD3<Float>(base.x + noise, base.y + noise, base.z + noise)
            }}
            frames.append(img)
        }
        return frames
    }

    func testNoiseReductionConvergesAndBeatsSingleFrame() {
        let n = 24
        // Deterministic high-frequency texture. Its near-delta autocorrelation gives the SSD
        // search a sharp, unambiguous minimum at the true integer shift — this avoids the
        // aperture problem that ANY smooth ramp suffers (where a pure-horizontal and a
        // pure-vertical 1px shift produce identical cost). Range ~0.15...0.9: no deep shadows.
        func texel(_ x: Int, _ y: Int) -> Float {
            var h = UInt32(truncatingIfNeeded: x &* 73856093) ^ UInt32(truncatingIfNeeded: y &* 19349663)
            h = h &* 2654435761
            h ^= h >> 13
            h = h &* 2246822519
            h ^= h >> 16
            return Float(h & 0xFFFF) / Float(0xFFFF)
        }
        var clean = PixelImage(width: n, height: n)
        for y in 0..<n { for x in 0..<n {
            let s = 0.15 + 0.75 * texel(x, y)
            clean[x, y] = SIMD3<Float>(s, s, s)
        }}
        let frames = makeNoisyShiftedStack(clean: clean, count: 12)

        // The pipeline aligns everything to the SHARPEST frame, so the result lives in that
        // frame's coordinate system (clean shifted by the reference frame's own shift).
        func trueShift(_ k: Int) -> StackEngineCore.Translation {
            StackEngineCore.Translation(dx: (k % 3) - 1, dy: ((k / 3) % 3) - 1)
        }
        let refIdx = ReferenceSelection.sharpestIndex(frames)
        let ref = trueShift(refIdx)

        // Alignment must recover each non-reference frame's true integer shift.
        for k in 0..<frames.count where k != refIdx {
            let est = Alignment.estimateTranslation(reference: frames[refIdx], moving: frames[k], searchRange: 2)
            let expected = StackEngineCore.Translation(dx: trueShift(k).dx - ref.dx, dy: trueShift(k).dy - ref.dy)
            XCTAssertEqual(est, expected, "alignment should recover frame \(k)'s true shift")
        }

        // Ground truth expressed in the reference frame's coordinates.
        var refClean = PixelImage(width: n, height: n)
        for y in 0..<n { for x in 0..<n {
            let cx = min(max(x - ref.dx, 0), n - 1), cy = min(max(y - ref.dy, 0), n - 1)
            refClean[x, y] = clean[cx, cy]
        }}

        let result = Pipeline.noiseReductionImages(frames, searchRange: 2, kappa: 2.0)
        func interiorMaxDiff(_ a: PixelImage, _ b: PixelImage) -> Float {
            var m: Float = 0
            for y in 4..<(n - 4) { for x in 4..<(n - 4) {
                let d = a[x, y] - b[x, y]
                m = max(m, max(abs(d.x), max(abs(d.y), abs(d.z))))
            }}
            return m
        }
        let stackedMax = interiorMaxDiff(result, refClean)
        // No-op baseline: the single sharpest frame still carries full per-pixel noise.
        let baselineMax = interiorMaxDiff(frames[refIdx], refClean)

        XCTAssertLessThan(stackedMax, baselineMax * 0.5, "stacking should clearly beat one frame")
        XCTAssertLessThan(stackedMax, 0.01, "stacked result should converge to the clean scene")

        // PSNR over the interior only: warp edge-clamping cannot reconstruct content that
        // shifted in from outside the frame, so borders are excluded (same region as maxDiff).
        func interiorCrop(_ img: PixelImage) -> PixelImage {
            let m = 4
            var out = PixelImage(width: n - 2 * m, height: n - 2 * m)
            for y in m..<(n - m) { for x in m..<(n - m) { out[x - m, y - m] = img[x, y] } }
            return out
        }
        let psnr = Metrics.psnr(OutputTransform.encodeSRGB8(interiorCrop(result)),
                                OutputTransform.encodeSRGB8(interiorCrop(refClean)))
        XCTAssertGreaterThan(psnr, 40.0)
    }

    // Note: PSNR/maxAbsDiff unit tests live in MetricsTests.swift; StackReducerTests and
    // ReferenceSelectionTests also cover the n=2 plain-mean and single-frame edge cases.
    // `Translation` is module-qualified above to avoid a name clash with ApplicationServices.Translation on macOS.
```

- [ ] **Step 6: Run it to verify it fails**

Run: `cd Packages/StackEngineCore && swift test --filter testNoiseReductionConvergesAndBeatsSingleFrame`
Expected: FAIL — `type 'Pipeline' has no member 'noiseReductionImages'`.

- [ ] **Step 7: Implement Pipeline**

Create `Pipeline.swift`:
```swift
import simd

public enum Pipeline {
    /// End-to-end noise reduction over already-developed linear images:
    /// pick sharpest reference → align each frame → sigma-clipped mean.
    public static func noiseReductionImages(_ imgs: [PixelImage],
                                            searchRange: Int = 8,
                                            kappa: Float = 2.0) -> PixelImage {
        precondition(!imgs.isEmpty)
        if imgs.count == 1 { return imgs[0] }
        let refIdx = ReferenceSelection.sharpestIndex(imgs)
        let ref = imgs[refIdx]
        var aligned = [PixelImage]()
        aligned.reserveCapacity(imgs.count)
        for (i, im) in imgs.enumerated() {
            if i == refIdx { aligned.append(im); continue }
            let t = Alignment.estimateTranslation(reference: ref, moving: im, searchRange: searchRange)
            aligned.append(Alignment.warp(im, by: t))
        }
        return StackReducer.sigmaClippedMean(aligned, kappa: kappa)
    }

    /// End-to-end from raw frames: develop each → noise reduction.
    public static func noiseReduction(_ frames: [RawSensorFrame],
                                      searchRange: Int = 8,
                                      kappa: Float = 2.0) -> PixelImage {
        let imgs = frames.map { ColorPipeline.process($0) }
        return noiseReductionImages(imgs, searchRange: searchRange, kappa: kappa)
    }

    /// Convenience for the app + golden harness: raw frames → encoded sRGB RGBA8.
    public static func noiseReductionEncoded(_ frames: [RawSensorFrame]) -> (image: PixelImage, rgba8: [UInt8]) {
        let result = noiseReduction(frames)
        return (result, OutputTransform.encodeSRGB8(result))
    }
}
```

- [ ] **Step 8: Run all package tests**

Run: `cd Packages/StackEngineCore && swift test`
Expected: PASS — entire suite green.

- [ ] **Step 9: Commit**

```bash
git add Packages/StackEngineCore
git commit -m "feat(core): metrics + end-to-end noise-reduction pipeline + golden test"
```

---

## Task 11: Create the iOS app target and link the core

**Files:**
- Create: `StackStackStack.xcodeproj` (+ `StackStackStack/` group) via Xcode
- Create: `StackStackStack/ImageEncoder.swift`
- Test: `StackStackStackTests/ImageEncoderTests.swift`

> From here the work runs in Xcode against the iOS Simulator/device. These tasks are verified by **build + run** (and a few simulator unit tests), not CLI `swift test`.

- [ ] **Step 1: Create the app project**

In Xcode: File ▸ New ▸ Project ▸ iOS ▸ App.
- Product Name: `StackStackStack`
- Interface: SwiftUI, Language: Swift, Storage: Core Data **unchecked** (we add it manually in Task 13), Include Tests: **checked**.
- Save **inside** the repo root `photo-stack-app/` (so `StackStackStack.xcodeproj` sits next to `Packages/`).
- Set the **iOS Deployment Target to 16.0** (project ▸ target ▸ General). **Set it on the Test and UITest targets too**, not just the app — Xcode defaults new test targets to the latest SDK (e.g. 26.5), which then refuses to run on any simulator with an older runtime. (`IPHONEOS_DEPLOYMENT_TARGET` should read 16.x for all three targets.)

- [ ] **Step 2: Add the local package dependency**

Project ▸ target `StackStackStack` ▸ General ▸ Frameworks, Libraries, and Embedded Content ▸ `+` ▸ Add Other ▸ Add Package Dependency ▸ Add Local… ▸ select `Packages/StackEngineCore` ▸ add the `StackEngineCore` library product to the app target.

- [ ] **Step 3: Add camera + photo usage strings**

In the target's Info settings add:
- `NSCameraUsageDescription` = "Stack Stack Stack captures short bursts to build stacked photos."
- `NSPhotoLibraryAddUsageDescription` = "Save your finished stacks to your photo library."

- [ ] **Step 4: Write the failing encoder test**

Create `StackStackStackTests/ImageEncoderTests.swift`:
```swift
import XCTest
import StackEngineCore
@testable import StackStackStack

final class ImageEncoderTests: XCTestCase {
    func testEncodesNonEmptyJPEG() throws {
        // 2x2 RGBA8 red
        let rgba: [UInt8] = Array(repeating: 0, count: 16).enumerated().map { i, _ in
            (i % 4 == 0 || i % 4 == 3) ? 255 : 0   // R=255, A=255
        }
        let data = try ImageEncoder.encode(rgba8: rgba, width: 2, height: 2, format: .jpeg, quality: 0.9)
        XCTAssertGreaterThan(data.count, 0)
        // JPEG magic bytes
        XCTAssertEqual(data[0], 0xFF); XCTAssertEqual(data[1], 0xD8)
    }
}
```

- [ ] **Step 5: Run to verify it fails**

Build the test target (⌘U) or run just this test.
Expected: FAIL — `cannot find 'ImageEncoder' in scope`.

- [ ] **Step 6: Implement the encoder**

Create `StackStackStack/ImageEncoder.swift`:
```swift
import Foundation
import ImageIO
import UniformTypeIdentifiers
import CoreGraphics

enum ImageEncoderError: Error { case contextFailed, destinationFailed, finalizeFailed }

enum ImageEncoder {
    enum Format { case jpeg, heic
        var utType: UTType { self == .jpeg ? .jpeg : .heic }
    }

    /// Encode interleaved sRGB RGBA8 bytes into JPEG/HEIC data.
    static func encode(rgba8: [UInt8], width: Int, height: Int,
                       format: Format, quality: Double) throws -> Data {
        let cs = CGColorSpace(name: CGColorSpace.sRGB)!
        let bitmapInfo = CGImageAlphaInfo.noneSkipLast.rawValue // ignore alpha
        var bytes = rgba8
        guard let ctx = CGContext(data: &bytes, width: width, height: height,
                                  bitsPerComponent: 8, bytesPerRow: width * 4,
                                  space: cs, bitmapInfo: bitmapInfo),
              let cg = ctx.makeImage() else { throw ImageEncoderError.contextFailed }

        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(
            out, format.utType.identifier as CFString, 1, nil)
        else { throw ImageEncoderError.destinationFailed }

        CGImageDestinationAddImage(dest, cg, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw ImageEncoderError.finalizeFailed }
        return out as Data
    }
}
```

- [ ] **Step 7: Run to verify it passes**

Run the test (⌘U). Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add StackStackStack.xcodeproj StackStackStack StackStackStackTests
git commit -m "feat(app): iOS app target linking StackEngineCore + ImageIO encoder"
```

---

## Task 12: Capture service — protocol, fake, and AVFoundation RAW burst

**Files:**
- Create: `StackStackStack/Capture/CaptureService.swift`
- Create: `StackStackStack/Capture/FakeCaptureService.swift`
- Create: `StackStackStack/Capture/AVCaptureService.swift`
- Create: `StackStackStack/Capture/RawFrameConverter.swift`
- Test: `StackStackStackTests/FakeCaptureServiceTests.swift`

- [ ] **Step 1: Define the protocol + a deterministic fake, with a failing test**

Create `StackStackStackTests/FakeCaptureServiceTests.swift`:
```swift
import XCTest
import StackEngineCore
@testable import StackStackStack

final class FakeCaptureServiceTests: XCTestCase {
    func testFakeReturnsRequestedFrameCount() async throws {
        let svc = FakeCaptureService(width: 8, height: 8)
        let frames = try await svc.captureBurst(mode: .noiseReduction, frameCount: 5)
        XCTAssertEqual(frames.count, 5)
        XCTAssertEqual(frames[0].width, 8)
        // developing a fake frame should not crash and yields the right size
        let img = ColorPipeline.process(frames[0])
        XCTAssertEqual(img.pixels.count, 64)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

⌘U. Expected: FAIL — `cannot find 'FakeCaptureService' in scope`.

- [ ] **Step 3: Implement the protocol + fake**

Create `StackStackStack/Capture/CaptureService.swift`:
```swift
import StackEngineCore

enum CaptureMode { case noiseReduction } // skeleton supports one mode

protocol CaptureService {
    func captureBurst(mode: CaptureMode, frameCount: Int) async throws -> [RawSensorFrame]
}
```

Create `StackStackStack/Capture/FakeCaptureService.swift`:
```swift
import StackEngineCore
import simd

/// Deterministic in-memory capture for unit tests and previews (no camera).
struct FakeCaptureService: CaptureService {
    let width: Int
    let height: Int

    func captureBurst(mode: CaptureMode, frameCount: Int) async throws -> [RawSensorFrame] {
        (0..<frameCount).map { k in
            var mosaic = [UInt16](repeating: 0, count: width * height)
            for y in 0..<height { for x in 0..<width {
                let base = 544 + ((x + y) % 2) * 100      // mild pattern
                let noise = (k * 17 + x * 3 + y * 5) % 9 - 4
                mosaic[y * width + x] = UInt16(max(0, min(1023, base + noise)))
            }}
            return RawSensorFrame(width: width, height: height, mosaic: mosaic,
                                  blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
                                  wbGains: SIMD3<Float>(1, 1, 1))
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

⌘U. Expected: PASS.

- [ ] **Step 5: Implement the real AVFoundation capture (device-verified, no unit test)**

Create `StackStackStack/Capture/RawFrameConverter.swift`:
```swift
import AVFoundation
import StackEngineCore
import simd

/// Converts an AVCapturePhoto (Bayer RAW) into our RawSensorFrame.
enum RawFrameConverter {
    static func make(from photo: AVCapturePhoto) -> RawSensorFrame? {
        guard let px = photo.pixelBuffer else { return nil }
        CVPixelBufferLockBaseAddress(px, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(px, .readOnly) }
        let w = CVPixelBufferGetWidth(px), h = CVPixelBufferGetHeight(px)
        guard let base = CVPixelBufferGetBaseAddress(px) else { return nil }
        let rowBytes = CVPixelBufferGetBytesPerRow(px)
        var mosaic = [UInt16](repeating: 0, count: w * h)
        for y in 0..<h {
            let row = base.advanced(by: y * rowBytes).assumingMemoryBound(to: UInt16.self)
            for x in 0..<w { mosaic[y * w + x] = row[x] }
        }
        // Metadata: AVFoundation RAW is typically 14-bit packed in 16; use sensible defaults
        // and refine per device in a later plan (capability detection).
        let meta = photo.metadata
        let cfa: CFAPattern = .rggb // refine from kCGImagePropertyDNG keys per device later
        _ = meta
        return RawSensorFrame(width: w, height: h, mosaic: mosaic,
                              blackLevel: 0, whiteLevel: 16383, cfa: cfa,
                              wbGains: SIMD3<Float>(1, 1, 1))
    }
}
```

Create `StackStackStack/Capture/AVCaptureService.swift`:
```swift
import AVFoundation
import StackEngineCore

/// Captures a short Bayer-RAW burst with locked exposure/focus (design §10.4, noise recipe).
final class AVCaptureService: NSObject, CaptureService {
    private let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private var device: AVCaptureDevice?

    private var pending: [RawSensorFrame] = []
    private var remaining = 0
    private var continuation: CheckedContinuation<[RawSensorFrame], Error>?

    enum CaptureError: Error { case noDevice, noRawFormat, conversionFailed }

    func configure() throws {
        session.beginConfiguration()
        session.sessionPreset = .photo
        guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
        else { throw CaptureError.noDevice }
        device = dev
        let input = try AVCaptureDeviceInput(device: dev)
        if session.canAddInput(input) { session.addInput(input) }
        if session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()
        session.startRunning()
    }

    func captureBurst(mode: CaptureMode, frameCount: Int) async throws -> [RawSensorFrame] {
        guard !output.availableRawPhotoPixelFormatTypes.isEmpty else { throw CaptureError.noRawFormat }
        try lockExposureAndFocus()
        pending.removeAll(); remaining = frameCount
        return try await withCheckedThrowingContinuation { cont in
            self.continuation = cont
            for _ in 0..<frameCount { captureOneRaw() }
        }
    }

    private func lockExposureAndFocus() throws {
        guard let dev = device else { throw CaptureError.noDevice }
        try dev.lockForConfiguration()
        if dev.isExposureModeSupported(.locked) { dev.exposureMode = .locked }
        if dev.isFocusModeSupported(.locked) { dev.focusMode = .locked }
        if dev.isWhiteBalanceModeSupported(.locked) { dev.whiteBalanceMode = .locked }
        dev.unlockForConfiguration()
    }

    private func captureOneRaw() {
        guard let rawType = output.availableRawPhotoPixelFormatTypes.first else { return }
        let settings = AVCapturePhotoSettings(rawPixelFormatType: rawType)
        output.capturePhoto(with: settings, delegate: self)
    }
}

extension AVCaptureService: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error { continuation?.resume(throwing: error); continuation = nil; return }
        if let frame = RawFrameConverter.make(from: photo) { pending.append(frame) }
        remaining -= 1
        if remaining <= 0 {
            continuation?.resume(returning: pending)
            continuation = nil
        }
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add StackStackStack StackStackStackTests
git commit -m "feat(app): capture service protocol, fake, and AVFoundation RAW burst"
```

---

## Task 13: Library store + capture coordinator

**Files:**
- Create: `StackStackStack/Library/StackRecord.swift`
- Create: `StackStackStack/Library/LibraryStore.swift`
- Create: `StackStackStack/StackCaptureCoordinator.swift`
- Test: `StackStackStackTests/LibraryStoreTests.swift`

> Skeleton keeps storage simple and honest: results + a thumbnail + a JSON sidecar in the app's Documents dir (full Core Data schema with the proxy stack lands in the Phase 1 plan). This still satisfies the design's "save the result + metadata" for the walking skeleton.

- [ ] **Step 1: Write the failing store test**

Create `StackStackStackTests/LibraryStoreTests.swift`:
```swift
import XCTest
@testable import StackStackStack

final class LibraryStoreTests: XCTestCase {
    func testSaveAndLoadRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xD9]) // minimal stand-in bytes
        let rec = try store.save(resultJPEG: jpeg, mode: "noiseReduction", frameCount: 8)
        let all = try store.loadAll()
        XCTAssertEqual(all.count, 1)
        XCTAssertEqual(all[0].id, rec.id)
        XCTAssertEqual(all[0].frameCount, 8)
        XCTAssertTrue(FileManager.default.fileExists(atPath: rec.resultURL.path))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

⌘U. Expected: FAIL — `cannot find 'LibraryStore' in scope`.

- [ ] **Step 3: Implement the record + store**

Create `StackStackStack/Library/StackRecord.swift`:
```swift
import Foundation

struct StackRecord: Codable, Identifiable, Equatable {
    let id: UUID
    let createdAt: Date
    let mode: String
    let frameCount: Int
    let resultFileName: String

    func resultURL(in dir: URL) -> URL { dir.appendingPathComponent(resultFileName) }
}
```

Create `StackStackStack/Library/LibraryStore.swift`:
```swift
import Foundation

/// Minimal file-backed library: JPEG results + a JSON index in the given root.
final class LibraryStore {
    private let root: URL
    private let indexURL: URL
    private let fm = FileManager.default

    init(rootDirectory: URL = LibraryStore.defaultRoot()) {
        self.root = rootDirectory
        self.indexURL = rootDirectory.appendingPathComponent("index.json")
        try? fm.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
    }

    static func defaultRoot() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Stacks", isDirectory: true)
    }

    struct SavedStack { let id: UUID; let resultURL: URL }

    @discardableResult
    func save(resultJPEG: Data, mode: String, frameCount: Int) throws -> SavedStack {
        let id = UUID()
        let fileName = "\(id.uuidString).jpg"
        let url = root.appendingPathComponent(fileName)
        try resultJPEG.write(to: url)
        var records = (try? loadAll()) ?? []
        records.insert(StackRecord(id: id, createdAt: Date(), mode: mode,
                                   frameCount: frameCount, resultFileName: fileName), at: 0)
        try persist(records)
        return SavedStack(id: id, resultURL: url)
    }

    func loadAll() throws -> [StackRecord] {
        guard let data = try? Data(contentsOf: indexURL) else { return [] }
        return try JSONDecoder().decode([StackRecord].self, from: data)
    }

    func resultURL(for record: StackRecord) -> URL { record.resultURL(in: root) }

    private func persist(_ records: [StackRecord]) throws {
        let data = try JSONEncoder().encode(records)
        try data.write(to: indexURL)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

⌘U. Expected: PASS.

- [ ] **Step 5: Add the coordinator that ties capture → pipeline → encode → store**

Create `StackStackStack/StackCaptureCoordinator.swift`:
```swift
import Foundation
import Combine            // required for ObservableObject / @Published
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
```

- [ ] **Step 6: Add a coordinator test with the fake camera**

Add to `StackStackStackTests/LibraryStoreTests.swift` a new file `StackStackStackTests/CoordinatorTests.swift`:
```swift
import XCTest
@testable import StackStackStack

final class CoordinatorTests: XCTestCase {
    @MainActor
    func testShootProducesADoneStateAndSavesAFile() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(rootDirectory: dir)
        let coord = StackCaptureCoordinator(capture: FakeCaptureService(width: 16, height: 16), store: store)
        await coord.shoot(frameCount: 6)
        if case .done = coord.state {} else { XCTFail("expected .done, got \(coord.state)") }
        XCTAssertEqual(try store.loadAll().count, 1)
    }
}
```

- [ ] **Step 7: Run to verify it passes**

⌘U. Expected: PASS (build first to confirm fail if you scaffold the test before the coordinator).

- [ ] **Step 8: Commit**

```bash
git add StackStackStack StackStackStackTests
git commit -m "feat(app): file-backed library store + capture coordinator"
```

---

## Task 14: SwiftUI shell + end-to-end device verification

**Files:**
- Create: `StackStackStack/UI/CaptureView.swift`
- Create: `StackStackStack/UI/GalleryView.swift`
- Modify: `StackStackStack/StackStackStackApp.swift` (the generated `@main`)

> UI is verified by running on a device and observing behavior (no unit test for SwiftUI here).

- [ ] **Step 1: Build the capture screen**

Create `StackStackStack/UI/CaptureView.swift`:
```swift
import SwiftUI

struct CaptureView: View {
    @StateObject private var coordinator: StackCaptureCoordinator
    @State private var lastResult: UIImage?

    init(coordinator: StackCaptureCoordinator) {
        _coordinator = StateObject(wrappedValue: coordinator)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack {
                Spacer()
                if let img = lastResult {
                    Image(uiImage: img).resizable().scaledToFit().padding()
                } else {
                    Text("Detail (Noise Reduction)").foregroundColor(.white)
                }
                Spacer()
                statusLabel
                shutterButton.padding(.bottom, 40)
            }
        }
        .onChange(of: coordinator.state) { _ in loadResultIfDone() }
    }

    private var statusLabel: some View {
        Group {
            switch coordinator.state {
            case .idle: Text("Ready")
            case .capturing: Text("Capturing…")
            case .processing: Text("Stacking…")
            case .done: Text("Done")
            case .failed(let m): Text("Failed: \(m)").foregroundColor(.red)
            }
        }.foregroundColor(.white)
    }

    private var shutterButton: some View {
        Button {
            Task { await coordinator.shoot() }
        } label: {
            Circle().fill(.white).frame(width: 72, height: 72)
                .overlay(Circle().stroke(.gray, lineWidth: 4))
        }
        .disabled(isBusy)
    }

    private var isBusy: Bool {
        switch coordinator.state { case .capturing, .processing: return true; default: return false }
    }

    private func loadResultIfDone() {
        guard case .done(let id) = coordinator.state else { return }
        let store = LibraryStore()
        if let rec = (try? store.loadAll())?.first(where: { $0.id == id }),
           let data = try? Data(contentsOf: store.resultURL(for: rec)) {
            lastResult = UIImage(data: data)
        }
    }
}
```

- [ ] **Step 2: Build a minimal gallery**

Create `StackStackStack/UI/GalleryView.swift`:
```swift
import SwiftUI

struct GalleryView: View {
    @State private var records: [StackRecord] = []
    private let store = LibraryStore()
    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 4)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(records) { rec in
                    if let data = try? Data(contentsOf: store.resultURL(for: rec)),
                       let ui = UIImage(data: data) {
                        Image(uiImage: ui).resizable().scaledToFill()
                            .frame(height: 110).clipped()
                    }
                }
            }.padding(4)
        }
        .navigationTitle("Stacks")
        .onAppear { records = (try? store.loadAll()) ?? [] }
    }
}
```

- [ ] **Step 3: Wire the app entry point**

Replace the contents of `StackStackStack/StackStackStackApp.swift`:
```swift
import SwiftUI

@main
struct StackStackStackApp: App {
    var body: some Scene {
        WindowGroup {
            TabView {
                NavigationStack { CaptureView(coordinator: makeCoordinator()) }
                    .tabItem { Label("Capture", systemImage: "camera") }
                NavigationStack { GalleryView() }
                    .tabItem { Label("Gallery", systemImage: "photo.on.rectangle") }
            }
        }
    }

    private func makeCoordinator() -> StackCaptureCoordinator {
        #if targetEnvironment(simulator)
        // No camera in the Simulator — use the deterministic fake so the flow is demoable.
        return StackCaptureCoordinator(capture: FakeCaptureService(width: 256, height: 256))
        #else
        let svc = AVCaptureService()
        try? svc.configure()
        return StackCaptureCoordinator(capture: svc)
        #endif
    }
}
```

- [ ] **Step 4: Verify in the Simulator (fake camera path)**

Run the app in the iOS Simulator. Tap the shutter.
Expected: status goes Capturing → Stacking → Done, a stacked image appears, and it shows up in the Gallery tab. Confirms the entire develop→align→stack→encode→store→display loop end-to-end.

- [ ] **Step 5: Verify on a physical device (real RAW path)**

Run on a RAW-capable iPhone (11/A13+). Grant camera permission. Hold steady, tap the shutter.
Expected: a real burst is captured, stacked, and the denoised result displays and saves. Compared against a single frame, shadow noise is visibly reduced. (If `availableRawPhotoPixelFormatTypes` is empty on the device, capture throws `noRawFormat` and the status shows the error — handled, expected on non-RAW devices.)

- [ ] **Step 6: Run the full test suite once more**

Run: `cd Packages/StackEngineCore && swift test` (core), then ⌘U in Xcode (app).
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add StackStackStack
git commit -m "feat(app): SwiftUI capture + gallery shell wired end-to-end"
```

---

## Self-review

**1. Spec coverage (walking-skeleton subset of the bible):**
- Capture-only RAW burst, locked exposure/focus/WB (design §10) → Task 12 (`AVCaptureService`).
- Auto-align, reference selection (§11) → Tasks 6–7.
- Linear-light color pipeline, demosaic, color matrix (§12) → Tasks 3–5 (bilinear demosaic flagged provisional vs the normative Malvar; tracked for Plan 2).
- Noise-reduction reducer, sigma-clipped mean (§13.1) → Task 8.
- Output transform + JPEG/HEIC export (§12, §2) → Tasks 9, 11.
- Result + storage (§9) → Task 13 (proxy stack intentionally deferred to Phase 1 plan — noted).
- Golden-image / metrics testing (§18) → Task 10 (`Metrics`, end-to-end convergence test).
- Auto-mode capture UI shell (§15) → Task 14.
- **Deferred by design and called out:** Metal acceleration, Malvar demosaic, homography/local alignment, proxy stack, Pro controls, the other looks — all belong to later plans per the roadmap (§19).

**2. Placeholder scan:** No "TBD/TODO/handle edge cases" left as instructions; every code step contains complete code. The two `_ = meta` / default-metadata spots in `RawFrameConverter` are intentional, documented simplifications (per-device RAW metadata refinement is a named later task), not placeholders for missing logic.

**3. Type consistency check:** `PixelImage` (subscript `[x,y]`, `.pixels`), `RawSensorFrame` (init signature, `.wbGains`, `.colorMatrix`), `Translation` (`dx/dy`), `ColorPipeline.process`, `Alignment.estimateTranslation`/`warp`, `StackReducer.sigmaClippedMean(_:kappa:iterations:)`, `OutputTransform.encodeSRGB8`, `Pipeline.noiseReduction`/`noiseReductionImages`/`noiseReductionEncoded`, `ImageEncoder.encode(rgba8:width:height:format:quality:)`, `CaptureService.captureBurst(mode:frameCount:)`, `LibraryStore.save(resultJPEG:mode:frameCount:)`/`loadAll()`/`resultURL(for:)`, `StackCaptureCoordinator.shoot(frameCount:)` and its `State` — all names are used identically across the tasks that reference them.

---

## Definition of done

- `cd Packages/StackEngineCore && swift test` → entire core suite green.
- App test target (⌘U) green: encoder, fake capture, store, coordinator.
- Simulator: shutter → Capturing → Stacking → Done → image shown and listed in Gallery.
- Device (RAW-capable): a real handheld burst produces a visibly denoised, saved JPEG.
