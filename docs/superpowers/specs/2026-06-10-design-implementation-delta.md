# Design ↔ Implementation Delta — iOS app vs. the Bible

- **Date:** 2026-06-10
- **Design reference:** `2026-06-04-stack-stack-stack-photography-design.md` (section numbers below refer to it)
- **Scope:** the iOS app (`StackStackStack/`) + engine (`Packages/StackEngineCore/`). Android is entirely unstarted and is not itemized here.
- **Purpose:** the authoritative gap list for planning future implementation phases. When an item ships, remove it from here (or mark it done with the PR number).

---

## TL;DR — the big missing pieces

1. **Depth of Field — DONE (PR #30).** Shipped end-to-end: chain alignment (adjacent-bracket links + bounds), lensPosition focus sweep, Depth chip + Near/Far Pro controls, capability gating, real-bracket regression fixture.
2. **Settings + Onboarding — DONE (PR #31).** Third tab (save-to-Photos, JPEG/HEIC capture-time format, storage, capability report, replay intro, about) + first-launch onboarding. Still missing from §15.6 by design: RAW toggle, grid/level, max session length (see the bible's §15.6 status note).
3. **Blend strength — DONE (PR #32, as a full-quality edit).** Deviation: no proxy stack — the working-res aligned reference is stored (`<uuid>.ref.<ext>`) and α is an ImageAdjustments field applied at full resolution (lerp needs the endpoints, not the reducer). Re-tuning other capture params stays out of scope (§9.3).
4. **No capture-time safeguards:** no thermal monitoring (`ProcessInfo.thermalState`), no low-battery check, no storage-full pre-flight, no metering guidance, no YUV/HEIC fallback on non-RAW devices (capture just throws `CaptureError.noRawFormat`). (§5.3, §10.2, §16, §17)
5. **No golden-image corpus.** Unit tests are strong (engine + app), but the cross-platform divergence guardrail of §18 — shared RAW sequences, reference outputs, PSNR/SSIM/ΔE tolerances in CI — does not exist. SSIM and ΔE metrics are unimplemented (`Metrics.swift` has PSNR and maxAbsDiff only). This becomes load-bearing the day Android starts.
6. **The Stack record is minimal.** `StackRecord` persists `id, createdAt, mode, frameCount, resultFileName, updatedAt` — none of the §9.1 capture metadata (per-frame ISO/shutter/lensPosition/timestamps, sensor info, rawUsed), result metadata, recipe, or EXIF. Output JPEGs carry no EXIF or ICC profile.

---

## Status by design section

| Design section | Status | Summary |
|---|---|---|
| §4 Auto/Pro tiers | Partial | Pro tray exists (ISO/shutter/focus/frame count); Auto is fixed recipes, not scene-adaptive; WB/histogram/grid/level/blend-strength missing |
| §9 Data model & storage | Partial | Result + immutable original + aligned reference (`<uuid>.ref.<ext>`) + edits sidecar persisted; proxy stack (unused — α uses the working-res reference instead), recipe, capture metadata, EXIF, persisted thumbnails missing |
| §10 Capture engine | Partial | RAW burst, locks, pacing, 60s cap, steadiness gating done; no YUV fallback, no scene-adaptive Auto, no DoF sweep |
| §11 Alignment | Partial | Deterministic pyramid similarity (Hooke–Jeeves SSD) done; no local/tiled refinement, no inlier-based frame dropping, bilinear (not bicubic) warp |
| §12 Color pipeline | Partial | Linearize/WB/color-matrix done; demosaic is provisional bilinear (not the pinned Malvar–He–Cutler); output is sRGB-only, no P3/rolloff/filmic, no ICC/EXIF |
| §13.1 Noise reduction | Mostly done | Sigma-clipped mean + median fallback done; κ not coupled to N; no optional unsharp mask |
| §13.2 Depth of field | Done (PR #30) | Shipped end-to-end (engine, capture, UI, chain alignment, capability gating) |
| §13.3 Long exposure | Done (improved) | Mean / lighten / boosted-mean reducers + streaming accumulation done; light trails upgraded to motion-masked composite (deliberate deviation); α blend done (full-quality, stored reference) |
| §14 Editor | Mostly done | Non-destructive EV/contrast/WB/shadows/highlights/crop/straighten (+90° rotate) + blend-strength slider (full-quality lerp) done; no tone curve control |
| §15 UX screens | Mostly done | Capture + Gallery + detail/editor + Settings + Onboarding; processing/gallery chrome gaps remain |
| §16 Performance/thermal | Partial | Streaming reducers + managed working resolution (2400px) done; **engine is CPU/SIMD, not Metal**; no thermal/battery policy; no perf regression gates |
| §17 Error handling | Partial | Permission/no-RAW/no-frames/cancel handled; alignment-failure messaging, storage/thermal/battery/metering responses missing |
| §18 Testing | Partial | 24 engine + 10 app unit test files, simulator UI tests, device camera tests; golden corpus / SSIM / ΔE / perf benchmarks / field suite missing |

---

## Detailed gaps by area

### 1. Capture (§10, §15.2)

Missing:
- **YUV/HEIC fallback for non-RAW devices** (§10.2, §5.3) — `AVCaptureService.swift` throws `CaptureError.noRawFormat`; the spec'd "Standard quality" degraded path doesn't exist. Capability probing/caching ("What your device supports") also missing.
- **Scene-adaptive Auto recipes** (§10.4, App. A) — frame counts are fixed per look in `CaptureService.swift:33-42` (e.g. noise = 8 frames); the spec wants N picked from estimated scene noise / ISO.
- **Focus-sweep capture for DoF** (§10.4) — `ProControls.focus` is a single lens position; no near→far bracket range, no DoF-overlap step computation.
- **Pro tray items** (§15.2): manual **white balance**, **histogram**, **grid/level overlay**, **blend strength**, **RAW toggle**. Top bar **flash** and **settings** buttons.
- **Progress ring** for duration modes — time remaining is text-only (`CaptureView.swift:278`); the steadiness gauge took the ring's visual slot.
- **Insufficient-light / overexposure metering guidance** (§17).

Done & worth knowing: ≤60s hard cap (`BurstSettings`), per-look burst sliders (photos 2–30, duration 1–60s), live steadiness gauge **that also gates frame capture** (deviation — spec only asked for a warning meter), tap-to-focus + long-press AE/AF lock (shown as a banner, not a top-bar button), sequential paced RAW burst (deviation from "buffered burst", forced by the RAW pipeline stalling — see CLAUDE.md gotchas), per-frame watchdog + cancellation.

### 2. Alignment (§11)

Missing / divergent:
- **Local refinement** — no tiled 16×16 translational refinement or optical-flow warp field (§11.2 step 3). Global similarity only.
- **Frame dropping on low confidence** — no inlier-ratio/residual computation, no 0.35 drop threshold (§11.2 step 5, §17 row 1). A badly-aligned frame currently still pollutes the stack.
- **Bicubic warp** — `AffineAligner.warp` resamples bilinearly (§11.2 step 4 says bicubic in linear light).
- **Model class** — code estimates **similarity** (rotation+scale+translation), not affine/homography. Adequate for shake; will matter for DoF focus breathing + parallax.
- **Algorithm substitution (likely fine, but document it in the spec):** phase-correlation init + ECC/ORB+RANSAC were replaced by a deterministic coarse-to-fine Hooke–Jeeves SSD search (`AffineAligner.swift`). Deterministic and golden-testable, which the spec values — but §11.2 should be amended to bless it ("where code and this document disagree, raise it").
- **Reference selection** — sharpest-frame works (`ReferenceSelection.swift`), but the §11.1 temporal-middle preference for long-exposure modes is not implemented.
- **FocusStacker bracket alignment is translation-only** — §11.4 requires scale (focus breathing) + local refinement for DoF.

### 3. Color / output (§12, App. B)

- **Demosaic**: bilinear, explicitly marked "Provisional — replaced by Malvar–He–Cutler in a later plan" (`ColorPipeline.swift:23`). The pinned spec algorithm (App. B) is still TODO. There's also a fast 2×2-bin half-res demosaic used for the managed working resolution.
- **Output transform** (`OutputTransform.swift`): **sRGB only** — no Display P3, no gentle highlight rolloff, no optional filmic curve for low-light. Encoding uses the sRGB piecewise curve (~2.4 exponent) vs. the spec's "gamma 2.2 + rolloff"; pick one and pin it in the spec.
- **No ICC profile / EXIF embedded** in encoded JPEG/HEIC (§12.3, §9.1). `ImageEncoder` writes bare images.
- **Working space**: code applies the per-frame camera color matrix but never names/pins the working space; spec says linear Rec.2020. Verify and pin (affects golden corpus + Android parity).

### 4. Reducers (§13)

- **κ–N coupling missing** (§13.1, App. A): `sigmaClippedMean` always runs κ=2.0; the spec requires κ ≤ 1.5 when N ≤ 5 so de-ghosting engages on small bursts. `StackReducer.swift` even documents the problem; the Pipeline just never adapts κ.
- **Survivors<3 fallback** uses clipped mean, spec says **median** — small deviation, confirm intent.
- **Optional edge-aware unsharp mask** (§13.1) — not implemented.
- **Blend strength α** (§13.3) — no engine entry point, no UI, no persisted value.
- **Light trails deviation (improvement):** implemented as a motion-masked composite (clean mean where static, lighten where moving — `MotionComposite.swift`) instead of the spec's plain running max. Should be written back into §13.3.
- **Streaming** (§10.3): implemented for smoothMotion/lightTrails. Noise reduction and lowLightBoost hold the full stack (inherent — sigma-clipping isn't associative), consistent with the spec's caveats.

### 5. Depth of Field (§13.2) — engine done, product missing

Engine has: modified-Laplacian 5×5 sharpness, guided-filter-regularized selection map, Laplacian-pyramid blend, frame cap config (`DepthConfig`, M ≤ 24), all unit-tested. To ship the look you still need:
- `StackMode.depthOfField` case (remember: raw values are persisted keys),
- focus-sweep capture recipe (`lensPosition` stepping from DoF overlap; exposure/WB locked),
- scale-aware bracket alignment (focus breathing),
- "Depth" in the look picker + near→far Pro range control,
- never-in-focus flagging + selection map stored in the recipe,
- manual-focus capability gating (§5.3).

### 6. Data model, storage, export (§9, §14, §15.4)

- **`StackRecord` vs. §9.1 `Stack`:** missing `capture` (per-frame iso/exposureTimeNs/lensPosition/wbGains/timestamp; sensor model/CFA/levels/colorMatrix/activeArea; rawUsed), `result` metadata (format/bitDepth/colorSpace/dimensions), `proxy`, `recipe` (modeParams/blendStrength/referenceFrameIndex), `thumbnail` path, `exif`. Note `RawSensorFrame` itself never receives ISO/shutter/lens-position/timestamp from capture, so this metadata is dropped at the converter, not just at persistence.
- **Proxy stack** (§9.2, scale 0.25) — not persisted at all; blocks the whole §9.3 re-tuning story.
- **Thumbnails** — generated on demand in `GalleryView` (240px), not persisted per §9.1. Fine at small libraries; revisit at scale.
- **Index**: JSON file (`index.json`) instead of Core Data — a sensible deviation (atomic, self-healing, simpler); amend §7.3 rather than migrating.
- **Export**: Share sheet only. No explicit Export flow, no **save-to-Photos toggle/auto-export**, no **HEIC** path in practice (`ImageEncoder` supports `.heic` but everything hardcodes `.jpeg`), no EXIF/ICC in output.
- **Done:** immutable original (`<uuid>.orig.jpg`) + edits sidecar (`<uuid>.edits.json`) + re-render from original = the non-destructive §14 contract for global adjustments works.

### 7. Editor (§14)

- Implemented: exposure EV, contrast, temp/tint, shadows/highlights, crop aspect, straighten, persisted 90° rotate; preview rendered off-main on a 1200px draft.
- Missing: **tone curve** as a distinct control (shadows/highlights partially covers it), **blend-strength / look re-tuning on proxy** with the honest "draft — re-shoot for full quality" label.

### 8. UX screens (§15)

- **§15.1**: Capture ✓, Gallery ✓, **Settings ✗** (no tab, no screen, nothing).
- **§15.3 Processing**: background + cancelable + count ("Processing N…") ✓; **no stage labels (align→stack→render), no live thumbnail, no ETA**. (Note: the app deliberately processes in the background while the user lines up the next shot — a better model than the spec's blocking processing screen; amend §15.3, then decide how much of the chrome still applies.)
- **§15.5 Gallery**: grid + detail + rotate/share/edit/delete ✓; **no look badges, no filter-by-look, no metadata view** (frame count/mode/capture params are stored or storable but never displayed).
- **§15.6 Onboarding**: Done (Settings+Onboarding PR) — permissions pre-prompt + look explainer with stylized cards.
- **§15.6 Settings contents**: Done (Settings+Onboarding PR) — export format default (JPEG/HEIC, wired capture-time), save-to-Photos (add-only auto-export), storage management (count + bytes + delete-all), capability report (RAW + manual-focus/Depth), replay onboarding, about. Deliberately omitted: default RAW toggle, grid/level, max session length (see §15.6 status note in the bible).

### 9. Performance & thermal (§16)

- **Engine is pure-Swift CPU/SIMD, not Metal/MPS** (§7.3 says Metal compute). This is a deliberate architecture change for determinism + Android parity (see CLAUDE.md); code comments say "slow on CPU until Metal". The spec's §7.3/§16 should be amended, and a Metal/Accelerate acceleration phase planned if the §16 timing targets (20-frame 12MP denoise ≤ 5s) are to be met.
- Implemented levers: managed working resolution (2400px long edge), streaming reducers, serialized background stacking, shutter gating during processing.
- Missing: **thermal monitoring + throttle/abort policy**, **battery checks**, **measured perf targets / regression thresholds**.

### 10. Error handling (§17)

| §17 situation | Status |
|---|---|
| Alignment failure → fall back + "hold steadier" | Missing (no confidence metric, no messaging) |
| No RAW → standard-quality fallback | Missing (hard error instead) |
| No manual focus → disable Depth | N/A until Depth ships (then required) |
| Insufficient light / overexposure guidance | Missing |
| Excess motion during long exposure | Partial — live gauge + capture gating; no extend/abort flow |
| Interruption (call/background) mid-capture | Partial — cancellation tokens + per-frame watchdog; backgrounding behavior untested/undocumented |
| Storage full pre-flight | Missing |
| Low battery / thermal critical | Missing |

### 11. Testing (§18)

- Implemented: 24 engine unit-test files (alignment, demosaic/color, every reducer, focus stack, pyramids, editor, output transform, streaming, metrics), 10 app unit-test files, simulator UI flow tests, physical-device camera tests. PSNR used in synthetic pipeline tests.
- Missing: **golden-image corpus** (shared RAW inputs + reference outputs + pinned tolerances, run in CI — the §7.5/§18 cross-platform guardrail), **SSIM** and **ΔE** metrics, performance/thermal benchmarks with regression thresholds, field scene suite (water/clouds/night traffic/low light/macro).

---

## Deliberate deviations to write back into the Bible

The doc's own rule: "Where code and this document disagree, raise it — do not silently diverge." These look intentional and (mostly) better; amend the spec or consciously revert:

1. Light trails = motion-masked composite, not plain running max (§13.3).
2. Alignment = deterministic Hooke–Jeeves SSD similarity search, not phase-correlation + ECC/ORB (§11.2); warp bilinear not bicubic.
3. Engine = pure-Swift CPU/SIMD shared-core library (`StackEngineCore`), not per-platform Metal/Vulkan implementations (§7.1–7.4). This *reverses* the "no shared code" Approach B decision — Android is now planned to reuse the engine algorithms. Biggest single spec/reality divergence; the architecture sections need rewriting.
4. Library index = JSON file store, not Core Data (§7.3, §9.2).
5. Burst = sequential paced captures with steadiness gating, not a free-running buffered burst (§10.1) — hardware-forced.
6. Long-exposure frame caps (15) below spec cadence ambitions (§10.4, ~24fps) — jetsam-forced.
7. Processing happens in the background behind continued shooting rather than a modal processing screen (§15.3).
8. Survivors<3 fallback = clipped mean, not median (§13.1).
9. sRGB piecewise output curve vs. "gamma 2.2 + rolloff" (§12.3) — until P3/rolloff lands, pin whichever is intended.
10. Blend strength α = full-quality lerp against a stored aligned reference, not the §9.2 proxy-stack draft preview.

---

## Confirmed out of scope (no action)

Motion effects (§13.4), star trails / long traffic trails, import path, cloud/accounts, video, TIFF-16/DNG export, full-res re-stacking ("keep full stack" toggle — §19 Phase 3), desktop/web.

## Suggested sequencing for the remaining work

1. **Quality core:** Malvar–He–Cutler demosaic → P3 output + highlight rolloff + ICC/EXIF → κ–N coupling → alignment confidence + frame dropping. (Unblocks the quality ceiling and the golden corpus.)
2. **Golden corpus + SSIM/ΔE in CI** — cheap now, indispensable before Android.
3. **Depth of Field end-to-end** (the engine is already waiting).
4. **Settings + onboarding + capability gating + RAW fallback** (product completeness).
5. **Recipe/metadata persistence + EXIF + proxy stack + α blend** (the re-edit story).
6. **Thermal/battery/storage safeguards**, then **Metal acceleration** if §16 targets demand it.
