package com.jdpneto.stackengine

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

// Engine-owned worker pool: parallelMap must be callable from ANY thread or coroutine context
// without deadlock (a runBlocking(Dispatchers.Default) implementation deadlocks when callers
// invoke the engine from Default-dispatcher coroutines — the Android app will). Slot-indexed
// writes preserve order: result[i] = transform(items[i]).
private val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) { r ->
    Thread(r).also { it.isDaemon = true }
}

/**
 * Map [transform] over [items] across all available cores. Per-frame develop, luma, and alignment
 * are independent and pure, so running them concurrently is a straight wall-clock win with no change
 * in result.
 *
 * Results are written to distinct, preallocated slots (indexed by position) — no shared mutable
 * state — so this is safe as long as [transform] itself is pure (reads only its argument).
 * Order is preserved: result[i] = transform(items[i]).
 *
 * [maxParallel] bounds the number of items in flight at once (effective width =
 * min(maxParallel, cores, items)). Use it when each [transform] transiently holds large buffers —
 * bounding the width bounds the peak transient memory. Width does NOT affect results: each item is
 * still transformed exactly once into its own slot, in deterministic per-item fashion.
 *
 * Falls back to a serial map for 0/1 elements (no point paying for thread fan-out).
 *
 * Engine API stays synchronous (returns `List<R>`) like the Swift port.
 */
@Suppress("UNCHECKED_CAST")
fun <T, R> parallelMap(items: List<T>, maxParallel: Int = Int.MAX_VALUE, transform: (T) -> R): List<R> {
    val n = items.size
    if (n <= 1) return items.map(transform)
    require(maxParallel >= 1) { "maxParallel must be >= 1" }
    // Pre-allocate result slots; each task writes into its own index (no overlap, no races).
    val results = arrayOfNulls<Any>(n)
    val width = minOf(maxParallel, Runtime.getRuntime().availableProcessors(), n)
    val callables: List<java.util.concurrent.Callable<Unit>>
    if (width >= n) {
        // One task per item (the pool itself has `cores` threads, so width is naturally ≤ cores).
        callables = items.mapIndexed { i, t -> java.util.concurrent.Callable { results[i] = transform(t) } }
    } else {
        // Bounded width: `width` workers pull the next index from a shared counter, so at most
        // `width` transforms (and their transient buffers) are live at once.
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        callables = (0 until width).map {
            java.util.concurrent.Callable {
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= n) break
                    results[i] = transform(items[i])
                }
            }
        }
    }
    try {
        pool.invokeAll(callables).forEach { it.get() }
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
    return results.map { it as R }
}
