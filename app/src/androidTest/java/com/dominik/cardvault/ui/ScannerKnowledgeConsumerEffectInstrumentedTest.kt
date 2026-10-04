package com.dominik.cardvault.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.knowledge.ScanKnowledgeRecord
import com.dominik.cardvault.data.knowledge.ScanKnowledgeStatus
import com.dominik.cardvault.data.sync.CollectionCloudState
import com.dominik.cardvault.data.sync.CollectionCloudStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exact ScannerScreen remembered consumer/effect. No camera, Firebase, files, account or network.
 * Synthetic adapters prove consumer reconciliation, NOT delivery of real server NO_ACCESS.
 * Pretester Reviewed Knowledge and its independent access/consent are deliberately untouched. */
@RunWith(AndroidJUnit4::class)
class ScannerKnowledgeConsumerEffectInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val source = Source()
    private val states = MutableStateFlow(CollectionCloudState(CollectionCloudStatus.ACTIVE, "a", true))
    private val visible = mutableStateOf(true)
    private lateinit var consumer: ScannerKnowledgeConsumer

    @After fun dispose() {
        if (::consumer.isInitialized) {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
        }
    }

    private fun show() {
        compose.setContent {
            if (visible.value) {
                val active = rememberScannerKnowledgeConsumer(states, source)
                SideEffect { consumer = active }
            }
        }
        compose.waitForIdle()
    }

    @Test fun existingCloudFlowRevokesActualSharedConsumerAndBlocksNextCapture() {
        show()
        lateinit var capture: ScannerKnowledgeConsumer.Capture
        compose.runOnIdle {
            capture = consumer.begin(FP)
            assertEquals(RECORD, capture.current())
            states.value = CollectionCloudState(CollectionCloudStatus.NO_ACCESS, "a")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, source.closed)
            assertNull(capture.current())
            source.late?.invoke(RECORD.copy(cardId = "late"))
            assertNull(capture.current())
            assertNull(consumer.begin(FP).current())
            assertEquals(1, source.reads)
        }
    }

    @Test fun initialDeniedStateIsConsumedBeforeUserCaptureWithoutAnySourceRead() {
        states.value = CollectionCloudState(CollectionCloudStatus.NO_ACCESS, "a")
        show()
        compose.runOnIdle {
            assertNull(consumer.begin(FP).current())
            assertEquals(0, source.reads)
            assertEquals(0, source.opened)
        }
    }

    @Test fun offlineAndForeignEventsKeepHintButEffectDisposalClosesObservation() {
        show()
        lateinit var capture: ScannerKnowledgeConsumer.Capture
        compose.runOnIdle {
            capture = consumer.begin(FP)
            states.value = CollectionCloudState(CollectionCloudStatus.LOCAL_ONLY, "a")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(RECORD, capture.current())
            states.value = CollectionCloudState(CollectionCloudStatus.NO_ACCESS, "b")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(RECORD, capture.current())
            assertEquals(0, source.closed)
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, source.closed)
            assertNull(capture.current())
            source.late?.invoke(RECORD)
            assertNull(capture.current())
        }
    }

    private class Source : ScannerKnowledgeSource {
        var reads = 0
        var opened = 0
        var closed = 0
        var late: ((ScanKnowledgeRecord?) -> Unit)? = null
        override fun currentOwner() = "a"
        override fun cached(fingerprint: String): ScanKnowledgeRecord {
            assertEquals(FP, fingerprint); reads++; return RECORD
        }
        override fun observe(fingerprint: String, update: (ScanKnowledgeRecord?) -> Unit): AutoCloseable {
            assertEquals(FP, fingerprint); opened++; late = update; update(RECORD)
            return AutoCloseable { closed++ }
        }
    }

    companion object {
        private val FP = "a".repeat(64)
        private val RECORD = ScanKnowledgeRecord(FP, "synthetic-card", "synthetic-set", "001",
            ScanKnowledgeStatus.STABLE, 91, 4, 0, 1, 1_800_000_000_000L)
    }
}
