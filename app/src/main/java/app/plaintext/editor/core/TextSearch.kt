package app.plaintext.editor.core

/** Literal (non-regex) search over a [CharSequence], normally a [TextBuffer]. Case folding is per UTF-16 unit. */
object TextSearch {
    class Options(val query: String, val caseSensitive: Boolean, val wholeWord: Boolean) {
        val valid: Boolean get() = query.isNotEmpty()
    }

    private fun fold(c: Char): Char = Character.toLowerCase(Character.toUpperCase(c))
    private fun isWord(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_'

    fun matchesAt(text: CharSequence, i: Int, o: Options): Boolean {
        val q = o.query
        val n = q.length
        if (n == 0 || i < 0 || i + n > text.length) return false
        if (o.caseSensitive) {
            for (j in 0 until n) if (text[i + j] != q[j]) return false
        } else {
            for (j in 0 until n) {
                val c = text[i + j]
                val d = q[j]
                if (c != d && fold(c) != fold(d)) return false
            }
        }
        if (o.wholeWord) {
            // Boundary checks only on sides where the query itself starts/ends with a word character.
            if (isWord(q[0]) && i > 0 && isWord(text[i - 1])) return false
            if (isWord(q[n - 1]) && i + n < text.length && isWord(text[i + n])) return false
        }
        return true
    }

    /**
     * Next match starting at or after [from] (forward) or the last match starting strictly before [from] (backward).
     * With [wrap], continues from the other end. Returns the match start, or -1.
     */
    fun find(text: CharSequence, o: Options, from: Int, forward: Boolean, wrap: Boolean): Int {
        if (!o.valid) return -1
        val len = text.length
        val n = o.query.length
        if (len < n) return -1
        val last = len - n
        if (forward) {
            var i = from.coerceIn(0, len)
            while (i <= last) { if (matchesAt(text, i, o)) return i; i++ }
            if (wrap) {
                i = 0
                val stop = minOf(from, last + 1)
                while (i < stop) { if (matchesAt(text, i, o)) return i; i++ }
            }
        } else {
            var i = minOf(from - 1, last)
            while (i >= 0) { if (matchesAt(text, i, o)) return i; i-- }
            if (wrap) {
                i = last
                val stop = maxOf(from, 0)
                while (i >= stop) { if (matchesAt(text, i, o)) return i; i-- }
            }
        }
        return -1
    }

    class Count(val total: Int, val before: Int, val capped: Boolean)

    /**
     * Counts non-overlapping matches (up to [cap]); [before] = how many start before [refOffset].
     * [cancelled] is polled periodically so a background scan can be abandoned.
     */
    fun count(text: CharSequence, o: Options, refOffset: Int, cap: Int, cancelled: () -> Boolean = { false }): Count {
        if (!o.valid) return Count(0, 0, false)
        val n = o.query.length
        val last = text.length - n
        var total = 0
        var before = 0
        var i = 0
        while (i <= last) {
            if ((i and 0xFFFF) == 0 && cancelled()) return Count(total, before, true)
            if (matchesAt(text, i, o)) {
                if (i < refOffset) before++
                total++
                if (total >= cap) return Count(total, before, true)
                i += n
            } else i++
        }
        return Count(total, before, false)
    }

    class ReplacePlan(val starts: IntArray, val removed: Array<String>, val result: CharArray, val resultLen: Int)

    /** Computes Replace All on [text] without modifying it. Returns null if there are no matches or too many. */
    fun planReplaceAll(text: CharSequence, o: Options, replacement: String, maxMatches: Int): ReplacePlan? {
        if (!o.valid) return null
        val n = o.query.length
        val last = text.length - n
        var starts = IntArray(64)
        var count = 0
        var i = 0
        while (i <= last) {
            if (matchesAt(text, i, o)) {
                if (count == maxMatches) return null
                if (count == starts.size) starts = starts.copyOf(count * 2)
                starts[count++] = i
                i += n
            } else i++
        }
        if (count == 0) return null
        val st = starts.copyOf(count)
        val removed = Array(count) { k -> if (o.caseSensitive) o.query else text.subSequence(st[k], st[k] + n).toString() }
        val newLen = text.length.toLong() + count.toLong() * (replacement.length - n)
        if (newLen > Int.MAX_VALUE - 1024) return null
        val out = CharArray(newLen.toInt() + 1024)
        var w = 0
        var prev = 0
        for (k in 0 until count) {
            for (p in prev until st[k]) out[w++] = text[p]
            for (c in replacement) out[w++] = c
            prev = st[k] + n
        }
        for (p in prev until text.length) out[w++] = text[p]
        return ReplacePlan(st, removed, out, w)
    }
}
