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

    func testApplyingOverridesFrameCountOnly() {
        let base = CaptureRecipe.recipe(for: .noiseReduction)   // 8 frames, 0.5s
        let r = base.applying(ProControls(frameCount: 20))
        XCTAssertEqual(r.frameCount, 20)
        XCTAssertEqual(r.durationSeconds, base.durationSeconds)  // duration untouched
        XCTAssertNil(r.manualISO)
    }

    func testApplyingAutoLeavesRecipeUnchanged() {
        let base = CaptureRecipe.recipe(for: .lightTrails)
        XCTAssertEqual(base.applying(.auto), base)
    }

    func testApplyingPropagatesManualExposure() {
        let r = CaptureRecipe.recipe(for: .noiseReduction)
            .applying(ProControls(iso: 800, shutterSeconds: 0.02, focus: 0.5))
        XCTAssertEqual(r.manualISO, 800)
        XCTAssertEqual(r.manualShutterSeconds, 0.02)
        XCTAssertEqual(r.manualFocus, 0.5)
    }

    func testApplyingClampsFrameCountToAtLeastOne() {
        let r = CaptureRecipe.recipe(for: .noiseReduction).applying(ProControls(frameCount: 0))
        XCTAssertEqual(r.frameCount, 1)
    }
}
