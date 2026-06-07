import XCTest
@testable import StackStackStack

final class BurstSettingsTests: XCTestCase {
    func testPhotoCountIsClampedToTwoThroughTwenty() {
        XCTAssertEqual(BurstSettings(photoCount: 99, durationSeconds: 5).photoCount, CaptureRecipe.maxBurstFrames)
        XCTAssertEqual(BurstSettings(photoCount: 0, durationSeconds: 5).photoCount, 2)
        XCTAssertEqual(BurstSettings(photoCount: 2, durationSeconds: 5).photoCount, 2)                       // lower boundary inclusive
        XCTAssertEqual(BurstSettings(photoCount: CaptureRecipe.maxBurstFrames, durationSeconds: 5).photoCount, CaptureRecipe.maxBurstFrames) // upper boundary inclusive
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 5).photoCount, 10)
    }

    func testDurationIsClampedToOneThroughSixty() {
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 999).durationSeconds, 60, accuracy: 1e-9)
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 0).durationSeconds, 1, accuracy: 1e-9)
        XCTAssertEqual(BurstSettings(photoCount: 10, durationSeconds: 18).durationSeconds, 18, accuracy: 1e-9)
    }
}
