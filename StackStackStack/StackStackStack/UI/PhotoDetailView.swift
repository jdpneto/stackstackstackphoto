import SwiftUI
import UIKit
import StackEngineCore

/// Full-screen viewer for one saved stack, opened by tapping a gallery thumbnail. Lets the user
/// share it (the iOS share sheet includes "Save Image" → camera roll), re-edit it, or delete it.
/// `onChanged` tells the gallery to reload after an edit or delete.
struct PhotoDetailView: View {
    let record: StackRecord
    let store: LibraryStore
    var onChanged: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var image: UIImage?
    @State private var editSource: EditSource?
    @State private var confirmingDelete = false

    /// Everything the editor needs, loaded off the main thread before the sheet is presented.
    private struct EditSource: Identifiable {
        let id: UUID
        let original: Data
        let adjustments: ImageAdjustments
        let preview: UIImage?
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let image {
                    Image(uiImage: image).resizable().scaledToFit()
                } else {
                    ProgressView().tint(.white)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    // Sharing the result file URL surfaces "Save Image" (→ Photos), AirDrop, Messages, etc.
                    ShareLink(item: store.resultURL(for: record)) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    Button { openEditor() } label: { Image(systemName: "slider.horizontal.3") }
                        .accessibilityLabel("Edit")
                    Button(role: .destructive) { confirmingDelete = true } label: { Image(systemName: "trash") }
                        .accessibilityLabel("Delete")
                }
            }
            .toolbarBackground(.visible, for: .navigationBar)
        }
        // Decode a screen-sized image off the main thread (full-res would be wasteful for a viewer).
        .task { image = await Thumbnailer.load(store.resultURL(for: record), maxPixel: 2048) }
        .sheet(item: $editSource) { src in
            EditorView(originalJPEG: src.original, initialAdjustments: src.adjustments,
                       initialPreview: src.preview, recordId: src.id, store: store) { renderedJPEG in
                image = UIImage(data: renderedJPEG)   // reflect the edit in the viewer
                onChanged()                           // and refresh the gallery (updatedAt bumped)
            }
        }
        .confirmationDialog("Delete this stack?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { delete() }
            Button("Cancel", role: .cancel) {}
        }
    }

    /// Load the original JPEG, persisted adjustments, and a downscaled preview OFF the main thread,
    /// then present the editor — so tapping Edit never blocks the UI on a full-res disk read/decode.
    private func openEditor() {
        let id = record.id
        let lib = store
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

    /// LibraryStore writes must stay MainActor-confined; a View action already runs on the MainActor.
    private func delete() {
        try? store.delete(id: record.id)
        onChanged()
        dismiss()
    }
}
