package com.dominik.cardvault.data.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalDataStartupTest {
    @Test fun statusTracksOnlyTheOperationThatIsActuallyAboutToRun() = runBlocking {
        val events = mutableListOf<String>()
        assertEquals(LocalDataStartupState.Ready, prepareLocalDataStartup(
            recover = { events += "recover" },
            seed = { events += "seed" },
            startCollectionSync = { events += "sync" },
            onPhaseChanged = { events += it.name },
        ))
        assertEquals(listOf("LOCAL_RECOVERY", "recover", "COLLECTION", "seed", "SYNC_SESSION", "sync"), events)
    }
    @Test fun failedRecoveryNeverReportsCollectionOrSyncPreparation() = runBlocking {
        val phases = mutableListOf<LocalDataStartupPhase>()
        assertTrue(prepareLocalDataStartup({ error("Broken journal") }, {}, {}, phases::add) is LocalDataStartupState.Failed)
        assertEquals(listOf(LocalDataStartupPhase.LOCAL_RECOVERY), phases)
    }
    @Test fun recoveryPrecedesSeedAndCollectionSync() = runBlocking {
        val calls = mutableListOf<String>()
        assertEquals(LocalDataStartupState.Ready, prepareLocalDataStartup(
            { calls.add("recovery") }, { calls.add("seed") }, { calls.add("cloud") },
        ))
        assertEquals(listOf("recovery", "seed", "cloud"), calls)
    }
    @Test fun brokenJournalBlocksSeedAndSyncWithoutCrashingOrSkipping() = runBlocking {
        val state = prepareLocalDataStartup({ error("Broken journal") }, { fail("No seed before recovery") }, { fail("No sync before recovery") })
        assertTrue(state is LocalDataStartupState.Failed)
    }
    @Test fun retryRunsRecoveryAgainBeforeEnablingCloud() = runBlocking {
        var attempts = 0
        var cloudStarts = 0
        val recovery: suspend () -> Unit = { if (++attempts == 1) error("Temporary read failure") }
        assertTrue(prepareLocalDataStartup(recovery, {}, { cloudStarts++ }) is LocalDataStartupState.Failed)
        assertEquals(0, cloudStarts)
        assertEquals(LocalDataStartupState.Ready, prepareLocalDataStartup(recovery, {}, { cloudStarts++ }))
        assertEquals(2, attempts)
        assertEquals(1, cloudStarts)
    }
    @Test fun seedFailureAlsoBlocksCloud() = runBlocking {
        assertTrue(prepareLocalDataStartup({}, { error("Cannot seed") }, { fail("No sync before seed") }) is LocalDataStartupState.Failed)
    }
    @Test fun cancellationIsNotShownAsAnError() = runBlocking {
        try { prepareLocalDataStartup({ throw CancellationException("Activity closed") }, {}, {}); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
    }
}
