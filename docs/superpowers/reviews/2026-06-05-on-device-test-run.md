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

## Resolution (branch `fix/sequential-burst-capture`)

The headline bug and the follow-up performance concern were fixed and re-verified on device.

**1. Sequential burst (commit `4749dfc`)** — issue the next `capturePhoto` only after the previous frame's `didFinishCaptureFor`, bounding in-flight RAW requests to one. Re-test: **Detail → Night captured in one session, both reached Done, 0 `-12773` bails** (no wedge).

**2. Fast capture + parallel stacking (commit `b3e91e8`)** — after the user noted the arms-up step must be fast (post-processing can run long):
- The 12 MP RAW→mosaic copy now runs on a dedicated processing queue, off the capture path (it previously ran in the photo delegate callback and blocked advancing).
- Frames fire **back-to-back** (pacing removed) with `.speed` photo-quality priority.
- Per-frame develop/luma/align/downscale parallelized across cores (`parallelMap`, result-identical — 89 engine tests pass).
- Re-test: **Detail capture ~1–2 s** (8 frames within ~1 s, 0 bails); stacking runs parallel in the background.

**3. Watchdog review fix (commit `…`)** — on a stalled frame the watchdog stops requesting new frames but waits for outstanding off-queue conversions before resuming, so it can't drop a still-converting frame.

**4. Capture/processing UX (commits `5d429fe`, `d2fb64b`)** — the two follow-ups:
- The coordinator now separates the fast foreground burst from a SERIAL background processing chain (jobs run one at a time; the shutter re-enables conceptually after capture). Status copy distinguishes phases: "Capturing…" vs **"Processing… you can lower your phone"** vs "Saved ✓". Switching looks drops the stale result.
- **Contention finding:** re-enabling the shutter to capture *during* a background stack is unreliable on device — the all-core stack starves the camera and the still-image pipeline bails (-12773) or underproduces frames. So the shutter stays disabled while processing (`isBusy = isCapturing || processingCount > 0`); the user only holds still for the short capture, then the status invites them to lower the phone. **True concurrent capture is deferred** — it needs cancelable/checkpointed processing + memory/QoS tuning.
- Device-verified the full cycle (capture ~2 s → "Processing… you can lower your phone", shutter disabled → "Saved ✓", shutter re-enabled). A late-session capture failure was traced to **thermal throttling** (camera Viewfinder thermal policy active after a marathon test session of RAW bursts + all-core stacks + repeated builds), not a code regression — the app degrades gracefully with a retry message. The all-core CPU stack generating heat is an inherent cost of CPU stacking (Accelerate/Metal is the roadmap lever).

Code review: extra-high-effort pass on each commit; confirmed findings fixed (watchdog dropping in-flight conversions; stale "Saved ✓" after a look change; a trivial test assertion). Remaining candidates refuted (`concurrentPerform` is a barrier; engine is pure; `outstanding`/`processingCount` can't go negative; the serial chain completes jobs in order so `lastResultJPEG`/`lastSavedID` never mismatch; `.speed` doesn't change the fixed RAW format).

## What worked well

- Live viewfinder (PR #23) — real-time feed, no black screen.
- White balance (PR #21) — today's captures are natural; the lone green thumbnail in the gallery is an older pre-fix capture.
- Managed-resolution + binned-demosaic pipeline — a successful Detail capture develops/aligns/stacks/encodes in ~20s on device.
- Editor adjustments + crop + save round-trip.
- Re-entrancy + look-switch result clearing.
