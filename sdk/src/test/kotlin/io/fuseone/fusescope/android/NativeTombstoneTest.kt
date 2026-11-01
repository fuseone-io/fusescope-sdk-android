package io.fuseone.fusescope.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class NativeTombstoneTest {
    @Test
    fun `decodes only the crashing thread and excludes memory logs and command line`() {
        val frame = scalar(1, 0x42) + text(4, "checkout") + scalar(5, 8) +
            text(6, "/data/app/example/libshop.so") + text(8, "aabbcc")
        val thread = scalar(1, 7) + message(4, frame) + text(5, "private memory")
        val mapEntry = scalar(1, 7) + message(2, thread)
        // Deliberately put the thread before tid: protobuf fields need not be ordered.
        val bytes = message(16, mapEntry) + scalar(6, 7) +
            message(10, text(2, "SIGABRT")) + text(9, "private command") + text(18, "private logs")
        val parsed = requireNotNull(parseNativeTombstone(bytes))
        assertEquals("SIGABRT", parsed.signal)
        assertTrue(parsed.stack.contains("#00 pc 42 /data/app/example/libshop.so (checkout+8) (BuildId: aabbcc)"))
        assertFalse(parsed.stack.contains("private"))
    }

    @Test
    fun `never substitutes another threads frames for the crashing thread`() {
        val other = scalar(1, 9) + message(2, message(4, scalar(1, 10) + text(6, "libwrong.so")))
        val parsed = requireNotNull(parseNativeTombstone(scalar(6, 7) + message(16, other)))
        assertFalse(parsed.stack.contains("libwrong"))
    }

    @Test
    fun `rejects malformed and oversized protobuf without throwing`() {
        for (bytes in listOf(byteArrayOf(0x82.toByte(), 1, 127), byteArrayOf(0), ByteArray(4 * 1024 * 1024 + 1))) {
            assertNull(parseNativeTombstone(bytes))
        }
    }

    @Test
    fun `limits frame output and formats high unsigned program counters`() {
        val frame = message(4, scalar(1, -1) + text(6, "libbounded.so"))
        val thread = (0..500).fold(ByteArray(0)) { result, _ -> result + frame }
        val bytes = scalar(6, 3) + message(16, scalar(1, 3) + message(2, thread))
        val parsed = requireNotNull(parseNativeTombstone(bytes))
        assertTrue(parsed.stack.contains("pc ffffffffffffffff"))
        assertTrue(parsed.stack.lines().count { it.trimStart().startsWith("#") } <= 201)
        assertTrue(parsed.stack.length <= 65537)
    }

    private fun scalar(number: Int, value: Long): ByteArray = varint((number shl 3).toLong()) + varint(value)

    private fun text(number: Int, value: String): ByteArray = message(number, value.toByteArray())

    private fun message(number: Int, value: ByteArray): ByteArray =
        varint(((number shl 3) or 2).toLong()) + varint(value.size.toLong()) + value

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = value.toULong()
        while (remaining >= 128u) {
            out.write(((remaining and 127u).toInt()) or 128)
            remaining = remaining shr 7
        }
        out.write(remaining.toInt())
        return out.toByteArray()
    }
}
