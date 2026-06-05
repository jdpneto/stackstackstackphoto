# Phase 2 DoF — Phase 2: SharpnessMap + SelectionMap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the focus-stacking selection stage: a per-pixel **sharpness** measure and a **selection map** that turns the per-frame sharpness into clean, edge-aware per-frame blend weights.

**Architecture:** Four pure-Swift units in `StackEngineCore`. `SharpnessMap` computes summed modified-Laplacian energy on luma. `BoxFilter` (separable window mean) backs a `GuidedFilter` (He et al. edge-preserving smoothing). `SelectionMap` normalizes per-frame sharpness into winner-biased soft weights, regularizes each with the guided filter (guide = reference luma), and renormalizes to sum to 1. Spec: `docs/superpowers/specs/2026-06-05-phase2-depth-of-field-design.md` §3.3–3.4.

**Tech Stack:** Swift, `simd`. Pure-CPU, golden-tested via `swift test`. Builds on `PixelImage` + `Luma`.

---

## File structure (this plan)

```
Packages/StackEngineCore/Sources/StackEngineCore/
  SharpnessMap.swift    # CREATE — modified-Laplacian focus measure
  BoxFilter.swift       # CREATE — separable window-mean (edge-clamped, count-normalized)
  GuidedFilter.swift    # CREATE — He-et-al. edge-preserving smoothing (uses BoxFilter)
  SelectionMap.swift    # CREATE — sharpness → soft per-frame weights, guided-filter regularized
Packages/StackEngineCore/Tests/StackEngineCoreTests/
  SharpnessMapTests.swift  # CREATE
  BoxFilterTests.swift     # CREATE
  GuidedFilterTests.swift  # CREATE
  SelectionMapTests.swift  # CREATE
```

Existing API used: `PixelImage` (`width`/`height`/`pixels`, `[x,y]`), `Luma.luminance(_:) -> [Float]`.

---

## Task 1: `SharpnessMap`

**Files:** Create `SharpnessMap.swift`, `SharpnessMapTests.swift`.

- [ ] **Step 1: Failing tests** — `SharpnessMapTests.swift`:
```swift
import XCTest
import simd
@testable import StackEngineCore

final class SharpnessMapTests: XCTestCase {
    func testUniformImageHasNearZeroSharpness() {
        let s = SharpnessMap.compute(PixelImage(width: 16, height: 16, fill: SIMD3<Float>(0.5, 0.5, 0.5)))
        XCTAssertLessThan(s.max() ?? 0, 1e-5)
    }

    func testSharpnessHigherInDetailedRegion() {
        // Left half: high-frequency checker (in focus). Right half: flat (no detail).
        let w = 32, h = 16
        var img = PixelImage(width: w, height: h, fill: SIMD3<Float>(0.5, 0.5, 0.5))
        for y in 0..<h { for x in 0..<(w / 2) {
            let v: Float = ((x + y) % 2 == 0) ? 0.9 : 0.1
            img[x, y] = SIMD3<Float>(v, v, v)
        }}
        let s = SharpnessMap.compute(img)
        func avg(_ x0: Int, _ x1: Int) -> Float {
            var sum: Float = 0; var n = 0
            for y in 4..<(h - 4) { for x in (x0 + 4)..<(x1 - 4) { sum += s[y * w + x]; n += 1 } }
            return sum / Float(n)
        }
        XCTAssertGreaterThan(avg(0, w / 2), avg(w / 2, w) * 5)   // detailed region much sharper
    }
}
```

- [ ] **Step 2: Run → FAIL** (`cannot find 'SharpnessMap'`): `cd Packages/StackEngineCore && swift test --filter SharpnessMapTests`

