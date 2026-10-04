package com.dominik.cardvault.ui

import com.dominik.cardvault.data.knowledge.ScanKnowledgeRecord
import com.dominik.cardvault.data.knowledge.ScanKnowledgeStatus
import com.dominik.cardvault.data.sync.CollectionCloudState
import com.dominik.cardvault.data.sync.CollectionCloudStatus
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The same community-hint consumer called by ScannerScreen, not a test-only bridge. */
class ScannerKnowledgeConsumerTest {
    @Test fun sameOwnerNoAccessClosesHintAndRejectsLateCallbacks() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        val capture = consumer.begin(FP)
        assertEquals(RECORD, capture.current())
        consumer.onCloudState(CollectionCloudState(CollectionCloudStatus.NO_ACCESS, "a"))
        assertEquals(listOf(1), source.closes)
        assertNull(capture.current())
        source.callback!!.invoke(RECORD.copy(cardId = "late"))
        assertNull(capture.current())
        capture.close(); consumer.close()
        assertEquals(listOf(1), source.closes)
    }

    @Test fun denialBeforeCapturePreventsAllCacheReadsAndRegistrations() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        consumer.onCloudState(CollectionCloudState(CollectionCloudStatus.NO_ACCESS, "a"))
        repeat(2) { assertNull(consumer.begin(FP).current()) }
        assertEquals(0, source.reads)
        assertEquals(0, source.opens)
        consumer.close()
    }

    @Test fun localOnlyAndCheckingNeverRevokeExistingOfflineHint() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        val capture = consumer.begin(FP)
        listOf(CollectionCloudStatus.LOCAL_ONLY, CollectionCloudStatus.CHECKING,
            CollectionCloudStatus.ERROR).forEach {
            consumer.onCloudState(CollectionCloudState(it, "a", accessConfirmed = false))
            assertEquals(RECORD, capture.current())
        }
        assertEquals(listOf(0), source.closes)
        consumer.close()
    }

    @Test fun foreignNoAccessDoesNotRevokeCurrentOwnerOrPoisonItsNextCapture() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        val first = consumer.begin(FP)
        consumer.onCloudState(CollectionCloudState(CollectionCloudStatus.NO_ACCESS, "b"))
        assertEquals(RECORD, first.current())
        assertEquals(listOf(0), source.closes)
        first.close()
        assertEquals(RECORD, consumer.begin(FP).current())
        assertEquals(2, source.reads)
        consumer.close()
    }

    @Test fun coarseAccessConfirmedOrMissingAccessFallbackCannotUndoObservedDenial() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        val capture = consumer.begin(FP)
        consumer.onCloudState(CollectionCloudState(CollectionCloudStatus.NO_ACCESS, "a"))
        // These coarse states may follow a missing accountAccess override. This consumer
        // neither reads that document nor interprets accessConfirmed as a proven regrant.
        listOf(CollectionCloudStatus.CHECKING, CollectionCloudStatus.LOCAL_ONLY,
            CollectionCloudStatus.ACTIVE).forEach {
            consumer.onCloudState(CollectionCloudState(it, "a", accessConfirmed = true))
            assertNull(consumer.begin(FP).current())
        }
        assertNull(capture.current())
        assertEquals(1, source.reads)
        assertEquals(1, source.opens)
        consumer.close()
    }

    @Test fun denialDuringRegistrationClosesReturnedHandleWithoutResurrection() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        source.duringObserve = {
            consumer.onCloudState(CollectionCloudState(CollectionCloudStatus.NO_ACCESS, "a"))
        }
        val capture = consumer.begin(FP)
        assertNull(capture.current())
        assertEquals(1, source.opens)
        assertEquals(listOf(1), source.closes)
        consumer.close()
    }

    @Test fun ownerTransitionClosesOldGenerationAndLateCallbacksCannotPopulateNewOwner() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        val first = consumer.begin(FP)
        val late = requireNotNull(source.callback)
        source.owner = "b"
        consumer.onCloudState(CollectionCloudState(CollectionCloudStatus.CHECKING, "b"))
        assertNull(first.current())
        assertEquals(listOf(1), source.closes)
        val second = consumer.begin(FP)
        late(RECORD.copy(cardId = "old-owner"))
        assertEquals(RECORD, second.current())
        assertNull(first.current())
        consumer.close()
    }

    @Test fun aDeniedOwnerDoesNotBlockBAndReturningACannotUseCacheAgain() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        consumer.onCloudState(CollectionCloudState(CollectionCloudStatus.NO_ACCESS, "a"))
        source.owner = "b"
        consumer.onCloudState(CollectionCloudState(CollectionCloudStatus.LOCAL_ONLY, "b"))
        val b = consumer.begin(FP)
        assertEquals(RECORD, b.current())
        source.owner = "a"
        consumer.onCloudState(CollectionCloudState(CollectionCloudStatus.CHECKING, "a"))
        assertNull(b.current())
        assertNull(consumer.begin(FP).current())
        assertEquals(1, source.reads)
        consumer.close()
    }

    @Test fun disposalAndReentrantCloseRejectLateCallbacks() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        val capture = consumer.begin(FP)
        source.onClose = { source.callback!!.invoke(RECORD) }
        consumer.close(); consumer.close(); capture.close()
        assertEquals(listOf(1), source.closes)
        assertNull(capture.current())
        assertNull(consumer.begin(FP).current())
        assertEquals(1, source.reads)
    }

    @Test fun throwingCleanupCannotKeepHintOrReviveCapture() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        val capture = consumer.begin(FP)
        source.onClose = { throw IllegalStateException("synthetic cleanup failure") }
        consumer.close()
        assertEquals(listOf(1), source.closes)
        assertNull(capture.current())
    }

    @Test fun externalCloseAllowsAnotherThreadsCallbackToFinishWithoutResurrection() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        val capture = consumer.begin(FP)
        val update = requireNotNull(source.callback)
        assertEquals(RECORD, capture.current())
        val closeEntered = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeObservedCallback = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        source.onClose = {
            closeEntered.countDown()
            // With an incorrectly held consumer monitor, the callback cannot finish
            // before this finite wait returns false. Never infer success from a later join.
            closeObservedCallback.set(callbackFinished.await(2, TimeUnit.SECONDS))
        }
        val callbackThread = Thread({
            try {
                check(closeEntered.await(2, TimeUnit.SECONDS)) { "External close never entered" }
                update(RECORD.copy(cardId = "during-close"))
                assertNull(capture.current())
            } catch (caught: Throwable) {
                failure.compareAndSet(null, caught)
            } finally {
                callbackFinished.countDown()
            }
        }, "knowledge-close-callback")
        val closingThread = Thread({
            try {
                consumer.onCloudState(CollectionCloudState(CollectionCloudStatus.NO_ACCESS, "a"))
            } catch (caught: Throwable) {
                failure.compareAndSet(null, caught)
            } finally {
                closeFinished.countDown()
            }
        }, "knowledge-close-owner")
        // Last-resort JVM containment is not success: both threads must actually terminate.
        callbackThread.isDaemon = true
        closingThread.isDaemon = true
        try {
            callbackThread.start()
            closingThread.start()
            assertTrue("External close did not finish", closeFinished.await(4, TimeUnit.SECONDS))
            assertTrue("Callback did not finish", callbackFinished.await(4, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Concurrent consumer callback failed", it) }
            assertTrue("External close held the consumer lock across a different thread's callback",
                closeObservedCallback.get())
            assertEquals(listOf(1), source.closes)
            assertNull(capture.current())
            update(RECORD.copy(cardId = "after-close"))
            assertNull(capture.current())
        } finally {
            // Interruption releases our own finite latch waits. It cannot be counted as
            // callback completion: the recorded successful await is checked above.
            if (callbackThread.isAlive) callbackThread.interrupt()
            if (closingThread.isAlive) closingThread.interrupt()
            callbackThread.join(2_000)
            closingThread.join(2_000)
            assertFalse("Callback thread leaked", callbackThread.isAlive)
            assertFalse("Closing thread leaked", closingThread.isAlive)
            capture.close()
            consumer.close()
        }
    }

    @Test fun ownerChangeNullPermanentlyRetiresCaptureBeforeReturningToSameOwner() {
        val source = Source()
        val consumer = ScannerKnowledgeConsumer(source)
        val capture = consumer.begin(FP)
        val late = requireNotNull(source.callback)
        try {
            assertEquals(RECORD, capture.current())
            source.owner = "b"
            // Genuine observation close publishes null while B is current. Deliberately
            // no onCloudState(B): the StateFlow collector can miss this transient owner.
            late(null)
            source.owner = "a"
            assertNull("A -> B/null -> A must not resurrect the old owner generation", capture.current())
            late(RECORD.copy(cardId = "late-after-return"))
            assertNull("An old callback must remain retired after the owner returns", capture.current())
            assertEquals(listOf(1), source.closes)
            // An auth generation change is NOT an entitlement denial. A genuinely new
            // capture may still use A's normal offline cache, without reviving the old one.
            val fresh = consumer.begin(FP)
            assertEquals(RECORD, fresh.current())
            late(RECORD.copy(cardId = "old-callback-must-not-poison-new-capture"))
            assertNull(capture.current())
            assertEquals(RECORD, fresh.current())
            assertEquals(2, source.reads)
            assertEquals(2, source.opens)
            assertEquals(listOf(1, 0), source.closes)
            fresh.close()
            assertEquals(listOf(1, 1), source.closes)
        } finally {
            capture.close()
            consumer.close()
        }
    }

    private class Source : ScannerKnowledgeSource {
        var owner: String? = "a"
        var reads = 0
        var opens = 0
        val closes = mutableListOf<Int>()
        var callback: ((ScanKnowledgeRecord?) -> Unit)? = null
        var duringObserve: () -> Unit = {}
        var onClose: () -> Unit = {}
        override fun currentOwner() = owner
        override fun cached(fingerprint: String): ScanKnowledgeRecord {
            assertEquals(FP, fingerprint); reads++; return RECORD
        }
        override fun observe(fingerprint: String, update: (ScanKnowledgeRecord?) -> Unit): AutoCloseable {
            assertEquals(FP, fingerprint); callback = update
            val index = closes.size
            closes += 0; opens++
            update(RECORD)
            duringObserve()
            return AutoCloseable { closes[index]++; onClose() }
        }
    }

    companion object {
        private val FP = "a".repeat(64)
        private val RECORD = ScanKnowledgeRecord(FP, "synthetic-card", "synthetic-set", "001",
            ScanKnowledgeStatus.STABLE, 91, 4, 0, 1, 1_800_000_000_000L)
    }
}
