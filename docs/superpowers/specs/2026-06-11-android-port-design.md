# Android Port — 1:1 Conversion of the iOS App

**Status:** Approved design (autonomous run per David's goal directive: "convert the app to Android, 1 to 1 as much as possible").
**Parents:** the bible (§5.2, §7.4 — amended by this port), the delta doc's deviation #3 (shared engine algorithms, not per-platform Metal/Vulkan), every 2026-06-* feature spec (all apply 1:1).

## 1. Strategy

The iOS codebase is the specification. The port mirrors it file-for-file where the platform allows:

- **`android/stackengine`** — pure Kotlin/JVM module (no Android dependencies), a 1:1 port of `Packages/StackEngineCore` (26 files, ~2k lines): same types, same algorithms, same constants, same doc-comment contracts. Tests run on the JVM (fast loop, like `swift test`).
- **`android/app`** — Jetpack Compose app (minSdk 33 per bible §5.2, target/compile 35), mirroring `StackStackStack/` component-for-component.
- **Parity proof:** the golden corpus IS the acceptance gate (bible §7.5/§18, built in PR #34 for exactly this day): the Kotlin engine must pass `GoldenCorpusTests` against the **same committed golden PNGs** with the same tolerances (PSNR ≥ 45 / SSIM ≥ 0.98 / ΔE ≤ 1.0), plus the ported unit suite (~146 tests) and the real-bracket heavy tier (same `SSS_REAL_BRACKETS` gate).

## 2. Toolchain & verification reality (probed on this machine)

JDK 21 (Temurin), Android SDK (platform 35, build-tools, NDK), cached Gradle 8.10.2, API 35 emulators. Therefore: engine = fully JVM-verified; app = built + emulator-verified (the emulator camera has no Bayer RAW, so it genuinely exercises the PR #33 non-RAW fallback — the FakeCaptureService stays for deterministic UI tests, mirroring the iOS simulator role). Real Android camera hardware: none available — `Camera2CaptureService` ships compile-verified with a device-test plan entry, the same treatment `AVCaptureService` had before phone access.

## 3. Engine mapping (Kotlin/JVM)

| iOS | Android | Notes |
|---|---|---|
| `PixelImage` (`[SIMD3<Float>]`) | `PixelImage` backed by flat `FloatArray` (xyz interleaved) with `get/set(x,y)` returning a small `Vec3` value class | Performance-equivalent; API mirrors. |
| simd ops | hand-rolled Vec3 math (inline) | Kotlin has no simd; scalar math is deterministic. |
| `parallelMap` (TaskGroup) | coroutines `parallelMap` over `Dispatchers.Default`, slot-indexed writes (order-preserving, same contract comment) | |
| Float math (`sin`, `pow`) | `kotlin.math` | libm last-ULP variance absorbed by golden tolerances — pinned in the corpus header (PR #34). |
| All 26 files | same names, same public surface, same constants | Including `ChainBounds`, `DepthConfig`, `Metrics.ssim`/`meanDeltaE` (7-digit literals copied verbatim per the Android-contract comments). |
| Engine tests + fixtures | ported 1:1; golden PNGs + `depth-brackets/*.jpg` copied into `src/test/resources` | PNG decode via `javax.imageio` (sRGB bytes — goldens carry only an sRGB chunk, per PR #34 review). JPEG bracket decode likewise. |

## 4. App mapping (Compose)

| iOS | Android | Notes |
|---|---|---|
| `CaptureService` protocol + `CapturedBurst`/`CaptureInfo` | interface + data classes, 1:1 | |
| `FakeCaptureService` | 1:1 (drives emulator/UI tests) | |
| `AVCaptureService` | `Camera2CaptureService`: Camera2 `RAW_SENSOR` sequential paced burst, same one-in-flight/watchdog/steadiness-gate state machine (single-thread executor ≈ stateQueue), JPEG fallback when no RAW capability | Compile-verified; emulator runs the fallback path. |
| `StackCaptureCoordinator` | `StackCaptureCoordinator` (ViewModel + StateFlow), serialized processing via a single-thread dispatcher chain, same snapshot-at-shutter semantics, same seams (`photosExporter`, `encodeImage`, `environment`) | |
| `LibraryStore`/`StackRecord` | 1:1 file store under `filesDir/Stacks`, `index.json` via kotlinx.serialization, same format-aware filenames/back-compat rules | JSON store is already the recorded deviation (#4). |
| `ImageEncoder`/`Decoder` | `Bitmap.compress` (JPEG/**HEIC** API 30+) + `BitmapFactory`; EXIF via `androidx.exifinterface`; sRGB via `Bitmap` colorspace | Same EXIF fields incl. Software tag. |
| `PhotoLibraryExporter` | MediaStore insert (no runtime permission needed for owned media on 29+) | Simpler than iOS; same fire-and-forget + non-blocking note. |
| `CaptureEnvironment` | `PowerManager.currentThermalStatus` (THERMAL_STATUS_SEVERE≈serious / _CRITICAL+), `BatteryManager`, `StatFs` | Same thresholds/policy. |
| `AppSettings` | thin `SharedPreferences` wrapper, same 3 keys + defaults | |
| `MotionSteadiness` | `SensorManager` rotation vector, same tolerance semantics | |
| SwiftUI screens | Compose: Capture / Gallery / Detail / Editor / Settings / Onboarding — same flows, same gating (supportsDepth/supportsRAW), same copy | Look cards/data 1:1. |

## 5. Phasing (each phase: plan → subagent implementation → review → merge)

- **P1 (PR: android engine, part 1):** Gradle scaffold (`android/` root project, wrapper 8.10.2) + engine core: PixelImage/Vec3, RawSensorFrame, Luma, BoxFilter, ImagePyramid, ColorPipeline, OutputTransform, Metrics, ParallelMap — with their ported tests.
- **P2 (same PR):** Transform2D, Alignment, AffineAligner (+chain/bounds), ReferenceSelection, StackReducer, MotionComposite, Pipeline (all entry points incl. WithReference/streaming/images-streaming) — ported tests incl. mutation-sensitive chain tests.
- **P3 (same PR, the gate):** FocusStacker family, ImageEditor/Adjustments/Geometry, **GoldenCorpus parity vs the SAME PNGs**, depth-brackets heavy tier. Merge when golden-green.
- **P4–P6 (PR: android app):** store/settings/encoder/environment → capture/coordinator → Compose UI + onboarding; emulator-verified end-to-end (fallback capture → stack → gallery → edit → settings).
- **P7:** docs — bible §5.2/§7.4 amendment note, delta doc Android section, device-test-plan additions (Android hardware items).

## 6. Honesty rules

Anything not verifiable here is labeled, not claimed: Camera2 RAW on real hardware, HEIC encode behavior across OEMs, MediaStore on physical devices. The emulator pass + JVM golden parity are the claims this port stands on.
