import SwiftUI
import UIKit
import Combine
import StackEngineCore

struct CaptureView: View {
    @ObservedObject var coordinator: StackCaptureCoordinator
    @ObservedObject var steadiness: MotionSteadiness
    @State private var lastResult: UIImage?
    @State private var editSource: EditSource?
    @State private var showPro = false
    @State private var previewLayer: CALayer?

    /// Everything the editor needs, loaded off the main thread before the sheet is presented.
    private struct EditSource: Identifiable {
        let id: UUID
        let original: Data
        let adjustments: ImageAdjustments
        let preview: UIImage?
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreviewView(previewLayer: previewLayer).ignoresSafeArea()   // live viewfinder (nil → black)
            burstSliders
            steadinessOverlay
            VStack {
                Spacer()
                if let img = lastResult {
                    VStack {
                        Image(uiImage: img).resizable().scaledToFit()
                        if coordinator.lastSavedID != nil {
                            Button("Edit") { openEditor() }
                                .buttonStyle(.bordered).tint(.white)
                        }
                    }.padding()
                } else {
                    Text(coordinator.mode.shortLabel).foregroundColor(.white).font(.title3)
                }
                Spacer()
                lookPicker
                proPanel
                statusLabel
                if coordinator.processingCount > 0 {
                    Button("Cancel") { coordinator.cancelProcessing() }
                        .buttonStyle(.bordered).tint(.white)
                        .accessibilityIdentifier("cancel-processing")
                }
                shutterButton.padding(.bottom, 40)
            }
        }
        // Start the live preview when the capture screen appears (no-op on the Simulator fake).
        .task { if previewLayer == nil { previewLayer = await coordinator.startPreview() } }
        // Decode the finished JPEG once, from the coordinator's published result — no disk read.
        .onReceive(coordinator.$lastResultJPEG) { data in
            lastResult = data.flatMap { UIImage(data: $0) }
        }
        .sheet(item: $editSource) { src in
            EditorView(originalJPEG: src.original, initialAdjustments: src.adjustments,
                       initialPreview: src.preview, recordId: src.id, store: coordinator.library) { renderedJPEG in
                lastResult = UIImage(data: renderedJPEG)   // reflect the edit directly — no disk read
            }
        }
    }

    /// Load the original JPEG, persisted adjustments, and a downscaled preview OFF the main thread,
    /// then present the editor — so tapping Edit never blocks the UI on a full-res disk read/decode.
    private func openEditor() {
        guard let id = coordinator.lastSavedID else { return }
        let lib = coordinator.library
        Task {
            let loaded = await Task.detached(priority: .userInitiated) { () -> (Data, ImageAdjustments, Data?)? in
                guard let data = lib.originalData(for: id) else { return nil }
                let adj = lib.adjustments(for: id)
                let prev = ResultRenderer.render(originalJPEG: data, adjustments: adj, quality: 0.85, maxPixel: 1200)
                return (data, adj, prev)
            }.value
            guard let (data, adj, prevData) = loaded else { return }
            editSource = EditSource(id: id, original: data, adjustments: adj,
                                    preview: prevData.flatMap { UIImage(data: $0) })
        }
    }

    private var statusLabel: some View {
        Group {
            if coordinator.isCapturing {
                Text("Capturing…")
            } else if coordinator.processingCount > 0 {
                // Capture is done — the phone can come down while the stack finishes in the background.
                Text(coordinator.processingCount > 1
                     ? "Processing \(coordinator.processingCount)… you can lower your phone"
                     : "Processing… you can lower your phone")
            } else if let err = coordinator.lastError {
                Text("Failed: \(err)").foregroundColor(.red).multilineTextAlignment(.center)
            } else if coordinator.lastResultJPEG != nil {
                Text("Saved ✓")
            } else {
                Text("Ready")
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
                Button {
                    coordinator.mode = m   // the coordinator drops the stale result when the look changes
                } label: {
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

    /// Two-circle steadiness guide: a fixed big ring + a small circle that drifts with device tilt;
    /// green inside tolerance, red outside. Shown only while a long-exposure burst is capturing.
    @ViewBuilder private var steadinessOverlay: some View {
        if coordinator.isCapturing && coordinator.mode.isLongExposure {
            GeometryReader { geo in
                let big: CGFloat = 120, small: CGFloat = 36
                // Constrain so the dot's EDGE stays inside the ring even at a diagonal max offset:
                // maxShift·√2 + small/2 ≤ big/2.
                let maxShift = (big / 2 - small / 2) / CGFloat(2).squareRoot()
                let cx = geo.size.width / 2, cy = geo.size.height / 2
                ZStack {
                    Circle().stroke(Color.white.opacity(0.7), lineWidth: 3)
                        .frame(width: big, height: big).position(x: cx, y: cy)
                    Circle().fill(steadiness.isWithinTolerance ? Color.green : Color.red)
                        .frame(width: small, height: small)
                        .position(x: cx + steadiness.offset.x * maxShift,
                                  y: cy + steadiness.offset.y * maxShift)
                }
            }
            .allowsHitTesting(false)
        }
    }

    /// Vertical Photos/Duration sliders pinned to the left/right edges, shown only for the
    /// long-exposure looks. Each shows its value live as you drag. (design 2026-06-07 §5)
    @ViewBuilder private var burstSliders: some View {
        if coordinator.mode.isLongExposure {
            HStack {
                verticalBurstControl(
                    title: "Photos",
                    readout: "\(coordinator.burst.photoCount)",
                    value: Binding(
                        get: { Double(coordinator.burst.photoCount) },
                        set: { coordinator.burst = BurstSettings(photoCount: Int($0.rounded()),
                                                                 durationSeconds: coordinator.burst.durationSeconds) }),
                    range: 2...Double(BurstSettings.maxPhotoCount), step: 1)
                Spacer()
                verticalBurstControl(
                    title: "Time",
                    readout: "\(Int(coordinator.burst.durationSeconds))s",
                    value: Binding(
                        get: { coordinator.burst.durationSeconds },
                        set: { coordinator.burst = BurstSettings(photoCount: coordinator.burst.photoCount,
                                                                 durationSeconds: $0) }),
                    range: 1...60, step: 1)
            }
            .padding(.horizontal, 6)
            .disabled(coordinator.isBusy)
        }
    }

    private func verticalBurstControl(title: String, readout: String, value: Binding<Double>,
                                      range: ClosedRange<Double>, step: Double) -> some View {
        VStack(spacing: 6) {
            Text(title).font(.caption2).foregroundColor(.white)
            Text(readout).font(.caption).bold().foregroundColor(.white)
                .accessibilityIdentifier("burst-\(title.lowercased())-value")
            Slider(value: value, in: range, step: step)
                .rotationEffect(.degrees(-90))
                .frame(width: 180)            // length of the slider track (becomes vertical extent)
                .frame(width: 44, height: 180) // constrain the rotated footprint so layout reserves the right box
                .tint(.white)
                .accessibilityLabel(title)
                .accessibilityValue(readout)
                .accessibilityIdentifier("burst-\(title.lowercased())-slider")
        }
    }

    private var proPanel: some View {
        VStack(spacing: 8) {
            Button(showPro ? "Pro ▴" : "Pro ▾") { showPro.toggle() }
                .font(.caption).foregroundColor(.white)
                .accessibilityIdentifier("pro-toggle")
                .disabled(coordinator.isBusy)
            if showPro {
                VStack(spacing: 10) {
                    if !coordinator.mode.isLongExposure {
                        optControl("Frames", unit: "",
                                   binding: Binding(get: { coordinator.pro.frameCount.map(Double.init) },
                                                    set: { coordinator.pro.frameCount = $0.map { Int($0.rounded()) } }),
                                   range: 2...Double(CaptureRecipe.maxBurstFrames), step: 1,
                                   // Default to the current look's burst length so enabling the control
                                   // doesn't silently change it; the user adjusts from there.
                                   defaultValue: Double(CaptureRecipe.recipe(for: coordinator.mode).frameCount)) { "\(Int($0))" }
                    }
                    optControl("ISO", unit: "",
                               binding: $coordinator.pro.iso, range: 50...3200, step: 10, defaultValue: 400) { "\(Int($0))" }
                    optControl("Shutter", unit: "s",
                               binding: $coordinator.pro.shutterSeconds, range: 0.001...1, step: 0.001, defaultValue: 0.02) {
                                   String(format: "1/%.0f", 1 / max($0, 0.0001)) }
                    optControl("Focus", unit: "",
                               binding: $coordinator.pro.focus, range: 0...1, step: 0.01, defaultValue: 0.5) {
                                   String(format: "%.2f", $0) }
                }
                .padding(.horizontal, 24)
            }
        }
        .padding(.bottom, 4)
    }

    /// A labelled control that reads "Auto" when off and shows a value slider when on.
    private func optControl(_ label: String, unit: String, binding: Binding<Double?>,
                            range: ClosedRange<Double>, step: Double, defaultValue: Double,
                            format: @escaping (Double) -> String) -> some View {
        VStack(spacing: 2) {
            Toggle(isOn: Binding(get: { binding.wrappedValue != nil },
                                 set: { binding.wrappedValue = $0 ? defaultValue : nil })) {
                Text(binding.wrappedValue.map { "\(label): \(format($0))\(unit)" } ?? "\(label): Auto")
                    .font(.caption2).foregroundColor(.white)
            }
            .tint(.white)
            if let v = binding.wrappedValue {
                Slider(value: Binding(get: { v }, set: { binding.wrappedValue = $0 }), in: range, step: step)
                    .tint(.white)
            }
        }
        .disabled(coordinator.isBusy)
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
