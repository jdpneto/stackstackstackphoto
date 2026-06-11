# Stack Stack Stack Photography — Design Document ("The Bible")

- **Status:** Approved design — ready for implementation planning
- **Date:** 2026-06-04
- **Platforms:** Native iOS (Swift) + native Android (Kotlin)
- **Repository:** `git@github.com:jdpneto/stackstackstackphoto.git`
- **Document owner:** David Neto
- **Audience:** Every engineer, designer, and QA contributor on the project. This document is the single source of truth. Where code and this document disagree, raise it — do not silently diverge.

---

## 1. Overview & vision

**Stack Stack Stack Photography** is a capture-only, fully on-device computational photography app for iOS and Android. The user shoots a short, handheld burst through the app's own camera; the app auto-aligns the frames and stacks them into a result a single frame could never produce — clean low-noise images, all-in-focus shots, and handheld "long exposure" looks like silky water.

The product premise: **multi-frame capture + alignment + the right blend math = results that beat a single exposure**, achieved entirely on the phone, offline, with no account and no cloud.

The core technical promise — and the main marketing hook — is **"long exposure and pro-grade stacking, handheld, no tripod."** Robust on-device alignment is what makes that promise real.

---

## 2. Goals & non-goals

### 2.1 Goals
- Deliver four spec'd, deterministic, blend-based capabilities: **Noise reduction / detail**, **Depth of field**, and a **Long-exposure family** (smooth motion, short light trails, low-light boost), all built on a shared **auto-align** engine.
- A **tiered UX**: a one-tap **Auto** mode for everyone, sitting on top of a **Pro** mode with full manual control.
- **Capture-only**: the app drives the camera for every stack. There is no import path.
- Ingest **RAW (DNG/Bayer)** where the device supports it, plus **JPEG/HEIC**.
- All processing runs **on-device** (GPU / Neural Engine / SIMD). No backend, no accounts, works in airplane mode.
- **Identical results across platforms** for the same scene, guaranteed by a shared algorithm specification and a shared golden-image test corpus.
- Sessions capped at **≤60 seconds**, handheld-first.

### 2.2 Non-goals (this version)
- Importing externally-shot photos or sequences.
- Cloud processing, sync, accounts, or a social feed.
- Video capture or editing.
- True long-duration astrophotography (star trails) and long (minutes-long) traffic trails — **physically incompatible with handheld + 60s and explicitly cut**.
- **Motion effects** (action sequence / stroboscopic, subject motion blur, panning) — deferred to a future phase and **not specified in this document** (see §13.4 and §19).
- Desktop or web app.
- TIFF-16 / DNG export (export is JPEG/HEIC only in this version).
- Full-resolution re-stacking after capture (see §9.3 — a deliberate storage tradeoff).

---

## 3. Scope summary

| # | Capability | Looks in scope | Blend math | ML required |
|---|---|---|---|---|
| 1 | Noise reduction / detail | Clean, low-noise image | sigma-clipped mean | No |
| 2 | Depth of field | All-in-focus | sharpness-selection + pyramid blend | No |
| 3 | Long exposure | Smooth motion · short light trails · low-light boost | mean · lighten/max · robust accumulation | No |
| — | Auto-align | Underpins all of the above | pyramidal global + local registration | No |
| (future) | Motion effects | action sequence · subject blur · panning | (unspecified) | Yes (segmentation) |

Every spec'd capability is deterministic and golden-testable. No on-device ML/segmentation is required for this version.

---

## 4. Target users & UX tiers

- **Auto (default):** point, choose a look, shoot. The app meters the scene, chooses frame count / cadence / focus strategy, captures, aligns, stacks, and shows the result. Zero required settings.
- **Pro:** reveals a manual control tray — ISO, shutter, focus (and the near→far focus-sweep range for Depth of Field), white balance, frame count / duration, blend strength, RAW toggle, AE/AF locks, histogram, grid/level.

One product, progressive disclosure. The look-picker and live preview are identical in both tiers; Pro only adds controls.

---

## 5. Target platforms, minimums & capability gating

