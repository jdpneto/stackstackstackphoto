# Android Engine Port (P1–P3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps.

**Goal:** `android/stackengine` — a pure Kotlin/JVM 1:1 port of `Packages/StackEngineCore`, proven by the ported unit suite and **golden-corpus parity against the same committed reference PNGs**. Spec: `docs/superpowers/specs/2026-06-11-android-port-design.md` §3/§5. Branch `feat/android-engine`.

**Ground truth:** the Swift sources + tests in `Packages/StackEngineCore/`. Implementers READ the Swift file(s) for each task and translate faithfully — same names, same constants, same doc-comment contracts (translate comments too; they carry the spec). The golden corpus header + `Metrics.swift` comments contain explicit Android-contract notes (7-digit literals, SSIM window/edge conventions, alpha-in-PSNR) — follow them to the letter.

## Translation rules (pinned)

1. **`PixelImage`**: `class PixelImage(val width: Int, val height: Int, val pixels: FloatArray /* size w*h*3, xyz interleaved */)` with `operator fun get(x: Int, y: Int): Vec3`, `operator fun set(x, y, v: Vec3)`, convenience ctors (fill / empty zero-fill), `copy()` for value semantics where Swift relied on struct copying (PORTING TRAP: Swift `var out = img` copies; Kotlin must `img.copy()` explicitly — every Swift mutation-of-copy site needs this).
2. **`Vec3`**: `data class Vec3(val x: Float, val y: Float, val z: Float)` with operators (+,-,*,scale), `min/max/clamp/dot` as needed. Allocation-light is nice but correctness first.
3. **Float math**: `kotlin.math` (`sin`, `sqrt`, `atan2`, `exp2` → `2f.pow()`, etc.). Keep Float precision (NOT Double) wherever Swift uses Float — accidental Double promotion changes results.
4. **`parallelMap`**: `fun <T, R> parallelMap(items: List<T>, transform: (T) -> R): List<R>` using `runBlocking(Dispatchers.Default) { items.mapIndexed { i, t -> async { i to transform(t) } }.awaitAll() ... }` writing into a pre-sized array slot-by-index (port the order-preservation contract comment). Engine API stays synchronous like Swift.
5. **Integer/UInt16**: Swift `UInt16` mosaic → Kotlin `ShortArray` with `(v.toInt() and 0xFFFF)` reads; document at `RawSensorFrame`.
6. **Errors**: Swift `precondition` → `require`/`check`; `throws CancellationError` → a dedicated `StackCancellationException` (do NOT reuse kotlinx `CancellationException` — coroutines machinery swallows it).
7. **Tests**: JUnit5 (kotlin-test), file-per-Swift-test-class, same test names camelCased, same tolerances VERBATIM. `XCTSkipIf` → JUnit `Assumptions.assumeFalse`. Resources: golden PNGs + depth-brackets copied to `android/stackengine/src/test/resources/golden/` and `.../depth-brackets/`; load via `javaClass.getResourceAsStream` + `javax.imageio.ImageIO` (decode to sRGB bytes — match the Swift loader: RGBA order, 0-255 → /255 linearize via the ported `OutputTransform.decodeSRGB8`).
8. **Module**: `android/` root Gradle project (wrapper 8.10.2 — the dist is already cached locally), `:stackengine` with `kotlin("jvm")` + `kotlinx-coroutines-core` + JUnit5. No Android plugin in this module. Java toolchain 17 (AGP-compatible later).

## Tasks

### Task A1: Scaffold + core types + first tests green
- `android/settings.gradle.kts` (root `stack-stack-stack-android`, include `:stackengine`), `gradle/wrapper` files pinned to 8.10.2 (generate via the cached dist: `~/.gradle/wrapper/dists/gradle-8.10.2-all/*/gradle-8.10.2/bin/gradle wrapper --gradle-version 8.10.2 --distribution-type all` from `android/`), `stackengine/build.gradle.kts` per rule 8.
- Port: `PixelImage`(+Vec3), `RawSensorFrame`, `Luma`, `BoxFilter`, `ImagePyramid`, `ParallelMap`, `Metrics`, `OutputTransform`, `ColorPipeline` — reading each Swift source in full first.
- Port tests: `PixelImageTests`, `RawSensorFrameTests`, `ImagePyramidTests`, `BoxFilterTests`, `MetricsTests`, `OutputTransformTests`, `ColorPipelineTests`.
- `cd android && ./gradlew :stackengine:test` green. Commit.

### Task A2: Geometry + alignment + reducers + pipeline
- Port: `Transform2D`, `Alignment`, `AffineAligner` (incl. `ChainBounds`, `alignChain`, `accumulateLinks` internal seam), `ReferenceSelection`, `StackMode`, `StackReducer`, `MotionComposite`, `Pipeline` (every public entry point: reduceImages/WithReference, reduceStreaming/WithReference, reduceImagesStreamingWithReference, developedFrames, noiseReduction*, alignedStack wrappers, constants).
- Port tests: `Transform2DTests`, `AlignmentTests`, `AffineAlignerTests`, `AffineAlignerChainTests` (incl. the algebraic accumulateLinks order tests — run the same up/down-sweep mutation check manually and report), `HandheldAlignmentTests`, `StackReducerTests`, `StackModeTests`, `PipelineTests`, `PipelineStreamingTests`.
- Full module test run green. Commit.

### Task A3: Focus stack + editor + GOLDEN PARITY (the gate)
- Port: `SharpnessMap`, `SelectionMap`, `GuidedFilter`, `LaplacianPyramidBlend`, `FocusStacker`, `DepthConfig`, `ImageAdjustments`, `ImageEditor`, `ImageGeometry`.
- Port tests: their seven test files + `GoldenCorpusTests` (same generators/LCG seeds/tolerance constants; regeneration path NOT ported — Kotlin must match the EXISTING PNGs, that's the whole point) + `DepthBracketRegressionTests` (env-gated `SSS_REAL_BRACKETS`, fixtures from resources).
- Acceptance: `./gradlew :stackengine:test` fully green INCLUDING all five golden cases; then `SSS_REAL_BRACKETS=1 ./gradlew :stackengine:test --tests '*DepthBracket*'` green. Report the golden metric values (PSNR/SSIM/ΔE per look) — they quantify Swift↔Kotlin drift and go in the PR body.
- If a golden case fails: that is the guardrail WORKING — debug the divergence (usual suspects: Double promotion, copy semantics, reduce/expand edge handling, LCG arithmetic overflow semantics — Kotlin Int overflow wraps like Swift &* only with explicit `*` on Int... verify UInt32 LCG uses Kotlin `UInt` or masked Long). Do NOT loosen tolerances.

### Task A4 (controller): /code-review pass on the branch, docs touch (bible §5.2/§7.4 amendment + delta Android section started), PR + merge.

## Self-review: rules 1–8 cover the known Swift↔Kotlin traps; A3's gate makes correctness objective; emulator/app work is explicitly OUT of this plan (P4–P6, next plan).
