package com.dominik.cardvault.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CollectionSyncOutboxEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionOutboxLifecycleInstrumentedTest {
    @Test fun observerRunsOffUiAndStopsWithItsSession() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val changes = Channel<Unit>(Channel.UNLIMITED)
        try {
            val observer = scope.observeCollectionOutbox(database) { changes.trySend(Unit) }
            withTimeout(3000) { changes.receive() }
            database.cardVaultDao().upsertSyncOutbox(CollectionSyncOutboxEntity(
                entryKey = "owner:item", mutationId = "first", ownerUid = "owner", syncId = "item",
                operation = "UPSERT", expectedRemoteRevision = 0, createdAtEpochMillis = 1,
            ))
            withTimeout(3000) { changes.receive() }
            observer.cancelAndJoin()
            while (changes.tryReceive().isSuccess) { }
            database.cardVaultDao().upsertSyncOutbox(CollectionSyncOutboxEntity(
                entryKey = "owner:item", mutationId = "second", ownerUid = "owner", syncId = "item",
                operation = "UPSERT", expectedRemoteRevision = 0, createdAtEpochMillis = 2,
            ))
            assertNull(withTimeoutOrNull(250) { changes.receive() })
        } finally { scope.cancel(); database.close(); changes.close() }
    }
}
