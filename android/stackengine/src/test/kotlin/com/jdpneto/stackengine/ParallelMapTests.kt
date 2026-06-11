package com.jdpneto.stackengine

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [parallelMap]'s width bound exists for MEMORY (each in-flight transform's transients), never for
 * results: any width must produce exactly the serial map, in order, with each item transformed once.
 */
class ParallelMapTests {

    @Test
    fun boundedWidthMatchesSerialMapInOrder() {
        val items = (0 until 23).toList()
        val expected = items.map { it * it + 1 }
        for (width in listOf(1, 2, 3, 4, Int.MAX_VALUE)) {
            assertEquals(expected, parallelMap(items, maxParallel = width) { it * it + 1 },
                         "maxParallel=$width must be result-identical to a serial map")
        }
    }

    @Test
    fun defaultWidthMatchesSerialMap() {
        val items = (0 until 17).toList()
        assertEquals(items.map { it + 100 }, parallelMap(items) { it + 100 })
    }

    @Test
    fun boundedWidthTransformsEachItemExactlyOnce() {
        val calls = AtomicInteger(0)
        val out = parallelMap((0 until 19).toList(), maxParallel = 2) { calls.incrementAndGet(); it }
        assertEquals(19, calls.get())
        assertEquals((0 until 19).toList(), out)
    }

    @Test
    fun boundedWidthNeverExceedsTheBound() {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        parallelMap((0 until 32).toList(), maxParallel = 2) {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { p -> maxOf(p, now) }
            Thread.sleep(2)   // widen the overlap window so a violation would be caught
            inFlight.decrementAndGet()
            it
        }
        assertTrue(peak.get() <= 2, "observed ${peak.get()} concurrent transforms with maxParallel=2")
    }

    @Test
    fun exceptionsPropagateFromBoundedWorkers() {
        var thrown = false
        try {
            parallelMap((0 until 8).toList(), maxParallel = 2) {
                if (it == 5) throw IllegalStateException("boom")
                it
            }
        } catch (e: IllegalStateException) {
            thrown = (e.message == "boom")
        }
        assertTrue(thrown, "the transform's exception must surface to the caller")
    }
}
