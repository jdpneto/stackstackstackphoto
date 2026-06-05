# Phase 2 DoF — Phase 3: LaplacianPyramidBlend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Composite the focus-bracket frames into a single all-in-focus image using **Burt–Adelson multiband (Laplacian-pyramid) blending** driven by the per-frame selection weights — so focus boundaries are seamless (no halos/seams).

**Architecture:** Two pure-Swift units in `StackEngineCore`. `ImagePyramid` provides `reduce`/`expand` (5-tap binomial, border-renormalized) and builds Gaussian/Laplacian pyramids + `collapse`. `LaplacianPyramidBlend` builds each frame's image Laplacian pyramid and each weight mask's Gaussian pyramid, blends per level (`Σ_k maskGauss_k · imgLap_k`), and collapses. Spec: `docs/superpowers/specs/2026-06-05-phase2-depth-of-field-design.md` §3.5.

**Tech Stack:** Swift, `simd`. Pure-CPU, golden-tested via `swift test`. Builds on `PixelImage`, `Luma`, `SharpnessMap`.

---

## File structure (this plan)

```
Packages/StackEngineCore/Sources/StackEngineCore/
  ImagePyramid.swift           # CREATE — reduce/expand + gaussian/laplacian/collapse (PixelImage)
  LaplacianPyramidBlend.swift  # CREATE — multiband blend of N frames by N weight masks
Packages/StackEngineCore/Tests/StackEngineCoreTests/
  ImagePyramidTests.swift      # CREATE
  LaplacianPyramidBlendTests.swift # CREATE
```

Existing API used: `PixelImage` (`width`/`height`/`pixels`, `[x,y]`, `init(width:height:fill:)`, `init(width:height:pixels:)`), `Luma.luminance`, `SharpnessMap.compute`.

---

## Task 1: `ImagePyramid`

**Files:** Create `ImagePyramid.swift`, `ImagePyramidTests.swift`.

- [ ] **Step 1: Failing tests** — `ImagePyramidTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class ImagePyramidTests: XCTestCase {
    func testReduceHalvesDimensions(){
        let img = PixelImage(width: 8, height: 6, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        let r = ImagePyramid.reduce(img)
        XCTAssertEqual(r.width, 4); XCTAssertEqual(r.height, 3)   // ceil(w/2), ceil(h/2)
    }

    func testReduceOfConstantIsConstant() {
        let img = PixelImage(width: 8, height: 8, fill: SIMD3<Float>(0.3, 0.6, 0.9))
        let r = ImagePyramid.reduce(img)
        XCTAssertEqual(r[1, 1].x, 0.3, accuracy: 1e-5)   // border-renormalized → no darkening
        XCTAssertEqual(r[1, 1].y, 0.6, accuracy: 1e-5)
        XCTAssertEqual(r[0, 0].z, 0.9, accuracy: 1e-5)   // even at the corner
    }

    func testExpandOfConstantIsConstantAtTargetSize() {
        let img = PixelImage(width: 4, height: 4, fill: SIMD3<Float>(0.4, 0.4, 0.4))
        let e = ImagePyramid.expand(img, toWidth: 8, toHeight: 7)
        XCTAssertEqual(e.width, 8); XCTAssertEqual(e.height, 7)
        XCTAssertEqual(e[3, 3].x, 0.4, accuracy: 1e-5)
    }

    func testLaplacianCollapseReconstructsConstant() {
        // For a constant image, reduce/expand are exact → collapse(laplacian(img)) == img exactly.
        let img = PixelImage(width: 12, height: 10, fill: SIMD3<Float>(0.25, 0.5, 0.75))
        let out = ImagePyramid.collapse(ImagePyramid.laplacian(img))
        XCTAssertEqual(out.width, 12); XCTAssertEqual(out.height, 10)
        XCTAssertLessThan(Metrics.maxAbsDiff(out, img), 1e-4)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`cannot find 'ImagePyramid'`): `cd Packages/StackEngineCore && swift test --filter ImagePyramidTests`

- [ ] **Step 3: Create `ImagePyramid.swift`:**
```swift
import simd

/// Gaussian/Laplacian image pyramids for multiband blending (Burt–Adelson). `reduce`/`expand` use a
/// separable 5-tap binomial kernel, renormalized at borders so edges aren't darkened. Reconstruction
/// is exact for constant images and a smooth approximation otherwise — sufficient for blending.
public enum ImagePyramid {
    private static let kernel: [Float] = [1.0 / 16, 4.0 / 16, 6.0 / 16, 4.0 / 16, 1.0 / 16]

    /// Blur + downsample by 2 → dimensions ceil(w/2) × ceil(h/2).
    public static func reduce(_ img: PixelImage) -> PixelImage {
        let w = img.width, h = img.height
        let ow = (w + 1) / 2, oh = (h + 1) / 2
        var out = PixelImage(width: ow, height: oh)
        for oy in 0..<oh {
            for ox in 0..<ow {
                var acc = SIMD3<Float>(repeating: 0); var wsum: Float = 0
                for dy in 0..<5 {
                    let sy = 2 * oy + dy - 2
                    guard sy >= 0, sy < h else { continue }
                    for dx in 0..<5 {
                        let sx = 2 * ox + dx - 2
                        guard sx >= 0, sx < w else { continue }
                        let wgt = kernel[dx] * kernel[dy]
                        acc += img[sx, sy] * wgt; wsum += wgt
                    }
                }
                out[ox, oy] = wsum > 0 ? acc / wsum : acc   // renormalize at borders
            }
        }
        return out
    }

