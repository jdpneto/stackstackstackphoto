package com.jdpneto.stackstackstack

import android.view.Surface
import com.jdpneto.stackengine.CFAPattern
import com.jdpneto.stackengine.RawSensorFrame
import com.jdpneto.stackengine.Vec3
import kotlinx.coroutines.yield

/**
 * Deterministic in-memory capture that SIMULATES scene motion across the burst, so the
 * long-exposure looks are visibly distinct in tests and the emulator: a bright object sweeps
 * across the frame, so max-blend "light trails" leaves a bright streak while mean "smooth
 * motion" averages it into a faint blur.
 *
 * Mirrors iOS [FakeCaptureService] 1:1 — same mosaic math, same focus-bracket logic, same
 * moving-object simulation. Always returns `.raw` payload (both fakes do).
 */
class FakeCaptureService(
    val width: Int,
    val height: Int
) : CaptureService {

    /** No live preview in tests / emulator — the capture screen shows its neutral background. */
    override suspend fun startPreview(): Surface? = null

    override val supportsDepthOfField: Boolean = true
    override val supportsRAWCapture: Boolean = true

    override suspend fun captureBurst(
        recipe: CaptureRecipe,
        isSteady: () -> Boolean,
        onProgress: ((Int) -> Unit)?
    ): CapturedBurst {
        yield()   // model a non-instant capture so the shutter's re-entrancy guard applies
        if (recipe.focusSweep != null) {
            return CapturedBurst(
                payload = CapturedBurst.Payload.Raw(focusBrackets(recipe.focusSweep.positions.size, onProgress)),
                info = null
            )
        }
        val n = maxOf(recipe.frameCount, 1)
        val frames = (0 until n).map { k ->
            val mosaicInt = IntArray(width * height)
            // Dim, slightly noisy static background.
            for (y in 0 until height) {
                for (x in 0 until width) {
                    mosaicInt[y * width + x] = 200 + (k * 17 + x * 3 + y * 5) % 11
                }
            }
            // A bright object sweeping left→right over the burst.
            val cx = ((k.toFloat() / maxOf(n - 1, 1).toFloat()) * (width - 1).toFloat()).toInt()
            val cy = height / 2
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until width && y in 0 until height) {
                        mosaicInt[y * width + x] = 1000
                    }
                }
            }
            onProgress?.invoke(k + 1)
            RawSensorFrame.fromIntMosaic(
                width = width, height = height,
                mosaic = mosaicInt,
                blackLevel = 64f, whiteLevel = 1024f,
                cfa = CFAPattern.RGGB,
                wbGains = Vec3(1f, 1f, 1f)
            )
        }
        return CapturedBurst(payload = CapturedBurst.Payload.Raw(frames), info = null)
    }

    /**
     * Focus-bracket fake (spec 2026-06-10 §5.5): frame k carries high-amplitude checker texture
     * only in vertical band k and a dim texture elsewhere (synthetic defocus), plus a small
     * per-frame horizontal drift so the chain aligner has real work. Drift is translation-only —
     * scaling a Bayer mosaic would corrupt the CFA pattern; the engine's unit tests cover scale.
     * No single frame is sharp in every band; the stacked result must be.
     *
     * Mirrors iOS `focusBrackets(steps:onProgress:)` identically.
     */
    private fun focusBrackets(steps: Int, onProgress: ((Int) -> Unit)?): List<RawSensorFrame> =
        (0 until steps).map { k ->
            val mosaicInt = IntArray(width * height)
            val band = maxOf(width / steps, 1)
            val drift = k   // px of horizontal drift per frame (handheld jitter)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val sx = x + drift
                    val inBand = minOf(x / band, steps - 1) == k
                    val amp = if (inBand) 350 else 40
                    val checker = if (((sx / 2) + (y / 2)) % 2 == 0) amp else -amp
                    mosaicInt[y * width + x] = maxOf(64, 500 + checker)
                }
            }
            onProgress?.invoke(k + 1)
            RawSensorFrame.fromIntMosaic(
                width = width, height = height,
                mosaic = mosaicInt,
                blackLevel = 64f, whiteLevel = 1024f,
                cfa = CFAPattern.RGGB,
                wbGains = Vec3(1f, 1f, 1f)
            )
        }
}
