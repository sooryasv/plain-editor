package app.plaintext.editor.core

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Gap buffer holding a whole document (the source of truth for save, search, undo and drafts).
 * Edits near the previous edit are O(edit size); a far-away edit moves the gap once (a memmove).
 * The EditText only ever shows a window of this buffer (see [Windowing]).
 * NOT thread-safe: mutate on one thread; hand background threads a [copyChars] snapshot.
 */
class TextBuffer private constructor(private var a: CharArray, private var gs: Int, private var ge: Int) : CharSequence {

    constructor() : this(CharArray(64), 0, 64)

    override val length: Int get() = a.size - (ge - gs)

    override fun get(index: Int): Char {
        if (index < 0) throw IndexOutOfBoundsException("index $index")
        val i = if (index < gs) index else index + (ge - gs)
        if (i >= a.size) throw IndexOutOfBoundsException("index $index, length $length")
        return a[i]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = substring(startIndex, endIndex)

    fun substring(start: Int, end: Int): String {
        require(start in 0..end && end <= length) { "range $start..$end of $length" }
        val n = end - start
        if (n == 0) return ""
        val out = CharArray(n)
        getChars(start, end, out, 0)
        return String(out)
    }

    fun getChars(start: Int, end: Int, dst: CharArray, dstOff: Int) {
        require(start in 0..end && end <= length) { "range $start..$end of $length" }
        var d = dstOff
        var s = start
        if (s < gs) {
            val n = minOf(end, gs) - s
            System.arraycopy(a, s, dst, d, n)
            d += n; s += n
        }
        if (s < end) {
            System.arraycopy(a, s + (ge - gs), dst, d, end - s)
        }
    }

    /** Full snapshot, safe to hand to a background thread. */
    fun copyChars(): CharArray {
        val n = length
        val out = CharArray(n)
        getChars(0, n, out, 0)
        return out
    }

    override fun toString(): String = String(copyChars())

    fun replace(start: Int, end: Int, text: CharSequence) {
        require(start in 0..end && end <= length) { "range $start..$end of $length" }
        moveGap(start)
        ge += end - start
        val n = text.length
        if (n > 0) {
            ensureGap(n)
            if (text is String) text.toCharArray(a, gs, 0, n) else for (i in 0 until n) a[gs + i] = text[i]
            gs += n
        }
    }

    /** Replaces many non-overlapping, ascending ranges in one O(n) pass. [lens][i] chars at [starts][i] become repl(i). */
    fun replaceMany(starts: IntArray, lens: IntArray, count: Int, repl: (Int) -> CharSequence) {
        val oldLen = length
        var newLen = oldLen.toLong()
        for (i in 0 until count) newLen += repl(i).length - lens[i]
        require(newLen in 0..Int.MAX_VALUE.toLong() - SLACK) { "result too large" }
        val na = CharArray(newLen.toInt() + slackFor(newLen.toInt()))
        var w = 0
        var prev = 0
        for (i in 0 until count) {
            val s = starts[i]
            require(s >= prev && s + lens[i] <= oldLen) { "ranges must be ascending and in bounds" }
            getChars(prev, s, na, w); w += s - prev
            val r = repl(i)
            for (k in 0 until r.length) na[w + k] = r[k]
            w += r.length
            prev = s + lens[i]
        }
        getChars(prev, oldLen, na, w); w += oldLen - prev
        a = na; gs = w; ge = na.size
    }

    /** Replace the entire content, taking ownership of [chars] (first [len] are the text). */
    fun assign(chars: CharArray, len: Int) {
        a = chars; gs = len; ge = chars.size
    }

    fun indexOf(ch: Char, from: Int): Int {
        var i = maxOf(from, 0)
        val off = ge - gs
        while (i < gs) { if (a[i] == ch) return i; i++ }
        var j = i + off
        while (j < a.size) { if (a[j] == ch) return j - off; j++ }
        return -1
    }

    /** Last index <= [from] holding [ch], or -1. */
    fun lastIndexOf(ch: Char, from: Int): Int {
        val len = length
        var i = minOf(from, len - 1)
        val off = ge - gs
        while (i >= gs) { if (a[i + off] == ch) return i; i-- }
        while (i >= 0) { if (a[i] == ch) return i; i-- }
        return -1
    }

    /** Offset of the first char of the line containing [offset]. */
    fun lineStart(offset: Int): Int = if (offset <= 0) 0 else lastIndexOf('\n', offset - 1) + 1

    /** Offset of the '\n' ending the line containing [offset] (or length if the last line has none). */
    fun lineEnd(offset: Int): Int = indexOf('\n', offset).let { if (it < 0) length else it }

    fun crc32(): Long = Crc.ofBuffer(this)

    fun contentEquals(other: CharSequence): Boolean {
        if (other.length != length) return false
        for (i in 0 until length) if (this[i] != other[i]) return false
        return true
    }

    private fun moveGap(pos: Int) {
        if (pos == gs) return
        if (pos < gs) {
            val n = gs - pos
            System.arraycopy(a, pos, a, ge - n, n)
            gs = pos; ge -= n
        } else {
            val n = pos - gs
            System.arraycopy(a, ge, a, gs, n)
            gs += n; ge += n
        }
    }

    private fun ensureGap(n: Int) {
        if (ge - gs >= n) return
        val len = length
        require(len.toLong() + n < Int.MAX_VALUE - SLACK) { "document too large" }
        val newCap = len + n + slackFor(len)
        val na = CharArray(newCap)
        System.arraycopy(a, 0, na, 0, gs)
        val tail = a.size - ge
        System.arraycopy(a, ge, na, newCap - tail, tail)
        ge = newCap - tail
        a = na
    }

    companion object {
        private const val SLACK = 1_000_000
        private fun slackFor(len: Int) = maxOf(1024, minOf(len / 4, SLACK))

        fun fromString(s: String): TextBuffer {
            val arr = CharArray(s.length + slackFor(s.length))
            s.toCharArray(arr, 0, 0, s.length)
            return TextBuffer(arr, s.length, arr.size)
        }

        /** Takes ownership of [chars]; the first [len] chars are the text, the rest becomes spare gap. */
        fun wrap(chars: CharArray, len: Int): TextBuffer = TextBuffer(chars, len, chars.size)

        fun withSlack(len: Int): CharArray = CharArray(len + slackFor(len))
    }
}

object Crc {
    /** CRC32 over the UTF-16BE bytes of the text. Same value for a String, a CharArray prefix and a TextBuffer. */
    fun ofChars(chars: CharArray, len: Int): Long {
        val crc = CRC32()
        val bytes = ByteArray(8192 * 2)
        var i = 0
        while (i < len) {
            val n = minOf(8192, len - i)
            val bb = ByteBuffer.wrap(bytes, 0, n * 2)
            bb.asCharBuffer().put(chars, i, n)
            crc.update(bytes, 0, n * 2)
            i += n
        }
        return crc.value
    }

    fun ofString(s: String): Long = ofChars(s.toCharArray(), s.length)

    fun ofBuffer(b: TextBuffer): Long {
        val crc = CRC32()
        val chunk = CharArray(8192)
        val bytes = ByteArray(8192 * 2)
        var i = 0
        val len = b.length
        while (i < len) {
            val n = minOf(8192, len - i)
            b.getChars(i, i + n, chunk, 0)
            val bb = ByteBuffer.wrap(bytes, 0, n * 2)
            bb.asCharBuffer().put(chunk, 0, n)
            crc.update(bytes, 0, n * 2)
            i += n
        }
        return crc.value
    }
}
