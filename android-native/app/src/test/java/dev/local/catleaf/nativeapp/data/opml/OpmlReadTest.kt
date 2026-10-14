package dev.local.catleaf.nativeapp.data.opml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class OpmlReadTest {
    @Test fun exact_budget_is_read_and_one_more_byte_is_rejected_without_consuming_the_file() {
        assertEquals(Opml.MAX_BYTES, Opml.readBytes(ByteArrayInputStream(ByteArray(Opml.MAX_BYTES))).size)
        val input = ByteArrayInputStream(ByteArray(Opml.MAX_BYTES * 3))
        val error = runCatching { Opml.readBytes(input) }.exceptionOrNull()
        assertTrue(error is Opml.OpmlError.TooLarge)
        assertEquals(Opml.MAX_BYTES * 2 - 1, input.available())
    }
}
