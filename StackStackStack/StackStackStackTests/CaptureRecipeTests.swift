import XCTest
import StackEngineCore
@testable import StackStackStack

final class CaptureRecipeTests: XCTestCase {
    func testLongExposureLooksCaptureMoreFramesThanNoiseReduction() {
        let nr = CaptureRecipe.recipe(for: .noiseReduction)
        let low = CaptureRecipe.recipe(for: .lowLightBoost)
        let smooth = CaptureRecipe.recipe(for: .smoothMotion)
        let trails = CaptureRecipe.recipe(for: .lightTrails)
        XCTAssertGreaterThanOrEqual(low.frameCount, nr.frameCount)
        XCTAssertGreaterThan(smooth.frameCount, nr.frameCount)
        XCTAssertGreaterThan(trails.frameCount, nr.frameCount)
        XCTAssertGreaterThan(smooth.durationSeconds, nr.durationSeconds)
        XCTAssertGreaterThan(trails.durationSeconds, smooth.durationSeconds)
        XCTAssertGreaterThan(nr.frameCount, 0)
    }
}
