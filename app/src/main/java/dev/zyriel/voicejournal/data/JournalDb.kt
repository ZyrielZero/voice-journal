package dev.zyriel.voicejournal.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN embeddingModel TEXT")
    }
}

@Dao
interface JournalDao {
    @Insert suspend fun insert(entry: JournalEntry): Long
    @Update suspend fun update(entry: JournalEntry)
    @Delete suspend fun delete(entry: JournalEntry)
    @Query("SELECT * FROM entries ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<JournalEntry>>
    @Query("SELECT * FROM entries")
    suspend fun allOnce(): List<JournalEntry>
    // IS NOT, not !=: rows migrated from v1 carry embeddingModel NULL, and
    // NULL != 'tag' is NULL (not true), so != skipped every one of them.
    @Query("SELECT * FROM entries WHERE embedding IS NULL OR embeddingModel IS NOT :model")
    suspend fun needingEmbedding(model: String): List<JournalEntry>

    /**
     * Compare-and-set vector write: lands only if the transcript is still
     * the one that was embedded. Returns 0 when the row was edited (or
     * deleted) since, and the caller drops the stale vector. Never use
     * [update] for derived columns: it writes every column from a snapshot
     * and can revert a concurrent edit.
     */
    @Query("UPDATE entries SET embedding = :embedding, embeddingModel = :model WHERE id = :id AND transcript = :transcript")
    suspend fun setEmbeddingIfUnchanged(id: Long, transcript: String, embedding: FloatArray, model: String): Int

    /** Saves new text and clears the now-stale vector in one statement. */
    @Query("UPDATE entries SET transcript = :transcript, embedding = NULL, embeddingModel = NULL WHERE id = :id")
    suspend fun setTranscript(id: Long, transcript: String): Int

    @Query("SELECT COUNT(*) FROM entries WHERE audioPath = :path")
    suspend fun countByAudioPath(path: String): Int
}

/**
 * Schemas are exported to app/schemas/ and committed; every version bump
 * needs its JSON there and a step in JournalDbMigrationTest (androidTest).
 * 1.json was reconstructed from MIGRATION_1_2 (v1 = v2 minus embeddingModel)
 * by running Room's exporter on that entity, since v1 predates export.
 */
@Database(entities = [JournalEntry::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class JournalDb : RoomDatabase() {
    abstract fun dao(): JournalDao

    companion object {
        @Volatile private var instance: JournalDb? = null
        fun get(context: Context): JournalDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, JournalDb::class.java, "journal.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
