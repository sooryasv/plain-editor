package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random

class CodecStreamingTest {
    private fun naive(text: String, le: LineEnding, bom: Boolean): ByteArray {
        val body = if (le == LineEnding.CRLF) text.replace("\r\n", "\n").replace("\n", "\r\n") else text
        return (if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) else ByteArray(0)) + body.toByteArray(Charsets.UTF_8)
    }

    @Test fun streamingMatchesNaiveEncodingForRandomText() {
        val rnd = Random(7)
        val alphabet = listOf("a", "b", "\n", "é", "😀", "日", " ", "\r\n", "\t")
        for (round in 0 until 40) {
            val text = (0 until rnd.nextInt(60_000)).joinToString("") { alphabet[rnd.nextInt(alphabet.size)] }
            for (le in LineEnding.values()) for (bom in listOf(false, true)) {
                assertArrayEquals("round $round $le $bom", naive(text, le, bom), TextCodec.encode(TextBuffer.fromString(text), le, bom))
            }
        }
    }

    @Test fun surrogatePairsAreNeverSplitAtChunkBoundaries() {
        for (pad in 16380..16390) {
            val text = "x".repeat(pad) + "😀😀😀" + "y".repeat(20)
            assertArrayEquals("pad $pad", text.toByteArray(), TextCodec.encode(text, LineEnding.LF, false))
            assertArrayEquals("pad $pad crlf", naive(text, LineEnding.CRLF, false), TextCodec.encode(text, LineEnding.CRLF, false))
        }
    }

    @Test fun digestAndCountMatchWrittenBytes() {
        val text = "héllo\nwörld 😀\n".repeat(5000)
        val md = MessageDigest.getInstance("SHA-256"); val out = ByteArrayOutputStream()
        val n = TextCodec.encodeTo(text, LineEnding.LF, true, out, md)
        assertEquals(out.size().toLong(), n)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()), md.digest())
    }

    @Test fun decodeToBufferRoundTripsAcrossManyShapes() {
        val rnd = Random(3)
        val alphabet = listOf("a", "\n", "é", "😀", "日", " ", "\t", "\r\n", "\r")
        for (round in 0 until 60) {
            val text = (0 until rnd.nextInt(30_000)).joinToString("") { alphabet[rnd.nextInt(alphabet.size)] }
            val bytes = text.toByteArray()
            val r = TextCodec.decodeToBuffer(bytes, bytes.size) as TextCodec.BufferResult.Ok
            val d = r.doc
            val again = TextCodec.encode(d.buffer, d.lineEnding, d.hasBom)
            if (!d.lineEndingsNormalized) assertArrayEquals("round $round exact", bytes, again)
            else assertFalse("normalized text has no CR", d.buffer.toString().contains('\r'))
        }
    }

    @Test fun decodeUsesOnlyTheGivenPrefixOfTheArray() {
        val arr = "abc\ndef".toByteArray() + ByteArray(100) { 0 }
        val r = TextCodec.decodeToBuffer(arr, 7) as TextCodec.BufferResult.Ok
        assertEquals("abc\ndef", r.doc.buffer.toString())
    }

    @Test fun tenMebibyteEncodeDecodeTiming() {
        val sb = StringBuilder(11 * 1024 * 1024)
        var i = 0
        while (sb.length < 8_300_000) { sb.append("    ".repeat(i % 4)).append("row $i, ünï 日本 😀, key: value\n"); i++ }
        val text = sb.toString()
        val src = TextBuffer.fromString(text)
        val t0 = System.nanoTime()
        val bytes = TextCodec.encode(src, LineEnding.LF, false)
        val t1 = System.nanoTime()
        val back = TextCodec.decodeToBuffer(bytes, bytes.size) as TextCodec.BufferResult.Ok
        val t2 = System.nanoTime()
        assertTrue(back.doc.buffer.contentEquals(text))
        println("CODEC: ${bytes.size} bytes, ${text.length} chars: encode ${(t1 - t0) / 1_000_000} ms, decode ${(t2 - t1) / 1_000_000} ms")
        assertTrue(bytes.size > 9_500_000 && bytes.size <= Limits.MAX_OPEN_BYTES.toInt())
    }
}
