import SwiftUI
import UIKit

struct GalleryView: View {
    @State private var records: [StackRecord] = []
    private let store = LibraryStore()
    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 4)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(records) { rec in
                    if let data = try? Data(contentsOf: store.resultURL(for: rec)),
                       let ui = UIImage(data: data) {
                        Image(uiImage: ui).resizable().scaledToFill()
                            .frame(height: 110).clipped()
                    }
                }
            }.padding(4)
        }
        .navigationTitle("Stacks")
        .onAppear { records = (try? store.loadAll()) ?? [] }
    }
}