### 5.1 iOS
- **Minimum OS:** iOS 16.
- **Recommended hardware:** iPhone 11 / A13 Bionic and newer (Metal 3, Neural Engine, fast GPU, AVFoundation Bayer RAW). Graceful degradation on A12.
- **Capture:** AVFoundation (`AVCapturePhotoOutput` Bayer RAW; `AVCaptureDevice` manual `exposureMode`, `focusModeLocked(lensPosition:)`, `whiteBalance`).
- **Compute:** Metal (compute shaders) + Metal Performance Shaders, Accelerate/vImage for CPU paths.

### 5.2 Android
- **Minimum OS:** Android 13 (API 33).
- **Required capabilities:** Camera2 `INFO_SUPPORTED_HARDWARE_LEVEL` of `FULL` or `LEVEL_3`, `RAW` capability (DNG / `RAW_SENSOR`), manual sensor + manual focus (`LENS_FOCUS_DISTANCE`), Vulkan 1.1.
- **Capture:** CameraX where sufficient, dropping to Camera2 for RAW + manual focus control.
- **Compute:** Vulkan compute shaders (primary), with an optional OpenCV assist for feature detection. OpenGL ES 3.2 compute as a fallback.

> **Implementation status (2026-06-11).** The Android engine shipped as a 1:1 Kotlin/JVM port of the shared `StackEngineCore` algorithms (NOT the per-platform Vulkan stack of §7.4 — see the delta doc's deviation #3) and passes the §18 golden corpus bit-exactly against the iOS reference outputs. Min SDK 33 per §5.2; Camera2 RAW capture and the Compose app are the next phase (see 2026-06-11-android-port-design.md).

### 5.3 Capability gating (Android fragmentation is a first-class concern)
At launch the app probes device capabilities and adapts:

| Missing capability | Behavior |
|---|---|
| No RAW (`RAW_SENSOR`) | Fall back to YUV/HEIC frame stacking; reduced quality ceiling; UI notes "Standard quality." |
| No manual focus distance | **Depth of Field** mode is disabled with an explanation. |
| No manual exposure | Long-exposure looks fall back to AE-locked metering only; reduced control. |
| Weak GPU / low RAM | Reduce default frame counts and working resolution; widen thermal headroom. |

Capability detection results are cached and surfaced in Settings ("What your device supports").

---

## 6. Decision log (locked during brainstorming)

| Decision | Choice | Rationale |
|---|---|---|
| Positioning | Tiered Auto + Pro | Widest market with one product. |
| Source of frames | Capture-only (no import) | Focus the engineering on an excellent mode-aware camera. |
| Image data | RAW (DNG/Bayer) + JPEG/HEIC | Best stacking quality with graceful fallback. |
| Processing location | On-device only | Privacy, offline, zero infra/cost. |
| Stability posture | Handheld-first | "No tripod" is the headline; alignment carries it. |
| Session length | ≤60s short bursts | Bounds memory/thermal/storage; removes long-duration looks. |
| Export | JPEG + HEIC | Covers sharing; TIFF/DNG deferred. |
| Persistence | Result + low-res proxy stack | Light re-editing without storing the full stack. |
| Cross-platform engine | **Fully separate native stacks (no shared code)** | Maximally idiomatic per platform; divergence neutralized by a shared spec + golden tests. |
| Long-duration looks | Dropped (star trails, long trails) | Physically incompatible with handheld + 60s. |
| Motion effects | Deferred, unspecified | Needs ML segmentation; out of scope this version. |

---

## 7. System architecture

### 7.1 Strategy: fully separate native stacks (Approach B)

Two completely independent native apps. There is **no shared code**. Each app uses its platform's best-in-class tools. This was chosen for maximum platform-idiomatic quality and zero cross-language interop.

**The risk of Approach B is result divergence** — the same scene producing visibly different stacks on iPhone vs Android because the algorithms were implemented twice. The architecture neutralizes this with a **shared, authoritative, language-agnostic specification layer** plus a **shared golden-image test corpus** that both apps must match within tolerance in CI.

### 7.2 The shared authoritative layer (documents & assets — not code)
Lives in the repository, owned jointly, version-controlled, and treated as the contract both apps implement:

1. **Algorithm Specification** — this document's §10–§14, expanded with exact parameters, formulas, color spaces, edge handling, and pinned constants (e.g., demosaic coefficients). When an algorithm changes, it changes here first.
2. **Golden test corpus** — shared RAW input sequences + reference output images + per-metric tolerances. See §18.
3. **UX & design tokens** — shared screen flows, naming, and a tokens file (color, spacing, type, iconography) so both apps feel like one product.

### 7.3 iOS module map (Swift)
Each app mirrors the **same module boundaries** so the spec maps 1:1.

| Module | Responsibility | Primary tech |
|---|---|---|
| `CaptureKit` | Mode-aware capture recipes, Bayer RAW capture, live preview, sensor metadata | AVFoundation |
| `AlignEngine` | Pyramidal global + local registration, warp, de-ghost masks, reference selection | Metal compute + vImage |
| `StackEngine` | Demosaic, color pipeline, per-mode reducers/compositors | Metal compute + Accelerate |
| `ColorPipeline` | Linear RAW → working space → Display P3/sRGB output transform | Metal + Accelerate |
| `LibraryStore` | Stack records, proxy stacks, recipes, thumbnails | Core Data + file store |
| `Editor` | Non-destructive global adjustments + proxy blend preview | Metal |
| `AppUI` | Auto/Pro capture, processing, gallery, editor, settings, onboarding | SwiftUI |

### 7.4 Android module map (Kotlin) — same boundaries

| Module | Responsibility | Primary tech |
|---|---|---|
| `capture` | Same recipes, DNG capture, preview, metadata | CameraX / Camera2 |
| `align` | Same registration algorithms | Vulkan compute (± OpenCV) |
| `stack` | Same demosaic + reducers/compositors | Vulkan compute |
| `color` | Same color math | Vulkan compute |
| `library` | Stack records, proxy stacks, recipes, thumbnails | Room + file store |
| `editor` | Same editor | Vulkan compute |
| `ui` | Same flows | Jetpack Compose |

### 7.5 Cross-platform consistency strategy
- Pin a **single demosaic algorithm** and a **single set of color matrices / working space** in the spec; both apps implement exactly these.
- All blend math is defined as floating-point operations in **linear light** — no platform-specific shortcuts that alter pixel values.
- The golden corpus runs in **both** CI pipelines on every change to align/stack/color code; a divergence beyond tolerance fails the build.
- Numeric reference values for isolated steps (e.g., demosaic of a synthetic Bayer tile) are embedded in unit tests on both sides.

---

## 8. Data flow (end to end)

```
Live preview (processed YUV)
        │  user picks look, taps shutter
        ▼
Mode-aware capture recipe  ──►  buffered RAW frames + per-frame metadata
        │
        ▼
Reference-frame selection (sharpest, via luma proxy)
        │
        ▼
Per frame (streaming where possible):
  ColorPipeline: linearize → WB → demosaic → working space (linear)
        │
        ▼
  AlignEngine: estimate transform on luma proxy → apply warp to the
              demosaiced linear frame (never resample the raw mosaic)
        │
        ▼
StackEngine reducer/compositor for the chosen mode  (mean / max / accumulate / focus-select)
        │
        ▼
Output transform → Display P3 / sRGB → JPEG/HEIC
        │
        ▼
LibraryStore: result + low-res proxy stack + recipe + thumbnail + EXIF
        │
        ▼
Result/Editor → Save / Share / Export / Save-to-Photos
```

---

## 9. Data model & storage

### 9.1 The `Stack` record
```
Stack {
  id: UUID
  createdAt: timestamp
  mode: enum { noiseReduction, depthOfField, smoothMotion, lightTrails, lowLightBoost }
  lookDisplayName: string

  capture: {
    frameCount: int
    durationMs: int
    perFrame: [ { iso, exposureTimeNs, lensPosition, wbGains:{r,g,b}, timestamp } ]
    sensor: { model, cfaPattern, blackLevel, whiteLevel, colorMatrix, activeArea }
    rawUsed: bool
  }

  result: { path, format: jpeg|heic, width, height, bitDepth, colorSpace }
  proxy:  { path(s), scale: float (e.g. 0.25), frameCount: int }       // low-res aligned frames
  recipe: {
    modeParams: { ... per-mode (see appendix) ... }
    blendStrength: float            // α used at capture
    referenceFrameIndex: int
    edits: { exposureEV, contrast, wbTempTint, crop, straighten, tone }  // non-destructive
  }
  thumbnail: { path }
  exif: { ... output metadata ... }
}
```

### 9.2 Where bytes live
- **Result** (full-res JPEG/HEIC): app sandbox; optionally exported to the system photo library (user setting).
- **Proxy stack** (downscaled aligned frames, default scale 0.25) + **recipe** + **thumbnail**: app sandbox.
- **Metadata index**: Core Data (iOS) / Room (Android).
- **Original full-res RAW frames are NOT retained** by default (see §9.3).

> **Implementation status (2026-06-11).** The §9.1 record is implemented leanly: ISO/shutter from the first frame (locked-exposure bursts), result format, and EXIF/ICC in outputs shipped (see 2026-06-11-capture-metadata-design.md). Per-frame metadata arrays, sensor block, and the proxy field remain unimplemented (proxy superseded — see §9.3 note).

### 9.3 The proxy-only re-edit tradeoff (explicit)
Because full-res source frames are discarded after stacking:
- **Global adjustments** (exposure, contrast, WB, crop/straighten, tone) apply non-destructively to the **full-res result** and re-render on export.
- **Blend-strength / look re-tuning** can only be **previewed on the low-res proxy**. There is no way to produce a *full-res* image at a new blend strength without re-shooting.
- **Escape hatch (future, see §19):** an optional Pro toggle "Keep full stack for this shot" that retains full-res frames for later full-quality re-stacking, at a large storage cost. Off by default; not in this version.

This limitation must be communicated honestly in the editor UI ("Previewing on a draft — re-shoot for full quality at this strength").

> **Status (2026-06-11):** superseded for α — the app stores the aligned reference and applies blend strength at full quality (see 2026-06-11-blend-strength-design.md); the proxy-stack mechanism was not built.

---

## 10. Capture engine

### 10.1 Capture spine (shared by all modes)
- **Live preview:** a processed YUV stream for framing; not used for the stack.
- **Capture stream:** a buffered burst of **RAW (Bayer/DNG)** frames where supported, each with full metadata (ISO, exposure time, WB gains, black/white levels, CFA pattern, color matrix, lens focus position, timestamp).
- **Hard cap:** total session ≤ 60 seconds.
- **Stability meter:** real-time device-motion readout (gyroscope/accelerometer) shown during capture; warns on excessive motion.

### 10.2 RAW handling & fallback
- Prefer Bayer RAW / DNG. On devices without RAW, capture the highest-quality YUV/HEIC available and run the same pipeline minus demosaic (treat as already-demosaiced linearizable input), accepting a lower quality ceiling.

### 10.3 Memory & streaming strategy (critical at ≤60s)
RAW frames are large (~24 MB for a 12 MP 16-bit frame). To stay within a phone's memory/thermal budget:
- **Stream and accumulate on the fly** wherever the reducer is associative: running mean (smooth motion, low-light boost, denoise) and running max (light trails) never hold the full sequence in RAM.
- Modes that **must** retain frames — **Depth of Field** (needs all focus brackets simultaneously for the selection map) — cap frame count (default M ≤ 24) and may operate at managed resolution.
- Release each frame's buffer immediately after it is consumed by the reducer.

### 10.4 Mode-aware capture recipes

| Mode | Frames / duration | Sensor control | Notes |
|---|---|---|---|
| **Noise reduction** | N frames (Auto 5–20) | Fixed ISO/shutter/focus/WB; shortest viable shutter; fastest cadence | Auto picks N from estimated scene noise (higher ISO ⇒ more frames). |
| **Depth of field** | M frames (Auto computed; Pro sets) | **Focus sweep** near→far via `lensPosition` / `LENS_FOCUS_DISTANCE`; exposure & WB locked | Step size from DoF overlap so adjacent frames share in-focus zones; gated on manual-focus devices. |
| **Smooth motion** | continuous burst, duration d ≤ 60s | Exposure & WB locked; higher cadence = smoother | Streaming running mean. |
| **Short light trails** | continuous burst, duration d ≤ 60s | Exposure & WB locked; lower cadence acceptable | Streaming running max (lighten). |
| **Low-light boost** | continuous burst or N frames | Exposure & WB locked; longer per-frame shutter allowed | Robust accumulation; align removes handheld shake. |

Auto chooses sensible defaults per scene; Pro exposes every parameter (see appendix A).

---

## 11. Alignment engine (auto-align)

Coarse-to-fine registration of every frame to a chosen reference. This is the technology that makes handheld stacking and "no-tripod long exposure" work.

### 11.1 Reference-frame selection
Choose the **sharpest** frame as the geometric anchor: the frame maximizing the sum of Laplacian magnitude over a downsampled luminance image. For long-exposure modes, prefer a frame near the temporal middle among the sharpest candidates.

### 11.2 Algorithm
For each frame `F_i`:
1. **Luminance proxy:** use the Bayer green channel (or quick luma) for speed and accuracy. Build a Gaussian pyramid `L0..Lk` (default k = 4, each level ½ resolution).
2. **Global registration (coarse→fine):**
   - Initialize translation via **phase correlation** at the coarsest level.
   - Refine to an affine/homography model via **ECC (enhanced correlation coefficient) maximization**, OR **ORB features + RANSAC homography** (platform may choose either implementation as long as it matches golden tolerances). Propagate the transform up the pyramid, scaling translations ×2 per level.
3. **Local refinement (mode-gated):** tiled (default 16×16) translational refinement or dense optical flow to model parallax and non-rigid residuals. Produces a warp field. Enabled for denoise/DoF/low-light; lighter for smooth-motion/trails where small residuals are hidden by the blur.
4. **Warp:** apply the estimated transform to the **demosaiced linear RGB frame** (the output of the color pipeline, §12), resampling into the reference geometry with **bicubic** interpolation in linear light. Registration is *estimated* on the luma proxy in steps 1–3; the warp is *applied* only after demosaic, so the raw Bayer mosaic is never resampled.
5. **Confidence:** record inlier ratio / residual error. If below threshold (default: inlier ratio < 0.35), **drop the frame** from the stack.

### 11.3 Camera motion vs scene motion
The engine always removes **camera motion** (handheld shake). **Scene motion** is handled per mode:
- **Denoise / DoF:** reject it — de-ghosting (see §13).
- **Long exposure:** preserve and shape it — that motion *is* the effect.

### 11.4 Depth-of-field special case
Focus brackets exhibit **focus breathing** (magnification changes with focus distance). Alignment must include **scale** (affine/homography, not pure translation) plus local refinement so the selection map lines up.

---

## 12. Color / RAW pipeline

All stacking happens in **linear light**. Averaging or blending in gamma-encoded space shifts brightness and color and is forbidden.

### 12.1 Per-frame steps
1. **Decode** Bayer/DNG: read CFA pattern, black level `B`, white level `W`, as-shot WB gains, camera→XYZ color matrix.
2. **Linearize:** `x = clamp((raw − B) / (W − B), 0, 1)`.
3. **White balance:** apply per-channel gains.
4. **Demosaic:** a single pinned algorithm both platforms implement identically — **Malvar–He–Cutler (2004) 5×5 linear demosaic** (deterministic, fast, golden-testable). Higher-quality AHD/Menon is a future upgrade, changed in the spec first.
5. **Color transform:** camera-native → XYZ → **working space** = linear, wide-gamut (Rec.2020 primaries, linear). Keep everything in 16-bit half or 32-bit float.

### 12.2 Stacking
Performed in the linear working space (see §13).

### 12.3 Output transform
Working space → output gamut (**Display P3** preferred, **sRGB** fallback) → tone curve (gamma 2.2 with gentle highlight rolloff; optional filmic for low-light) → gamut map → encode 8/10-bit → **JPEG/HEIC** with EXIF + embedded ICC profile.

---

## 13. Algorithm specification per mode (the heart)

All reducers operate on aligned frames in linear working space.

### 13.1 Noise reduction / detail
- Capture N frames, fixed settings; align all to reference.
- **Per-pixel sigma-clipped mean:** compute mean μ and std σ across frames; iteratively reject samples with `|x − μ| > κσ` (default κ = 2), recompute; output the mean of survivors. If survivors < 3, fall back to the **median**.
  - **κ vs frame count (important):** a single outlier's maximum z-score is √(N−1), so at κ = 2 outlier rejection is only effective for **N ≥ 6**; at N ≤ 5 the result is a plain mean. Auto mode must therefore couple κ to the burst size — for small bursts (N ≤ 5) it should use κ ≤ 1.5 so de-ghosting actually engages. (A future option is MAD-based robust rejection, which behaves better at small N.)
- Noise reduces ~ `σ_single / √N_eff`.
- **Optional** mild edge-aware unsharp mask in linear to restore micro-contrast (default amount low).
- *Future:* sub-pixel shifts between frames enable drizzle/Bayer super-resolution for true added detail — not in this version.

### 13.2 Depth of field
- Capture M focus-bracketed frames; align with scale (focus-breathing) + local refinement.
- **Sharpness measure** per pixel per frame: modified Laplacian energy over a small window (default 5×5).
- **Selection map:** for each pixel choose the frame index with maximum sharpness; regularize the index map (guided filter or graph-cut) to remove noise and produce clean boundaries.
- **Compositing:** multiband (Laplacian-pyramid) blending across selection boundaries to avoid halos/seams → all-in-focus image.
- Store the selection/depth map in the recipe for the editor.
- **Edge case:** pixels never in focus within the bracket range take the best available frame and are flagged.

### 13.3 Long exposure (one engine, three reducers)
- Capture a continuous burst (exposure & WB locked), duration d ≤ 60s, total frames T (decimated if needed). Align all frames to the reference (this is what removes handheld shake).
- **Reducer over the aligned stream (streaming):**
  - **Smooth motion** → running **mean**: `μ_t = μ_{t−1} + (x_t − μ_{t−1}) / t`. Static content stays sharp; moving content (water, clouds, crowds) blurs smoothly.
  - **Short light trails** → running **max (lighten)** per channel: `o = max(o, x_t)`. Bright moving lights accumulate as streaks over a darker static base.
  - **Low-light boost** → robust **accumulation** (sigma-clipped running mean) plus exposure gain in the output transform; aligned to the static scene so there is no blur — a brighter, cleaner night shot, SNR ↑ ~`√T`.
- **Blend strength α** (editor): `out = α · reduced + (1 − α) · reference`, or equivalently an effective-frame-window control. Fixed at capture for the full-res result; the proxy lets the user explore α (see §9.3).
- Static foreground staying sharp while a moving element blurs is automatic with the mean reducer.

### 13.4 Motion effects — DEFERRED, NOT SPECIFIED
Action sequence (stroboscopic), subject motion blur, and panning require on-device **subject segmentation** and are **out of scope for this version**. They are listed in the roadmap (§19) as a future phase and will be specified in a separate document when scheduled. **Do not implement them from this document.**

---

## 14. Editor & non-destructive recipe

- **Global adjustments** on the full-res result, stored in `recipe.edits`, re-rendered on export: exposure (EV), contrast, white balance (temp/tint), crop, straighten, tone curve.
- **Blend-strength / look preview** on the low-res proxy stack only (see §9.3); clearly labeled as a draft.
- All edits are **non-destructive** — the original result and recipe are preserved; export bakes the recipe.

---

## 15. UX flows & screens

### 15.1 App structure
Three primary areas: **Capture** (default), **Gallery**, **Settings**.

### 15.2 Capture screen
- Full-bleed live preview.
- **Look-picker** carousel: *Detail* (noise reduction), *Depth* (focus stacking), *Smooth motion*, *Light trails*, *Night* (low-light boost).
- **Shutter:** tap for burst modes; tap-to-start / tap-to-stop (or press-and-hold) for duration modes, with a **≤60s progress ring**, frame counter, and live **stability meter**.
- **Top bar:** RAW toggle, flash, AE/AF locks (Pro), settings.
- **Pro toggle:** reveals the manual tray — ISO, shutter, focus (near→far sweep range in Depth mode), WB, frame count / duration, blend strength, histogram, grid/level.
- After capture → **Processing**.

### 15.3 Processing screen
Cancelable progress (align → stack → render) with a live thumbnail and ETA; runs on GPU/Neural Engine; UI stays responsive.

### 15.4 Result / Editor screen
Result preview with **Save / Share / Export / Save-to-Photos / Edit**. Edit exposes the §14 global adjustments + proxy blend preview.

### 15.5 Gallery
Look-tagged thumbnail grid; tap → result + metadata + edit/share/export/delete; filter by look.

### 15.6 Onboarding & Settings
- **Onboarding:** camera/photos permissions + a short look explainer with sample shots.
- **Settings:** default export format (JPEG/HEIC), save-to-Photos toggle, default RAW on/off, grid/level, max session length, storage management, "What your device supports" (capability report), replay onboarding, about.

> **Implementation status (2026-06-10).** Onboarding (permissions pre-prompt + look explainer with stylized cards) and Settings shipped with the Settings+Onboarding PR: default export format (JPEG/HEIC, wired capture-time), save-to-Photos (add-only auto-export), storage management, "What your device supports" (RAW + manual-focus/Depth), replay onboarding, about. Deliberately NOT shipped, awaiting their underlying features: **default RAW on/off** (blocked on the YUV/HEIC capture fallback, §10.2), **grid/level** (the overlay feature doesn't exist), **max session length** (superseded by the capture screen's burst sliders, hard-capped at 60 s).

---

## 16. Performance, memory & thermal budget

**Targets** (measured, not guaranteed; tracked as regression thresholds per device tier):
- A 20-frame, 12 MP **noise-reduction** stack completes in **≤ 5 s** on an A15 / Snapdragon 8 Gen 1-class device.
- A 60 s **smooth-motion** capture (streaming reducer, ~300 frames decimated) renders within **a few seconds** after capture ends.
- Peak working memory **< ~300 MB** via streaming reducers and prompt buffer release.

**Mechanisms:**
- On-the-fly accumulation (no full-stack RAM) for associative reducers.
- GPU compute for align/demosaic/stack; downscaled alignment pyramids.
- Capped frame counts and working resolution on weak devices.
- **Thermal monitoring:** iOS `ProcessInfo.thermalState`, Android thermal/`PowerManager` API → throttle cadence/frame count and warn before a sustained 60 s GPU-heavy capture; abort gracefully on `critical`.

---

## 17. Error handling & edge cases

| Situation | Detection | Response |
|---|---|---|
| Alignment failure (low texture / too much motion) | low inlier ratio / high residual on most frames | fall back to best single frame or fewer frames; "Couldn't align — hold steadier." |
| Device lacks RAW | capability probe | YUV/HEIC fallback; "Standard quality." |
| Device lacks manual focus | capability probe | disable Depth of Field with explanation. |
| Insufficient light / overexposure | metering | guidance + suggested settings. |
| Excess motion during long exposure | live stability meter | real-time hint; optionally extend/abort. |
| Interruption (call, backgrounded) mid-capture | lifecycle events | graceful cancel or save partial stack. |
| Storage full | pre-flight check | block capture with clear message. |
| Low battery / thermal critical | system APIs | reduce session length / warn / abort. |

> **Implementation status (2026-06-11).** Thermal throttle/abort, storage pre-flight, low-battery warning, and the no-RAW standard-quality fallback shipped (see 2026-06-11-capture-safeguards-design.md). Metering guidance (insufficient light / overexposure) remains open.

---

## 18. Testing strategy

> **Status (2026-06-11, feat/golden-corpus):** SSIM and ΔE76 metrics implemented (`Metrics.ssim`, `Metrics.meanDeltaE`). `GoldenCorpusTests` pins every look (noiseReduction / smoothMotion / lightTrails / lowLightBoost / depth) against committed reference PNGs in `Tests/StackEngineCoreTests/Resources/golden/` using PSNR ≥ 45 dB / SSIM ≥ 0.98 / ΔE ≤ 1.0 tolerances — the Android cross-platform contract. Corpus is Tier-1 synthetic (deterministic 96×64 Bayer bursts + focus brackets, seeded LCG). Regeneration: `SSS_REGENERATE_GOLDENS=1 swift test --filter GoldenCorpusTests`. Still open: Tier-2 real-bracket corpus, perf/thermal benchmarks, field scene suite, CIEDE2000 upgrade.

- **Unit tests** per module: alignment transforms, demosaic (with embedded numeric reference values on a synthetic Bayer tile), each reducer, color transforms.
- **Golden-image cross-platform corpus (the divergence guardrail):** shared RAW input sequences → run each platform's full pipeline → compare to reference outputs within per-metric tolerance:
  - **PSNR** ≥ threshold, **SSIM** ≥ threshold, mean **ΔE** ≤ threshold (exact values pinned in the spec/corpus).
  - Runs in **both** CI pipelines on every change to align/stack/color code; failure blocks merge.
- **Device-matrix testing:** a representative set of iPhones and Androids, RAW-capable and not.
- **Performance/thermal benchmarks:** time, peak memory, thermal state per device tier with regression thresholds.
- **Field scene suite** per look: moving water, clouds, night traffic (short trails), low light, focus-stacked macro.

---

## 19. Phased roadmap

- **Phase 0 — Foundations:** capture spine (RAW burst + metadata + preview), color/RAW pipeline, alignment engine, storage/library, **golden-test harness**, Auto-mode shell. Both platforms in parallel against the shared spec.
- **Phase 1 — Core looks:** Noise reduction, Low-light boost, Smooth motion, Short light trails (all reuse the align+reduce engine → fastest path to a shippable, delightful product). + Pro controls + global editor.
- **Phase 2 — Depth of field:** focus-sweep capture (manual focus), sharpness/selection map, pyramid compositing; gated on manual-focus devices.
- **Phase 3 — Future (separate specs):** **motion effects** (action sequence, subject blur, panning) + on-device segmentation; optional "keep full stack" power feature; sub-pixel super-resolution; TIFF-16/DNG export.

---

## 20. Risks & open questions

| Risk | Mitigation |
|---|---|
| Android RAW + manual-focus fragmentation | capability gating + documented fallbacks (§5.3). |
| Demosaic/color matching across platforms | pin one demosaic + color matrices; golden tests (§7.5, §18). |
| Handheld alignment quality over 60 s sequences | sharpest-reference strategy, robust per-frame rejection, real-world tuning. |
| Sustained-load thermals during 60 s GPU capture | thermal monitoring + throttle policy (§16). |
| Proxy-only re-edit expectations | honest editor UI + future "keep full stack" toggle (§9.3, §19). |
| Streaming reducers vs frame-retaining DoF memory | cap M, managed resolution, prompt buffer release (§10.3). |

---

## 21. Glossary

- **Stack:** a set of frames captured for one shot, and the combined result.
- **Reducer:** the per-pixel temporal operation combining aligned frames (mean / max / accumulate).
- **Align / registration:** geometrically matching frames to a reference to cancel camera motion.
- **De-ghosting:** rejecting scene-motion outliers so moving objects don't smear (denoise/DoF).
- **Demosaic:** reconstructing full RGB from the Bayer CFA mosaic.
- **CFA / Bayer:** the color filter array on the sensor.
- **Linear light:** image values proportional to scene radiance (gamma removed) — required for correct blending.
- **Working space:** the wide-gamut linear color space stacking runs in.
- **Proxy stack:** downscaled aligned frames kept for low-res re-tuning.
- **Recipe:** the parameters + non-destructive edits that regenerate a result.
- **Golden image:** a reference output both platforms must match within tolerance.
- **Focus breathing:** magnification change as focus distance changes.

---

## Appendix A — Default parameters (Auto)

| Parameter | Default | Pro range | Notes |
|---|---|---|---|
| Noise: frame count N | 5–20 (scene-adaptive) | 2–30 | more at high ISO |
| Noise: sigma-clip κ | 2.0 (κ ≤ 1.5 when N ≤ 5) | 1.5–3.0 | survivors < 3 ⇒ median; κ=2 only clips at N ≥ 6 |
| DoF: bracket count M | computed | 3–24 | from DoF overlap |
| DoF: sharpness window | 5×5 | 3×3–9×9 | modified Laplacian |
| Long-exp: duration d | scene/intent | 0.5–60 s | hard cap 60 s |
| Long-exp: target cadence | 24 fps (smooth) | sensor-limited | lower ok for trails |
| Blend strength α | 1.0 | 0.0–1.0 | mix with sharp reference |
| Alignment pyramid levels k | 4 | 3–5 | ½ per level |
| Local refine tile | 16×16 | 8–32 | translational/flow |
| Align inlier-ratio drop threshold | 0.35 | — | frame rejected below |
| Proxy scale | 0.25 | — | of full resolution |

## Appendix B — Color constants (pinned)
- Demosaic: **Malvar–He–Cutler (2004)** 5×5 linear; coefficients pinned in the golden corpus.
- Working space: **linear Rec.2020 primaries**, 16/32-bit float.
- Output: **Display P3** (preferred) / **sRGB** (fallback); tone gamma 2.2 + highlight rolloff.
- Pipeline order is normative: linearize → WB → demosaic → working space → **apply alignment warp** → **stack** → output transform → encode. (Alignment is *estimated* on a luma proxy earlier; only the warp lands here.)
