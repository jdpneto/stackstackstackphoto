import SwiftUI

@main
struct StackStackStackApp: App {
    // Owned once for the app's lifetime — constructing it in `body` would re-build/leak the
    // capture session on every view update.
    @StateObject private var coordinator = StackCaptureCoordinator(capture: StackStackStackApp.makeCaptureService())

    var body: some Scene {
        WindowGroup {
            TabView {
                NavigationStack { CaptureView(coordinator: coordinator) }
                    .tabItem { Label("Capture", systemImage: "camera") }
                NavigationStack { GalleryView() }
                    .tabItem { Label("Gallery", systemImage: "photo.on.rectangle") }
            }
        }
    }

    private static func makeCaptureService() -> CaptureService {
        #if targetEnvironment(simulator)
        // No camera in the Simulator — use the deterministic fake so the flow is demoable.
        return FakeCaptureService(width: 256, height: 256)
        #else
        // AVCaptureService configures lazily (and off the main thread) on first capture.
        return AVCaptureService()
        #endif
    }
}
