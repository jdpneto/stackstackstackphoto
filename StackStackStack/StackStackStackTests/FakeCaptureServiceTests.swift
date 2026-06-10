import XCTest
import StackEngineCore
@testable import StackStackStack

final class FakeCaptureServiceTests: XCTestCase {
    func testFakeReturnsRecipeFrameCount() async throws {
        let svc = FakeCaptureService(width: 8, height: 8)
        let frames = try await svc.captureBurst(recipe: CaptureRecipe(frameCount: 5, durationSeconds: 1))
        XCTAssertEqual(frames.count, 5)
        XCTAssertEqual(frames[0].width, 8)
        let img = ColorPipeline.process(frames[0])
        XCTAssertEqual(img.pixels.count, 64)
    }

    func testMotionMakesLightTrailsBrighterThanSmoothMotion() async throws {
        let svc = FakeCaptureService(width: 32, height: 32)
        let frames = try await svc.captureBurst(recipe: CaptureRecipe(frameCount: 16, durationSeconds: 1))
        let trails = Pipeline.reduce(frames, mode: .lightTrails)    // per-channel max → bright streak
        let smooth = Pipeline.reduce(frames, mode: .smoothMotion)   // mean → faint blur

        // Across the centre row the moving object leaves a brighter max-streak than the mean.
        let y = 16
        var trailsMax: Float = 0, smoothMax: Float = 0
        for x in 0..<32 {
            trailsMax = max(trailsMax, trails[x, y].x)
            smoothMax = max(smoothMax, smooth[x, y].x)
        }
        XCTAssertGreaterThan(trailsMax, smoothMax, "light trails must keep the moving highlight brighter than smooth motion")
    }

    func testFocusSweepRecipeProducesDistinctOrderedBrackets() async throws {
        let svc = FakeCaptureService(width: 32, height: 16)
        let recipe = CaptureRecipe.recipe(for: .depthOfField).applying(ProControls(frameCount: 4))
        let frames = try await svc.captureBurst(recipe: recipe)
        XCTAssertEqual(frames.count, 4)
        // Each bracket is sharp in a different band → mosaics must differ pairwise.
        for i in 0..<frames.count {
            for j in (i + 1)..<frames.count {
                XCTAssertNotEqual(frames[i].mosaic, frames[j].mosaic,
                                  "bracket \(i) and \(j) must differ (different sharp band)")
            }
        }
    }

    func testFocusSweepReportsProgressPerBracket() async throws {
        let svc = FakeCaptureService(width: 32, height: 16)
        let recipe = CaptureRecipe.recipe(for: .depthOfField).applying(ProControls(frameCount: 3))
        let counter = ProgressCounter()
        _ = try await svc.captureBurst(recipe: recipe, isSteady: { true },
                                       onProgress: { n in Task { await counter.record(n) } })
        // Give the recording tasks a beat to land.
        try await Task.sleep(nanoseconds: 50_000_000)
        let seen = await counter.values
        XCTAssertEqual(seen, [1, 2, 3])
    }
}

private actor ProgressCounter {
    private(set) var values: [Int] = []
    func record(_ n: Int) { values.append(n) }
}
