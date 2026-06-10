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

    func testProFrameCountIsCappedAt20() {
        let recipe = CaptureRecipe(frameCount: 8, durationSeconds: 0.5)
            .applying(ProControls(frameCount: 40))
        XCTAssertEqual(recipe.frameCount, 20, "burst frame count must be hard-capped at 20")
    }

    func testProFrameCountFloorIsRespected() {
        let recipe = CaptureRecipe(frameCount: 8, durationSeconds: 0.5)
            .applying(ProControls(frameCount: 0))
        XCTAssertEqual(recipe.frameCount, 1, "frame count must stay >= 1")
    }

    // MARK: - Depth focus sweep (spec 2026-06-10 §5.1)

    func testDepthRecipeHasFullRangeSweepMatchingFrameCount() throws {
        let r = CaptureRecipe.recipe(for: .depthOfField)
        let sweep = try XCTUnwrap(r.focusSweep)
        XCTAssertEqual(sweep.steps, r.frameCount, "one bracket per sweep step")
        XCTAssertEqual(sweep.near, 0)
        XCTAssertEqual(sweep.far, 1)
        XCTAssertEqual(sweep.positions.count, r.frameCount)
        XCTAssertEqual(sweep.positions.first, 0)
        XCTAssertEqual(sweep.positions.last, 1)
    }

    func testSweepPositionsAreMonotonicNearToFar() {
        let positions = CaptureRecipe.FocusSweep(near: 0.2, far: 0.8, steps: 5).positions
        XCTAssertEqual(positions.count, 5)
        for i in 1..<positions.count {
            XCTAssertGreaterThan(positions[i], positions[i - 1], "sweep order is what the chain aligner relies on")
        }
    }

    func testSweepNormalizesAReversedRange() {
        let s = CaptureRecipe.FocusSweep(near: 0.9, far: 0.1, steps: 5)
        XCTAssertEqual(s.near, 0.1, accuracy: 1e-6)
        XCTAssertEqual(s.far, 0.9, accuracy: 1e-6)
    }

    func testApplyingMergesSweepRangeAndKeepsStepsEqualToFrames() throws {
        let r = CaptureRecipe.recipe(for: .depthOfField)
            .applying(ProControls(frameCount: 6, focusSweepNear: 0.2, focusSweepFar: 0.8))
        let sweep = try XCTUnwrap(r.focusSweep)
        XCTAssertEqual(r.frameCount, 6)
        XCTAssertEqual(sweep.steps, 6)
        XCTAssertEqual(sweep.near, 0.2, accuracy: 1e-6)
        XCTAssertEqual(sweep.far, 0.8, accuracy: 1e-6)
    }

    func testManualFocusIsIgnoredForSweepRecipes() {
        // The sweep owns lens position; a lingering Pro single-focus value must not leak in.
        let r = CaptureRecipe.recipe(for: .depthOfField).applying(ProControls(focus: 0.5))
        XCTAssertNil(r.manualFocus)
    }

    func testNonDepthRecipesHaveNoSweep() {
        XCTAssertNil(CaptureRecipe.recipe(for: .noiseReduction).focusSweep)
        XCTAssertNil(CaptureRecipe.recipe(for: .lightTrails).focusSweep)
    }

    func testSteadinessGatePolicy() {
        // Long-exposure looks gate (existing) and Depth gates too — per-step handheld motion must
        // stay inside the chain aligner's bounds (spec 2026-06-10 §5.3).
        XCTAssertTrue(StackMode.smoothMotion.usesSteadinessGate)
        XCTAssertTrue(StackMode.lightTrails.usesSteadinessGate)
        XCTAssertTrue(StackMode.depthOfField.usesSteadinessGate)
        XCTAssertFalse(StackMode.noiseReduction.usesSteadinessGate)
        XCTAssertFalse(StackMode.lowLightBoost.usesSteadinessGate)
    }
}
