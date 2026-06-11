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
 * Falls back to a serial map for 0/1 elements (no point paying for thread fan-out).
 *
 * Engine API stays synchronous (returns `List<R>`) like the Swift port.
 */
@Suppress("UNCHECKED_CAST")
fun <T, R> parallelMap(items: List<T>, transform: (T) -> R): List<R> {
    val n = items.size
    if (n <= 1) return items.map(transform)
    // Pre-allocate result slots; each task writes into its own index (no overlap, no races).
    val results = arrayOfNulls<Any>(n)
    val callables = items.mapIndexed { i, t -> java.util.concurrent.Callable { results[i] = transform(t) } }
    try {
        pool.invokeAll(callables).forEach { it.get() }
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
    return results.map { it as R }
}
