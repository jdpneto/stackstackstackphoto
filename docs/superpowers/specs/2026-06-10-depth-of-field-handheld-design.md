# Depth of Field, Handheld — Chain Alignment + End-to-End Ship

**Status:** Approved design (brainstorm).
**Parent specs:** the bible (`2026-06-04-stack-stack-stack-photography-design.md` §11.4, §13.2) and the Phase 2 DoF design (`2026-06-05-phase2-depth-of-field-design.md`), whose engine half (build steps 1–4) shipped in PRs #9/#17/#18/#19. This spec supersedes the Phase 2 design's alignment strategy and tiering, and covers its unbuilt steps 5–6.

## 1. Goal

Ship the **Depth** (all-in-focus) look end-to-end, handheld: a focus-bracketed capture sweep, blur-robust frame alignment, and the "Depth" chip in the carousel — verified on a physical iPhone with a handheld close-subject + far-background scene.

## 2. Why DoF was postponed (post-mortem)

The Phase 2 engine work landed, but alignment failed on focus brackets and the look was shelved before capture/UI were built (`FocusStacker.swift` header, `DepthConfig.alignFrames` default-off). The root cause was **not** focus breathing itself — `AffineAligner` recovers scale fine. It is that the SSD cost compares intensities between frames whose **blur differs by design**: fitting a warp between a sharp and a defocused rendering of the same content lets the optimizer "explain" blur differences with a spurious scale/shift that smears detail. Alignment was demoted to opt-in translation-only as damage control. Focus breathing is *why* alignment is needed; the blur-variant cost is *why* it failed.

## 3. Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Alignment strategy | **Chain alignment**: estimate similarity between *adjacent* brackets, compose to the reference | Adjacent brackets have nearly identical blur (focus moved one step), so the existing SSD fit is well-conditioned exactly where it's used. Standard practice in mature focus stackers. |
| Spurious-warp guard | **Physics bounds per link** + translation-only fallback | Focus breathing is a small, monotonic magnification change; an out-of-bounds link is implausible and degrades to a warp that cannot smear. |
| Handheld posture | **Handheld is the acceptance bar**, steadiness-gated sweep | The product promise; the steadiness gate keeps per-step motion inside the chain's translation budget. |
| Resolution tier | **12 MP capture cap (existing), single managed working resolution (1500 px), no full-res tier** | A 48 MP RAW run hit the ~3 GB iOS app memory limit. `DepthConfig.pro` (full-res) is deleted. |
| Pro controls for Depth | **Near/Far sweep-range sliders** + existing ISO/shutter/frame-count | More control over the sweep, not more resolution. |
| Warp interpolation | Keep **bilinear** (engine-wide today) | Bicubic is an engine-wide refinement (bible §11.2), not snuck into this change. |
| Direct-to-reference refinement (hybrid) | **Deferred** | Only if device results show visible chain drift; YAGNI now. |

## 4. Engine (`StackEngineCore`)

### 4.1 `Transform2D.compose` + `inverse` (new)

2×3 affine composition and inverse (similarity transforms are invertible). Round-trip unit test: `t.compose(t.inverse) ≈ identity`.

### 4.2 `AffineAligner.alignChain(frames:referenceIndex:bounds:)` (new)

1. **Pairwise links:** for each adjacent pair *(k, k+1)* in sweep order, estimate a similarity transform with the existing `AffineAligner.estimate` on downscaled luma (existing estimate-small / scale-translation-up pattern).
2. **Validate each link** against a `ChainBounds` struct (defaults, tunable): per-step |scale − 1| ≤ 2 %, |rotation| ≤ 1°, |translation| ≤ 1.5 % of the long edge. The steadiness gate keeps real handheld per-step motion well inside these. An out-of-bounds link is re-estimated **translation-only** (`Alignment.estimateTranslation`) instead of accepting a spurious warp.
3. **Compose** links outward from the reference index to give each frame its warp-to-reference; warp with the existing bilinear `AffineAligner.warp`.

Frames must arrive in sweep order — capture guarantees it; the API documents it.

### 4.3 `FocusStacker` changes

- Replace the opt-in translation-only block with `alignChain`, **on by default**; rewrite the defensive header comment to document the chain rationale.
- Reference remains the sharpest frame (`ReferenceSelection.sharpestIndex`).
- Unchanged: sharpness → selection weights → Laplacian-pyramid blend; < 2 usable frames → return the sharpest frame.

### 4.4 `DepthConfig` changes

- **Delete the `.pro` full-res preset.**
- Single preset: `workingResolution: 1500`, `maxFrames: 10`; the Pro frame-count slider can raise brackets to 20.
- Why 1500 (vs the 2400 other looks use): DoF is the one mode that holds **all** frames plus Laplacian pyramids and weight masks simultaneously — ~700 MB peak at 1500 px for 10 brackets vs ~1.8 GB at 2400 px, which flirts with jetsam.
- `alignFrames` flips to default **true** and is kept (not removed): the device acceptance run (§8) uses `alignFrames: false` to capture the alignment-disabled comparison.

