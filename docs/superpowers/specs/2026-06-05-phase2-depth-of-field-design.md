# Phase 2 — Depth of Field (Focus Stacking) Design

**Status:** Approved design (brainstorm). Implements the bible's §11.4 + §13.2 for v1.
**Parent spec:** `docs/superpowers/specs/2026-06-04-stack-stack-stack-photography-design.md` (the "bible").

## 1. Goal

Add an all-in-focus **Depth** look: capture a focus-bracketed burst, align for focus breathing, choose the sharpest source per pixel, and multiband-blend into a single image where the whole subject is sharp. iOS first, pure-Swift CPU engine, golden-tested, with a synthetic fake so the selection + blend are fully Simulator-testable.

## 2. Decisions (locked in brainstorm)

| Decision | Choice | Rationale |
|---|---|---|
| Algorithm scope | **Full bible spec**: scale-aware alignment + regularized selection map + multiband Laplacian-pyramid compositing | Quality bar; the blend is where visible quality lives. |
| Operating point | **Tiered**: Auto = managed working res (~1500 px long edge, M ≈ 10); Pro = full sensor res, M ≤ 24, opt-in "Max quality (slow)" | DoF retains all frames + pyramids; CPU/memory bound until Metal. Matches the tiered UX. |
| Alignment estimation | **Deterministic intensity (Lucas-Kanade / ECC)** similarity/affine on the luma pyramid; bicubic warp | Deterministic + golden-testable; reuses the pyramid scaffolding; focus breathing is mostly uniform scale, so no feature detector needed. |
| Scene motion | **Static-scene target**; moving-subject de-ghosting **deferred** | Focus stacking assumes a still scene (macro/product/landscape). |
| Device gating | **Disabled on devices without manual-focus control**, with an explanation | Per bible §6 / §16. |
| Selection/depth map storage | **Deferred** (YAGNI) | Nothing consumes it in v1; the all-in-focus JPEG flows through the existing editor unchanged. |
| Simulator testability | **Recipe-driven focus-bracket fake** (each frame sharp in a different depth band) | Mirrors the existing motion-sweep fake; makes selection + blend unit/UI-testable. |

## 3. Architecture & module boundaries

DoF is a focus-stacking pipeline, **not** a streaming reducer, so it gets its own orchestrator in `StackEngineCore` (pure-Swift CPU) alongside `Pipeline`. New units, each one job + a clean interface:

```
Packages/StackEngineCore/Sources/StackEngineCore/
  Transform2D.swift          # similarity/affine transform value + apply/compose/identity
  AffineAligner.swift        # deterministic intensity (LK/ECC) estimator on a luma pyramid; bicubic warp
  SharpnessMap.swift         # modified-Laplacian energy (5×5) on luma → per-frame Float map
  SelectionMap.swift         # per-pixel argmax → soft per-frame weight masks, guided-filter regularized
  LaplacianPyramidBlend.swift# Burt–Adelson multiband blend of N frames by N weight masks
  FocusStacker.swift         # orchestrates develop → align → sharpness → selection → blend
  DepthConfig.swift          # operating point: workingResolution cap, maxFrames M
```

### 3.1 `Transform2D`
A 2×3 affine (or 4-DOF similarity: scale `s`, rotation `θ`, translation `tx, ty`). Operations: `identity`, `apply(toPoint:)`, `compose`, and inverse. Used by the aligner to express the per-frame warp.

### 3.2 `AffineAligner`
- **Reference:** the sharpest frame (max total Laplacian over downscaled luma) — reuses bible §11.1.
- **Estimate:** coarse→fine on a luma Gaussian pyramid (default 4 levels). Initialize translation by the existing search at the coarsest level; refine a **similarity/affine** transform by iterative intensity optimization (Lucas-Kanade / ECC) per level, propagating up. Converges for focus-breathing (mostly scale + small translation).
- **Warp:** apply the estimated transform to the **demosaiced linear RGB** frame with **bicubic** resampling (the raw mosaic is never resampled). Edge-clamp out-of-bounds samples.
- **Confidence:** record residual/correlation; below threshold → caller drops the frame.

### 3.3 `SharpnessMap`
Per pixel, the **modified Laplacian** energy `ML(x,y) = |2L − L(x−s) − L(x+s)| + |2L − L(y−s) − L(y+s)|`, summed over a 5×5 window on the luma of the aligned frame → a non-negative `Float` map. Higher = more in-focus.

### 3.4 `SelectionMap`
- Per pixel, the frame index with maximum sharpness (argmax across the M maps).
- Convert to **soft per-frame weight masks** `W_k(x,y) ≥ 0`, `Σ_k W_k = 1` (e.g. normalized/softmax over sharpness, biased to the winner).
- **Regularize** each weight mask with a **guided filter** (guide = reference luma) so boundaries follow real edges and index noise is removed; renormalize. Output: M smooth weight masks.

### 3.5 `LaplacianPyramidBlend`
Burt–Adelson multiband blend: for each frame build a Laplacian pyramid of the image and a Gaussian pyramid of its weight mask; per level `L_blend = Σ_k G_k^level · L_k^level`; collapse → the all-in-focus image. Avoids halos/seams a hard selection would produce.

### 3.6 `FocusStacker`
`static func allInFocus(_ frames: [RawSensorFrame], config: DepthConfig) -> PixelImage` (and/or a developed-image overload). Steps: optionally downscale to the working resolution → develop each (ColorPipeline) → align each to the reference → sharpness → selection → multiband blend. Releases each frame's buffer once its pyramids are built.

