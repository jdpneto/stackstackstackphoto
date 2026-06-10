import simd

/// Scale-aware (similarity) registration for focus-breathing frames: warp a frame by a
/// `Transform2D` about its centre, and estimate the transform that aligns a moving frame to a
/// reference by a deterministic intensity pattern search on the luma proxy (spec §3.2).
public enum AffineAligner {
    /// Warp `img` by `t` about the image centre, bilinear + edge-clamped:
    /// out[x,y] samples img at t.apply(x − cx, y − cy) + (cx, cy).
    public static func warp(_ img: PixelImage, by t: Transform2D) -> PixelImage {
        let w = img.width, h = img.height
        let cx = Float(w - 1) / 2, cy = Float(h - 1) / 2
        var out = PixelImage(width: w, height: h)
        for y in 0..<h {
            for x in 0..<w {
                let p = t.apply(Float(x) - cx, Float(y) - cy)
                out[x, y] = sampleRGB(img, p.x + cx, p.y + cy)
            }
        }
        return out
    }

    /// Estimate the similarity transform that best aligns `moving` to `reference`, minimising luma
    /// SSD. COARSE-TO-FINE over a Gaussian luma pyramid: the coarsest level is smooth (no aliasing →
    /// a global basin), then each finer level refines. scale/rotation are resolution-invariant;
    /// translation doubles per finer level. Robust on real high-frequency frames.
    /// `robustClip` (when set) caps each pixel's squared luma residual, so a region that moves
    /// differently from the global motion — a person/video in a handheld scene — can't pull the
    /// estimate off the static background it should lock onto. nil = plain SSD.
    /// `translationHint` seeds the coarsest-level optimizer (before the integer translation search),
    /// measured in pixels at the finest (input) resolution; useful when the caller has a robust
    /// prior on translation (e.g. from a robust-SSD pre-pass) that defeats a plain-SSD search.
    public static func estimate(reference ref: PixelImage, moving mov: PixelImage,
                                translationSearch: Int = 8, robustClip: Float? = nil,
                                translationHint: SIMD2<Float>? = nil) -> Transform2D {
        precondition(ref.width == mov.width && ref.height == mov.height)
        let refPyr = ImagePyramid.gaussian(ref, minSize: 24)
        let movPyr = ImagePyramid.gaussian(mov, minSize: 24)
        let levels = refPyr.count
        var s: Float = 1, r: Float = 0
        var tx: Float = (translationHint?.x ?? 0) / Float(1 << (levels - 1))
        var ty: Float = (translationHint?.y ?? 0) / Float(1 << (levels - 1))
        for lvl in stride(from: levels - 1, through: 0, by: -1) {   // coarsest → finest
            let rL = Luma.luminance(refPyr[lvl]), mL = Luma.luminance(movPyr[lvl])
            let lw = refPyr[lvl].width, lh = refPyr[lvl].height
            // Only run the integer translation search when there is no caller-provided hint.
            let tInit = (translationHint == nil && lvl == levels - 1) ? translationSearch : 0
            (s, r, tx, ty) = refine(rL, mL, width: lw, height: lh, s: s, r: r, tx: tx, ty: ty,
                                    translationInit: tInit, robustClip: robustClip)
            if lvl > 0 { tx *= 2; ty *= 2 }   // propagate translation to the next finer level
        }
        return .similarity(scale: s, rotation: r, tx: tx, ty: ty)
    }

