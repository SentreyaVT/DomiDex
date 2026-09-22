package com.dominik.cardvault.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ScanTaskResourcesTest {
    @Test fun `timeout completion error and cancellation race emits exactly one outcome`() {
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val deliveries = AtomicInteger()
        val gate = ScanTaskCompletion<String> { deliveries.incrementAndGet() }
        val workers = Executors.newFixedThreadPool(4)
        try {
            listOf("success", "error", "timeout", "cancel").forEach { outcome ->
                workers.execute { ready.countDown(); start.await(); gate.complete(outcome); completed.countDown() }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, deliveries.get())
            assertTrue(gate.isFinished)
            assertFalse(gate.complete("late callback"))
        } finally { workers.shutdownNow() }
    }

    @Test fun `cancellation releases pending crops but waits for active native input`() {
        val released = mutableListOf<Any>()
        val resources = ScanTaskResources<Any> { released += it }
        val active = resources.own(Any())
        val pending = resources.own(Any())
        assertTrue(resources.lease(active))
        resources.close()
        assertEquals(listOf(pending), released)
        assertFalse(resources.lease(active))
        resources.releaseLease(active)
        assertEquals(listOf(pending, active), released)
        resources.close()
        assertEquals(2, released.size)
    }

    @Test fun `successful normalized output transfers to caller exactly once`() {
        val released = mutableListOf<Any>()
        val resources = ScanTaskResources<Any> { released += it }
        val output = resources.own(Any())
        resources.own(output)
        val rejected = resources.own(Any())
        resources.close(retain = output)
        resources.close()
        assertEquals(listOf(rejected), released)
    }

    @Test fun `distinct but equal resources are each released`() {
        data class Resource(val id: Int)
        val released = mutableListOf<Resource>()
        val resources = ScanTaskResources<Resource> { released += it }
        resources.own(Resource(1)); resources.own(Resource(1))
        resources.close()
        assertEquals(2, released.size)
        assertNotSame(released[0], released[1])
    }

    @Test fun `multiple orientation outputs transfer while intermediate buffers are freed`() {
        val released = mutableListOf<Any>()
        val resources = ScanTaskResources<Any> { released += it }
        val first = resources.own(Any())
        val second = resources.own(Any())
        val temporary = resources.own(Any())
        resources.transfer(first); resources.transfer(second); resources.transfer(first)
        resources.close()
        assertEquals(listOf(temporary), released)
    }
}
