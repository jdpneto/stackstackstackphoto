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
    @State private var loaded = false
    @State private var editSource: EditSource?
    @State private var sharing = false
    @State private var confirmingDelete = false
    @State private var rotating = false

    /// Everything the editor needs, loaded off the main thread before the sheet is presented.
    private struct EditSource: Identifiable {
        let id: UUID
        let original: Data
        let adjustments: ImageAdjustments
        let preview: UIImage?
        let format: ImageEncoder.Format
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let image {
                    ZoomableScrollView(image: image).ignoresSafeArea()
                } else if loaded {
                    VStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle")
                        Text("Couldn't load this photo.")
                    }.foregroundColor(.white)
                } else {
                    ProgressView().tint(.white)
                }
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Done") { dismiss() }
                }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button { rotate(by: -1) } label: { Image(systemName: "rotate.left") }
                        .accessibilityLabel("Rotate left").disabled(rotating)
                    Button { rotate(by: 1) } label: { Image(systemName: "rotate.right") }
                        .accessibilityLabel("Rotate right").disabled(rotating)
                    Button { sharing = true } label: { Image(systemName: "square.and.arrow.up") }
                        .accessibilityLabel("Share")
                    Button { openEditor() } label: { Image(systemName: "slider.horizontal.3") }
                        .accessibilityLabel("Edit")
                    Button(role: .destructive) { confirmingDelete = true } label: { Image(systemName: "trash") }
                        .accessibilityLabel("Delete")
                }
            }
            .toolbarBackground(.visible, for: .navigationBar)
        }
        // Decode a screen-sized image off the main thread (full-res would be wasteful for a viewer).
        .task {
            image = await Thumbnailer.load(store.resultURL(for: record), maxPixel: 2048)
            loaded = true
        }
        // Share the result file URL — the activity sheet's "Save Image" writes it to the camera roll
        // (and AirDrop/Messages/etc. get the full-quality JPEG).
        .sheet(isPresented: $sharing) {
            ShareSheet(items: [store.resultURL(for: record)])
        }
        .sheet(item: $editSource) { src in
            EditorView(originalJPEG: src.original, initialAdjustments: src.adjustments,
                       initialPreview: src.preview, recordId: src.id, recordFormat: src.format,
                       store: store) { renderedJPEG in
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
        let fmt = record.encoderFormat   // the record's own format (spec §4: never the current setting)
        Task {
            let loaded = await Task.detached(priority: .userInitiated) { () -> (Data, ImageAdjustments, Data?)? in
                guard let data = lib.originalData(for: id) else { return nil }
                let adj = lib.adjustments(for: id)
                let prev = ResultRenderer.render(originalJPEG: data, adjustments: adj, quality: 0.85, maxPixel: 1200, format: fmt)
                return (data, adj, prev)
            }.value
            guard let (data, adj, prevData) = loaded else { return }
            editSource = EditSource(id: id, original: data, adjustments: adj,
                                    preview: prevData.flatMap { UIImage(data: $0) }, format: fmt)
        }
    }

    /// Persist a 90° rotation as a non-destructive `quarterTurns` adjustment: render the original
    /// through the updated adjustments off-main, then save via the same path the editor uses.
    private func rotate(by delta: Int) {
        guard !rotating else { return }
        rotating = true
        let id = record.id, lib = store
        let fmt = record.encoderFormat   // the record's own format (spec §4: never the current setting)
        Task {
            let result: (ImageAdjustments, Data)? = await Task.detached(priority: .userInitiated) {
                // originalData and adjustments are safe to read off the main thread (file I/O only).
                guard let original = lib.originalData(for: id) else { return nil }
                var adj = lib.adjustments(for: id)
                adj.quarterTurns += delta   // ImageAdjustments normalizes into 0…3 via its didSet
                guard let rendered = ResultRenderer.render(originalJPEG: original, adjustments: adj, quality: 0.95, format: fmt)
                else { return nil }
                return (adj, rendered)
            }.value
            rotating = false
            guard let (adj, rendered) = result else { return }
            do {
                // applyEdit is a write (read-modify-write on index.json) — must be MainActor-confined.
                // The enclosing Task inherits the MainActor from the SwiftUI action that called rotate(by:).
                try lib.applyEdit(id: id, adjustments: adj, rendered: rendered)
                image = UIImage(data: rendered)   // reflect in the viewer
                onChanged()                        // refresh the gallery
            } catch { /* transient render/save failure: leave the current image */ }
        }
    }

    /// LibraryStore writes must stay MainActor-confined; a View action already runs on the MainActor.
    private func delete() {
        try? store.delete(id: record.id)
        onChanged()
        dismiss()
    }
}

/// Thin wrapper over `UIActivityViewController` so the standard share sheet (incl. "Save Image" →
/// camera roll) is available from SwiftUI.
private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
