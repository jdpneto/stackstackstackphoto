import SwiftUI
import UIKit
import Combine
import StackEngineCore

struct CaptureView: View {
    @ObservedObject var coordinator: StackCaptureCoordinator
    @State private var lastResult: UIImage?
    @State private var showEditor = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack {
                Spacer()
                if let img = lastResult {
                    VStack {
                        Image(uiImage: img).resizable().scaledToFit()
                        if coordinator.lastSavedID != nil {
                            Button("Edit") { showEditor = true }
                                .buttonStyle(.bordered).tint(.white)
                        }
                    }.padding()
                } else {
                    Text(coordinator.mode.shortLabel).foregroundColor(.white).font(.title3)
                }
                Spacer()
                lookPicker
                statusLabel
                shutterButton.padding(.bottom, 40)
            }
        }
        // Decode the finished JPEG once, from the coordinator's published result — no disk read.
        .onReceive(coordinator.$lastResultJPEG) { data in
            lastResult = data.flatMap { UIImage(data: $0) }
        }
        .sheet(isPresented: $showEditor) {
            if let id = coordinator.lastSavedID, let original = coordinator.library.originalData(for: id) {
                EditorView(originalJPEG: original, recordId: id, store: coordinator.library) {
                    // Reflect the saved edit in the on-screen result.
                    if let data = try? Data(contentsOf: coordinator.library.resultURL(forID: id)) {
                        lastResult = UIImage(data: data)
                    }
                }
            }
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
        .disabled(coordinator.isBusy)
        .accessibilityIdentifier("shutter")
    }

    private var lookPicker: some View {
        HStack(spacing: 8) {
            ForEach(StackMode.allCases, id: \.self) { m in
                Button { coordinator.mode = m } label: {
                    Text(m.shortLabel)
                        .font(.caption)
                        .fontWeight(coordinator.mode == m ? .bold : .regular)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(coordinator.mode == m ? Color.white : Color.white.opacity(0.18))
                        .foregroundColor(coordinator.mode == m ? .black : .white)
                        .clipShape(Capsule())
                }
                .accessibilityIdentifier("look-\(m)")
                .disabled(coordinator.isBusy)
            }
        }
        .padding(.bottom, 8)
    }
}

extension StackMode {
    /// Short label shown in the capture-screen look-picker.
    var shortLabel: String {
        switch self {
        case .noiseReduction: return "Detail"
        case .smoothMotion:   return "Smooth"
        case .lightTrails:    return "Trails"
        case .lowLightBoost:  return "Night"
        }
    }
}