- [ ] **Step 3: Create `SharpnessMap.swift`:**
```swift
import simd

/// Per-pixel focus measure: summed modified-Laplacian energy over a (2·radius+1)² window of luma
/// (design §13.2). Higher = more in-focus. The basis for the focus-stacking selection map.
public enum SharpnessMap {
    public static func compute(_ img: PixelImage, radius: Int = 2) -> [Float] {
        compute(luma: Luma.luminance(img), width: img.width, height: img.height, radius: radius)
    }

    static func compute(luma l: [Float], width w: Int, height h: Int, radius: Int = 2) -> [Float] {
        @inline(__always) func at(_ x: Int, _ y: Int) -> Float {
            l[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        // Modified Laplacian per pixel: |2L − L(x−1) − L(x+1)| + |2L − L(y−1) − L(y+1)|.
        var ml = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                let lx = abs(2 * at(x, y) - at(x - 1, y) - at(x + 1, y))
                let ly = abs(2 * at(x, y) - at(x, y - 1) - at(x, y + 1))
                ml[y * w + x] = lx + ly
            }
        }
        // Sum over the window (edge-clamped).
        @inline(__always) func mlAt(_ x: Int, _ y: Int) -> Float {
            ml[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        var out = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                var s: Float = 0
                for dy in -radius...radius { for dx in -radius...radius { s += mlAt(x + dx, y + dy) } }
                out[y * w + x] = s
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `git add Packages/StackEngineCore && git commit -m "feat(core): SharpnessMap (modified-Laplacian focus measure)"`

---

## Task 2: `BoxFilter`

**Files:** Create `BoxFilter.swift`, `BoxFilterTests.swift`.

- [ ] **Step 1: Failing tests** — `BoxFilterTests.swift`:
```swift
import XCTest
@testable import StackEngineCore

final class BoxFilterTests: XCTestCase {
    func testMeanOfConstantIsConstant() {
        let out = BoxFilter.mean([Float](repeating: 0.3, count: 8 * 8), width: 8, height: 8, radius: 2)
        XCTAssertEqual(out.max()!, 0.3, accuracy: 1e-5)
        XCTAssertEqual(out.min()!, 0.3, accuracy: 1e-5)
    }

