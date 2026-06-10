import SwiftUI
import AVFoundation
import UIKit

/// One onboarding page's content — data, not views, so copy/imagery edits never touch flow logic. (spec §6)
struct OnboardingPage: Identifiable {
    let id: String
    let symbol: String
    let tint: Color
    let title: String
    let what: String
    let when: String

    static let looks: [OnboardingPage] = [
        OnboardingPage(id: "detail", symbol: "sparkles", tint: .cyan, title: "Detail",
                       what: "Stacks a burst into one clean, low-noise shot.",
                       when: "Everyday shots, low light, anywhere you want maximum quality."),
        OnboardingPage(id: "smooth", symbol: "water.waves", tint: .blue, title: "Smooth",
                       what: "Averages motion into silky blur while still things stay sharp.",
                       when: "Waterfalls, rivers, clouds, busy crowds."),
        OnboardingPage(id: "trails", symbol: "car.rear.road.lane", tint: .orange, title: "Trails",
                       what: "Keeps the bright paths moving lights leave behind.",
                       when: "Night traffic, fairground rides, sparklers."),
        OnboardingPage(id: "night", symbol: "moon.stars.fill", tint: .indigo, title: "Night",
                       what: "Stacks and brightens a dark scene without the noise.",
                       when: "Dusk, dim rooms, city nights."),
        OnboardingPage(id: "depth", symbol: "camera.macro", tint: .green, title: "Depth",
                       what: "Sweeps focus near→far and keeps the sharpest of each.",
                       when: "Close subjects with a background you also want sharp."),
    ]
}

/// First-launch introduction (bible §15.6): welcome → the five looks → camera pre-prompt.
/// Skippable everywhere; finishing or skipping sets `hasSeenOnboarding`. (spec §6)
struct OnboardingView: View {
    @EnvironmentObject private var settings: AppSettings
    @Binding var isPresented: Bool
    // Fix 3: recomputed on appear so the state is fresh if the user granted/denied between pages.
    @State private var cameraDenied = false

    var body: some View {
        VStack {
            HStack {
                Spacer()
                Button("Skip") { finish() }
                    .padding()
                    .accessibilityIdentifier("onboarding-skip")
            }
            TabView {
                welcome
                ForEach(OnboardingPage.looks) { lookCard($0) }
                cameraPage
            }
            .tabViewStyle(.page)
            .indexViewStyle(.page(backgroundDisplayMode: .always))
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
    }

    private var welcome: some View {
        pageScaffold(symbol: "square.stack.3d.up.fill", tint: .white, title: "Stack Stack Stack",
                     line1: "Shoot a short handheld burst; the app aligns and stacks it into a shot one frame can't make.",
                     line2: "Everything runs on your phone. No cloud, no account.")
    }

    private func lookCard(_ page: OnboardingPage) -> some View {
        pageScaffold(symbol: page.symbol, tint: page.tint, title: page.title,
                     line1: page.what, line2: page.when)
    }

    // Fix 2: camera buttons live inside the scaffold footer slot so the inner bottom Spacer
    // cannot push them off-screen on small devices (e.g. iPhone SE).
    private var cameraPage: some View {
        pageScaffold(symbol: "camera.fill", tint: .yellow, title: "One thing first",
                     line1: "Stack Stack Stack is a camera — it needs camera access to shoot.",
                     line2: "Nothing is captured until you press the shutter.") {
            VStack(spacing: 12) {
                if cameraDenied {
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                    .buttonStyle(.borderedProminent)
                } else {
                    Button("Enable Camera") {
                        AVCaptureDevice.requestAccess(for: .video) { _ in
                            Task { @MainActor in finish() }   // continue regardless of the answer
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("onboarding-enable-camera")
                }
                Button("Done") { finish() }
                    .foregroundColor(.secondary)
                    .accessibilityIdentifier("onboarding-done")
            }
        }
        // Fix 3: re-read the authorization status each time this page appears (the user may have
        // granted or denied access while swiping through the earlier pages).
        .onAppear { cameraDenied = AVCaptureDevice.authorizationStatus(for: .video) == .denied }
    }

    // MARK: - Scaffold

    // Fix 2: two overloads so the default (no footer) compiles without a @ViewBuilder default
    // parameter (which is syntactically valid in Swift 5.9 but triggers a spurious warning on
    // some Xcode 15 toolchains when the default is EmptyView). Welcome/lookCard callers use the
    // no-footer overload; cameraPage uses the footer overload.

    private func pageScaffold(symbol: String, tint: Color, title: String,
                              line1: String, line2: String) -> some View {
        pageScaffold(symbol: symbol, tint: tint, title: title,
                     line1: line1, line2: line2) { EmptyView() }
    }

    private func pageScaffold<Footer: View>(symbol: String, tint: Color, title: String,
                                            line1: String, line2: String,
                                            @ViewBuilder footer: () -> Footer) -> some View {
        VStack(spacing: 20) {
            Spacer()
            ZStack {   // stylized stand-in for a sample shot; a real image can replace it later
                RoundedRectangle(cornerRadius: 24)
                    .fill(LinearGradient(colors: [tint.opacity(0.55), .black],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 180, height: 180)
                Image(systemName: symbol).font(.system(size: 64)).foregroundColor(.white)
            }
            Text(title).font(.title).bold().foregroundColor(.white)
            Text(line1).multilineTextAlignment(.center).foregroundColor(.white.opacity(0.9))
            Text(line2).font(.callout).multilineTextAlignment(.center).foregroundColor(.white.opacity(0.6))
            footer()
                .padding(.top, 12)
            Spacer()
        }
        .padding(.horizontal, 32)
    }

    private func finish() {
        settings.hasSeenOnboarding = true
        isPresented = false
    }
}
