package com.jdpneto.stackengine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals as kAssertEquals

/**
 * Synthetic focus brackets WITH handheld drift, shared by the chain-aligner and FocusStacker
 * tests (file-scope so both files in this test module can use them — don't instantiate a test
 * case to borrow a fixture). Bracket k is "in focus" (full texture amplitude) only in vertical
 * band k and "defocused" (damped amplitude) elsewhere, then warped by the CUMULATIVE drift D_k
 * (breathing scale + jitter). Low pixel frequency keeps bilinear resampling accurate (same
 * rationale as AffineAlignerTests.texture).
 *
 * [steps] contains one per-step transform; `frames.count == steps.count + 1`. The cumulative
 * drift is `drifts[0] = .identity`, `drifts[k] = steps[k-1].composed(with: drifts[k-1])`.
 * Using VARIED (non-constant) steps makes the composition non-commutative, so tests catch a
 * transposed `link.composed(with: transforms[i-1])` vs `transforms[i-1].composed(with: link)`.
 */
internal fun chainBracketFrames(
    w: Int, h: Int, steps: List<Transform2D>
): Pair<List<PixelImage>, List<Transform2D>> {
    val count = steps.size + 1
    val drifts = mutableListOf(Transform2D.identity)
    for (step in steps) {
        drifts.add(step.composed(with = drifts.last()))
    }
    val frames = mutableListOf<PixelImage>()
    for (k in 0 until count) {
        val content = chainBracketContent(band = k, of = count, w = w, h = h)
        frames.add(if (k == 0) content else AffineAligner.warp(content, by = drifts[k]))
    }
    return Pair(frames, drifts)
}

/** The un-drifted content of bracket k: shared ramp + texture, amplitude full in band k only. */
internal fun chainBracketContent(band: Int, of: Int, w: Int, h: Int): PixelImage {
    val img = PixelImage(w, h)
    val bandW = w / of
    for (y in 0 until h) {
        for (x in 0 until w) {
            val fx = x.toFloat() / (w - 1).toFloat()
            val fy = y.toFloat() / (h - 1).toFloat()
            val amp: Float = if ((x / bandW) == band) 0.25f else 0.05f    // sharp in band k, defocused elsewhere
            val v = 0.15f + 0.5f * fx * fy + amp * sin(20f * fx) * sin(16f * fy)
            img[x, y] = Vec3(v, v, v)
        }
    }
    return img
}

class AffineAlignerChainTests {

    private fun params(t: Transform2D): Quadruple<Float, Float, Float, Float> {
        val scale = sqrt(t.a * t.a + t.c * t.c)
        val rot = atan2(t.c, t.a)
        return Quadruple(scale, rot, t.tx, t.ty)
    }

    @Test
    fun testChainRecoversCompoundDriftAcrossBlurVaryingBrackets() {
        // 4 brackets: varied per-step drift (alternating rotation sign, varied tx/ty) — all
        // inside ChainBounds: |scale−1| ≤ 0.012, |rot| ≤ 0.004, |t| ≤ ~1.1 px.
        // Steps are NON-CONSTANT and VARIED so composition is non-commutative in general.
        val steps = listOf(
            Transform2D.similarity(scale = 1.012f, rotation =  0.004f, tx =  1.0f, ty = -0.5f),
            Transform2D.similarity(scale = 1.008f, rotation = -0.003f, tx =  0.7f, ty =  0.8f),
            Transform2D.similarity(scale = 1.010f, rotation =  0.002f, tx = -0.6f, ty = -0.9f),
        )
        val (frames, drifts) = chainBracketFrames(w = 96, h = 64, steps = steps)
        val transforms = AffineAligner.alignChain(frames, referenceIndex = 0)
        kAssertEquals(4, transforms.size)
        kAssertEquals(Transform2D.identity, transforms[0])
        for (k in 1 until 4) {
            // frame_k = warp(content_k, D_k) ⇒ the warp-to-reference is D_k⁻¹.
            val want = params(drifts[k].inverse)
            val got = params(transforms[k])
            assertEquals(got.first,  want.first,  0.02f, "frame $k scale")
            assertEquals(got.second, want.second, 0.02f, "frame $k rotation")
            assertEquals(got.third,  want.third,  1.5f,  "frame $k tx")
            assertEquals(got.fourth, want.fourth, 1.5f,  "frame $k ty")
        }
        // And the warp actually registers: the last (most-drifted) frame, warped back, matches its
        // un-drifted content in the interior.
        val aligned = AffineAligner.warp(frames[3], by = transforms[3])
        val target = chainBracketContent(band = 3, of = 4, w = 96, h = 64)
        var maxd = 0f
        for (y in 16 until 48) for (x in 24 until 72) { maxd = max(maxd, abs(aligned[x, y].x - target[x, y].x)) }
        assertTrue(maxd < 0.08f, "chain-aligned frame must match its un-drifted content: maxd=$maxd")
    }

