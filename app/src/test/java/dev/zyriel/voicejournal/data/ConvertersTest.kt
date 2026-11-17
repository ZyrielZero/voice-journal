package dev.zyriel.voicejournal.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertersTest {
    private val c = Converters()

    @Test fun roundTripPreservesValues() {
        val v = floatArrayOf(0.1f, -2.5f, 3.14159f, 0f, Float.MIN_VALUE, -1e30f)
        assertArrayEquals(v, c.toFloatArray(c.fromFloatArray(v)), 0f)
    }

    @Test fun nullPassesThrough() {
        assertNull(c.fromFloatArray(null))
        assertNull(c.toFloatArray(null))
    }

    @Test fun blobSizeIsFourBytesPerFloat() {
        assertEquals(768 * 4, c.fromFloatArray(FloatArray(768))!!.size)
    }

    @Test fun malformedBlobThrows() {
        assertThrows(IllegalArgumentException::class.java) { c.toFloatArray(ByteArray(7)) }
    }

    @Test fun emptyVectorRoundTrips() {
        assertEquals(0, c.toFloatArray(c.fromFloatArray(FloatArray(0)))!!.size)
    }

    /**
     * Room does not run a type converter on an array bound in a @Query: it
     * expands it into one placeholder per element. A DAO method that takes
     * a FloatArray therefore compiles, passes every JVM test (none of them
     * runs Room) and fails with a syntax error on the device, which is how
     * the embedding backfill was broken. Vectors go in as their BLOB.
     */
    @Test fun noDaoMethodTakesAFloatArray() {
        val offenders = JournalDao::class.java.declaredMethods
            .filter { m -> m.parameterTypes.any { it == FloatArray::class.java } }
            .map { it.name }
        assertTrue("pass the vector as a ByteArray (Converters.fromFloatArray): $offenders", offenders.isEmpty())
    }
}
