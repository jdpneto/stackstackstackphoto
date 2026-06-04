import SwiftUI
import UIKit
import Combine

struct CaptureView: View {
    @ObservedObject var coordinator: StackCaptureCoordinator
    @State private var lastResult: UIImage?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack {
                Spacer()
                if let img = lastResult {
                    Image(uiImage: img).resizable().scaledToFit().padding()
                } else {
                    Text("Detail (Noise Reduction)").foregroundColor(.white)
                }
                Spacer()
                statusLabel
                shutterButton.padding(.bottom, 40)
            }
        }
        // Decode the finished JPEG once, from the coordinator's published result — no disk read.
        .onReceive(coordinator.$lastResultJPEG) { data in
            lastResult = data.flatMap { UIImage(data: $0) }
        }
    }

    private var statusLabel: some View {
        Group {
            switch coordinator.state {
            case .idle: Text("Ready")
            case .capturing: Text("Capturing…")
            case .processing: Text("Stacking…")
            case .done: Text("Done")
            case .failed(let m): Text("Failed: \(m)").foregroundColor(.red).multilineTextAlignment(.center)
            }
        }.foregroundColor(.white).padding(.horizontal)
    }

    private var shutterButton: some View {
        Button {
            Task { await coordinator.shoot() }
        } label: {
            Circle().fill(.white).frame(width: 72, height: 72)
                .overlay(Circle().stroke(.gray, lineWidth: 4))
        }
        .disabled(isBusy)
        .accessibilityIdentifier("shutter")
    }

    private var isBusy: Bool {
        switch coordinator.state { case .capturing, .processing: return true; default: return false }
    }
}
