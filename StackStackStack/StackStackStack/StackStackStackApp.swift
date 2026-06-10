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
    }

    var body: some Scene {
        WindowGroup {
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
            // The coordinator stays ignorant of AppSettings: the root mirrors the two prefs in.
            .onAppear {
                coordinator.exportFormat = settings.exportFormat
                coordinator.saveToPhotosEnabled = settings.saveToPhotos
                if !settings.hasSeenOnboarding { showOnboarding = true }
            }
            .onReceive(settings.$exportFormat) { coordinator.exportFormat = $0 }
            .onReceive(settings.$saveToPhotos) { coordinator.saveToPhotosEnabled = $0 }
            .fullScreenCover(isPresented: $showOnboarding) {
                OnboardingView(isPresented: $showOnboarding)
                    .environmentObject(settings)
            }
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
