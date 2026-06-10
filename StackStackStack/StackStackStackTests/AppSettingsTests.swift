import XCTest
@testable import StackStackStack

final class AppSettingsTests: XCTestCase {
    /// A throwaway suite so tests never touch the app's real defaults.
    private func makeSettings() -> (AppSettings, UserDefaults) {
        let suite = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        return (AppSettings(defaults: suite), suite)
    }

    func testDefaultsAreSafeOutOfTheBox() {
        let (s, _) = makeSettings()
        XCTAssertFalse(s.saveToPhotos, "Photos export is opt-in")
        XCTAssertEqual(s.exportFormat, .jpeg, "JPEG until the user opts into HEIC")
        XCTAssertFalse(s.hasSeenOnboarding, "fresh install shows onboarding")
    }

    func testValuesRoundTripThroughDefaults() {
        let (s, suite) = makeSettings()
        s.saveToPhotos = true
        s.exportFormat = .heic
        s.hasSeenOnboarding = true
        // A second instance over the same suite sees the persisted values.
        let s2 = AppSettings(defaults: suite)
        XCTAssertTrue(s2.saveToPhotos)
        XCTAssertEqual(s2.exportFormat, .heic)
        XCTAssertTrue(s2.hasSeenOnboarding)
    }

    func testUnknownStoredFormatFallsBackToJPEG() {
        let (_, suite) = makeSettings()
        suite.set("avif", forKey: "exportFormat")   // a future/corrupt value
        XCTAssertEqual(AppSettings(defaults: suite).exportFormat, .jpeg)
    }

    func testFormatRawValuesAreStableStorageKeys() {
        XCTAssertEqual(ImageEncoder.Format.jpeg.rawValue, "jpeg")
        XCTAssertEqual(ImageEncoder.Format.heic.rawValue, "heic")
    }
}
