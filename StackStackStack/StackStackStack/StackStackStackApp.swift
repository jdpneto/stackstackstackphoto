import SwiftUI

@main
struct StackStackStackApp: App {
    var body: some Scene {
        WindowGroup {
            TabView {
                NavigationStack { CaptureView(coordinator: makeCoordinator()) }
                    .tabItem { Label("Capture", systemImage: "camera") }
                NavigationStack { GalleryView() }
                    .tabItem { Label("Gallery", systemImage: "photo.on.rectangle") }
            }
        }
    }

    private func makeCoordinator() -> StackCaptureCoordinator {
        #if targetEnvironment(simulator)
        // No camera in the Simulator — use the deterministic fake so the flow is demoable.
        return StackCaptureCoordinator(capture: FakeCaptureService(width: 256, height: 256))
        #else
        let svc = AVCaptureService()
        try? svc.configure()
        return StackCaptureCoordinator(capture: svc)
        #endif
    }
}
