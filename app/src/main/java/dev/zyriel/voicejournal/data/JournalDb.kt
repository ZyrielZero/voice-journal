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
    @Query("SELECT * FROM entries WHERE embedding IS NULL OR embeddingModel != :model")
    suspend fun needingEmbedding(model: String): List<JournalEntry>
}

@Database(entities = [JournalEntry::class], version = 2, exportSchema = false)
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
