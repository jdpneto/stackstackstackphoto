/// Edge-preserving smoothing of `input` guided by `guide` (He, Sun & Tang 2010). Output follows the
/// guide's edges while smoothing the input within flat regions. Used to regularize selection masks.
enum GuidedFilter {
    static func filter(input p: [Float], guide I: [Float], width w: Int, height h: Int,
                       radius r: Int, eps: Float) -> [Float] {
        let n = w * h
        let meanI = BoxFilter.mean(I, width: w, height: h, radius: r)
        let meanP = BoxFilter.mean(p, width: w, height: h, radius: r)
        var ip = [Float](repeating: 0, count: n), ii = [Float](repeating: 0, count: n)
        for i in 0..<n { ip[i] = I[i] * p[i]; ii[i] = I[i] * I[i] }
        let meanIp = BoxFilter.mean(ip, width: w, height: h, radius: r)
        let meanII = BoxFilter.mean(ii, width: w, height: h, radius: r)
        var a = [Float](repeating: 0, count: n), b = [Float](repeating: 0, count: n)
        for i in 0..<n {
            let varI = meanII[i] - meanI[i] * meanI[i]
            let covIp = meanIp[i] - meanI[i] * meanP[i]
            a[i] = covIp / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        let meanA = BoxFilter.mean(a, width: w, height: h, radius: r)
        let meanB = BoxFilter.mean(b, width: w, height: h, radius: r)
        var out = [Float](repeating: 0, count: n)
        for i in 0..<n { out[i] = meanA[i] * I[i] + meanB[i] }
        return out
    }
}
