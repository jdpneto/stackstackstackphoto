import SwiftUI

/// The app's third area (bible §15.1/§15.6): every row is backed by a real feature. (spec §3.2)
struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var coordinator: StackCaptureCoordinator
    let store: LibraryStore
    /// Set by the About section; the app root presents the onboarding cover.
    @Binding var showOnboarding: Bool

    @State private var usedBytes: Int64?
    @State private var stackCount: Int?
    @State private var confirmDeleteAll = false
    @State private var deleteError: String?

    var body: some View {
        Form {
            Section("Capture & Export") {
                Toggle("Save to Photos", isOn: $settings.saveToPhotos)
                Picker("Format", selection: $settings.exportFormat) {
                    Text("JPEG").tag(ImageEncoder.Format.jpeg)
                    Text("HEIC").tag(ImageEncoder.Format.heic)
                }
            }
            Section("Storage") {
                LabeledContent("Stacks", value: stackCount.map(String.init) ?? "…")
                LabeledContent("Space Used", value: usedBytes.map {
                    ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) } ?? "…")
                Button("Delete All Stacks", role: .destructive) { confirmDeleteAll = true }
                    .disabled((stackCount ?? 0) == 0)
                if let deleteError { Text(deleteError).font(.caption).foregroundColor(.red) }
            }
            Section("This Device") {
                LabeledContent("RAW Capture", value: coordinator.supportsRAW ? "Supported" : "Not supported")
                LabeledContent("Depth (Manual Focus)", value: coordinator.supportsDepth ? "Supported" : "Not supported")
            }
            Section("About") {
                LabeledContent("Version", value: Self.versionString)
                Button("Replay Introduction") { showOnboarding = true }
            }
        }
        .navigationTitle("Settings")
        .task { await refreshStorage() }
        .confirmationDialog("Delete all stacks? This cannot be undone.",
                            isPresented: $confirmDeleteAll, titleVisibility: .visible) {
            Button("Delete All", role: .destructive) {
                do { try store.deleteAll(); deleteError = nil } catch { deleteError = error.localizedDescription }
                Task { await refreshStorage() }
            }
        }
    }

    /// Storage accounting is file I/O — compute off-main, render a placeholder until ready. (spec §8)
    private func refreshStorage() async {
        let lib = store
        let (bytes, count) = await Task.detached {
            (lib.storageUsedBytes(), (try? lib.loadAll().count) ?? 0)
        }.value
        usedBytes = bytes
        stackCount = count
    }

    private static var versionString: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }
}
