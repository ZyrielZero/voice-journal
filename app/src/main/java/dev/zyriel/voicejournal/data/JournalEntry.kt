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
    // Structural equality, hand-written because FloatArray in a data class
    // would compare by reference. Must include every field: the entries list
    // flows through StateFlow, which drops emissions equal to the current
    // value. An equals that ignores fields makes edits to those fields
    // invisible to the UI even though the DB row updated.
    override fun equals(other: Any?): Boolean =
        other is JournalEntry &&
            other.id == id &&
            other.transcript == transcript &&
            other.timestampMs == timestampMs &&
            other.audioPath == audioPath &&
            other.embeddingModel == embeddingModel &&
            (other.embedding contentEquals embedding)

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + transcript.hashCode()
        h = 31 * h + timestampMs.hashCode()
        h = 31 * h + audioPath.hashCode()
        h = 31 * h + (embeddingModel?.hashCode() ?: 0)
        h = 31 * h + (embedding?.contentHashCode() ?: 0)
        return h
    }
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
