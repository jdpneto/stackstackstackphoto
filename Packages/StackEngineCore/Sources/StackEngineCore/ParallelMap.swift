import Dispatch

/// Map `transform` over `input` across all available cores. Per-frame develop, luma, and alignment
/// are independent and pure, so running them concurrently is a straight wall-clock win with no change
/// in result. Results are written to distinct, preallocated slots (no shared mutable state), so this
/// is safe as long as `transform` itself is pure (reads only its argument).
///
/// Falls back to a serial map for 0/1 elements (no point paying for thread fan-out).
func parallelMap<T, R>(_ input: [T], _ transform: (T) -> R) -> [R] {
    let n = input.count
    if n <= 1 { return input.map(transform) }
    return [R](unsafeUninitializedCapacity: n) { buffer, initializedCount in
        let base = buffer.baseAddress!
        input.withUnsafeBufferPointer { inBuf in
            // Each iteration initializes ONE distinct slot — no overlap, no CoW, no races.
            DispatchQueue.concurrentPerform(iterations: n) { i in
                (base + i).initialize(to: transform(inBuf[i]))
            }
        }
        initializedCount = n
    }
}
