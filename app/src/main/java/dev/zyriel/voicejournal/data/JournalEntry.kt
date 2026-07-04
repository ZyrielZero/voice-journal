package dev.zyriel.voicejournal.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Entity(tableName = "entries")
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transcript: String,
    val timestampMs: Long,
    val audioPath: String,
    /** Embedding vector, null until an embedding engine is available. */
    val embedding: FloatArray? = null,
    /** Which model produced [embedding]. Vectors from different models are not comparable. */
    val embeddingModel: String? = null,
) {
    override fun equals(other: Any?) = other is JournalEntry && other.id == id
    override fun hashCode() = id.hashCode()
}

/** FloatArray <-> little-endian BLOB. Little-endian to match on-device model output conventions. */
class Converters {
    @TypeConverter
    fun fromFloatArray(v: FloatArray?): ByteArray? {
        if (v == null) return null
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return buf.array()
    }

    @TypeConverter
    fun toFloatArray(b: ByteArray?): FloatArray? {
        if (b == null) return null
        require(b.size % 4 == 0) { "BLOB size not a multiple of 4" }
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.float }
    }
}
