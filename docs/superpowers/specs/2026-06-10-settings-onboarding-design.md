# Settings + Onboarding (and HEIC as a Real Format)

**Status:** Approved design (brainstorm). Implements the bible's §15.1 (third area) + §15.6 for v1, delta doc TL;DR #2.
**Parent specs:** the bible (`2026-06-04-stack-stack-stack-photography-design.md`), delta doc (`2026-06-10-design-implementation-delta.md`).

## 1. Goal

Give the app its missing third area — a Settings tab backed only by real features — and a first-launch onboarding flow (welcome → look explainers → camera pre-prompt). Make the "default export format" setting honest by wiring HEIC end-to-end as a capture-time library format.

## 2. Decisions (locked in brainstorm)

| Decision | Choice | Rationale |
|---|---|---|
| Settings scope | **Lean + HEIC**: save-to-Photos toggle, export format (JPEG/HEIC), storage management, capability report, replay onboarding, about | Every entry is backed by a real feature. |
| Pruned §15.6 items | Default RAW toggle (blocked on YUV fallback), grid/level (feature doesn't exist), max session length (superseded by burst sliders' 60s cap) | No dead switches; **annotate the bible §15.6** with this status rather than silently diverging. |
| HEIC depth | **Capture-time format** — the library stores results/originals in the chosen format | That's what §12.3's encode step means; HEIC's value (≈40% smaller) applies to the library, not just shares. Share-time transcode would double-compress. |
| Onboarding shape | Standard flow: welcome → 5 look cards → camera pre-prompt; skippable; replayable from Settings | §15.6. Photos permission stays contextual (iOS asks on first export) — no extra page. |
| Look-card imagery | Stylized (gradient + SF Symbol), with copy in a static data array | No asset curation/binary bloat now; real shots can drop in later without touching flow logic. |
| Preference storage | `AppSettings`: small ObservableObject over `UserDefaults` (3 keys) | No library; matches the app's no-ceremony persistence style. |

## 3. Settings infrastructure

### 3.1 `AppSettings` (new, `StackStackStack/Settings/AppSettings.swift`)

`ObservableObject` wrapping `UserDefaults` with typed accessors:
- `saveToPhotos: Bool` — default `false`.
- `exportFormat: ImageEncoder.Format` — default `.jpeg`, persisted as a string raw value.
- `hasSeenOnboarding: Bool` — default `false`.

Created once in `StackStackStackApp` (alongside the coordinator), injected with `.environmentObject`. A `UserDefaults` instance is injectable for tests.

### 3.2 `SettingsView` (new, third tab)

`StackStackStackApp.swift` gains a third `tabItem` ("Settings", `gearshape`). A plain `Form`:

| Section | Rows |
|---|---|
| Capture & Export | Save to Photos toggle · Format picker (JPEG / HEIC) |
| Storage | Used space + stack count (computed off-main from `LibraryStore`) · **Delete All Stacks** behind a confirmation dialog |
| This Device | RAW capture: yes/no · Depth (manual focus): yes/no — fed by the existing capture-service probes |
| About | Version/build from the bundle · **Replay Introduction** |

## 4. HEIC as a capture-time format

- **`StackRecord.format: String?`** — `nil` = JPEG (back-compat with every existing record; same optional-field trick as `updatedAt`). Computed `ImageEncoder.Format` accessor beside it.
- **`LibraryStore`**: format-aware URLs — new saves use `.heic`/`.jpg` per the record; lookups resolve through the record so legacy `.jpg` files keep working. `save(...)` takes the format; the immutable original is written in the same format; `reconcileOrphans` learns both extensions.
- **Coordinator**: gains a plain `var exportFormat: ImageEncoder.Format` (default `.jpeg`), kept in sync by the app root observing `AppSettings` (the coordinator stays ignorant of the settings object). `shoot()` snapshots it at shutter-press time, like `mode`/`pro`; the encode step (`makeJPEG` → renamed `makeResult`) receives it. `ImageEncoder.Format` becomes `String`-backed for persistence.
- **`ResultRenderer`** re-encodes edits in **the record's own format** — changing the default never silently transcodes existing stacks.
- **Read path is free**: `ImageDecoder` and thumbnails use ImageIO, which decodes HEIC already.
- **Encoder fallback**: if HEIC encoding fails, fall back to JPEG and stamp the record accordingly — never lose a stack to an encoder hiccup.

