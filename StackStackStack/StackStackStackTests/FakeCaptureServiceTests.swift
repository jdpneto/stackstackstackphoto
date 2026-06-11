import XCTest
import Foundation
import StackEngineCore
@testable import StackStackStack

final class FakeCaptureServiceTests: XCTestCase {
    func testFakeReturnsRecipeFrameCount() async throws {
        let svc = FakeCaptureService(width: 8, height: 8)
        let burst = try await svc.captureBurst(recipe: CaptureRecipe(frameCount: 5, durationSeconds: 1))
        guard case .raw(let frames) = burst else { XCTFail("expected .raw burst"); return }
        XCTAssertEqual(frames.count, 5)
        XCTAssertEqual(frames[0].width, 8)
        let img = ColorPipeline.process(frames[0])
        XCTAssertEqual(img.pixels.count, 64)
    }

    func testMotionMakesLightTrailsBrighterThanSmoothMotion() async throws {
        let svc = FakeCaptureService(width: 32, height: 32)
        let burst = try await svc.captureBurst(recipe: CaptureRecipe(frameCount: 16, durationSeconds: 1))
        guard case .raw(let frames) = burst else { XCTFail("expected .raw burst"); return }
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
        let burst = try await svc.captureBurst(recipe: recipe)
        guard case .raw(let frames) = burst else { XCTFail("expected .raw burst"); return }
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
        let log = ProgressLog()
        // FakeCaptureService fires onProgress synchronously per bracket — record directly with no
        // Task wrapping so the ordering is guaranteed without any sleep. (design §12)
        _ = try await svc.captureBurst(recipe: recipe, isSteady: { true },
                                       onProgress: { n in log.record(n) })
        XCTAssertEqual(log.values, [1, 2, 3])
    }
}

/// Thread-safe progress log for synchronous callbacks. `NSLock.withLock` is available iOS 16+.
private final class ProgressLog: @unchecked Sendable {
    private let lock = NSLock()
    private var _values: [Int] = []
    var values: [Int] { lock.withLock { _values } }
    func record(_ n: Int) { lock.withLock { _values.append(n) } }
}
