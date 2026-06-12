import XCTest
import simd
@testable import StackEngineCore

/// Regression for the dim-scene / bright-mover misregistration found on device (investigation
/// 2026-06-12): a handheld Smooth Motion burst of a DIM living room with a bright TV came out as
/// discrete displaced echoes of the TV frame/subtitles, with static furniture smeared.
///
/// Mechanism (validated by the investigation's probe suite): the whole-frame robust clip was an
/// ABSOLUTE linear-luma residual cap (0.02 ≈ |Δluma| 0.14). In a dim room the static background
/// (luma 0.01–0.1) only produces residuals ≲ 0.01 — negligible cost — while the bright TV
/// (luma 0.2–0.97) dominates: its scene cuts saturate at the cap (still 20–200× the background
/// signal) and its smooth pans stay UNDER the cap and are fully trusted. The coarse integer seed
/// additionally ran UNclipped SSD. Net effect: the optimizer fits the TV's content motion as
/// camera motion (43 px misregistration at 1.5 px of true drift on device; mean error 184 px on
/// this synthetic repro with clip 0.02 vs 1.2 px with clip 1e-4).
///
/// This test is the Swift port of the investigation's core repro (probe 2), SHRUNK from the
/// investigation's 1024 px / 20 frames to 512 px / 10 frames so the suite stays fast. The
/// mechanism — dim multi-scale static scene + bright moving "TV" region (~26% of the frame,
/// pans + cuts) + static bright subtitle bar + high-ISO noise + cumulative handheld drift
/// (~27 px @ 512, the same ~129 px at the device's working frame, + 0.7° roll ramp, frame-0
/// anchor) — is preserved, and red/green was re-verified at this size:
///   OLD code path (fixed clip 0.02, plain-SSD integer seed, no plausibility check), measured
///   2026-06-12: mean error 40.58 px, worst 163.22 px @ 512; static-region PSNR 41.0 dB
///   (vs 39.5 dB with no alignment at all — i.e. aligning made it barely better than nothing).
///   Fixed path (robust pre-pass + scene-adaptive clip + bounded fallback): see the asserts.
final class DimSceneAlignmentTests: XCTestCase {

    // MARK: - Geometry / poses

    /// Camera pose for one frame: roll `theta` (radians) about the image centre + scene-px translation.
    private struct Pose { var theta: Float; var tx: Float; var ty: Float }

    private let w = 512, h = 384
    private var cx: Float { Float(w - 1) / 2 }
    private var cy: Float { Float(h - 1) / 2 }
    /// The scene "furniture" below is authored in the investigation's 1024-px coordinates;
    /// `tex` maps a frame pixel onto that texture (512-px frame → ×2).
    private var tex: Float { 1024 / Float(w) }

    /// 10-frame / ~2 s handheld drift: a smooth, mostly-monotonic pan reaching ~27 px at this
    /// 512-px frame (~129 px at the device's working frame), gated-magnitude vertical sway, a
    /// slight roll ramp to ~0.7°, plus per-frame tremor jitter. Frame 0 is the anchor (identity),
    /// matching the streaming paths' contract.
    private func driftPoses(_ n: Int = 10) -> [Pose] {
        (0..<n).map { i in
            if i == 0 { return Pose(theta: 0, tx: 0, ty: 0) }
            let u = Float(i) / Float(n - 1)
            let jx = 1.5 * sin(3.7 * Float(i))          // per-frame jitter (tremor), deterministic
            let jy = 1.2 * sin(2.9 * Float(i) + 1)
            return Pose(theta: 0.012 * u + 0.0015 * sin(2.1 * Float(i)),   // roll ramp to ~0.7°
                        tx: 27 * powf(u, 1.3) + jx,
                        ty: 7 * sin(.pi * u) + jy)
        }
    }

    /// Scene coords of frame-i pixel q: s = R_theta(q − c) + c + T.
    private func sceneCoords(_ p: Pose, _ x: Float, _ y: Float) -> (Float, Float) {
        let vx = x - cx, vy = y - cy
        let co = cos(p.theta), si = sin(p.theta)
        return (co * vx - si * vy + cx + p.tx, si * vx + co * vy + cy + p.ty)
    }

    /// Ground-truth registration transform for `p` (what a perfect estimate should return):
    /// t.apply(v) = R_{−theta}(v − T) — derived from warp()'s convention
    /// out[x,y] = moving.sample(t.apply(q − c) + c).
    private func groundTruth(_ p: Pose) -> Transform2D {
        let co = cos(p.theta), si = sin(p.theta)
        return .similarity(scale: 1, rotation: -p.theta,
                           tx: -(co * p.tx + si * p.ty),
                           ty: -(-si * p.tx + co * p.ty))
    }

