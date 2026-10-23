package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Test

class TextCodecTest {
    private fun ok(bytes: ByteArray) = (TextCodec.decode(bytes) as TextCodec.DecodeResult.Ok).doc

    private fun roundTrip(text: String) {
        val d = ok(TextCodec.encode(text, LineEnding.LF, false))
        assertEquals(text, d.text)
    }

    @Test fun emptyFile() {
        val d = ok(ByteArray(0))
        assertEquals("", d.text); assertEquals(LineEnding.LF, d.lineEnding); assertFalse(d.hasBom)
        assertEquals(0, TextCodec.encode("", LineEnding.LF, false).size)
    }

    @Test fun unicodeEmojiAndNonLatinRoundTrip() {
        roundTrip("héllo wörld — Привет мир — 日本語のテキスト — العربية — 😀👩‍👩‍👧‍👦🏳️‍🌈 — \u00A0nbsp")
    }

    @Test fun indentationBlankLinesTabsAndTrailingSpacesPreserved() {
        val yaml = "root:\n  child:\n    - a: 1\n      b:  \t x \n\n\n  other: \"q\"   \n\tTAB\n"
        roundTrip(yaml)
        val py = "def f(x):\n    if x:\n        return \"é\"   \n\n\n    return None\n\t# tab\n"
        roundTrip(py)
        roundTrip("no trailing newline")
        roundTrip("\n\n\n")
        roundTrip("   ")
    }

    @Test fun longLineRoundTrip() { roundTrip("x".repeat(500_000) + "\n" + "y".repeat(10)) }

    @Test fun bomIsStrippedFromBufferAndRestoredOnSave() {
        val original = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo\n".toByteArray()
        val d = ok(original)
        assertTrue(d.hasBom); assertEquals("héllo\n", d.text)
        assertArrayEquals(original, TextCodec.encode(d.text, d.lineEnding, d.hasBom))
    }

    @Test fun noBomStaysNoBom() {
        val original = "abc\n".toByteArray()
        val d = ok(original)
        assertFalse(d.hasBom)
        assertArrayEquals(original, TextCodec.encode(d.text, d.lineEnding, d.hasBom))
    }

    @Test fun crlfFileRoundTripsExactly() {
        val original = "a\r\n  b\r\n\r\nc".toByteArray()
        val d = ok(original)
        assertEquals(LineEnding.CRLF, d.lineEnding); assertFalse(d.lineEndingsNormalized)
        assertEquals("a\n  b\n\nc", d.text)
        assertArrayEquals(original, TextCodec.encode(d.text, d.lineEnding, d.hasBom))
    }

    @Test fun lfFileRoundTripsExactly() {
        val original = "a\n  b\n\nc\n".toByteArray()
        val d = ok(original)
        assertEquals(LineEnding.LF, d.lineEnding); assertFalse(d.lineEndingsNormalized)
        assertArrayEquals(original, TextCodec.encode(d.text, d.lineEnding, d.hasBom))
    }

    @Test fun mixedLineEndingsAreNormalizedAndFlagged() {
        val crlfMajority = ok("a\r\nb\r\nc\r\nd\n".toByteArray())
        assertTrue(crlfMajority.lineEndingsNormalized)
        assertEquals("a\nb\nc\nd\n", crlfMajority.text)
        assertEquals(LineEnding.CRLF, crlfMajority.lineEnding)
        val lfMajority = ok("a\nb\nc\r\nd\n".toByteArray())
        assertTrue(lfMajority.lineEndingsNormalized); assertEquals(LineEnding.LF, lfMajority.lineEnding)
        assertEquals("a\nb\nc\nd\n", lfMajority.text)
        val tie = ok("a\r\nb\nc\r\nd\r".toByteArray()) // 2 CRLF vs 1 LF + 1 CR: no strict majority -> LF
        assertEquals(LineEnding.LF, tie.lineEnding); assertEquals("a\nb\nc\nd\n", tie.text)
        val lone = ok("a\rb\rc".toByteArray())
        assertTrue(lone.lineEndingsNormalized); assertEquals("a\nb\nc", lone.text)
    }

    @Test fun pastedCrlfInCrlfModeDoesNotDouble() {
        assertArrayEquals("a\r\nb".toByteArray(), TextCodec.encode("a\r\nb", LineEnding.CRLF, false))
    }

    @Test fun invalidUtf8IsRejectedNotRepaired() {
        val r = TextCodec.decode(byteArrayOf(0x61, 0xC3.toByte(), 0x28, 0x62))
        assertTrue(r is TextCodec.DecodeResult.Rejected)
        assertTrue(TextCodec.decode(byteArrayOf(0xFF.toByte(), 0x41)) is TextCodec.DecodeResult.Rejected)
    }

    @Test fun utf16AndBinaryAreRejected() {
        assertTrue(TextCodec.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61, 0x00)) is TextCodec.DecodeResult.Rejected)
        assertTrue(TextCodec.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x61)) is TextCodec.DecodeResult.Rejected)
        assertTrue(TextCodec.decode(byteArrayOf(0x61, 0x00, 0x62)) is TextCodec.DecodeResult.Rejected)
    }

    @Test fun unpairedSurrogateFailsToEncodeInsteadOfWritingQuestionMark() {
        val bad = "ab\uD83Dcd"
        try {
            TextCodec.encode(bad, LineEnding.LF, false)
            fail("expected TextEncodingException")
        } catch (e: TextEncodingException) {
            assertEquals(2, e.index)
        }
    }

    @Test fun findUnpairedSurrogate() {
        assertEquals(-1, TextCodec.findUnpairedSurrogate("a😀b"))
        assertEquals(0, TextCodec.findUnpairedSurrogate("\uDE00x"))
        assertEquals(1, TextCodec.findUnpairedSurrogate("a\uD83D"))
    }
}
