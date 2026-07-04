package dev.zyriel.voicejournal.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
}
