# Golden Corpus + SSIM/ΔE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps.

**Goal:** SSIM + ΔE metrics; always-on golden-image tests pinning every look's full-pipeline output (PSNR ≥ 45, SSIM ≥ 0.98, ΔE ≤ 1.0) against committed reference PNGs. Spec: `docs/superpowers/specs/2026-06-11-golden-corpus-design.md`. Branch `feat/golden-corpus`. Engine-only.

### Task 1: Metrics — SSIM + meanDeltaE

**Files:** Modify `Packages/StackEngineCore/Sources/StackEngineCore/Metrics.swift`; Test `Tests/StackEngineCoreTests/MetricsTests.swift`.

- [ ] Failing tests (append; READ the file's helpers first):
```swift
    func testSSIMIdentityIsOneAndNoiseLowers() {
        let img = gradientImage(64, 48)          // ADAPT: build a deterministic textured PixelImage
        XCTAssertEqual(Metrics.ssim(img, img), 1.0, accuracy: 1e-9)
        var noisy = img
        var seed: UInt32 = 1
        for i in 0..<noisy.pixels.count {        // seeded LCG — deterministic noise
            seed = seed &* 1664525 &+ 1013904223
            let n = (Float(seed >> 16 & 0x7FFF) / 32767 - 0.5) * 0.05
            noisy.pixels[i] += SIMD3(repeating: n)
        }
        let s = Metrics.ssim(img, noisy)
        XCTAssertLessThan(s, 0.9999)
        XCTAssertGreaterThan(s, 0.85, "mild noise must not crater SSIM")
    }

    func testDeltaECatchesAHueShiftPSNRBarelySees() {
        let img = gradientImage(64, 48)
        var shifted = img
        for i in 0..<shifted.pixels.count { shifted.pixels[i].x *= 1.06 }   // 6% red gain
        XCTAssertEqual(Metrics.meanDeltaE(img, img), 0, accuracy: 1e-9)
        XCTAssertGreaterThan(Metrics.meanDeltaE(img, shifted), 0.8, "a visible color cast must register")
    }
```
- [ ] Implement: SSIM per spec §2 (8×8 box windows, stride 4, K1=0.01/K2=0.03/L=1, Rec.709 luma via `Luma.rec709`/`Luma.luminance` — reuse, don't duplicate); `meanDeltaE` linear-sRGB→XYZ(D65)→Lab→ΔE76 with the matrices/constants in comments (pin them for Android: sRGB D65 matrix rows + Lab f(t) with δ=6/29). Pure functions, `public`, no new imports beyond Foundation/simd.
- [ ] `swift test --filter MetricsTests` then full suite. Commit: `feat(core): SSIM + mean ΔE76 metrics (golden-corpus tolerances)`.

### Task 2: Corpus fixtures + goldens + GoldenCorpusTests

**Files:** Create `Tests/StackEngineCoreTests/GoldenCorpusTests.swift`; Create `Tests/StackEngineCoreTests/Resources/golden/*.png` (5 files, generated); Modify nothing in Sources.

- [ ] Fixture generators (file-scope in GoldenCorpusTests.swift, fully deterministic — seeded LCG, no Date/random):
  - `goldenBurst() -> [RawSensorFrame]`: 6 frames, 96×64 RGGB mosaics of a textured scene (ramp + two sine octaves), per-frame integer translation jitter ((0,0),(1,0),(-1,1),(2,-1),(0,2),(-2,0)) and seeded noise amplitude ±8 on a 200-800 mosaic range, blackLevel 64 whiteLevel 1024, wbGains 1. (Render the scene in float, shift per frame, then mosaic-sample per CFA position — keep the generator ~40 lines and heavily commented: it is the cross-platform input contract.)
  - `goldenBrackets() -> [PixelImage]`: reuse `chainBracketFrames(w: 96, h: 64, steps: [two small varied similarity steps])` from the existing chain fixture.
- [ ] `GoldenCorpusTests` — for each case: noiseReduction/smoothMotion/lightTrails/lowLightBoost via `Pipeline.reduce(goldenBurst(), mode:, binnedDevelop: false)`; depth via `FocusStacker.allInFocus(goldenBrackets(), config: DepthConfig(workingResolution: nil, maxFrames: 12))`. Then:
```swift
    private static let minPSNR = 45.0, minSSIM = 0.98, maxDeltaE = 1.0   // the Android contract
```
  Compare result vs the bundled golden (decode via the same CGImage loader pattern as DepthBracketRegressionTests — extract that loader into a shared file-scope helper `loadPNG(url:)` if sensible) using `Metrics.psnr` (on encodeSRGB8 rgba bytes), `Metrics.ssim`, `Metrics.meanDeltaE` (golden decoded → linearized via OutputTransform.decodeSRGB8).
  **Regeneration path** (header-documented): when `ProcessInfo.processInfo.environment["SSS_REGENERATE_GOLDENS"] == "1"`, write each result as PNG to `/tmp/sss-goldens/` (reuse the save helper pattern from _DebugRealFrames), print the copy command, and `throw XCTSkip("goldens regenerated — copy into Resources/golden and re-run")` BEFORE any comparison.
  **Missing golden = XCTFail** (not skip) — same rule as the bracket suite.
- [ ] Authoring flow (the implementer DOES this): run with SSS_REGENERATE_GOLDENS=1 → copy the 5 PNGs into `Tests/StackEngineCoreTests/Resources/golden/` → run WITHOUT the env (full suite) → all 5 cases pass against their own goldens (sanity: PSNR ∞/clamped, SSIM ≈ 1, ΔE ≈ 0 — the value of the suite is catching FUTURE drift). Verify Package.swift's existing `.copy("Resources")` picks the new subfolder (it copies the tree — confirm via Bundle.module lookup in the test).
- [ ] Sanity mutation: temporarily change `StackReducer.defaultLowLightGain` (or kappa) → lowLightBoost golden FAILS → revert, pass. Report the observed failure.
- [ ] Docs: delta TL;DR #5 → done-with-scope (synthetic corpus + real-bracket heavy tier; perf benchmarks + CIEDE2000 remain); bible §18 status blockquote.
- [ ] Full engine suite + commit: `test(core): golden corpus — synthetic Bayer sequences + per-look reference images with PSNR/SSIM/ΔE gates` then docs commit.

## Self-review: §2→T1, §3→T2 (both tiers, tolerances, regen flow, fail-on-missing), §5 docs→T2. Loader reuse honored; no Sources changes in T2.
