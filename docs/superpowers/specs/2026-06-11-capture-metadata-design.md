# Capture Metadata + EXIF/ICC in Outputs

**Status:** Approved design (autonomous run, recommended options). Delta TL;DR #6 (lean scope); bible §9.1/§12.3.

## 1. Scope (lean)

- **`CaptureInfo`** (app): `iso: Double?`, `shutterSeconds: Double?` — read from the FIRST frame's `AVCapturePhoto.metadata` EXIF dict (ISOSpeedRatings[0], ExposureTime). The first frame represents the locked-exposure burst (exposure/WB lock at burst start makes per-frame capture redundant — deviation from §9.1's full per-frame array, recorded).
- **`CapturedBurst` becomes a struct**: `{ payload: Payload (.raw/.developed), info: CaptureInfo? }` — the third and final shape change to the capture contract this run; fakes return `info: nil`.
- **`StackRecord`**: + `iso: Double?`, `shutterSeconds: Double?` (optional = back-compat, same pattern as `format`).
- **EXIF embedding**: `ImageEncoder.encode` gains `exif: ExifMetadata? = nil` (`ExifMetadata { iso, shutterSeconds, capturedAt }`) → writes kCGImagePropertyExifDictionary (ISOSpeedRatings, ExposureTime, DateTimeOriginal) + TIFF Software "Stack Stack Stack". Applied to the RESULT encode at capture (original shares the same bytes; edit re-renders keep it best-effort — re-encoded outputs lose EXIF this round, recorded as a known gap).
- **ICC**: encoded outputs must carry an sRGB profile — a TEST asserts the decoded output's color space; if ImageIO doesn't embed it automatically for our CGImage (sRGB colorspace), fix the encoder until the test passes.
- **NOT in scope** (recorded): per-frame metadata arrays, sensor model/active area, gallery metadata view (§15.5 chrome gap stands), EXIF preservation through edit re-renders.

## 2. Error handling

Missing/unparseable photo metadata → `info` nil → record fields nil → EXIF dict simply omits the missing keys. Never affects the save.

## 3. Testing

Encoder: EXIF round-trip (encode with metadata → CGImageSource properties show ISO/ExposureTime/Software) + ICC assertion (color space ≈ sRGB). Store: fields persist + legacy decode (missing keys → nil). Coordinator: a fake providing `info` lands on the record; the real extraction is device-verified (manual plan) since fakes have no EXIF.