    /// Max displacement disagreement (px) between two transforms over the frame corners + centre.
    private func transformError(_ est: Transform2D, _ gt: Transform2D) -> Float {
        let pts: [(Float, Float)] = [(0, 0), (-cx, -cy), (cx, -cy), (-cx, cy), (cx, cy)]
        var worst: Float = 0
        for (vx, vy) in pts {
            let e = est.apply(vx, vy), g = gt.apply(vx, vy)
            worst = max(worst, simd_length(e - g))
        }
        return worst
    }

    // MARK: - Dim-living-room scene

    private struct RectF {
        var x0, y0, x1, y1: Float
        func contains(_ x: Float, _ y: Float) -> Bool { x >= x0 && x < x1 && y >= y0 && y < y1 }
        func inset(_ d: Float) -> RectF { RectF(x0: x0 + d, y0: y0 + d, x1: x1 - d, y1: y1 - d) }
    }

    // Scene-coordinate furniture (frame 0 == scene coords). All luma is LINEAR light: the dim
    // room sits at ~0.01–0.12; the TV at ~0.2–0.97.
    private let tvOuter = RectF(x0: 360, y0: 200, x1: 790, y1: 520)       // bezel (static, dark)
    private let tvContent = RectF(x0: 374, y0: 214, x1: 776, y1: 506)     // emissive content (changes per frame)
    private let subtitle = RectF(x0: 430, y0: 455, x1: 720, y1: 480)      // STATIC bright bar inside the content

    private func hash01(_ ix: Int32, _ iy: Int32) -> Float {
        var n = (ix &* 73856093) ^ (iy &* 19349663)
        n = n ^ (n >> 13); n = n &* 1274126177; n = n ^ (n >> 16)
        return Float(n & 0xFFFF) / 65535
    }
    private func hash01(_ fx: Float, _ fy: Float) -> Float {
        hash01(Int32(fx.isFinite ? max(min(fx, 1e6), -1e6) : 0),
               Int32(fy.isFinite ? max(min(fy, 1e6), -1e6) : 0))
    }

    /// Static (non-TV-content) scene luma at scene coords — dim room, multi-scale structure.
    private func staticLuma(_ sx: Float, _ sy: Float) -> Float {
        var v = 0.018 + 0.012 * sin(sx * 0.013) * sin(sy * 0.017)         // walls, slow shading
        v += (hash01(sx / 4, sy / 4) - 0.5) * 0.012                       // fine wall texture
        // doorway: bright static vertical strip (sharpness probe in the static region)
        if sx >= 60 && sx < 86 { v = 0.12 + (hash01(sx, sy / 3) - 0.5) * 0.01 }
        // sofa + shelf: mid-dark blocks
        if sx >= 820 && sx < 1010 && sy >= 420 && sy < 740 { v = 0.05 + (hash01(sx / 6, sy / 6) - 0.5) * 0.02 }
        if sx >= 120 && sx < 330 && sy >= 80 && sy < 150 { v = 0.06 + (hash01(sx / 5, sy / 5) - 0.5) * 0.025 }
        // plant: speckled leaves
        let dx = sx - 150, dy = sy - 540
        if dx * dx + dy * dy < 110 * 110 { v = 0.035 + (hash01(sx / 3, sy / 3) - 0.5) * 0.05 }
        // TV bezel (content interior is overridden by the per-frame renderer)
        if tvOuter.contains(sx, sy) { v = 0.006 }
        return min(max(v, 0.002), 0.95)
    }

    /// TV content at frame i: scene cuts every 3 frames + a moving pattern + STATIC subtitle.
    private func tvLuma(_ i: Int, _ sx: Float, _ sy: Float) -> Float {
        if subtitle.contains(sx, sy) { return 0.85 }                      // static subtitles
        let cuts: [Float] = [0.18, 0.50, 0.28, 0.62]
        let lvl = cuts[min(i / 3, cuts.count - 1)]
        let v = lvl + 0.22 * sin(0.045 * sx + 0.08 * sy + 1.9 * Float(i))
        return min(max(v, 0.01), 0.97)
    }

    private func sceneLuma(_ i: Int, _ sx: Float, _ sy: Float) -> Float {
        tvContent.contains(sx, sy) ? tvLuma(i, sx, sy) : staticLuma(sx, sy)
    }

    /// Deterministic Gaussian noise source (LCG + Box–Muller) — high-ISO sensor noise stand-in.
    private struct Rng {
        var state: UInt64
        mutating func next01() -> Float {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return Float(state >> 40) * (1.0 / 16777216.0)
        }
        mutating func gaussian() -> Float {
            let u1 = max(next01(), 1e-7), u2 = next01()
            return (-2 * log(u1)).squareRoot() * cos(2 * .pi * u2)
        }
    }

