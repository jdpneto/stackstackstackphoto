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
                    ThumbnailCell(url: store.resultURL(for: rec))
                }
            }.padding(4)
        }
        .navigationTitle("Stacks")
        .onAppear { records = (try? store.loadAll()) ?? [] }
    }
}

/// A single gallery cell that loads a downsampled thumbnail off the main thread.
private struct ThumbnailCell: View {
    let url: URL
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
        .task(id: url) { image = await Thumbnailer.load(url, maxPixel: 240) }
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
