import Foundation
import Combine

/// The app's user preferences: a thin observable wrapper over UserDefaults (three keys — no
/// settings framework). Injected once at the app root via `.environmentObject`. (spec §3.1)
final class AppSettings: ObservableObject {
    private let defaults: UserDefaults

    /// Mirror every successful save into the system photo library (add-only). Opt-in.
    @Published var saveToPhotos: Bool { didSet { defaults.set(saveToPhotos, forKey: Keys.saveToPhotos) } }
    /// Library/encode format for NEW captures (existing records keep their own format).
    @Published var exportFormat: ImageEncoder.Format { didSet { defaults.set(exportFormat.rawValue, forKey: Keys.exportFormat) } }
    /// First-launch onboarding gate; "Replay Introduction" presents the flow without resetting this.
    @Published var hasSeenOnboarding: Bool { didSet { defaults.set(hasSeenOnboarding, forKey: Keys.hasSeenOnboarding) } }

    private enum Keys {
        static let saveToPhotos = "saveToPhotos"
        static let exportFormat = "exportFormat"
        static let hasSeenOnboarding = "hasSeenOnboarding"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.saveToPhotos = defaults.bool(forKey: Keys.saveToPhotos)
        self.exportFormat = defaults.string(forKey: Keys.exportFormat)
            .flatMap(ImageEncoder.Format.init(rawValue:)) ?? .jpeg   // unknown/corrupt value → JPEG
        self.hasSeenOnboarding = defaults.bool(forKey: Keys.hasSeenOnboarding)
    }
}
