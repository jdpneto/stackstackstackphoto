# Manual Device Test Plan — overnight run (PRs #31–#35)

## Results — automated device pass, 2026-06-11 morning (Release build, fresh install)

| # | Test | Result |
|---|---|---|
| A1 | Fresh-install onboarding + real camera dialog | PASS — onboarding gates the root, NO system prompt until "Enable Camera"; Allow lands on a live viewfinder. **Finding 1:** landscape camera page clips the Enable Camera/Done buttons behind the page dots (portrait correct). **Finding 2 (cosmetic):** page-dot indicator overlaps the second text line. |
| A2 | Photos auto-export | PASS — contextual add-only prompt on first export; subsequent exports clean ("Saved ✓" with no failure note). Denied path NOT tested (needs Settings.app toggling) — for David. |
| A3 | HEIC end-to-end | PASS — record + original + reference all `.heic`; edit re-render stays HEIC; mixed JPEG/HEIC library coexists. |
| A4 | Storage numbers | PASS (1 stack = 2.1 MB, sane). Delete All intentionally NOT exercised — left for David. |
| B5 | Blend sweep | PASS — slider present on the Smooth/HEIC record, α≈0 visibly switches to the sharp reference, save persists `blendStrength: 0.1`, record stays HEIC. |
| B6 | Night gain-match | PASS (numeric) — reference/result mean-luma ratio **1.007** (unboosted would be ≈0.5). |
| B7 | No Blend on Depth | PASS. |
| C8-9,11 | Thermal / storage-full / low-battery | NOT testable on demand — opportunistic, for David. |
| C10 | Non-RAW fallback | Not testable on this hardware (has RAW) — capability rows correctly read "Supported". |
| D12 | EXIF/ICC | PASS — on-device HEIC carries ISO 400, 1/60s, correct DateTimeOriginal, Software tag, embedded sRGB; index.json records iso/shutter. |
| E13 | All-looks sweep | PASS — Detail, Smooth, Trails, Night, Depth all captured + saved on the Release build. |

**Open findings for a follow-up fix:** (1) onboarding camera-page buttons clipped in landscape; (2) page-dots/text overlap.
**Remaining for David:** Photos denied path, Delete All, opportunistic thermal/battery/storage-full, non-RAW hardware someday.


Draft for discussion. Everything below is simulator-green but needs (or strongly benefits from) a physical-device pass. Ordered by value. Device: David's iPhone (RAW + manual-focus capable), mobile-mcp workflow available.

## A. Settings + Onboarding (PR #31)

1. **Fresh-install onboarding with the REAL camera dialog** — delete the app, reinstall, launch. Expect: onboarding appears INSTEAD of the app; no system camera prompt until the "One thing first" page's *Enable Camera* button; granting lands on a working viewfinder. (This verifies the gated-root fix — the original bug was the prompt firing under the cover, device-only visible.)
2. **Photos auto-export round-trip** — Settings ▸ Save to Photos ON → shoot → first export triggers the Add-Only permission prompt → photo appears in Photos.app. Then the denied path: revoke in iOS Settings → shoot → in-app save still succeeds with "Saved ✓ · Photos export failed — check Settings ▸ Privacy".
3. **HEIC end-to-end** — Settings ▸ Format ▸ HEIC → shoot → Settings ▸ Storage shows the smaller footprint; share sheet exports a `.heic`; edit the shot (the re-render must stay HEIC); switch the setting back to JPEG and confirm the existing HEIC stack still edits as HEIC (record-owns-format rule).
4. **Storage management** — numbers look sane against Files.app; Delete All with a populated library (incl. mixed JPEG/HEIC) empties the gallery quickly with no UI freeze.

## B. Blend strength (PR #32)

5. **Blend sweep on a real stack** — shoot Smooth (moving water/traffic ideal, a waving hand works), open the editor: Blend slider present; α=1 → full silk, α=0 → the sharp single frame, midpoints plausible; save at α≈0.5 and confirm the gallery thumbnail/result reflect it; rotate afterwards (blend must survive the re-render).
6. **Night blend brightness sanity** — Night look, sweep α: brightness must stay constant (the gain-matched reference); only noise/cleanliness should change.
7. **Depth has no Blend slider** (by design).

## C. Capture safeguards (PR #33)

8. **Warm-device behavior** — after a heavy session (several 20+ frame stacks back-to-back or a few minutes of 4K video in Camera.app), expect "Ready · Device is warm — shorter bursts" and halved frame counts (check the saved stack's frame count); at critical (hard to provoke deliberately — opportunistic) the shutter blocks with "Too hot — let the phone cool down."
9. **Storage-full block** — only if convenient (fill the device or temporarily raise `minimumFreeBytes` in a debug build): shutter blocks with the message.
10. **Non-RAW fallback** — NOT testable on this device (it has RAW). Stays compile+sim-verified; needs a borrowed non-RAW/ProRAW-only device someday. The flagged low-confidence EXIF-orientation question on fallback captures rides with it.
11. **Low battery** — opportunistic: under 10% unplugged, "Ready · Low battery" note appears; capture still works.

## D. Capture metadata (PR #35)

12. **EXIF spot-check** — shoot, share the file to a Mac, `exiftool` (or Photos info panel): ISO and shutter match shooting conditions, DateTimeOriginal correct, Software = "Stack Stack Stack", color profile = sRGB IEC61966-2.1.

## E. Regression sweep (10 min)

13. One shot per look (Detail / Smooth / Trails / Night / Depth) — all save, gallery/editor/share/delete behave; Depth still all-in-focus handheld (PR #30 regression guard); steadiness gauge shows for Smooth/Trails/Depth.

## Known gaps that are *recorded*, not bugs

Metering guidance (§17), gallery metadata view (§15.5), EXIF through edit re-renders, per-frame metadata arrays, perf benchmarks, CIEDE2000 — all noted in the delta doc with owners-of-record.