## 5. Save-to-Photos auto-export

`PhotoLibraryExporter` (new, one file): `PHPhotoLibrary` with **add-only** authorization (`.addOnly` access level — no library read). When `settings.saveToPhotos` is on, the coordinator calls it fire-and-forget after `store.save(...)`. Failures (permission denied, disk) surface as a non-blocking status note ("Saved ✓ · Photos export failed — check Settings ▸ Privacy") and never fail the in-app save. The system prompt appears contextually on first export. `NSPhotoLibraryAddUsageDescription` added to the target's Info configuration if absent.

## 6. Onboarding

`OnboardingView` (new): full-screen cover presented from the app root when `!settings.hasSeenOnboarding`. `TabView` (`.page` style):

1. **Welcome** — the pitch: stack a handheld burst into a shot one frame can't make; on-device, no cloud.
2–6. **Look cards** — Detail / Smooth / Trails / Night / Depth; stylized gradient + SF Symbol, one line *what it does*, one line *when to use it*. Card copy/imagery live in a static data array (`OnboardingPage` model) so edits never touch flow logic.
7. **Camera** — why the camera is needed; "Enable Camera" triggers `AVCaptureDevice.requestAccess`; if previously denied, the button becomes "Open Settings" (deep link). Continue regardless of the answer.

"Skip" on every page; finishing or skipping sets `hasSeenOnboarding = true`. **Replay Introduction** presents the same cover again (it does not — cannot — reset the system permission).

## 7. Docs updates shipped with this change

- **Bible §15.6**: add an *Implementation status (2026-06-10)* note in place — shipped items marked, pruned items listed with reasons (default RAW toggle → blocked on the YUV/HEIC capture fallback; grid/level → overlay feature doesn't exist; max session length → superseded by the burst sliders' hard 60s cap).
- **Delta doc**: TL;DR #1 marked done (PR #30), #2 marked done (this PR); §15.6 rows updated; the delta doc gets committed (it is currently untracked).

## 8. Error handling

| Situation | Response |
|---|---|
| HEIC encode fails | Fall back to JPEG; record stamped as JPEG. |
| Photos export fails (permission/disk) | Non-blocking status note; in-app save unaffected. |
| Delete All fails partway | Report the error; `reconcileOrphans` self-heals the index on next load. |
| Camera permission denied in onboarding | Flow continues; capture screen's existing error toast still explains; "Open Settings" offered. |
| Storage usage computation | Off-main; renders a placeholder until ready. |

## 9. Testing

- **Unit (app):** `AppSettings` round-trip with an injected `UserDefaults`; `LibraryStore` format-aware save/load/delete, legacy `.jpg` back-compat, mixed-format `reconcileOrphans`; `ImageEncoder` HEIC output decodes back; coordinator save honors the format setting (fake capture); HEIC-failure fallback stamps JPEG.
- **UI (simulator):** fresh-install launch (a launch argument resets defaults) shows onboarding; skip lands on Capture; Settings tab renders all sections; Delete All empties the gallery.
- **Device (manual):** save-to-Photos round-trip incl. the permission prompt and the denied path (simulator photo library is unreliable).

## 10. Out of scope (noted)

Default RAW toggle, grid/level overlay, max-session-length setting (see §2 pruning); Photos-permission onboarding page; real sample photographs on look cards; scene-adaptive Auto and the rest of delta TL;DR #4–6.
