package dev.zyriel.voicejournal.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies every migration against the committed schemas in app/schemas/
 * (packaged as androidTest assets). Device-only: run with
 * ./gradlew :app:connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class JournalDbMigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JournalDb::class.java,
    )

    @Test
    fun migrate1To2KeepsRowsAndAddsANullModelColumn() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO entries (id, transcript, timestampMs, audioPath, embedding) " +
                    "VALUES (1, 'before the bump', 1700000000000, '/r/a.wav', x'0000803f')"
            )
            db.execSQL(
                "INSERT INTO entries (id, transcript, timestampMs, audioPath, embedding) " +
                    "VALUES (2, 'never embedded', 1700000001000, '', NULL)"
            )
        }

        // Validates the migrated table against 2.json column by column.
        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT transcript, embeddingModel FROM entries ORDER BY id").use { c ->
                assertEquals(2, c.count)
                c.moveToFirst()
                assertEquals("before the bump", c.getString(0))
                assertNull(c.getString(1))
            }
        }

        // Through Room itself: both v1 rows need a vector under the current
        // query, including the one that already has a (model-less) vector.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(ctx, JournalDb::class.java, dbName)
            .addMigrations(MIGRATION_1_2)
            .build()
        try {
            val needing = runBlocking { room.dao().needingEmbedding("any-model") }
            assertEquals(listOf(1L, 2L), needing.map { it.id }.sorted())
        } finally {
            room.close()
        }
    }
}