    /// One pyramid level of the deterministic Hooke–Jeeves search over scale / rotation / sub-pixel
    /// translation, starting from `(s,r,tx,ty)`. `translationInit > 0` seeds translation by an integer
    /// SSD search (only needed at the coarsest level). Scale is clamped to a sane range.
    private static func refine(_ refL: [Float], _ movL: [Float], width w: Int, height h: Int,
                               s s0: Float, r r0: Float, tx tx0: Float, ty ty0: Float,
                               translationInit: Int, robustClip: Float?) -> (Float, Float, Float, Float) {
        var s = s0, r = r0, tx = tx0, ty = ty0
        if translationInit > 0 {
            let t0 = Alignment.estimateTranslation(referenceLuma: refL, movingLuma: movL,
                                                   width: w, height: h, searchRange: translationInit)
            tx = Float(t0.dx); ty = Float(t0.dy)
        }
        func cost(_ s: Float, _ r: Float, _ tx: Float, _ ty: Float) -> Float {
            ssdWarped(movL, refL, width: w, height: h,
                      by: .similarity(scale: s, rotation: r, tx: tx, ty: ty), robustClip: robustClip)
        }
        var best = cost(s, r, tx, ty)
        var stepS: Float = 0.05, stepR: Float = 0.04, stepT: Float = 1.0
        let minScale: Float = 0.5, maxScale: Float = 2.0
        var guardCount = 0
        while stepT > 0.01 && guardCount < 1000 {
            guardCount += 1
            var improved = false
            let trials: [(Float, Float, Float, Float)] = [
                ( stepS, 0, 0, 0), (-stepS, 0, 0, 0),
                (0,  stepR, 0, 0), (0, -stepR, 0, 0),
                (0, 0,  stepT, 0), (0, 0, -stepT, 0),
                (0, 0, 0,  stepT), (0, 0, 0, -stepT),
            ]
            for (dS, dR, dTx, dTy) in trials {
                let ns = s + dS
                if ns < minScale || ns > maxScale { continue }
                let c = cost(ns, r + dR, tx + dTx, ty + dTy)
                if c < best - 1e-9 { best = c; s = ns; r += dR; tx += dTx; ty += dTy; improved = true }
            }
            if !improved { stepS *= 0.5; stepR *= 0.5; stepT *= 0.5 }
        }
        return (s, r, tx, ty)
    }

    /// Estimate the registration of `moving` to `reference` and return `moving` warped into the
    /// reference frame.
    public static func align(reference ref: PixelImage, moving mov: PixelImage) -> PixelImage {
        warp(mov, by: estimate(reference: ref, moving: mov))
    }

    /// Mean SSD between `reference` luma and `moving` luma warped by `t` (centred, bilinear).
    private static func ssdWarped(_ movL: [Float], _ refL: [Float], width w: Int, height h: Int,
                                  by t: Transform2D, robustClip: Float?) -> Float {
        let cx = Float(w - 1) / 2, cy = Float(h - 1) / 2
        var sum: Float = 0
        for y in 0..<h {
            for x in 0..<w {
                let p = t.apply(Float(x) - cx, Float(y) - cy)
                let m = sampleLuma(movL, width: w, height: h, p.x + cx, p.y + cy)
                let d = m - refL[y * w + x]
                let d2 = d * d
                sum += robustClip.map { Swift.min(d2, $0) } ?? d2   // cap outliers (moving regions)
            }
        }
        return sum / Float(w * h)
    }

    // MARK: - Chain alignment for focus sweeps (spec 2026-06-10 §4.2)

    /// Chain-align a focus sweep: estimate a similarity link between each ADJACENT pair — whose
    /// blur is nearly identical, so the SSD cost is valid there, unlike a sharp-vs-defocused
    /// direct-to-reference fit (the documented spurious-warp failure) — validate each link against
    /// `bounds`, and compose links outward from `referenceIndex`.
    ///
    /// Returns one transform per frame mapping reference coords → that frame's coords (identity at
    /// the reference); `warp(frames[i], by: result[i])` aligns frame i. Frames MUST be in sweep
    /// (focus) order — adjacency is what makes the links well-conditioned.
    public static func alignChain(_ frames: [PixelImage], referenceIndex: Int,
                                  bounds: ChainBounds = .default) -> [Transform2D] {
        precondition(frames.indices.contains(referenceIndex), "referenceIndex out of range")
        precondition(frames.allSatisfy { $0.width == frames[0].width && $0.height == frames[0].height },
                     "all frames must be the same size")
        var links = [Transform2D](repeating: .identity, count: frames.count)
        for i in (referenceIndex + 1)..<frames.count {
            links[i] = boundedLink(reference: frames[i - 1], moving: frames[i], bounds: bounds)
        }
        for i in stride(from: referenceIndex - 1, through: 0, by: -1) {
            links[i] = boundedLink(reference: frames[i + 1], moving: frames[i], bounds: bounds)
        }
        return accumulateLinks(links, referenceIndex: referenceIndex)
    }