    private func renderFrame(_ i: Int, _ pose: Pose, noiseSigma: Float = 0.008) -> PixelImage {
        var img = PixelImage(width: w, height: h)
        var rng = Rng(state: UInt64(1000 + i))
        for y in 0..<h {
            for x in 0..<w {
                let (sx, sy) = sceneCoords(pose, Float(x), Float(y))
                let v = max(sceneLuma(i, sx * tex, sy * tex) + rng.gaussian() * noiseSigma, 0)
                img[x, y] = SIMD3(v, v, v)
            }
        }
        return img
    }

    /// The static scene rendered at the anchor pose (the ideal sharp background for PSNR).
    private func staticTruthAtAnchor() -> PixelImage {
        var img = PixelImage(width: w, height: h)
        for y in 0..<h {
            for x in 0..<w {
                let v = staticLuma(Float(x) * tex, Float(y) * tex)
                img[x, y] = SIMD3(v, v, v)
            }
        }
        return img
    }

    // MARK: - Metrics

    /// PSNR (peak 1.0) of `img` vs `truth` over the static region: border + dilated TV excluded.
    /// The border and the TV exclusion are in texture (1024) coordinates, like the furniture.
    private func staticRegionPSNR(_ img: PixelImage, _ truth: PixelImage) -> Float {
        let ex = tvOuter.inset(-30)
        let border = Int(110 / tex)
        var sum = 0.0
        var n = 0
        for y in border..<(h - border) {
            for x in border..<(w - border) {
                if ex.contains(Float(x) * tex, Float(y) * tex) { continue }
                let d = Double(img[x, y].x - truth[x, y].x)
                sum += d * d; n += 1
            }
        }
        return Float(10 * log10(1.0 / (sum / Double(n))))
    }

    /// The exact per-frame transform the product paths compute: downscale both frames to the
    /// estimate edge, run the shared whole-frame helper, translation scaled back up.
    private func productAnchorTransform(_ anchor: PixelImage, _ moving: PixelImage) -> Transform2D {
        func small(_ img: PixelImage) -> PixelImage {
            var out = img
            while max(out.width, out.height) > Pipeline.alignmentEstimateEdge { out = ImagePyramid.reduce(out) }
            return out
        }
        let refSmall = small(anchor), movSmall = small(moving)
        let factor = Float(anchor.width) / Float(refSmall.width)
        return Pipeline.estimateWholeFrameAlignment(referenceSmall: refSmall, movingSmall: movSmall,
                                                    factor: factor, searchRange: 8)
    }

    // MARK: - Test

    func testDimRoomBrightTvDriftBurstStaysRegistered() throws {
        let poses = driftPoses()
        let frames = parallelMap(Array(poses.indices)) { self.renderFrame($0, poses[$0]) }

        // (1) Per-frame whole-frame registration accuracy through the shared product helper —
        // the SAME transform every product path (batch + both streaming) computes per frame.
        // Computed once, in parallel, and reused for the quality gate below so this stays a
        // tolerable cost in the routine debug `swift test` loop.
        let transforms = parallelMap(Array(1..<frames.count)) { self.productAnchorTransform(frames[0], frames[$0]) }
        let errs = (1..<frames.count).map { transformError(transforms[$0 - 1], groundTruth(poses[$0])) }
        let meanErr = errs.reduce(0, +) / Float(errs.count)
        let worstErr = errs.max()!
        print(String(format: "DimScene: per-frame anchor error mean %.2f px, worst %.2f px", meanErr, worstErr))

        XCTAssertLessThan(meanErr, 3,
            "whole-frame alignment must lock onto the dim static room, not the bright TV's content motion (mean error \(meanErr) px)")
        XCTAssertLessThan(worstErr, 8,
            "no frame may be hijacked by the TV's pans/cuts (worst error \(worstErr) px)")

        // (2) Static-region quality gate — the user-visible "smeared furniture / echoed TV"
        // symptom. The aligned mean below is exactly what the streaming Smooth Motion path folds
        // from these same per-frame transforms (the streaming wiring itself is pinned by
        // PipelineStreamingTests; re-running the serial streaming entry point here would just
        // recompute the 19 estimates a second time).
        let truth = staticTruthAtAnchor()
        let alignedFrames = parallelMap(Array(frames.indices)) { i in
            i == 0 ? frames[0] : AffineAligner.warp(frames[i], by: transforms[i - 1])
        }
        let result = StackReducer.mean(alignedFrames)
        let noAlign = StackReducer.mean(frames)
        let alignedPSNR = staticRegionPSNR(result, truth)
        let baselinePSNR = staticRegionPSNR(noAlign, truth)
        print(String(format: "DimScene: static-region PSNR no-align %.1f dB, aligned %.1f dB", baselinePSNR, alignedPSNR))

        XCTAssertGreaterThan(alignedPSNR, baselinePSNR + 6,
            "aligned smooth-motion stack must clearly out-resolve the unaligned mean in the static region "
            + "(no-align \(baselinePSNR) dB, aligned \(alignedPSNR) dB)")
    }
}
