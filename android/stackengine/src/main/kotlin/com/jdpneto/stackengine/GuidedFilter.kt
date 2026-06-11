package com.jdpneto.stackengine

/**
 * Edge-preserving smoothing of `input` guided by `guide` (He, Sun & Tang 2010). Output follows the
 * guide's edges while smoothing the input within flat regions. Used to regularize selection masks.
 */
internal object GuidedFilter {

    fun filter(input: FloatArray, guide: FloatArray, width: Int, height: Int,
               radius: Int, eps: Float): FloatArray {
        val w = width; val h = height
        val p = input; val I = guide
        val n = w * h
        val meanI = BoxFilter.mean(I, w, h, radius)
        val meanP = BoxFilter.mean(p, w, h, radius)
        val ip = FloatArray(n); val ii = FloatArray(n)
        for (i in 0 until n) { ip[i] = I[i] * p[i]; ii[i] = I[i] * I[i] }
        val meanIp = BoxFilter.mean(ip, w, h, radius)
        val meanII = BoxFilter.mean(ii, w, h, radius)
        val a = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = meanII[i] - meanI[i] * meanI[i]
            val covIp = meanIp[i] - meanI[i] * meanP[i]
            a[i] = covIp / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = BoxFilter.mean(a, w, h, radius)
        val meanB = BoxFilter.mean(b, w, h, radius)
        val out = FloatArray(n)
        for (i in 0 until n) { out[i] = meanA[i] * I[i] + meanB[i] }
        return out
    }
}
