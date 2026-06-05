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
    /// SSD. Coarse integer-translation init (reuses Alignment.estimateTranslation) then a
    /// deterministic Hooke–Jeeves pattern search over scale / rotation / sub-pixel translation,
    /// with scale clamped to a sane range.
    ///
    /// PHASE-1 FOUNDATION: single-resolution search, validated on well-conditioned (smooth) inputs.
    /// A coarse-to-fine luma Gaussian pyramid is REQUIRED — not merely a precision upgrade — before
    /// this runs on real high-frequency or full-sensor-resolution frames: the SSD surface is then
    /// multimodal (local-minima risk) and the full-res cost is large. It is a prerequisite for the
    /// FocusStacker integration (spec §3.2).
    public static func estimate(reference ref: PixelImage, moving mov: PixelImage,
                                translationSearch: Int = 8) -> Transform2D {
        precondition(ref.width == mov.width && ref.height == mov.height)
        let w = ref.width, h = ref.height
        let refL = Luma.luminance(ref), movL = Luma.luminance(mov)

        // Coarse integer translation handles handheld shift; the search refines the rest.
        let t0 = Alignment.estimateTranslation(referenceLuma: refL, movingLuma: movL,
                                               width: w, height: h, searchRange: translationSearch)
        var s: Float = 1, r: Float = 0, tx = Float(t0.dx), ty = Float(t0.dy)

        func cost(_ s: Float, _ r: Float, _ tx: Float, _ ty: Float) -> Float {
            ssdWarped(movL, refL, width: w, height: h,
                      by: .similarity(scale: s, rotation: r, tx: tx, ty: ty))
        }

        var best = cost(s, r, tx, ty)
        var stepS: Float = 0.05, stepR: Float = 0.04, stepT: Float = 1.0
        let minScale: Float = 0.5, maxScale: Float = 2.0   // bar a degenerate / mirror-flipped scale
        var guardCount = 0
        // The loop normally exits via stepT shrinking below the sub-pixel threshold; the iteration
        // cap is only a safety backstop against a pathological never-converging cost surface.
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
                if ns < minScale || ns > maxScale { continue }   // never accept a degenerate scale
                let c = cost(ns, r + dR, tx + dTx, ty + dTy)
                if c < best - 1e-9 {
                    best = c; s = ns; r += dR; tx += dTx; ty += dTy; improved = true
                }
            }
            if !improved { stepS *= 0.5; stepR *= 0.5; stepT *= 0.5 }
        }
        return .similarity(scale: s, rotation: r, tx: tx, ty: ty)
    }

    /// Estimate the registration of `moving` to `reference` and return `moving` warped into the
    /// reference frame.
    public static func align(reference ref: PixelImage, moving mov: PixelImage) -> PixelImage {
        warp(mov, by: estimate(reference: ref, moving: mov))
    }

    /// Mean SSD between `reference` luma and `moving` luma warped by `t` (centred, bilinear).
    private static func ssdWarped(_ movL: [Float], _ refL: [Float], width w: Int, height h: Int,
                                  by t: Transform2D) -> Float {
        let cx = Float(w - 1) / 2, cy = Float(h - 1) / 2
        var sum: Float = 0
        for y in 0..<h {
            for x in 0..<w {
                let p = t.apply(Float(x) - cx, Float(y) - cy)
                let m = sampleLuma(movL, width: w, height: h, p.x + cx, p.y + cy)
                let d = m - refL[y * w + x]
                sum += d * d
            }
        }
        return sum / Float(w * h)
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
