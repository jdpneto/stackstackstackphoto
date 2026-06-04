import XCTest
import StackEngineCore
@testable import StackStackStack

final class FakeCaptureServiceTests: XCTestCase {
    func testFakeReturnsRequestedFrameCount() async throws {
        let svc = FakeCaptureService(width: 8, height: 8)
        let frames = try await svc.captureBurst(mode: .noiseReduction, frameCount: 5)
        XCTAssertEqual(frames.count, 5)
        XCTAssertEqual(frames[0].width, 8)
        // developing a fake frame should not crash and yields the right size
        let img = ColorPipeline.process(frames[0])
        XCTAssertEqual(img.pixels.count, 64)
    }
}