## 4. Capture (focus sweep + fake)

- **`CaptureRecipe`** gains an optional **focus-sweep** descriptor (near→far `lensPosition` range + M steps; exposure & WB locked). DoF's recipe sets it; the time-paced burst is bypassed for DoF.
- **Device (`AVCaptureService`):** when the recipe is a focus sweep, step `lensPosition` across the M frames (locked exposure/WB), capturing one RAW frame per step. Device-only, compile-verified. Requires `isLockingFocusWithCustomLensPositionSupported`; otherwise DoF is gated off.
- **Simulator (`FakeCaptureService`):** branch on the recipe — for a focus sweep, synthesize **focus brackets**: frame `k` is sharp in depth band `k` (e.g. a horizontal/foreground band) and progressively blurred elsewhere. The correct all-in-focus result is sharp in **every** band, which no single input frame achieves — a strong, deterministic test oracle.

## 5. App integration

- **`StackMode.depthOfField`** ("Depth") — a 5th look in the carousel. The coordinator's processing step routes `.depthOfField` to `FocusStacker.allInFocus`, all other modes to `Pipeline.reduce`.
- **Recipe:** `.depthOfField` → focus-sweep recipe; M and working resolution from the tier.
- **Tiering:** Auto → `DepthConfig(workingResolution: managed ~1500px, maxFrames: ~10)`; Pro → a **"Max quality (full res)"** toggle (`workingResolution: full`, `maxFrames` up to 24, labeled slow) reusing the Pro panel.
- **Gating:** the capture layer exposes a `supportsDepthOfField` capability (device manual-focus probe; always true for the fake). When false, the Depth chip is disabled with a short explanation.
- **Result:** the all-in-focus JPEG saves to the Library and opens in the **existing non-destructive editor unchanged**.

## 6. Data flow

```
shutter (Depth)
  → focus-sweep burst: M RAW frames (lensPosition near→far; exposure/WB locked)   [device]  | focus-bracket fake [sim]
  → develop each (ColorPipeline → linear RGB)
  → AffineAligner: warp each frame to the sharpest reference (scale-aware, bicubic); drop low-confidence
  → SharpnessMap per frame (modified Laplacian)
  → SelectionMap: argmax → soft weight masks → guided-filter regularize
  → LaplacianPyramidBlend: multiband composite → all-in-focus PixelImage
  → OutputTransform.encodeSRGB8 → ImageEncoder JPEG
  → LibraryStore.save → editor
```

## 7. Error handling & edge cases

- **Low-confidence alignment** on a bracket → drop it; proceed with the remaining frames (bible §11.2).
- **Pixel never sharp** in any bracket → take the best-available frame (per-pixel flagging deferred).
- **< 2 usable frames** → return the single sharpest frame (no stacking).
- **Memory/thermals** → cap M + working res per tier; release each frame buffer after its pyramids are built; full-res Pro path is labeled slow.
- **No manual focus on device** → Depth gated off with explanation.
- **Moving scene** → ghosting possible; static-scene assumption, de-ghosting deferred.

## 8. Testing strategy

**Engine (golden/unit, deterministic, CPU):**
- `SharpnessMap`: a synthetic image with a sharp region and a blurred region → sharpness is higher in the sharp region.
- `SelectionMap`: two frames each sharp in one half → selection weights favour the correct frame per half; regularized masks sum to 1.
- `LaplacianPyramidBlend`: blend of two half-sharp frames is sharp in **both** halves (higher total Laplacian energy than either input).
- `AffineAligner`: warp a known image by a known similarity transform, estimate it back → recovered transform within tolerance; warp→align ≈ original (low residual / high PSNR).
- `FocusStacker` end-to-end on synthetic focus brackets → total sharpness of the result exceeds any single input frame's.

**App (Simulator):**
- Focus-bracket fake → coordinator DoF path produces a `.done` result + saved stack.
- UI: selecting the **Depth** look and shooting produces a stack (screenshot attachment).

**Device-only (compile-verified, flagged):** real `lensPosition` focus sweep, focus-breathing alignment on real magnification change, manual-focus capability gating.

## 9. Phased build (one spec, sequential TDD → /code-review → merge cycles)

1. **`Transform2D` + `AffineAligner`** — similarity/affine intensity alignment + bicubic warp (golden: recover a known transform).
2. **`SharpnessMap` + `SelectionMap`** — modified Laplacian + guided-filter-regularized soft weight masks.
3. **`LaplacianPyramidBlend`** — Burt–Adelson multiband blend.
4. **`FocusStacker` + `DepthConfig`** — end-to-end orchestration on synthetic brackets.
5. **Capture focus sweep + focus-bracket fake** — recipe descriptor, `AVCaptureService` `lensPosition` stepping (device), fake brackets (sim).
6. **"Depth" look + tiering + gating** — `StackMode.depthOfField`, coordinator routing, tiered `DepthConfig`, Pro full-res toggle, capability gating, UI.

## 10. Deferred (noted, not in v1)

- Moving-subject **de-ghosting** in DoF.
- **Selection/depth-map storage** + depth-based editor features (re-focus, depth-aware adjustments).
- **Metal** acceleration of the DoF pipeline (full-res Pro path is CPU-slow until then).
- Graph-cut selection regularization (guided filter is the v1 choice).
- Per-pixel "never in focus" flagging surfaced in the UI.
