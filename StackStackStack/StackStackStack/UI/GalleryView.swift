import SwiftUI
import UIKit
import ImageIO

struct GalleryView: View {
    @State private var records: [StackRecord] = []
    private let store = LibraryStore()
    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 4)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(records) { rec in
                    // version = updatedAt so an edited result's cell reloads (the file bytes changed
                    // but the URL didn't).
                    ThumbnailCell(url: store.resultURL(for: rec), version: rec.updatedAt ?? rec.createdAt)
                }
            }.padding(4)
        }
        .navigationTitle("Stacks")
        // Load the index off the main thread (re-runs when the tab re-appears, picking up edits).
        .task {
            let lib = store
            records = await Task.detached(priority: .userInitiated) { (try? lib.loadAll()) ?? [] }.value
        }
    }
}

/// A single gallery cell that loads a downsampled thumbnail off the main thread.
private struct ThumbnailCell: View {
    let url: URL
    let version: Date
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Color(white: 0.15)
            }
        }
        .frame(height: 110)
        .clipped()
        // Re-run when either the URL or the version changes (an edit bumps version → reload).
        .task(id: "\(url.path)#\(version.timeIntervalSince1970)") {
            image = await Thumbnailer.load(url, maxPixel: 240)
        }
    }
}

enum Thumbnailer {
    /// Decode a downsampled thumbnail with ImageIO on a background task (never the main thread),
    /// so large full-res JPEGs don't stall scrolling or blow up memory for 110pt cells.
    static func load(_ url: URL, maxPixel: Int) async -> UIImage? {
        await Task.detached(priority: .utility) {
            guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            let opts: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            ]
            guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
            return UIImage(cgImage: cg)
        }.value
    }
}