    /// Accumulate per-adjacent-pair links into per-frame warp-to-reference transforms, composing
    /// outward from `referenceIndex`. `links[referenceIndex]` is ignored (the reference maps to
    /// identity). This function is separated from link estimation so it can be unit-tested directly
    /// with exact algebraically-constructed links, bypassing estimator noise.
    ///
    /// Composition direction: `transforms[i] = links[i].composed(with: transforms[i-1])` for the
    /// up-sweep, so `transforms[i].apply(p)` = `links[i].apply(transforms[i-1].apply(p))` — each
    /// link is applied in sequence from the reference outward (reference side applied last).
    static func accumulateLinks(_ links: [Transform2D], referenceIndex: Int) -> [Transform2D] {
        var transforms = [Transform2D](repeating: .identity, count: links.count)
        // Up the sweep: link maps frame[i-1] coords → frame[i] coords.
        for i in (referenceIndex + 1)..<links.count {
            transforms[i] = links[i].composed(with: transforms[i - 1])
        }
        // Down the sweep: link maps frame[i+1] coords → frame[i] coords.
        for i in stride(from: referenceIndex - 1, through: 0, by: -1) {
            transforms[i] = links[i].composed(with: transforms[i + 1])
        }
        return transforms
    }

    /// One chain link: estimate the moving→reference similarity on a reduced copy (cheap; matches
    /// the Pipeline's estimate-small/scale-translation-up pattern), then accept it only if it is
    /// physically plausible for one focus step. An implausible fit is a blur difference posing as
    /// warp — re-estimate translation-only, which cannot smear detail.
    private static func boundedLink(reference ref: PixelImage, moving mov: PixelImage,
                                    bounds: ChainBounds) -> Transform2D {
        let (refSmall, factor) = reduceForEstimate(ref)
        let (movSmall, _) = reduceForEstimate(mov)
        // Pre-compute a robust translation so the Hooke–Jeeves optimizer starts at the right basin.
        // Plain-SSD translation search can be pulled toward focal-band alignment (band k+1 aligning
        // with band k) rather than handheld-drift correction; robust SSD suppresses that.
        let robustShift = Alignment.estimateTranslation(reference: refSmall, moving: movSmall,
                                                        searchRange: 8, robustClip: bounds.robustClip)
        let hint = SIMD2<Float>(Float(robustShift.dx), Float(robustShift.dy))
        // Use robust clip + the robust translation hint so the optimizer starts in the right basin.
        let t = estimate(reference: refSmall, moving: movSmall, robustClip: bounds.robustClip,
                         translationHint: hint)
        let scale = (t.a * t.a + t.c * t.c).squareRoot()
        let rotation = atan2(t.c, t.a)
        let translation = (t.tx * t.tx + t.ty * t.ty).squareRoot()
        let longEdge = Float(max(refSmall.width, refSmall.height))
        if abs(scale - 1) <= bounds.maxScaleDelta,
           abs(rotation) <= bounds.maxRotationRadians,
           translation <= bounds.maxTranslationFraction * longEdge {
            return Transform2D(a: t.a, b: t.b, c: t.c, d: t.d, tx: t.tx * factor, ty: t.ty * factor)
        }
        // Scale or rotation is implausible — fall back to robust translation-only (no smearing).
        return .similarity(scale: 1, rotation: 0,
                           tx: Float(robustShift.dx) * factor, ty: Float(robustShift.dy) * factor)
    }

