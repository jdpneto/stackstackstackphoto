# Blend Strength (α) as a Full-Quality Non-Destructive Edit

**Status:** Approved design (autonomous run; decisions taken with recommended options per David's 2026-06-10 directive). Implements the *intent* of bible §13.3's blend strength + §9.3's re-tuning, replacing the proxy-stack mechanism. Delta TL;DR #3.

## 1. Goal

Let the user re-tune a stack's look strength after capture — `out = α·reduced + (1−α)·reference` — as a normal, **full-quality**, non-destructive editor adjustment.

## 2. The key deviation (and why it's strictly better)

The bible (§9.2/§9.3) stores a low-res **proxy stack** and limits α re-tuning to a draft preview, because re-running the *reducer* at full resolution post-capture is out of scope. But α-blending doesn't need the reducer — it is a per-pixel lerp between two endpoints that both already exist at working resolution at capture time: the reduced result and the aligned reference frame. **Store the reference; drop the proxy stack entirely.**

Consequences: α becomes just another `ImageAdjustments` field (persisted in the existing edits sidecar, applied in the existing render path); previews and exports are full quality; no "draft — re-shoot" honesty label is needed; disk cost is one extra working-res image per stack (~0.4–1 MB) instead of N proxy frames. Re-tuning *other* capture parameters (κ, trails sensitivity) remains out of scope exactly as §9.3 already accepted. The delta doc and bible get a deviation note.

## 3. Engine (`StackEngineCore`)

- **`ImageAdjustments.blendStrength: Float = 1`** — 1 = full look (today's behavior). Back-compat decode default 1 (existing sidecars lack the key). `isIdentity`/`hasTonalAdjustments` untouched (blend is its own pass); add `hasBlend` (`blendStrength < 1`).
- **`ImageEditor.apply(_:to:reference:)`** — new overload; the existing `apply(_:to:)` forwards with `reference: nil`. When `adj.hasBlend`, `reference != nil`, and dimensions match: lerp FIRST, in linear light, before geometry/tonal (`p = α·img + (1−α)·ref`). Dimension mismatch or missing reference → skip the blend silently (defensive: never break rendering).
- **Pipeline returns the reference:**
  - `Pipeline.reduceStreamingWithReference(...) -> (result: PixelImage, reference: PixelImage)` — the streaming path already keeps its anchor (frame 0) alive for alignment; return it. Existing `reduceStreaming` becomes a wrapper discarding the reference.
  - `Pipeline.reduceImagesWithReference(...) -> (result: PixelImage, reference: PixelImage)` — static path; reference = the aligned stack's sharpest frame (`aligned[refIdx]`, which IS `imgs[refIdx]` unwarped). Existing `reduceImages` wraps it.
- Determinism unaffected; no new platform deps.

## 4. App

- **Capture:** `makeResult` also encodes the reference (same orientation bake — the reference MUST go through the same `ImageGeometry.rotated` as the result, or the lerp misaligns; same format/quality). Depth of Field produces NO reference (α is a long-exposure/noise concept; the slider is hidden for depth records). The HEIC→JPEG encode fallback applies to the pair atomically (both endpoints share one format).
- **`LibraryStore`:** `save(result:reference:format:mode:frameCount:)` (reference optional) writes `<uuid>.ref.<ext>`; `referenceData(for:)` reads it via the record's format; `delete`/`deleteAll`/`reconcileOrphans` learn the `.ref.` file.
- **`ResultRenderer.render`** gains `referenceJPEG: Data? = nil`: decodes it with the SAME `maxPixel` as the original (same source dims → same decoded dims), linearizes, passes to `ImageEditor.apply(_:to:reference:)`.
- **`EditorView`:** a "Blend" slider (0…1, step 0.05, default = saved `blendStrength`) in the existing controls list, shown only when the record has a reference (loaded once, off-main, alongside the original in the existing `openEditor` paths). Preview and save both pass the reference through `ResultRenderer`.
- **Back-compat:** records without a ref file (everything pre-this-PR, all depth records) → `referenceData` nil → no slider, render path unchanged.

## 5. Docs

Delta TL;DR #3 marked done with this PR + the deviation note ("proxy stack replaced by stored reference; α is full-quality"); bible §9.3 gets a one-line status note pointing at this spec; "Deliberate deviations" list in the delta gains the entry.

## 6. Error handling

| Situation | Response |
|---|---|
| Reference encode fails at capture | Save the stack WITHOUT a reference (never lose the shot; slider just won't appear). |
| Ref file missing/corrupt at edit | `referenceData` nil → slider hidden; existing render path untouched. |
| Dimension mismatch (defensive) | `ImageEditor` skips the blend. |

## 7. Testing

- **Engine:** adjustments decode back-compat (missing key → 1); lerp math (α=0 → reference, α=1 → untouched input, α=0.5 midpoint, linear-light); mismatch/missing-reference skip; `reduceImagesWithReference` returns the sharpest aligned frame; streaming variant returns the anchor; existing entry points unchanged (suite stays green).
- **App:** store round-trip (`.ref.` write/read/delete, both formats, reconcile keeps refs of live records); coordinator saves a reference for noise/smooth but NOT depth (fake capture); renderer α=0 output ≈ reference bytes' decode; editor slider hidden for a record without a ref (unit-level via store, UI smoke optional).
- **Manual (device plan):** visual blend sweep on a real Smooth/Trails stack.
