package com.dominik.cardvault.data.sync

import android.os.Build
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import com.dominik.cardvault.data.local.PrivateBinderEntity
import com.dominik.cardvault.data.local.PrivateBinderPlacementEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Direct production slot-helper + real Room tests, synthetic in-memory DB only.
 * No Firebase, Worker, authentication, application DB singleton, Activity or network.
 * This is NOT a 20k sync benchmark: only the local position lookup is measured.
 *
 * Scale test is opt-in: release27PlacementScale=true, release27PlacementRepeats=3 (3..10).
 * Fixed sizes 1k/5k/20k; no timing SLA. QueryCallback observes SQL, not disk I/O or wire reads.
 * EXPLAIN uses the actual captured DAO statement and arguments, without INDEXED BY hints.
 */
@RunWith(AndroidJUnit4::class)
class PrivatePlacementPositionInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val dao get() = database.cardVaultDao()
    private val calls = CopyOnWriteArrayList<SqlCall>()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java,
        ).setQueryCallback(RoomDatabase.QueryCallback { sql, args ->
            val normalized = sql.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            if (normalized.startsWith("select") && normalized.contains("private_binder_placements")) {
                calls += SqlCall(sql, args.toList(), normalized)
            }
        }, Executor { it.run() }).build()
    }

    @After fun tearDown() = database.close()

    @Test fun freeSlotUsesOneExistenceQueryWithoutMaterializingPlacements() = runBlocking {
        seedParents()
        add("occupied", 0L)
        val before = placementHash()
        calls.clear()
        assertEquals(99L, position("incoming", 99L))
        assertOnlyExistenceQuery()
        assertEquals(before, placementHash())
    }

    @Test fun collisionPreservesFirstFreePolicyIncludingSparseSlots() = runBlocking {
        seedParents()
        add("zero", 0L)
        add("one", 1L)
        add("three", 3L)
        add("far-away", 90_000L)
        val before = placementHash()
        calls.clear()
        assertEquals(2L, position("incoming", 3L))
        assertEquals(1, calls.count { it.isExists })
        assertEquals(1, calls.count { it.isFullMaterialization })
        assertEquals(2, calls.size)
        assertEquals(before, placementHash())
    }

    @Test fun ownDocumentIsExcludedButSecondActiveOccupantStillCollides() = runBlocking {
        seedParents()
        add("own", 0L)
        add("another", 0L)
        add("next", 1L)
        calls.clear()
        assertEquals(2L, position("own", 0L))
        assertEquals(1, calls.count { it.isFullMaterialization })
        // Own old slot is reusable when moving onto a different occupied slot.
        dao.updatePrivateBinderPlacement(requireNotNull(dao.privateBinderPlacement("another", OWNER))
            .copy(slotPosition = 3L))
        calls.clear()
        assertEquals(0L, position("own", 1L))
        assertEquals(1, calls.count { it.isFullMaterialization })
    }

    @Test fun onlyOwnDocumentAtRequestedSlotIsNotACollision() = runBlocking {
        seedParents()
        add("own", 7L)
        calls.clear()
        assertEquals(7L, position("own", 7L))
        assertOnlyExistenceQuery()
    }

    @Test fun tombstonesForeignOwnerAndOtherBinderDoNotReserveSlots() = runBlocking {
        seedParents()
        seedParents(OTHER_OWNER)
        seedParents(binder = OTHER_BINDER)
        add("deleted", 5L, deleted = true)
        add("foreign-owner", 5L, owner = OTHER_OWNER)
        add("other-binder", 5L, binder = OTHER_BINDER)
        val before = placementHash()
        calls.clear()
        assertEquals(5L, position("incoming", 5L))
        assertOnlyExistenceQuery()
        assertEquals(before, placementHash())

        // Ignore those same rows while finding a replacement slot, too.
        add("occupied", 8L)
        add("deleted-zero", 0L, deleted = true)
        add("foreign-zero", 0L, owner = OTHER_OWNER)
        add("other-binder-zero", 0L, binder = OTHER_BINDER)
        assertEquals(0L, position("incoming", 8L))
    }

    @Test fun actualExistenceQueryUsesExistingOwnerBinderSlotIndex() = runBlocking {
        seedParents()
        add("occupied", 0L)
        calls.clear()
        assertEquals(1L, position("incoming", 1L))
        assertSlotIndex(calls.single())
    }

    @Test fun localOnlySlotLookupAtOneFiveAndTwentyThousandPlacements() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit opt-in for local-only scale measurements",
            args.getString("release27PlacementScale") == "true")
        assumeTrue("Scale measurements use only an emulator",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.contains("sdk_gphone") ||
                Build.FINGERPRINT.contains("emulator"))
        val repeats = args.getString("release27PlacementRepeats")?.toIntOrNull() ?: 3
        require(repeats in 3..10)
        seedParents()
        var seeded = 0
        for (size in listOf(1_000, 5_000, 20_000)) {
            seedRange(seeded, size)
            seeded = size
            val before = placementHash()
            // All N placements exist before each read. This is NOT N sync/apply operations.
            for (repeat in 0 until repeats) {
                calls.clear()
                val start = System.nanoTime()
                assertEquals(size.toLong(), position("incoming", size.toLong()))
                val optimizedNanos = System.nanoTime() - start
                assertOnlyExistenceQuery()
                val existsCall = calls.single()
                assertSlotIndex(existsCall)

                calls.clear()
                val legacyStart = System.nanoTime()
                val legacyResult = database.withTransaction {
                    // Baseline for the prior materialization strategy only, not copied sync logic.
                    val active = dao.activePrivateBinderPlacements(BINDER, OWNER)
                    val requested = size.toLong()
                    val targetOccupied = active.any { it.id != "incoming" && it.slotPosition == requested }
                    var candidate = requested
                    if (targetOccupied) {
                        val occupied = active.asSequence()
                            .filter { it.id != "incoming" && it.deletedAtEpochMillis == null && it.slotPosition >= 0L }
                            .map { it.slotPosition }.toHashSet()
                        candidate = 0L
                        while (candidate in occupied) candidate++
                    }
                    candidate
                }
                val legacyNanos = System.nanoTime() - legacyStart
                assertEquals(size.toLong(), legacyResult)
                assertEquals(1, calls.size)
                assertTrue(calls.single().isFullMaterialization)

                calls.clear()
                val collisionStart = System.nanoTime()
                assertEquals(size.toLong(), position("incoming", 0L))
                val collisionNanos = System.nanoTime() - collisionStart
                assertEquals(1, calls.count { it.isExists })
                assertEquals(1, calls.count { it.isFullMaterialization })
                Log.i(TAG, "scope=localRoomSlotLookup size=$size repeat=$repeat " +
                    "freeNs=$optimizedNanos freeQueries=1 freeMaterializedRows=0 " +
                    "priorStrategyNs=$legacyNanos priorMaterializedRows=$size " +
                    "collisionNs=$collisionNanos collisionQueries=2 collisionMaterializedRows=$size")
            }
            assertEquals("Slot selection must not modify any placement field", before, placementHash())
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse("Synthetic fixture must preserve foreign keys", it.moveToFirst())
            }
        }
    }

    private suspend fun position(id: String, requested: Long): Long = database.withTransaction {
        remotePrivatePlacementPosition(dao, OWNER, BINDER, id, requested)
    }

    private suspend fun seedParents(owner: String = OWNER, binder: String = BINDER) {
        dao.insertCatalogCards(listOf(CatalogCardEntity(
            CARD, "POKEMON", "synthetic-set", "Synthetic set", "Synthetic card", "1",
            "GERMAN", "Normal", "Standard", 1L,
        )))
        dao.upsertPrivateBinder(PrivateBinderEntity(
            id = binder, ownerUid = owner, name = "Synthetic binder", createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L, syncState = SYNC_CLEAN, remoteRevision = 1L,
        ))
    }

    private suspend fun add(
        id: String, slot: Long, owner: String = OWNER, binder: String = BINDER, deleted: Boolean = false,
    ) {
        val entryId = dao.insertCollectionEntry(CollectionEntryEntity(
            cardId = CARD, quantity = 1, condition = "Near Mint", acquisitionPriceMinor = null,
            acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "synthetic",
            frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
            ownerUid = owner, syncId = "$binder-$id", syncState = SYNC_CLEAN, remoteRevision = 1L,
        ))
        dao.insertPrivateBinderPlacement(PrivateBinderPlacementEntity(
            id = id, ownerUid = owner, binderId = binder, collectionEntryId = entryId,
            slotPosition = slot, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
            syncState = SYNC_CLEAN, remoteRevision = 1L, deletedAtEpochMillis = if (deleted) 2L else null,
        ))
    }

    private suspend fun seedRange(from: Int, limit: Int) = database.withTransaction {
        // Compiled SQL keeps fixture setup bounded; measurements use production Room DAO/helper.
        val sql = database.openHelper.writableDatabase
        sql.compileStatement("""INSERT INTO collection_entries
            (cardId, quantity, condition, notes, createdAtEpochMillis, updatedAtEpochMillis,
             ownerUid, syncId, syncState, remoteRevision)
            VALUES (?, 1, 'Near Mint', 'synthetic scale', 1, 1, ?, ?, 'CLEAN', 1)""").use { entries ->
            sql.compileStatement("""INSERT INTO private_binder_placements
                (id, ownerUid, binderId, collectionEntryId, slotPosition, createdAtEpochMillis,
                 updatedAtEpochMillis, syncState, remoteRevision)
                VALUES (?, ?, ?, ?, ?, 1, 1, 'CLEAN', 1)""").use { placements ->
                for (i in from until limit) {
                    entries.bindString(1, CARD)
                    entries.bindString(2, OWNER)
                    entries.bindString(3, "scale-entry-$i")
                    val entryId = entries.executeInsert()
                    check(entryId > 0L)
                    placements.bindString(1, "scale-placement-$i")
                    placements.bindString(2, OWNER)
                    placements.bindString(3, BINDER)
                    placements.bindLong(4, entryId)
                    placements.bindLong(5, i.toLong())
                    check(placements.executeInsert() != -1L)
                }
            }
        }
    }

    private fun assertOnlyExistenceQuery() {
        assertEquals("Free slot must not fetch the whole binder", 1, calls.size)
        assertTrue(calls.single().isExists)
        assertFalse(calls.any { it.isFullMaterialization })
    }

    private fun assertSlotIndex(call: SqlCall) {
        val details = mutableListOf<String>()
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery(
            "EXPLAIN QUERY PLAN ${call.sql}", call.args.toTypedArray(),
        )).use { cursor ->
            val column = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) details += cursor.getString(column)
        }
        assertTrue("Actual DAO plan must use the existing slot index: $details",
            details.any { it.contains("index_private_binder_placements_ownerUid_binderId_slotPosition") &&
                it.contains("SEARCH", ignoreCase = true) })
        Log.i(TAG, "scope=localRoomSlotLookup plan=${details.joinToString(" | ")}")
    }

    private fun placementHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        database.openHelper.readableDatabase.query(
            "SELECT * FROM private_binder_placements ORDER BY ownerUid, id",
        ).use { cursor ->
            while (cursor.moveToNext()) for (column in 0 until cursor.columnCount) {
                val value = if (cursor.isNull(column)) "N;" else cursor.getString(column).let {
                    "${cursor.getType(column)}:${it.length}:$it;"
                }
                digest.update(value.toByteArray(Charsets.UTF_8))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class SqlCall(val sql: String, val args: List<Any?>, val normalized: String) {
        val isExists get() = normalized.startsWith("select exists(")
        val isFullMaterialization get() = normalized.startsWith("select * from private_binder_placements")
    }

    companion object {
        private const val TAG = "R27PlacementPosition"
        private const val OWNER = "synthetic-slot-owner"
        private const val OTHER_OWNER = "synthetic-slot-other-owner"
        private const val BINDER = "synthetic-slot-binder"
        private const val OTHER_BINDER = "synthetic-slot-other-binder"
        private const val CARD = "synthetic-slot-card"
    }
}
