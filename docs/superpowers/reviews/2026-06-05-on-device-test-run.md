# On-Device Manual Test Run — 2026-06-05

**Device:** iPhone (Pro Max, iOS 26.3.1), udid `00008120-000A74E22278C01E`
**Build:** `com.jdpneto.StackStackStack` (main @ 9fb6b6d)
**Driver:** mobile-mcp over go-ios tunnel + WebDriverAgent (port 8100 forward)
**Method:** interactive — tap/screenshot the live UI (not scripted XCUITest)

## Test plan & results

| # | Test | Result |
|---|------|--------|
| T1 | Launch + live preview | ✅ PASS — live viewfinder shows a real scene (no black screen); permission already granted; all controls present |
| T2 | Capture each look | ❌ **FAIL (High)** — only the **first** capture after launch works (Detail); Night/Smooth/Trails and all subsequent captures produce **zero frames** |
| T3 | Pro controls | ✅ PASS — panel expands; toggling Frames flips "Auto"→value and reveals a slider; dragging changes value (12→2) |
| T4 | Editor | ✅ PASS (1 minor) — Exposure/Straighten sliders + 16:9 crop applied and saved; result reflects edit. Minor: **no image preview** visible in landscape |
| T5 | Gallery | ✅ PASS (1 minor) — "Stacks" shows saved thumbnails; newest = edited 16:9, oldest shows pre-WB-fix green cast. Minor: thumbnails **not tappable** (view-only) |
| T6 | Robustness | ✅ PASS — rapid double-tap shutter → exactly one capture (re-entrancy guard holds); switching look clears the stale result |

## Headline bug — multi-frame burst wedges the capture session (High)

**Symptom:** `Failed: Couldn't read the captured frames. Please try again.` Reproducible on every look except a fresh session's first Detail shot.

**Root cause:** `AVCaptureService.captureBurst` (StackStackStack/Capture/AVCaptureService.swift:82-89) schedules **all N `capturePhoto` requests up front** on overlapping `sessionQueue.asyncAfter` timers spread across the recipe's duration window. RAW still-image capture (the Iris pipeline) cannot service overlapping/rapid capture requests — each one bails.

**Evidence (device syslog during a Night burst):**
```
BWStillImageCoordinatorNode  Fig assert: "err == 0" at bail (BWStillImageCoordinatorNode.m:1932) - (err=-12773)
FigCaptureSession            Fig assert: "err == 0" at bail (FigCaptureSession.m:7376)   - (err=-12773)
FigCaptureSession  Posted notification IrisWillBeginCapture { ErrorStatus = "-15541"; SettingsID = 41..46 }
```
All capture requests in the burst bail with `-12773` (24 bails captured across two Night bursts → 0 frames delivered). The session repeatedly logs `startDeferredGraphSetupWork` / `beginDeferredGraphSetupAndWaitForCameraSourcesToStart` — the still-image graph thrashes and never stabilizes.

**Persistence / blast radius:** the thrash **wedges the session**. After the first multi-frame failure, *every* subsequent capture fails — confirmed by capturing 2-frame Detail (the look that worked at launch) and seeing the same `-12773` bails. Only relaunching the app (fresh session) restores a single working capture.

**Why Detail "passed" T2:** Detail is the 8-frame look and happened to survive as the *first* capture on a fresh session — but even then likely only a few frames survive, so the "stack" is thinner than intended. Night/Smooth/Trails (12–30 frames) overlap harder and lose everything.

**Fix direction:** drive the burst **sequentially** — issue the next `capturePhoto` only after the previous frame's `didFinishProcessingPhoto`/`didFinishCaptureFor` returns, pacing long-exposure looks across the window with a delay between frames rather than firing them all up front. This bounds in-flight RAW requests to 1 and avoids the graph thrash. Consider also: tear down/restart the session on `noFramesProduced` so a failure isn't sticky.

## Secondary findings

- **Editor shows no image preview in landscape** (T4). The device ran in landscape (screen 932×430); the editor's slider stack fills the screen with no visible image. Either lock primary orientation or give the editor a landscape layout with a visible preview.
- **Gallery thumbnails are not interactive** (T5) — no tap-to-open / re-edit / share of past stacks. Functional gap vs. expectation, not a crash.
- **App is not orientation-locked** — runs in landscape. For a camera/stacking app, portrait is usually primary; landscape layouts (esp. editor) need attention if landscape is to be supported.

## What worked well

- Live viewfinder (PR #23) — real-time feed, no black screen.
- White balance (PR #21) — today's captures are natural; the lone green thumbnail in the gallery is an older pre-fix capture.
- Managed-resolution + binned-demosaic pipeline — a successful Detail capture develops/aligns/stacks/encodes in ~20s on device.
- Editor adjustments + crop + save round-trip.
- Re-entrancy + look-switch result clearing.