### 4.5 Engine tests (golden, deterministic)

Synthetic fixture: a known scene rendered as M brackets, each with band-dependent Gaussian blur **plus** a known per-step similarity (breathing scale + handheld jitter). Assert:
- (a) the chain recovers each frame's compound transform within tolerance;
- (b) an injected garbage link is caught by the bounds and degraded to translation-only;
- (c) end-to-end `FocusStacker` output is sharper in every band than any single input (all-in-focus oracle);
- (d) `Transform2D` compose/inverse round-trips.

## 5. Capture

### 5.1 `CaptureRecipe.FocusSweep` (new, optional)

`FocusSweep(near: Float, far: Float, steps: Int)` in normalized `lensPosition` space (0 = closest, 1 = infinity). Depth sets it (Auto default 0→1, steps = bracket count); all other looks leave it nil. Capture order is near→far and **frames stay in sweep order** (the chain depends on it).

### 5.2 `AVCaptureService` (device)

When the recipe has a sweep, the burst loop is **step-paced** instead of time-paced: per step — `setFocusModeLocked(lensPosition:)` → await the settle completion (same pattern as the Pro focus override, `AVCaptureService.swift:306`) → steadiness gate → capture one RAW frame. Exposure and WB locked once at sweep start (existing `lockExposureAndFocus`). Fits the sequential paced-burst architecture from PRs #24/#27 (no session-wedge risk). The 12 MP capture cap stays.

### 5.3 Steadiness gating

`MotionSteadiness` gating (currently long-exposure-only) is **enabled for Depth** — it bounds per-step handheld motion, which is what keeps the chain links inside `ChainBounds`.

### 5.4 Capability gating

`CaptureService` gains `supportsDepthOfField` (device: `isLockingFocusWithCustomLensPositionSupported`; fake: `true`). The UI disables the Depth chip with a one-line explanation when false.

### 5.5 `FakeCaptureService` (simulator)

For a sweep recipe, synthesize focus brackets: frame *k* sharp in depth-band *k*, Gaussian-blurred elsewhere, **plus a small per-frame similarity drift** (breathing + jitter) so the simulator exercises the chain aligner, not just the blend. Oracle: no single input is sharp in every band; the output must be.

## 6. App integration & UI

- **`StackMode.depthOfField`** — new case, fresh persisted raw value (existing libraries untouched), display name "Depth", not a streaming mode.
- **Routing:** the coordinator's background step routes `.depthOfField` → `FocusStacker.allInFocus(rawFrames:config:)`; all other modes stay on `Pipeline.reduce`. DoF working resolution comes from `DepthConfig` (1500), not the 2400 managed resolution.
- **Carousel:** "Depth" is the 5th chip; disabled with an explanation when unsupported.
- **Pro tray (Depth selected):** the single focus slider is replaced by **Near / Far** sweep-range sliders (0–1, defaults 0 and 1, clamped near < far, existing `optControl` pattern). ISO/shutter/frame-count apply to Depth as-is.
- **Tap-to-focus during Depth:** still meters exposure; lens position is owned by the sweep.
- **Result:** saves through the existing `LibraryStore` path and opens in the editor unchanged.

## 7. Error handling

| Situation | Response |
|---|---|
| Chain link out of bounds | Translation-only re-estimate for that link (engine, automatic). |
| < 2 usable frames | Return the sharpest single frame (existing FocusStacker behavior). |
| Capture step stalls | Existing per-frame watchdog skips the frame; the stack proceeds with the rest. |
| Device lacks manual focus | Depth chip disabled with explanation (`supportsDepthOfField`). |
| Moving scene | Static-scene assumption stands; de-ghosting deferred (Phase 2 decision). |
| Disappointing device results | `dumpFramesForDiagnostics` dumps the developed brackets for offline debugging. |

## 8. Testing & acceptance

- **Engine:** §4.5 golden tests.
- **App (simulator):** recipe test (Depth → sweep descriptor, steps follow the frame-count override); coordinator test (Depth routes to `FocusStacker`, saves a stack via the fake); UI test (select Depth chip → shoot → saved result).
- **Device (the acceptance bar):** handheld focus sweep of a close-subject + far-background scene on a physical iPhone (mobile-mcp workflow). Accept when the result is visibly all-in-focus with no smearing/ghosting and no jetsam. Also capture an alignment-disabled comparison to demonstrate the chain's contribution.

## 9. Process

Feature branch → TDD per task → `/code-review` before merge → device verification (established workflow).

## 10. Deferred (noted, not in this round)

- Hybrid refinement (chain init + blur-equalized direct-to-reference) — only if device results show visible chain drift.
- Moving-subject de-ghosting; selection/depth-map storage; Metal acceleration; graph-cut regularization; never-in-focus flagging (all carried over from Phase 2 deferrals).
- Bicubic warp (engine-wide refinement, bible §11.2).
