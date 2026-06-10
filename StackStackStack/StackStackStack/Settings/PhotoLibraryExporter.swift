import Photos

/// Writes an encoded image into the system photo library using ADD-ONLY authorization (the
/// lightweight permission — no library read). The system prompt appears contextually on the first
/// export. (spec §5)
enum PhotoLibraryExporter {
    enum ExportError: LocalizedError {
        case notAuthorized
        var errorDescription: String? { "Photos access is off. Enable Add-Only access in Settings ▸ Privacy ▸ Photos." }
    }

    /// Throws on denial or write failure; the caller treats failures as non-blocking.
    static func export(_ data: Data, format: ImageEncoder.Format) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw ExportError.notAuthorized }
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.uniformTypeIdentifier = format.utType.identifier
            request.addResource(with: .photo, data: data, options: options)
        }
    }
}