    @Test
    fun testReferenceInTheMiddleAlignsBothDirections() {
        // 3 brackets with varied steps so composition is non-commutative.
        val steps = listOf(
            Transform2D.similarity(scale = 1.010f, rotation =  0.003f, tx =  1.0f, ty = -0.4f),
            Transform2D.similarity(scale = 1.008f, rotation = -0.002f, tx =  0.8f, ty =  0.5f),
        )
        val (frames, drifts) = chainBracketFrames(w = 96, h = 64, steps = steps)
        val transforms = AffineAligner.alignChain(frames, referenceIndex = 1)
        kAssertEquals(Transform2D.identity, transforms[1])
        // transforms[k] maps reference(frame-1) coords → frame-k coords.
        // Derivation: aligned_k[p] = frame_k[T(p)] must reproduce the reference geometry, i.e.
        // D_k·T(p) = D_1·p  ⇒  T = D_k⁻¹ ∘ D_1.
        for (k in listOf(0, 2)) {
            val want = params(drifts[k].inverse.composed(with = drifts[1]))
            val got = params(transforms[k])
            assertEquals(got.first, want.first, 0.02f, "frame $k scale")
            assertEquals(got.third, want.third, 1.5f,  "frame $k tx")
            assertEquals(got.fourth, want.fourth, 1.5f, "frame $k ty")
        }
    }

    /**
     * Directly verifies that [AffineAligner.accumulateLinks] uses `link.composed(with: prev)` (not reversed)
     * by injecting exact algebraically-constructed links — bypassing the estimator entirely so
     * composition-order errors are never masked by estimation noise.
     *
     * WHY the integration tests above cannot catch this:
     *   For within-ChainBounds drifts (|rot| ≤ 0.0175 rad, |tx| ≤ 1.44 px), the Lie commutator
     *   [link₂, link₁] is O(|rot|·|tx|) ≈ 0.003–0.05 px — sub-pixel and undetectable at 1.5 px
     *   tolerance. The integration tests verify ESTIMATION QUALITY; this test isolates the
     *   COMPOSITION-ORDER algebra in accumulateLinks via exact inputs.
     *
     * Mutation sensitivity: for scale+translation links with (scale−1)·tx terms:
     *   link2.composed(with: link1).tx = scale2 · tx1 + tx2
     *   link1.composed(with: link2).tx = scale1 · tx2 + tx1   [wrong order]
     *   difference = |(scale2−1)·tx1 − (scale1−1)·tx2|
     *
     * With link1=(scale=1.5, tx=3) and link2=(scale=0.7, tx=−4):
     *   diff = |(0.7−1)·3 − (1.5−1)·(−4)| = |−0.9 + 2.0| = 1.1 px >> 1e-4 tolerance.
     */
    @Test
    fun testAccumulateLinksOrderIsLinkComposedWithPrev() {
        // Pure scale+translation links (rotation=0). These do NOT commute when scales differ:
        //   L2 ∘ L1 tx = scale2*tx1 + tx2, L1 ∘ L2 tx = scale1*tx2 + tx1  (generally unequal).
        val link1 = Transform2D.similarity(scale = 1.5f, rotation = 0f, tx =  3.0f, ty = 0f)
        val link2 = Transform2D.similarity(scale = 0.7f, rotation = 0f, tx = -4.0f, ty = 0f)
        val link3 = Transform2D.similarity(scale = 1.2f, rotation = 0f, tx =  2.0f, ty = 0f)

        // Correct accumulated transforms (link.composed(with: prev)):
        //   t1 = link1; t2 = link2 ∘ link1; t3 = link3 ∘ link2 ∘ link1
        val t1Want = link1
        val t2Want = link2.composed(with = link1)
        val t3Want = link3.composed(with = t2Want)

        // Wrong accumulated transforms (prev.composed(with: link)):
        //   t1 = link1 (same for first step: id.composed(with: link1) = link1)
        //   t2 = link1 ∘ link2; t3 = (link1 ∘ link2) ∘ link3
        val t2Wrong = link1.composed(with = link2)
        // t3Wrong is not asserted, but kept to document the wrong chain:
        @Suppress("UNUSED_VARIABLE") val t3Wrong = t2Wrong.composed(with = link3)

        // Verify the fixture is non-degenerate: the two orderings differ by > 1 px.
        assertTrue(abs(t2Want.tx - t2Wrong.tx) > 1.0f,
            "t2_want and t2_wrong must differ by > 1 px — fixture is degenerate: ${t2Want.tx} vs ${t2Wrong.tx}")

        // Run accumulateLinks with exact links and ref=0.
        val links = listOf(Transform2D.identity, link1, link2, link3)
        val result = AffineAligner.accumulateLinks(links, referenceIndex = 0)

        kAssertEquals(Transform2D.identity, result[0])
        // t1: both orderings agree on the first step (trivially identity.composed(with:) = itself).
        assertEquals(result[1].tx, t1Want.tx, 1e-4f, "t1 tx")

        // t2: correct = link2 ∘ link1; wrong = link1 ∘ link2 → diff = 1.1 px.
        assertEquals(result[2].tx, t2Want.tx, 1e-4f,
            "t2 tx must be link2∘link1 not link1∘link2 (catches transposed loop)")
        assertTrue(abs(result[2].tx - t2Wrong.tx) > 0.5f, "t2 correct and wrong must be distinguishable")

        // t3: accumulated error grows further.
        assertEquals(result[3].tx, t3Want.tx, 1e-4f, "t3 tx must follow correct composition chain")
    }