    func testMeanSpreadsAnImpulse() {
        var src = [Float](repeating: 0, count: 9 * 9); src[4 * 9 + 4] = 9
        let out = BoxFilter.mean(src, width: 9, height: 9, radius: 1)
        XCTAssertLessThan(out[4 * 9 + 4], 9)        // central value reduced
        XCTAssertGreaterThan(out[4 * 9 + 3], 0)     // neighbour raised
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Create `BoxFilter.swift`:**
```swift
/// Separable window mean (edge-clamped, normalized by the true in-image sample count at borders).
/// The smoothing primitive behind the guided filter.
enum BoxFilter {
    static func mean(_ src: [Float], width w: Int, height h: Int, radius r: Int) -> [Float] {
        var tmp = [Float](repeating: 0, count: w * h)   // horizontal pass
        for y in 0..<h {
            for x in 0..<w {
                var s: Float = 0; var n = 0
                for dx in -r...r { let xx = x + dx; if xx >= 0, xx < w { s += src[y * w + xx]; n += 1 } }
                tmp[y * w + x] = s / Float(n)
            }
        }
        var out = [Float](repeating: 0, count: w * h)   // vertical pass
        for y in 0..<h {
            for x in 0..<w {
                var s: Float = 0; var n = 0
                for dy in -r...r { let yy = y + dy; if yy >= 0, yy < h { s += tmp[yy * w + x]; n += 1 } }
                out[y * w + x] = s / Float(n)
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `git add Packages/StackEngineCore && git commit -m "feat(core): BoxFilter (separable window mean)"`

---

## Task 3: `GuidedFilter`

**Files:** Create `GuidedFilter.swift`, `GuidedFilterTests.swift`.

- [ ] **Step 1: Failing tests** — `GuidedFilterTests.swift`:
```swift
import XCTest
@testable import StackEngineCore

final class GuidedFilterTests: XCTestCase {
    func testConstantInputStaysConstant() {
        let guide = (0..<64).map { Float($0 % 8) / 8 }       // arbitrary guide
        let p = [Float](repeating: 0.5, count: 64)           // constant input
        let out = GuidedFilter.filter(input: p, guide: guide, width: 8, height: 8, radius: 2, eps: 1e-3)
        for v in out { XCTAssertEqual(v, 0.5, accuracy: 1e-3) }
    }

    func testPreservesAGuideEdge() {
        // Guide is a vertical step; input tracks it with deterministic noise. Output keeps the step.
        let w = 16, h = 8
        var I = [Float](repeating: 0, count: w * h), p = [Float](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w {
            let step: Float = x < w / 2 ? 0.2 : 0.8
            I[y * w + x] = step
            p[y * w + x] = step + (((x * 7 + y * 13) % 5 == 0) ? 0.05 : -0.03)
        }}
        let out = GuidedFilter.filter(input: p, guide: I, width: w, height: h, radius: 2, eps: 1e-4)
        let left = out[4 * w + (w / 2 - 1)], right = out[4 * w + (w / 2)]
        XCTAssertGreaterThan(right - left, 0.4)   // step preserved (not blurred away)
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Create `GuidedFilter.swift`:**
```swift
/// Edge-preserving smoothing of `input` guided by `guide` (He, Sun & Tang 2010). Output follows the
/// guide's edges while smoothing the input within flat regions. Used to regularize selection masks.
enum GuidedFilter {
    static func filter(input p: [Float], guide I: [Float], width w: Int, height h: Int,
                       radius r: Int, eps: Float) -> [Float] {
        let n = w * h
        let meanI = BoxFilter.mean(I, width: w, height: h, radius: r)
        let meanP = BoxFilter.mean(p, width: w, height: h, radius: r)
        var ip = [Float](repeating: 0, count: n), ii = [Float](repeating: 0, count: n)
        for i in 0..<n { ip[i] = I[i] * p[i]; ii[i] = I[i] * I[i] }
        let meanIp = BoxFilter.mean(ip, width: w, height: h, radius: r)
        let meanII = BoxFilter.mean(ii, width: w, height: h, radius: r)
        var a = [Float](repeating: 0, count: n), b = [Float](repeating: 0, count: n)
        for i in 0..<n {
            let varI = meanII[i] - meanI[i] * meanI[i]
            let covIp = meanIp[i] - meanI[i] * meanP[i]
            a[i] = covIp / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        let meanA = BoxFilter.mean(a, width: w, height: h, radius: r)
        let meanB = BoxFilter.mean(b, width: w, height: h, radius: r)
        var out = [Float](repeating: 0, count: n)
        for i in 0..<n { out[i] = meanA[i] * I[i] + meanB[i] }
        return out
    }
}
```

- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `git add Packages/StackEngineCore && git commit -m "feat(core): GuidedFilter (edge-preserving smoothing)"`

---

## Task 4: `SelectionMap`

**Files:** Create `SelectionMap.swift`, `SelectionMapTests.swift`.

- [ ] **Step 1: Failing tests** — `SelectionMapTests.swift`:
```swift
import XCTest
@testable import StackEngineCore

final class SelectionMapTests: XCTestCase {
    func testFavoursTheSharperFramePerRegion() {
        // Frame 0 sharp on the left half; frame 1 sharp on the right half.
        let w = 24, h = 12
        func sharp(left: Bool) -> [Float] {
            var s = [Float](repeating: 0, count: w * h)
            for y in 0..<h { for x in 0..<w { s[y * w + x] = ((x < w / 2) == left) ? 1.0 : 0.05 } }
            return s
        }
        let guide = [Float](repeating: 0.5, count: w * h)
        let weights = SelectionMap.weights(sharpness: [sharp(left: true), sharp(left: false)],
                                           guide: guide, width: w, height: h)
        let li = 6 * w + 4, ri = 6 * w + (w - 4)
        XCTAssertGreaterThan(weights[0][li], 0.7)   // left region → frame 0
        XCTAssertGreaterThan(weights[1][ri], 0.7)   // right region → frame 1
        XCTAssertEqual(weights[0][li] + weights[1][li], 1.0, accuracy: 1e-4)   // sums to 1
    }

    func testNoDetailGivesEqualWeights() {
        let w = 8, h = 8, flat = [Float](repeating: 0, count: w * h)
        let weights = SelectionMap.weights(sharpness: [flat, flat], guide: flat, width: w, height: h)
        XCTAssertEqual(weights[0][0], 0.5, accuracy: 1e-4)
        XCTAssertEqual(weights[1][0], 0.5, accuracy: 1e-4)
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Create `SelectionMap.swift`:**
```swift
/// Turns per-frame sharpness maps into per-frame blend weights for focus stacking (design §13.2):
/// each pixel favours its sharpest frame (winner-biased), the weights are guided-filter-regularized
/// against the reference luma for clean edge-aware boundaries, then renormalized to sum to 1.
public enum SelectionMap {
    public static func weights(sharpness: [[Float]], guide: [Float], width w: Int, height h: Int,
                               radius: Int = 4, eps: Float = 1e-4) -> [[Float]] {
        precondition(!sharpness.isEmpty, "need at least one frame")
        let m = sharpness.count, n = w * h

        // Raw soft weights: normalize across frames, biased to the winner by squaring the sharpness.
        var raw = Array(repeating: [Float](repeating: 0, count: n), count: m)
        for i in 0..<n {
            var sum: Float = 0
            for k in 0..<m { let s = sharpness[k][i]; let wk = s * s; raw[k][i] = wk; sum += wk }
            if sum > 0 { for k in 0..<m { raw[k][i] /= sum } }
            else { for k in 0..<m { raw[k][i] = 1 / Float(m) } }   // no detail anywhere → equal
        }

        // Regularize each mask against the guide, clamp ≥ 0, then renormalize so they sum to 1.
        var reg = raw.map { GuidedFilter.filter(input: $0, guide: guide, width: w, height: h,
                                                radius: radius, eps: eps) }
        for i in 0..<n {
            var sum: Float = 0
            for k in 0..<m { reg[k][i] = max(reg[k][i], 0); sum += reg[k][i] }
            if sum > 0 { for k in 0..<m { reg[k][i] /= sum } }
            else { for k in 0..<m { reg[k][i] = 1 / Float(m) } }
        }
        return reg
    }
}
```

- [ ] **Step 4: Run the file + full suite** — `swift test --filter SelectionMapTests` → PASS; `swift test` → ALL green (61 prior + new).
- [ ] **Step 5: Commit** — `git add Packages/StackEngineCore && git commit -m "feat(core): SelectionMap (guided-filter-regularized focus-stack weights)"`

---

## Self-review

**1. Spec coverage (§3.3–3.4):** modified-Laplacian sharpness → `SharpnessMap`; per-pixel argmax → soft winner-biased weights in `SelectionMap`; regularization (guided filter) → `GuidedFilter` (+ `BoxFilter`); per-frame weight masks summing to 1 → `SelectionMap` renormalize. The multiband compositing of these weights is Phase 3 (`LaplacianPyramidBlend`).

**2. Placeholder scan:** every step has complete code; no TBD.

**3. Type consistency:** `SharpnessMap.compute(_:radius:)` / `compute(luma:width:height:radius:)`; `BoxFilter.mean(_:width:height:radius:)`; `GuidedFilter.filter(input:guide:width:height:radius:eps:)`; `SelectionMap.weights(sharpness:guide:width:height:radius:eps:)` → `[[Float]]`. All operate on `[Float]` row-major buffers consistent with `Luma.luminance`.

---

## Definition of done

- `cd Packages/StackEngineCore && swift test` → all green (61 prior + 8 new).
- Sharpness higher in detailed regions; box mean correct; guided filter preserves guide edges; selection weights favour the sharper frame per region and sum to 1.
- Ready for Phase 3 (`LaplacianPyramidBlend`) which composites frames by these weights.
