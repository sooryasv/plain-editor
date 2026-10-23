package app.plaintext.editor.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Line-ending convention of a file on disk. The editor itself always works with `\n`. */
enum class LineEnding(val label: String) { LF("LF"), CRLF("CRLF") }

object Limits {
    /** Largest text file the app will open (bytes): 10 MiB. */
    const val MAX_OPEN_BYTES: Long = 10L * 1024 * 1024
    const val MAX_TABS = 10
    /** Upper bound for the characters held by all open text tabs together (~60 MB of heap). */
    const val MAX_TOTAL_CHARS = 30_000_000L
    /** Documents up to this many chars are shown in one piece; larger ones are shown through a window. */
    const val FULL_DOC_CHARS = 250_000
    /** Window size (chars) for large documents, and how far it moves when you scroll to an edge. Not tuned on a device. */
    const val WINDOW_CHARS = 120_000
    const val WINDOW_STEP = 40_000
    /** Longest search for a line break when aligning a window edge (a longer line is cut mid-line). */
    const val ALIGN_SCAN = 20_000
    const val MAX_REPLACE_MATCHES = 2_000_000
}

class TextEncodingException(val index: Int, message: String) : Exception(message)

/**
 * Converts between file bytes and editor text. Rules (also documented in README):
 *  - UTF-8 only. Malformed UTF-8, UTF-16 files and files containing NUL bytes are rejected, never "repaired".
 *  - A leading UTF-8 BOM is removed from the editing buffer and written back on save, so round-trips are exact.
 *  - Files whose line endings are all LF or all CRLF round-trip exactly. Mixed / lone-CR files are normalised
 *    to their dominant convention and the caller is told (lineEndingsNormalized).
 */
object TextCodec {
    private const val CHUNK = 16 * 1024

    class Decoded(
        val text: String,
        val lineEnding: LineEnding,
        val hasBom: Boolean,
        val lineEndingsNormalized: Boolean,
    )

    sealed interface DecodeResult {
        class Ok(val doc: Decoded) : DecodeResult
        class Rejected(val message: String) : DecodeResult
    }

    class DecodedBuffer(
        val buffer: TextBuffer,
        val lineEnding: LineEnding,
        val hasBom: Boolean,
        val lineEndingsNormalized: Boolean,
    )

    sealed interface BufferResult {
        class Ok(val doc: DecodedBuffer) : BufferResult
        class Rejected(val message: String) : BufferResult
    }

    /** String-based convenience wrapper (tests, small inputs). */
    fun decode(bytes: ByteArray): DecodeResult = when (val r = decodeToBuffer(bytes, bytes.size)) {
        is BufferResult.Rejected -> DecodeResult.Rejected(r.message)
        is BufferResult.Ok -> DecodeResult.Ok(
            Decoded(r.doc.buffer.toString(), r.doc.lineEnding, r.doc.hasBom, r.doc.lineEndingsNormalized)
        )
    }

