import XCTest
@testable import StackStackStack

final class BurstSettingsTests: XCTestCase {
    func testPhotoCountIsClampedToTwoThroughMax() {
        XCTAssertEqual(BurstSettings.maxPhotoCount, 30, "long-exposure (streaming) burst cap")
        XCTAssertEqual(BurstSettings(photoCount: 99, durationSeconds: 5).photoCount, BurstSettings.maxPhotoCount)
        XCTAssertEqual(BurstSettings(photoCount: 0, durationSeconds: 5).photoCount, 2)
        XCTAssertEqual(BurstSettings(photoCount: 2, durationSeconds: 5).photoCount, 2)                       // lower boundary inclusive
        XCTAssertEqual(BurstSettings(photoCount: BurstSettings.maxPhotoCount, durationSeconds: 5).photoCount, BurstSettings.maxPhotoCount) // upper boundary inclusive
        XCTAssertEqual(BurstSettings(photoCount: 25, durationSeconds: 5).photoCount, 25)                     // within the new range (was clamped at 20)
    }

    func testDurationIsClampedToOneThroughSixty() {
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 999).durationSeconds, 60, accuracy: 1e-9)
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 0).durationSeconds, 1, accuracy: 1e-9)
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 18).durationSeconds, 18, accuracy: 1e-9)
    }
}
