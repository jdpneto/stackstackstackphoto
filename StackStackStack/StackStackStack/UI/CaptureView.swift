import SwiftUI
import UIKit

struct CaptureView: View {
    @StateObject private var coordinator: StackCaptureCoordinator
    @State private var lastResult: UIImage?

    init(coordinator: StackCaptureCoordinator) {
        _coordinator = StateObject(wrappedValue: coordinator)
    }

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
        .onChange(of: coordinator.state) { _ in loadResultIfDone() }
    }

    private var statusLabel: some View {
        Group {
            switch coordinator.state {
            case .idle: Text("Ready")
            case .capturing: Text("Capturing…")
            case .processing: Text("Stacking…")
            case .done: Text("Done")
            case .failed(let m): Text("Failed: \(m)").foregroundColor(.red)
            }
        }.foregroundColor(.white)
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

    private func loadResultIfDone() {
        guard case .done(let id) = coordinator.state else { return }
        let store = LibraryStore()
        if let rec = (try? store.loadAll())?.first(where: { $0.id == id }),
           let data = try? Data(contentsOf: store.resultURL(for: rec)) {
            lastResult = UIImage(data: data)
        }
    }
}
