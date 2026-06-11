package com.jdpneto.stackengine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

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
fun <T, R> parallelMap(items: List<T>, transform: (T) -> R): List<R> {
    val n = items.size
    if (n <= 1) return items.map(transform)
    // Pre-allocate result slots; each coroutine writes into its own index (no overlap, no races).
    return runBlocking(Dispatchers.Default) {
        items.mapIndexed { i, t -> async { i to transform(t) } }
            .awaitAll()
            .sortedBy { it.first }   // restore input order
            .map { it.second }
    }
}