    // MARK: - Part B: down-sweep composition order

    /**
     * Mirrors [testAccumulateLinksOrderIsLinkComposedWithPrev] for the DOWN-SWEEP path
     * (referenceIndex at the end, i.e. referenceIndex = links.count - 1).
     *
     * Down-sweep code: `transforms[i] = links[i].composed(with: transforms[i+1])`.
     *
     * Links array: [link3, link1, link2, .identity], referenceIndex = 3.
     * Correct accumulated transforms:
     *   transforms[3] = .identity   (reference)
     *   transforms[2] = link2.composed(with: .identity)           = link2
     *   transforms[1] = link1.composed(with: transforms[2])       = link1 ∘ link2
     *   transforms[0] = link3.composed(with: transforms[1])       = link3 ∘ link1 ∘ link2
     *
     * Wrong (transposed: transforms[i+1].composed(with: links[i])):
     *   transforms[2] = .identity.composed(with: link2)           = link2  (trivially same)
     *   transforms[1] = transforms[2].composed(with: link1)       = link2 ∘ link1  [WRONG]
     *   transforms[0] = transforms[1].composed(with: link3)       = ...            [WRONG]
     *
     * Mutation sensitivity at transforms[1]:
     *   correct  link1∘link2 tx = scale1*tx2 + tx1 = 1.5*(-4) + 3 = -3.0
     *   wrong    link2∘link1 tx = scale2*tx1 + tx2 = 0.7*3 + (-4) = -1.9
     *   diff = 1.1 px >> 1e-4 tolerance.
     */
    @Test
    fun testAccumulateLinksDownSweepOrderIsLinkComposedWithNext() {
        val link1 = Transform2D.similarity(scale = 1.5f, rotation = 0f, tx =  3.0f, ty = 0f)
        val link2 = Transform2D.similarity(scale = 0.7f, rotation = 0f, tx = -4.0f, ty = 0f)
        val link3 = Transform2D.similarity(scale = 1.2f, rotation = 0f, tx =  2.0f, ty = 0f)

        // links[3] = .identity is at the reference; links[2,1,0] are non-trivial.
        // For the down-sweep, links[i] maps frame[i+1] coords → frame[i] coords.
        val links = listOf(link3, link1, link2, Transform2D.identity)
        val referenceIndex = links.size - 1   // = 3

        // Correct accumulated transforms:
        //   t[3] = .identity
        //   t[2] = link2 ∘ .identity = link2
        //   t[1] = link1 ∘ t[2]      = link1 ∘ link2
        //   t[0] = link3 ∘ t[1]      = link3 ∘ link1 ∘ link2
        val t2Want = link2                                  // link2 ∘ identity
        val t1Want = link1.composed(with = link2)           // link1 ∘ link2
        val t0Want = link3.composed(with = t1Want)          // link3 ∘ link1 ∘ link2

        // Wrong (transposed: transforms[i+1].composed(with: links[i])):
        //   t[2] = .identity ∘ link2          = link2  (trivially same as correct)
        //   t[1] = link2 ∘ link1              [WRONG — reversed]
        //   t[0] = (link2 ∘ link1) ∘ link3   [WRONG — accumulated reversed]
        val t1Wrong = link2.composed(with = link1)

        // Verify the fixture is non-degenerate: the two orderings of t[1] differ by > 1 px.
        assertTrue(abs(t1Want.tx - t1Wrong.tx) > 1.0f,
            "t1_want and t1_wrong must differ by > 1 px — fixture is degenerate")

        val result = AffineAligner.accumulateLinks(links, referenceIndex = referenceIndex)

        kAssertEquals(Transform2D.identity, result[referenceIndex])

        // t[2]: both orderings trivially agree (link ∘ identity = identity ∘ link = link).
        assertEquals(result[2].tx, t2Want.tx, 1e-4f, "t[2] tx")
        assertEquals(result[2].a,  t2Want.a,  1e-4f, "t[2] scale")

        // t[1]: correct = link1 ∘ link2; wrong = link2 ∘ link1 → diff = 1.1 px.
        assertEquals(result[1].tx, t1Want.tx, 1e-4f,
            "t[1] tx must be link1∘link2 not link2∘link1 (catches transposed down-sweep loop)")
        assertTrue(abs(result[1].tx - t1Wrong.tx) > 0.5f, "t[1] correct and wrong must be distinguishable")

        // t[0]: accumulated error grows further.
        assertEquals(result[0].tx, t0Want.tx, 1e-4f, "t[0] tx must follow correct down-sweep composition chain")
    }