    /// Upsample to an exact target size with the binomial kernel (border-renormalized interpolation).
    public static func expand(_ img: PixelImage, toWidth tw: Int, toHeight th: Int) -> PixelImage {
        let w = img.width, h = img.height
        var out = PixelImage(width: tw, height: th)
        for ty in 0..<th {
            for tx in 0..<tw {
                var acc = SIMD3<Float>(repeating: 0); var wsum: Float = 0
                for dy in 0..<5 {
                    let syNum = ty + dy - 2
                    guard syNum % 2 == 0 else { continue }
                    let sy = syNum / 2
                    guard sy >= 0, sy < h else { continue }
                    for dx in 0..<5 {
                        let sxNum = tx + dx - 2
                        guard sxNum % 2 == 0 else { continue }
                        let sx = sxNum / 2
                        guard sx >= 0, sx < w else { continue }
                        let wgt = kernel[dx] * kernel[dy]
                        acc += img[sx, sy] * wgt; wsum += wgt
                    }
                }
                out[tx, ty] = wsum > 0 ? acc / wsum : acc
            }
        }
        return out
    }

    /// Gaussian pyramid, finest first, down to a min dimension of `minSize` (default 4) — but at
    /// least one level beyond the input.
    public static func gaussian(_ img: PixelImage, minSize: Int = 4) -> [PixelImage] {
        var levels = [img]
        while min(levels.last!.width, levels.last!.height) > minSize {
            levels.append(reduce(levels.last!))
        }
        return levels
    }

    /// Laplacian pyramid: L[i] = G[i] − expand(G[i+1] → G[i] size); the coarsest level is G[last].
    public static func laplacian(_ img: PixelImage, minSize: Int = 4) -> [PixelImage] {
        let g = gaussian(img, minSize: minSize)
        var lap = [PixelImage]()
        for i in 0..<(g.count - 1) {
            let up = expand(g[i + 1], toWidth: g[i].width, toHeight: g[i].height)
            var d = g[i]
            for j in 0..<d.pixels.count { d.pixels[j] -= up.pixels[j] }
            lap.append(d)
        }
        lap.append(g.last!)   // coarsest residual
        return lap
    }

