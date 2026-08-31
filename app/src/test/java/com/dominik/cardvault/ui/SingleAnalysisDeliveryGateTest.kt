package com.dominik.cardvault.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class SingleAnalysisDeliveryGateTest {
    @Test
    fun timeoutAndCompletionCanBuildOnlyOneSnapshot() {
        val gate = SingleAnalysisDeliveryGate()
        val startsTogether = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val builds = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        repeat(2) {
            executor.execute {
                startsTogether.await()
                gate.claim { builds.incrementAndGet() }
                finished.countDown()
            }
        }
        startsTogether.countDown()
        finished.await(2, TimeUnit.SECONDS)
        executor.shutdownNow()
        assertEquals(1, builds.get())
    }
}
