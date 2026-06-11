# Capture Safeguards: Thermal / Battery / Storage + the Non-RAW Fallback

**Status:** Approved design (autonomous run, recommended options per David's directive). Delta TL;DR #4; bible §5.3, §10.2, §16, §17.

## 1. Scope

| §17/§16 item | This round | Shape |
|---|---|---|
| Thermal monitoring | ✅ | `.serious` → warn + halve burst frame counts; `.critical` → block new shots ("Too hot — let the phone cool down."). Existing captures finish. |
| Low battery | ✅ | < 10% (not charging) → persistent status warning ("Low battery"). No blocking (a stack doesn't endanger data). |
| Storage pre-flight | ✅ | < 200 MB free (importantUsage) → block the shutter with "Not enough storage to capture." |
| No-RAW fallback (§10.2) | ✅ | Devices without Bayer RAW capture processed HEIC instead — "Standard quality": decoded at working resolution, aligned + reduced through the existing image pipeline. UI shows a "Standard quality" tag. |
| Metering guidance (low light / overexposure) | ❌ deferred | Needs live AE sampling infrastructure; recorded as the remaining §17 gap in the delta. |

## 2. Environment providers (testable policy)

`CaptureEnvironment` (new, app): a value of closures the coordinator consults — `thermalState: () -> ProcessInfo.ThermalState`, `batteryLevel: () -> Float` (-1 = unknown), `batteryCharging: () -> Bool`, `freeDiskBytes: () -> Int64`. Default = real system APIs (`ProcessInfo.processInfo.thermalState`, `UIDevice` battery with monitoring enabled lazily, `volumeAvailableCapacityForImportantUsage`). Injectable in tests like `photosExporter`/`encodeImage`.

Coordinator policy (checked at shutter press, published for the UI):
- `environmentNote: String?` — "Low battery" / "Device is warm — shorter bursts" (serious), recomputed before each shot and on a 30 s timer while the capture tab is visible? **Lean: recomputed at shutter press and on `thermalStateDidChange`/battery notifications only.**
- `shoot()` guards: critical thermal → `lastError = "Too hot — let the phone cool down."`; low storage → `lastError = "Not enough storage to capture."`; both return before capturing.
- `makeRecipe` halving: when thermal ≥ serious, `frameCount = max(2, frameCount / 2)` for all looks (and sweep steps follow frameCount as established).

## 3. Non-RAW fallback

- **Capture:** `CapturedBurst` enum — `.raw([RawSensorFrame])` / `.developed([PixelImage])`. `CaptureService.captureBurst` returns it (protocol change; fake returns `.raw` as today). In `AVCaptureService`, when no supported Bayer format exists (the current `noRawFormat` throw site): capture **HEIC** (`AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.hevc])`) per frame through the SAME sequential state machine (settings construction is the only branch); the delegate converts via `photo.fileDataRepresentation()` → `ImageDecoder.rgba8(maxPixel: fallbackDecodeLongEdge = 2400)` → inverse-sRGB to linear (`OutputTransform.decodeSRGB8`) → `PixelImage`, off the capture path on `processingQueue` as today. Exposure/focus/WB locks, pacing, watchdog, steadiness gating: unchanged.
- **Processing:** coordinator's `makeResult` takes `CapturedBurst`. `.developed` routes ALL looks through the images pipeline: long-exposure → `Pipeline.reduceImagesWithReference` (non-streaming — acceptable: frames are already capped at 2400 px from decode, ~15 × 26 MB ≈ 390 MB peak); static → same; depth → `FocusStacker.allInFocus(images:)` (exists). The reference endpoint works identically (`reduceImagesWithReference`).
- **Quality ceiling honesty:** capture view shows a small "Standard quality" tag when `!coordinator.supportsRAW` (probe exists since PR #31).
- **Memory:** fallback frames decode straight to ≤ 2400 px (never full 12 MP buffers ×N).

## 4. Error handling

| Situation | Response |
|---|---|
| Thermal critical mid-burst | Current burst finishes (watchdog/caps already bound it); only NEW shots are blocked. |
| Battery/thermal APIs unavailable (simulator) | Providers return nominal values; no warnings. |
| HEIC fallback photo fails to decode | That frame is skipped (same as a failed RAW conversion today). |
| Free-space probe fails | Treat as "enough" (never wrongly block). |

## 5. Testing

- Coordinator policy with injected environment: critical thermal blocks with the message; serious halves frame counts (recipe assertion via saved frameCount); low battery sets the note but doesn't block; low storage blocks; nominal environment unchanged behavior (existing tests stay green).
- `CapturedBurst.developed` path: a fake returning developed images → save succeeds for noise/smooth/depth, reference still stored for blendable looks.
- Engine untouched.
- Manual (device plan): warm-device behavior, real free-space block, fallback path needs a non-RAW device (note: David's iPhone has RAW — fallback stays compile+sim verified).