    /// Collapse a Laplacian pyramid back to a single image.
    public static func collapse(_ lap: [PixelImage]) -> PixelImage {
        var out = lap.last!
        for i in stride(from: lap.count - 2, through: 0, by: -1) {
            let up = expand(out, toWidth: lap[i].width, toHeight: lap[i].height)
            var sum = lap[i]
            for j in 0..<sum.pixels.count { sum.pixels[j] += up.pixels[j] }
            out = sum
        }
        return out
    }
}
```

- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `git add Packages/StackEngineCore && git commit -m "feat(core): ImagePyramid (Gaussian/Laplacian reduce/expand/collapse)"`

---

## Task 2: `LaplacianPyramidBlend`

**Files:** Create `LaplacianPyramidBlend.swift`, `LaplacianPyramidBlendTests.swift`.

- [ ] **Step 1: Failing tests** — `LaplacianPyramidBlendTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class LaplacianPyramidBlendTests: XCTestCase {
    func testFullWeightOnOneFrameReturnsThatFrame() {
        let w = 16, h = 16, n = w * h
        let a = PixelImage(width: w, height: h, fill: SIMD3<Float>(0.8, 0.2, 0.2))
        let b = PixelImage(width: w, height: h, fill: SIMD3<Float>(0.2, 0.2, 0.8))
        let out = LaplacianPyramidBlend.blend(images: [a, b],
                                              weights: [[Float](repeating: 1, count: n),
                                                        [Float](repeating: 0, count: n)])
        XCTAssertEqual(out[8, 8].x, 0.8, accuracy: 2e-3)   // all weight on A → A
        XCTAssertEqual(out[8, 8].z, 0.2, accuracy: 2e-3)
    }

    func testCombinesEachFramesSharpRegion() {
        // Frame A: left half checker (sharp), right half flat. Frame B: the opposite.
        let w = 32, h = 16
        func frame(sharpLeft: Bool) -> PixelImage {
            var img = PixelImage(width: w, height: h, fill: SIMD3<Float>(0.5, 0.5, 0.5))
            for y in 0..<h { for x in 0..<w {
                let inLeft = x < w / 2
                if inLeft == sharpLeft {
                    let v: Float = ((x + y) % 2 == 0) ? 0.85 : 0.15
                    img[x, y] = SIMD3<Float>(v, v, v)
                }
            }}
            return img
        }
        let a = frame(sharpLeft: true), b = frame(sharpLeft: false)
        // Weights pick the in-focus frame per half.
        var wA = [Float](repeating: 0, count: w * h), wB = wA
        for y in 0..<h { for x in 0..<w {
            if x < w / 2 { wA[y * w + x] = 1 } else { wB[y * w + x] = 1 }
        }}
        let out = LaplacianPyramidBlend.blend(images: [a, b], weights: [wA, wB])
        // The composite is detailed in BOTH halves → total sharpness exceeds either single frame.
        let total = SharpnessMap.compute(out).reduce(0, +)
        XCTAssertGreaterThan(total, SharpnessMap.compute(a).reduce(0, +) * 1.3)
        XCTAssertGreaterThan(total, SharpnessMap.compute(b).reduce(0, +) * 1.3)
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Create `LaplacianPyramidBlend.swift`:**
```swift
import simd

/// Burt–Adelson multiband blend: composite N images by N per-pixel weight masks. Each image's
/// Laplacian pyramid is combined level-by-level using the Gaussian pyramid of its (normalized)
/// weight mask, then collapsed — giving seamless focus-boundary blending without halos (design §13.2).
public enum LaplacianPyramidBlend {
    /// `weights[k]` is a row-major per-pixel weight for frame k (length width*height). Weights need
    /// not be pre-normalized; they are normalized per pixel here.
    public static func blend(images: [PixelImage], weights: [[Float]], minSize: Int = 4) -> PixelImage {
        precondition(!images.isEmpty && images.count == weights.count, "images/weights mismatch")
        let w = images[0].width, h = images[0].height, n = w * h
        let m = images.count

        // Per-pixel normalize the weights (so they sum to 1), then carry each as a PixelImage so the
        // single pyramid machinery (SIMD3) handles masks and images alike.
        var maskImgs = [PixelImage]()
        var norm = [Float](repeating: 0, count: n)
        for i in 0..<n { var s: Float = 0; for k in 0..<m { s += max(weights[k][i], 0) }; norm[i] = s }
        for k in 0..<m {
            var px = [SIMD3<Float>](repeating: .zero, count: n)
            for i in 0..<n {
                let wgt = norm[i] > 0 ? max(weights[k][i], 0) / norm[i] : 1 / Float(m)
                px[i] = SIMD3<Float>(repeating: wgt)
            }
            maskImgs.append(PixelImage(width: w, height: h, pixels: px))
        }

        // Image Laplacian pyramids + mask Gaussian pyramids (same level dimensions for all frames).
        let imgLaps = images.map { ImagePyramid.laplacian($0, minSize: minSize) }
        let maskGaus = maskImgs.map { ImagePyramid.gaussian($0, minSize: minSize) }
        let levels = imgLaps[0].count

        // Blend each level: L_blend = Σ_k maskGauss_k · imgLap_k.
        var blended = [PixelImage]()
        for lvl in 0..<levels {
            let lw = imgLaps[0][lvl].width, lh = imgLaps[0][lvl].height
            var px = [SIMD3<Float>](repeating: .zero, count: lw * lh)
            for k in 0..<m {
                let lap = imgLaps[k][lvl].pixels, mask = maskGaus[k][lvl].pixels
                for j in 0..<px.count { px[j] += lap[j] * mask[j] }
            }
            blended.append(PixelImage(width: lw, height: lh, pixels: px))
        }
        return ImagePyramid.collapse(blended)
    }
}
```

- [ ] **Step 4: Run the file + full suite** — `swift test --filter LaplacianPyramidBlendTests` → PASS; `swift test` → ALL green (70 prior + new).
- [ ] **Step 5: Commit** — `git add Packages/StackEngineCore && git commit -m "feat(core): LaplacianPyramidBlend (Burt-Adelson multiband composite)"`

---

## Self-review

**1. Spec coverage (§3.5):** Gaussian/Laplacian pyramids → `ImagePyramid`; multiband composite of frames by Gaussian-pyramid weight masks → `LaplacianPyramidBlend`. This closes the core DoF algorithm (align → sharpness → select → blend); Phase 4 (`FocusStacker`) wires the stages end-to-end with `DepthConfig`.

**2. Placeholder scan:** every step has complete code; no TBD.

**3. Type consistency:** `ImagePyramid.reduce(_:)` / `expand(_:toWidth:toHeight:)` / `gaussian(_:minSize:)` / `laplacian(_:minSize:)` / `collapse(_:)`; `LaplacianPyramidBlend.blend(images:weights:minSize:)`. Mask pyramids and image pyramids share identical level dimensions (same input size + same `minSize`), so the per-level element-wise multiply is valid. `Metrics.maxAbsDiff` used in tests.

---

## Definition of done

- `cd Packages/StackEngineCore && swift test` → all green (70 prior + new).
- reduce halves dims; reduce/expand of a constant are exact (no border darkening); collapse(laplacian(constant)) == constant; full-weight blend returns that frame; a per-half selection blend is sharper than either input.
- Core DoF algorithm complete; ready for Phase 4 (`FocusStacker` + `DepthConfig`).