    /** Strict decode of the first [size] bytes straight into a [TextBuffer] (no intermediate String). */
    fun decodeToBuffer(bytes: ByteArray, size: Int): BufferResult {
        if (size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE)) {
                return BufferResult.Rejected("This file looks like UTF-16. Only UTF-8 text files are supported.")
            }
        }
        var offset = 0
        var bom = false
        if (size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            offset = 3
            bom = true
        }
        for (i in offset until size) {
            if (bytes[i].toInt() == 0) {
                return BufferResult.Rejected("This file contains NUL bytes, so it is probably binary, not text.")
            }
        }
        val n = size - offset
        val out = TextBuffer.withSlack(n)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val cb = CharBuffer.wrap(out)
        val res = decoder.decode(ByteBuffer.wrap(bytes, offset, n), cb, true)
        if (res.isError || res.isOverflow || decoder.flush(cb).isError) {
            return BufferResult.Rejected("This file is not valid UTF-8, so opening it could corrupt it. Nothing was changed.")
        }
        return BufferResult.Ok(normalizeInPlace(out, cb.position(), bom))
    }

    /** Counts line endings, then converts CRLF / lone CR to LF in place when needed. */
    private fun normalizeInPlace(chars: CharArray, len: Int, bom: Boolean): DecodedBuffer {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < len) {
            val c = chars[i]
            if (c == '\r') {
                if (i + 1 < len && chars[i + 1] == '\n') { crlf++; i += 2; continue }
                cr++
            } else if (c == '\n') {
                lf++
            }
            i++
        }
        if (cr == 0 && crlf == 0) return DecodedBuffer(TextBuffer.wrap(chars, len), LineEnding.LF, bom, false)
        val ending: LineEnding
        val normalized: Boolean
        if (cr == 0 && lf == 0) { ending = LineEnding.CRLF; normalized = false }
        else { ending = if (crlf > lf + cr) LineEnding.CRLF else LineEnding.LF; normalized = true }
        var w = 0
        var r = 0
        while (r < len) {
            val c = chars[r]
            if (c == '\r') {
                if (r + 1 < len && chars[r + 1] == '\n') { r++; continue } // drop the CR; the LF is copied next
                chars[w++] = '\n'
            } else {
                chars[w++] = c
            }
            r++
        }
        return DecodedBuffer(TextBuffer.wrap(chars, w), ending, bom, normalized)
    }

    /** Strict encoder returning all bytes: throws [TextEncodingException] instead of writing replacement characters. */
    fun encode(text: CharSequence, lineEnding: LineEnding, withBom: Boolean): ByteArray {
        val out = ByteArrayOutputStream(text.length + 16)
        encodeTo(text, lineEnding, withBom, out, null)
        return out.toByteArray()
    }

    /**
     * Streaming strict encoder: writes UTF-8 to [out] in 16K-char chunks (never holds a second full copy), feeding
     * [digest] with the same bytes. In CRLF mode every "\n" becomes "\r\n" and an existing "\r\n" is not doubled.
     * @return number of bytes written.
     */
    fun encodeTo(text: CharSequence, lineEnding: LineEnding, withBom: Boolean, out: OutputStream, digest: MessageDigest?): Long {
        val bad = findUnpairedSurrogate(text)
        if (bad >= 0) {
            throw TextEncodingException(bad, "The text contains an invalid character (unpaired surrogate) at position $bad and cannot be written as UTF-8.")
        }
        val crlf = lineEnding == LineEnding.CRLF
        val enc = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val chars = CharArray(CHUNK + 2)
        val bytes = ByteArray((CHUNK + 2) * 3 + 8)
        var total = 0L
        fun emit(n: Int) {
            out.write(bytes, 0, n); digest?.update(bytes, 0, n); total += n
        }
        if (withBom) {
            bytes[0] = 0xEF.toByte(); bytes[1] = 0xBB.toByte(); bytes[2] = 0xBF.toByte(); emit(3)
        }
        val len = text.length
        var i = 0
        while (i < len) {
            var n = 0
            while (i < len && n < CHUNK) {
                val c = text[i]
                if (crlf) {
                    if (c == '\r' && i + 1 < len && text[i + 1] == '\n') { i++; continue }
                    if (c == '\n') { chars[n++] = '\r' }
                }
                chars[n++] = c
                i++
            }
            if (n > 0 && Character.isHighSurrogate(chars[n - 1]) && i < len) { n--; i-- } // keep pairs together
            val bb = ByteBuffer.wrap(bytes)
            val res = enc.encode(CharBuffer.wrap(chars, 0, n), bb, i >= len)
            if (res.isError || res.isOverflow) throw TextEncodingException(-1, "The text could not be encoded as UTF-8.")
            emit(bb.position())
        }
        return total
    }

    fun findUnpairedSurrogate(s: CharSequence): Int {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { i += 2; continue }
                return i
            }
            if (Character.isLowSurrogate(c)) return i
            i++
        }
        return -1
    }
}
