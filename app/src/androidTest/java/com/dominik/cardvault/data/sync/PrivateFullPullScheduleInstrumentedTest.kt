package com.dominik.cardvault.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.AppMetadataEntity
import com.dominik.cardvault.data.local.CardVaultDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Actual production metadata helper and Room rollback/reopen; no Firebase, default DB or network. */
@RunWith(AndroidJUnit4::class)
class PrivateFullPullScheduleInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val generation = AtomicLong(1)
    private val closed = AtomicBoolean(false)
    private var currentOwner: String? = OWNER
    private val dao get() = database.cardVaultDao()

    @Before fun setup() {
        // A single writer connection makes the TEMP-trigger observation unambiguous.
        database = Room.inMemoryDatabaseBuilder(context, CardVaultDatabase::class.java)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
    }

    @After fun teardown() { closed.set(true); database.close() }

    @Test fun rotationPreservesPartialCursorsAndEofClearsOnlyItsOwnerAndGroup() = runBlocking(Dispatchers.IO) {
        val obsolete = store()
        seedCursors(database, OWNER)
        switchOwner(OTHER)
        val other = store(owner = OTHER)
        for (group in 0..3) other.saveCursor(group, "other-$group")
        assertEquals(PrivateFullPullSchedule(2, 0), other.advanceGroup(1, false))
        switchOwner(OWNER)
        val before = snapshot(database)
        expectFailure<PrivatePullAccountChangedException> { obsolete.saveCursor(0, "must-not-write") }
        assertEquals(before, snapshot(database))

        val active = store()
        assertEquals(PrivateFullPullSchedule(2, 0), active.advanceGroup(1, false))
        for (group in 0..3) assertEquals("prior-$group", active.readCursor(group))
        assertEquals(PrivateFullPullSchedule(3, 4), active.advanceGroup(2, true))
        assertEquals("", active.readCursor(2))
        for (group in listOf(0, 1, 3)) assertEquals("prior-$group", active.readCursor(group))
        assertEquals(before.filterKeys { it == phase(OTHER) || it in (0..3).map { group -> cursor(OTHER, group) } },
            snapshot(database).filterKeys { it == phase(OTHER) || it in (0..3).map { group -> cursor(OTHER, group) } })
    }

    @Test fun corruptScheduleFailsWithoutClearingAnyCursorOrReplacingEvidence() = runBlocking(Dispatchers.IO) {
        seedCursors(database, OWNER)
        dao.putMetadata(AppMetadataEntity("unrelated-fixture-metadata", "unchanged"))
        val active = store()
        for (raw in listOf("", "5", "rr1|0|16", "rr1|0|01", " rr1|0|0", "rr2|0|0")) {
            dao.putMetadata(AppMetadataEntity(phase(OWNER), raw))
            val before = snapshot(database)
            expectFailure<IllegalStateException> { active.readSchedule() }
            assertEquals(before, snapshot(database))
            expectFailure<IllegalStateException> { active.advanceGroup(0, true) }
            assertEquals("Corruption must not clear the cursor before decode", before, snapshot(database))
            expectFailure<IllegalStateException> { active.resetCycle() }
            assertEquals(before, snapshot(database))
        }
    }

    @Test fun sqliteFailureAfterEofAndStateWritesRollsBothBackThenIdenticalRetrySucceeds() = runBlocking(Dispatchers.IO) {
        seedCursors(database, OWNER)
        seedCursors(database, OTHER)
        dao.putMetadata(AppMetadataEntity(phase(OWNER), "rr1|2|1"))
        val before = snapshot(database)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""
            CREATE TEMP TRIGGER full_pull_schedule_rollback_probe
            AFTER INSERT ON main.app_metadata
            WHEN NEW.`key` = '${phase(OWNER)}' AND NEW.value = 'rr1|3|5'
            BEGIN
                SELECT CASE WHEN
                    (SELECT value FROM main.app_metadata WHERE `key` = '${cursor(OWNER, 2)}') = ''
                    AND (SELECT value FROM main.app_metadata WHERE `key` = '${phase(OWNER)}') = 'rr1|3|5'
                    THEN RAISE(ABORT, 'FULL_PULL_AFTER_EOF_AND_SCHEDULE')
                    ELSE RAISE(ABORT, 'FULL_PULL_ROLLBACK_PROBE_TOO_EARLY') END;
            END
        """.trimIndent())
        try {
            val failure = runCatching { store().advanceGroup(2, true) }.exceptionOrNull()
            assertNotNull("The real SQLite writer must reach the rollback probe", failure)
            val causes = generateSequence(requireNotNull(failure)) { it.cause }.toList()
            assertTrue(causes.any { it is SQLiteException })
            assertTrue("Both new values must have existed inside the transaction: $failure",
                causes.any { it.message.orEmpty().contains("FULL_PULL_AFTER_EOF_AND_SCHEDULE") })
            assertEquals("Every metadata row must be restored", before, snapshot(database))
        } finally {
            sql.execSQL("DROP TRIGGER IF EXISTS temp.full_pull_schedule_rollback_probe")
        }
        assertEquals(PrivateFullPullSchedule(3, 5), store().advanceGroup(2, true))
        assertEquals(before + mapOf(phase(OWNER) to "rr1|3|5", cursor(OWNER, 2) to ""), snapshot(database))
    }

    @Test fun accountRoundTripAtFinalGuardRollsBackAlreadyWrittenEofAndState() = runBlocking(Dispatchers.IO) {
        seedCursors(database, OWNER)
        seedCursors(database, OTHER)
        dao.putMetadata(AppMetadataEntity(phase(OWNER), "rr1|2|1"))
        val before = snapshot(database)
        val captured = session(OWNER)
        var checks = 0
        var inside: Map<String, String>? = null
        val guarded = PrivateFullPullMetadataStore(database, OWNER) {
            if (++checks == 2) {
                // The real final session guard runs on the Room writer after BOTH writes.
                inside = snapshot(database)
                switchOwner(OTHER)
                switchOwner(OWNER)
            }
            captured.isCurrent()
        }
        expectFailure<PrivatePullAccountChangedException> { guarded.advanceGroup(2, true) }
        assertEquals(2, checks)
        assertEquals(before + mapOf(phase(OWNER) to "rr1|3|5", cursor(OWNER, 2) to ""), inside)
        assertEquals(before, snapshot(database))
        assertEquals(OWNER, currentOwner)
        assertFalse("Returning to the same owner cannot revive the old generation", captured.isCurrent())
        assertEquals(PrivateFullPullSchedule(3, 5), store().advanceGroup(2, true))
        assertEquals(before + mapOf(phase(OWNER) to "rr1|3|5", cursor(OWNER, 2) to ""), snapshot(database))
    }

    @Test fun legacyAndCompletedStateSurviveRealDatabaseReopenWithoutLosingPartialCursors() = runBlocking(Dispatchers.IO) {
        val token = UUID.randomUUID().toString()
        val name = "private-full-pull-schedule-$token.db"
        val file = context.getDatabasePath(name)
        val files = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
        assertTrue("Never reuse an existing database fixture", files.none(File::exists))
        val marker = "private-full-pull-schedule-fixture-owner"
        fun open() = Room.databaseBuilder(context, CardVaultDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        var disk = open()
        var marked = false
        try {
            disk.cardVaultDao().putMetadata(AppMetadataEntity(marker, token))
            marked = true
            seedCursors(disk, OWNER)
            seedCursors(disk, OTHER)
            disk.cardVaultDao().putMetadata(AppMetadataEntity(phase(OWNER), "2"))
            val legacy = snapshot(disk)
            disk.close(); disk = open()
            assertEquals(PrivateFullPullSchedule(2, 3), store(disk).readSchedule())
            assertEquals("Reading legacy state is not a destructive migration", legacy, snapshot(disk))
            assertEquals(PrivateFullPullSchedule(3, 3), store(disk).advanceGroup(2, false))
            val partial = snapshot(disk)
            disk.close(); disk = open()
            assertEquals(PrivateFullPullSchedule(3, 3), store(disk).readSchedule())
            assertEquals(partial, snapshot(disk))
            assertEquals(PrivateFullPullSchedule(0, 11), store(disk).advanceGroup(3, true))
            assertEquals(PrivateFullPullSchedule(3, 15), store(disk).advanceGroup(2, true))
            val complete = snapshot(disk)
            disk.close(); disk = open()
            assertTrue(store(disk).readSchedule().isComplete)
            assertEquals(complete, snapshot(disk))
            store(disk).resetCycle()
            assertEquals(PrivateFullPullSchedule(3, 0), store(disk).readSchedule())
            assertEquals("Reset changes only EOF evidence, not the next group or any cursor",
                complete + (phase(OWNER) to "rr1|3|0"), snapshot(disk))
            assertEquals("prior-0", store(disk).readCursor(0))
            assertEquals("prior-1", store(disk).readCursor(1))

            disk.cardVaultDao().putMetadata(AppMetadataEntity(phase(OWNER), "4"))
            val legacyComplete = snapshot(disk)
            disk.close(); disk = open()
            assertEquals(PrivateFullPullSchedule(0, 15), store(disk).readSchedule())
            assertEquals(legacyComplete, snapshot(disk))
            store(disk).resetCycle()
            assertEquals(legacyComplete + (phase(OWNER) to "rr1|0|0"), snapshot(disk))
        } finally {
            disk.close()
            check(marked && file.isFile) { "Unverified fixture; refusing cleanup" }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                sqlite.rawQuery("SELECT value FROM app_metadata WHERE `key` = ?", arrayOf(marker)).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == token && !cursor.moveToNext()) { "Fixture owner mismatch" }
                }
            }
            assertTrue("Delete only this exact verified test database", context.deleteDatabase(name))
            assertTrue(files.none(File::exists))
        }
    }

    private fun switchOwner(owner: String) { currentOwner = owner; generation.incrementAndGet() }
    private fun session(owner: String) = PrivatePullAuthSession(PrivatePullSessionLease(owner, generation.get()), generation, closed) { currentOwner }
    private fun store(db: CardVaultDatabase = database, owner: String = OWNER): PrivateFullPullMetadataStore {
        val captured = session(owner)
        return PrivateFullPullMetadataStore(db, owner) { captured.isCurrent() }
    }
    private suspend fun seedCursors(db: CardVaultDatabase, owner: String) {
        for (group in 0..3) db.cardVaultDao().putMetadata(AppMetadataEntity(cursor(owner, group), "prior-$group"))
    }
    private fun snapshot(db: CardVaultDatabase): Map<String, String> =
        db.openHelper.readableDatabase.query("SELECT `key`, value FROM app_metadata ORDER BY `key`").use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $failure", failure is T)
        return failure as T
    }
    private fun phase(owner: String) = PrivateFullPullMetadataStore.phaseKey(owner)
    private fun cursor(owner: String, group: Int) = PrivateFullPullMetadataStore.cursorKey(owner, group)

    companion object {
        private const val OWNER = "full-pull-schedule-A"
        private const val OTHER = "full-pull-schedule-B"
    }
}
