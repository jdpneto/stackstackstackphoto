import SwiftUI
import UIKit
import StackEngineCore

/// Non-destructive tonal editor (design §14): exposure / contrast / white balance with a
/// live preview rendered off the main thread. Save persists the adjustments + rendered result.
struct EditorView: View {
    let originalJPEG: Data
    let recordId: UUID
    let store: LibraryStore
    var onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var adj: ImageAdjustments
    @State private var preview: UIImage?
    @State private var renderTask: Task<Void, Never>?

    init(originalJPEG: Data, recordId: UUID, store: LibraryStore, onSaved: @escaping () -> Void) {
        self.originalJPEG = originalJPEG
        self.recordId = recordId
        self.store = store
        self.onSaved = onSaved
        _adj = State(initialValue: store.adjustments(for: recordId))
    }

    var body: some View {
        NavigationStack {
            VStack {
                Group {
                    if let preview { Image(uiImage: preview).resizable().scaledToFit() }
                    else if let ui = UIImage(data: originalJPEG) { Image(uiImage: ui).resizable().scaledToFit() }
                }
                .padding()
                Spacer()
                slider("Exposure", value: $adj.exposureEV, range: -2...2)
                slider("Contrast", value: $adj.contrast, range: -1...1)
                slider("Warmth", value: $adj.temperature, range: -1...1)
                slider("Tint", value: $adj.tint, range: -1...1)
            }
            .navigationTitle("Edit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { save() } }
            }
            .onAppear { schedulePreview() }
        }
    }

    private func slider(_ label: String, value: Binding<Float>, range: ClosedRange<Float>) -> some View {
        HStack {
            Text(label).frame(width: 80, alignment: .leading)
            // Re-render the preview when the user finishes dragging (not on every tick).
            Slider(value: value, in: range, onEditingChanged: { editing in if !editing { schedulePreview() } })
        }.padding(.horizontal)
    }

    /// Render a preview off the main thread, cancelling any in-flight render.
    private func schedulePreview() {
        renderTask?.cancel()
        let current = adj
        let jpeg = originalJPEG
        renderTask = Task {
            let data = await Task.detached(priority: .userInitiated) {
                ResultRenderer.render(originalJPEG: jpeg, adjustments: current, quality: 0.85)
            }.value
            if Task.isCancelled { return }
            preview = data.flatMap { UIImage(data: $0) }
        }
    }

    private func save() {
        let current = adj, jpeg = originalJPEG, theStore = store, id = recordId
        Task {
            let rendered = await Task.detached(priority: .userInitiated) {
                ResultRenderer.render(originalJPEG: jpeg, adjustments: current, quality: 0.95)
            }.value
            if let rendered { try? theStore.applyEdit(id: id, adjustments: current, renderedJPEG: rendered) }
            onSaved()
            dismiss()
        }
    }
}