    // MARK: - Part A: bounds-fallback tests

    /**
     * A 4°-per-step rotation (0.07 rad) is far outside the 1° default bound.
     * [boundedLink] must reject the similarity fit and fall back to translation-only,
     * so the resulting transform has scale = 1, rotation = 0.
     */
    @Test
    fun testImplausibleLinkFallsBackToTranslationOnly() {
        val (frames, _) = chainBracketFrames(
            w = 96, h = 64,
            steps = listOf(Transform2D.similarity(scale = 1.0f, rotation = 0.07f, tx = 0f, ty = 0f))
        )
        val p = params(AffineAligner.alignChain(frames, referenceIndex = 0)[1])
        assertEquals(p.first,  1f, 1e-4f, "fallback link must carry no scale")
        assertEquals(p.second, 0f, 1e-4f, "fallback link must carry no rotation")
    }

    /**
     * Same frames as [testImplausibleLinkFallsBackToTranslationOnly], but with bounds wide
     * enough to accept a 4° rotation. The similarity fit is now accepted, proving it was the
     * BOUNDS (not the estimator) that gated the previous test.
     */
    @Test
    fun testWideBoundsAcceptTheSameLink() {
        val (frames, drifts) = chainBracketFrames(
            w = 96, h = 64,
            steps = listOf(Transform2D.similarity(scale = 1.0f, rotation = 0.07f, tx = 0f, ty = 0f))
        )
        // maxRotationRadians: 0.2 rad (~11.5°) — wide enough to accept the 4° (0.07 rad) rotation.
        val wide = ChainBounds(
            maxScaleDelta = 0.5f,
            maxRotationRadians = 0.2f,
            maxTranslationFraction = 0.2f,
            robustClip = null
        )
        val p = params(AffineAligner.alignChain(frames, referenceIndex = 0, bounds = wide)[1])
        // drifts[1] = steps[0], so its inverse has rotation = -0.07 rad.
        val wantRot = params(drifts[1].inverse).second   // ≈ -0.07
        assertEquals(p.second, wantRot, 0.02f,
            "with wide bounds the rotation must be recovered (estimator accepted by wide ChainBounds)")
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/** Tiny quadruple since Kotlin stdlib has no Quadruple. */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun assertEquals(actual: Float, expected: Float, tolerance: Float, message: String = "") {
    assertTrue(
        kotlin.math.abs(actual - expected) <= tolerance,
        if (message.isEmpty()) "expected $expected but got $actual (tolerance $tolerance)"
        else "$message: expected $expected but got $actual (tolerance $tolerance)"
    )
}
