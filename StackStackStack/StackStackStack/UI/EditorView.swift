import SwiftUI
import UIKit
import StackEngineCore

/// Non-destructive tonal editor (design §14): exposure / contrast / white balance with a
/// downscaled live preview rendered off the main thread. Save persists the adjustments + result.
struct EditorView: View {
    let originalJPEG: Data
    let referenceJPEG: Data?   // aligned reference frame for the blend-strength lerp (nil → slider hidden)
    let recordId: UUID
    let store: LibraryStore
    var onSaved: (Data) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var adj: ImageAdjustments
    @State private var preview: UIImage?
    @State private var renderTask: Task<Void, Never>?
    @State private var generation = 0
    @State private var isSaving = false
    @State private var saveError = false

    /// Resolved once: the record's format can't change while the editor is open.
    private let recordFormat: ImageEncoder.Format

    /// Adjustments + an initial downscaled preview are loaded OFF the main thread by the presenter
    /// and passed in, so the editor's init/body do no synchronous disk reads or full-res decodes.
    /// `recordFormat` is supplied by the caller (already resolved off-main by the presenter) so the
    /// editor init never touches the index.
    init(originalJPEG: Data, referenceJPEG: Data? = nil, initialAdjustments: ImageAdjustments,
         initialPreview: UIImage?, recordId: UUID, recordFormat: ImageEncoder.Format,
         store: LibraryStore, onSaved: @escaping (Data) -> Void) {
        self.originalJPEG = originalJPEG
        self.referenceJPEG = referenceJPEG
        self.recordId = recordId
        self.store = store
        self.onSaved = onSaved
        self.recordFormat = recordFormat
        _adj = State(initialValue: initialAdjustments)
        _preview = State(initialValue: initialPreview)
    }

    var body: some View {
        NavigationStack {
            VStack {
                Group {
                    if let preview { Image(uiImage: preview).resizable().scaledToFit() }
                    else { Color.black }   // brief placeholder; the initial preview is supplied off-main
                }
                .padding()
                Spacer()
                // Blend slider only when a reference frame was stored at capture (long-exposure looks).
                // α=1 is the full stacked look; α=0 is the aligned reference frame. (spec 2026-06-11 §3)
                if referenceJPEG != nil {
                    slider("Blend", value: $adj.blendStrength, range: 0...1, step: 0.05)
                }
                slider("Exposure", value: $adj.exposureEV, range: -2...2)
                slider("Contrast", value: $adj.contrast, range: -1...1)
                slider("Warmth", value: $adj.temperature, range: -1...1)
                slider("Tint", value: $adj.tint, range: -1...1)
                slider("Shadows", value: $adj.shadows, range: -1...1)
                slider("Highlights", value: $adj.highlights, range: -1...1)
                slider("Straighten", value: $adj.straightenDegrees, range: -15...15)
                Picker("Crop", selection: $adj.cropAspect) {
                    ForEach(CropAspect.allCases, id: \.self) { Text($0.shortLabel).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                // The segmented Picker has no editing-changed callback, so re-render on selection.
                .onChange(of: adj.cropAspect) { _ in schedulePreview() }
            }
            .navigationTitle("Edit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(isSaving) }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { save() }.disabled(isSaving) }
            }
            .onAppear { schedulePreview() }
            .onDisappear { renderTask?.cancel() }   // don't keep rendering after the sheet closes
            .alert("Couldn't save the edit", isPresented: $saveError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("The image couldn't be processed. Please try again.")
            }
        }
    }

    private func slider(_ label: String, value: Binding<Float>, range: ClosedRange<Float>, step: Float? = nil) -> some View {
        HStack {
            Text(label).frame(width: 80, alignment: .leading)
            // Re-render the preview when the user finishes dragging (not on every tick).
            if let step {
                Slider(value: value, in: range, step: step, onEditingChanged: { editing in if !editing { schedulePreview() } })
            } else {
                Slider(value: value, in: range, onEditingChanged: { editing in if !editing { schedulePreview() } })
            }
        }.padding(.horizontal)
    }

    /// Render a downscaled preview off the main thread, cancelling any in-flight render.
    private func schedulePreview() {
        renderTask?.cancel()
        generation += 1
        let gen = generation
        let current = adj, jpeg = originalJPEG, fmt = recordFormat, refJPEG = referenceJPEG
        renderTask = Task {
            let data = await Task.detached(priority: .userInitiated) {
                ResultRenderer.render(originalJPEG: jpeg, adjustments: current, quality: 0.85, maxPixel: 1200,
                                      format: fmt, referenceJPEG: refJPEG)
            }.value
            // Drop a stale render: a newer schedulePreview (higher generation) or a cancel supersedes it.
            if Task.isCancelled || gen != generation { return }
            preview = data.flatMap { UIImage(data: $0) }
        }
    }

    private func save() {
        guard !isSaving else { return }   // reject a re-entrant Save while one is in flight
        isSaving = true
        let current = adj, jpeg = originalJPEG, theStore = store, id = recordId, fmt = recordFormat
        let refJPEG = referenceJPEG
        Task {
            let rendered = await Task.detached(priority: .userInitiated) {
                ResultRenderer.render(originalJPEG: jpeg, adjustments: current, quality: 0.95, format: fmt,
                                      referenceJPEG: refJPEG)
            }.value
            isSaving = false
            guard let rendered else { saveError = true; return }   // don't claim success on a failed render
            do {
                try theStore.applyEdit(id: id, adjustments: current, rendered: rendered)
            } catch {
                saveError = true
                return
            }
            onSaved(rendered)   // hand the rendered bytes back directly — no disk re-read
            dismiss()
        }
    }
}

extension CropAspect {
    /// Short label for the crop segmented control.
    var shortLabel: String {
        switch self {
        case .original:    return "Original"
        case .square:      return "Square"
        case .fourThree:   return "4:3"
        case .sixteenNine: return "16:9"
        }
    }
}