    /// Halve until the long edge is within `maxEdge`; returns the reduced image and the factor to
    /// scale a reduced-space translation back to input pixels (powers of 2 — exact).
    private static func reduceForEstimate(_ img: PixelImage, maxEdge: Int = 512) -> (PixelImage, Float) {
        var out = img
        var factor: Float = 1
        while max(out.width, out.height) > maxEdge {
            out = ImagePyramid.reduce(out)
            factor *= 2
        }
        return (out, factor)
    }

    // MARK: - Private samplers (bilinear, edge-clamped)

    private static func sampleRGB(_ img: PixelImage, _ fxIn: Float, _ fyIn: Float) -> SIMD3<Float> {
        let w = img.width, h = img.height
        // Clamp to a finite, near-bounds range so Int(floor(...)) can't overflow/trap on a
        // non-finite or runaway coordinate; the ±1 border is handled by the edge clamp in `at`.
        let fx = min(max(fxIn.isFinite ? fxIn : 0, -1), Float(w))
        let fy = min(max(fyIn.isFinite ? fyIn : 0, -1), Float(h))
        let x0 = Int(floor(fx)), y0 = Int(floor(fy))
        let tx = fx - Float(x0), ty = fy - Float(y0)
        @inline(__always) func at(_ x: Int, _ y: Int) -> SIMD3<Float> {
            img.pixels[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        let top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        let bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (bot - top) * ty
    }

    private static func sampleLuma(_ l: [Float], width w: Int, height h: Int, _ fxIn: Float, _ fyIn: Float) -> Float {
        let fx = min(max(fxIn.isFinite ? fxIn : 0, -1), Float(w))
        let fy = min(max(fyIn.isFinite ? fyIn : 0, -1), Float(h))
        let x0 = Int(floor(fx)), y0 = Int(floor(fy))
        let tx = fx - Float(x0), ty = fy - Float(y0)
        @inline(__always) func at(_ x: Int, _ y: Int) -> Float {
            l[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        }
        let top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        let bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (bot - top) * ty
    }
}

/// Per-link plausibility bounds for `AffineAligner.alignChain`. Focus breathing between ADJACENT
/// brackets is a small, monotonic magnification change, and the steadiness gate bounds handheld
/// per-step motion — so a link estimate outside these is a spurious fit (a blur difference being
/// "explained" by warp) and must not be trusted with scale/rotation. (spec 2026-06-10 §4.2)
public struct ChainBounds: Sendable, Equatable {
    /// Max |scale − 1| per step.
    public var maxScaleDelta: Float
    /// Max |rotation| per step (radians).
    public var maxRotationRadians: Float
    /// Max translation magnitude per step, as a fraction of the long edge.
    public var maxTranslationFraction: Float
    /// Per-pixel squared-residual cap passed to `AffineAligner.estimate` and the robust
    /// translation pre-pass, so focus-blur mismatches between adjacent brackets cannot pull the
    /// optimizer off the common background signal.
    /// Default (0.0001) tuned on the synthetic bracket fixture (band amplitude diff ≈ 0.20);
    /// the proven handheld clip for whole-frame alignment is Pipeline.alignmentRobustClip = 0.02.
    /// Validate on-device (Task 12); if high-ISO links mis-seed, relax toward 0.001.
    /// nil = plain SSD (no clipping).
    public var robustClip: Float?

    public init(maxScaleDelta: Float = 0.02,
                maxRotationRadians: Float = Float.pi / 180,
                maxTranslationFraction: Float = 0.015,
                robustClip: Float? = 0.0001) {
        self.maxScaleDelta = maxScaleDelta
        self.maxRotationRadians = maxRotationRadians
        self.maxTranslationFraction = maxTranslationFraction
        self.robustClip = robustClip
    }

    public static let `default` = ChainBounds()
}
