import SwiftUI

@main
struct StackStackStackApp: App {
    // Owned once for the app's lifetime — constructing it in `body` would re-build/leak the
    // capture session on every view update.
    @StateObject private var coordinator = StackCaptureCoordinator(capture: StackStackStackApp.makeCaptureService())
    @StateObject private var settings = AppSettings()
    @State private var showOnboarding = false

    init() {
        // UI-test hook: a fresh-install run (defaults are otherwise sticky per simulator install).
        if ProcessInfo.processInfo.arguments.contains("-resetOnboarding") {
            UserDefaults.standard.removeObject(forKey: "hasSeenOnboarding")
        }
        // UI-test hook: skip onboarding without resetting it so non-onboarding tests land directly
        // on Capture without waiting for (or dismissing) the onboarding flow.
        if ProcessInfo.processInfo.arguments.contains("-skipOnboarding") {
            UserDefaults.standard.set(true, forKey: "hasSeenOnboarding")
        }
    }

    var body: some Scene {
        WindowGroup {
            // First launch renders onboarding INSTEAD of the app: the capture stack must not mount
            // (its preview start would fire the system camera prompt under the cover — the exact
            // dialog the onboarding pre-prompt page exists to precede). Replay (from Settings)
            // presents over the running app via fullScreenCover; permission is already determined.
            if !settings.hasSeenOnboarding {
                OnboardingView(isPresented: .constant(true))   // dismissal driven by hasSeenOnboarding
                    .environmentObject(settings)
            } else {
                mainTabs
            }
        }
    }

    /// The main tab UI, shown once onboarding has been completed.
    /// Also hosts the Settings → "Replay Introduction" fullScreenCover path.
    private var mainTabs: some View {
        TabView {
            NavigationStack { CaptureView(coordinator: coordinator, steadiness: coordinator.steadiness) }
                .tabItem { Label("Capture", systemImage: "camera") }
            NavigationStack { GalleryView() }
                .tabItem { Label("Gallery", systemImage: "photo.on.rectangle") }
            NavigationStack {
                SettingsView(coordinator: coordinator, store: coordinator.library,
                             showOnboarding: $showOnboarding)
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .environmentObject(settings)
        // onReceive fires with the current value on subscription — this covers the initial sync
        // without needing a separate .onAppear pref-sync block.
        .onReceive(settings.$exportFormat) { coordinator.exportFormat = $0 }
        .onReceive(settings.$saveToPhotos) { coordinator.saveToPhotosEnabled = $0 }
        .fullScreenCover(isPresented: $showOnboarding) {
            OnboardingView(isPresented: $showOnboarding)
                .environmentObject(settings)
        }
    }

    private static func makeCaptureService() -> CaptureService {
        #if targetEnvironment(simulator)
        // No camera in the Simulator — use the deterministic fake so the flow is demoable.
        return FakeCaptureService(width: 128, height: 128)
        #else
        // AVCaptureService configures lazily (and off the main thread) on first capture.
        return AVCaptureService()
        #endif
    }
}
