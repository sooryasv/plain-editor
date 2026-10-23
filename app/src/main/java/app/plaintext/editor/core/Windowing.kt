package app.plaintext.editor.core

/** A window [start, end) of a document that the EditText currently shows. Edges are line-aligned where possible. */
data class Win(val start: Int, val end: Int) {
    val length: Int get() = end - start
    fun contains(a: Int, b: Int) = a >= start && b <= end
}

/**
 * Decides which slice of a (possibly 10 MiB) document the EditText holds. Small documents are one window.
 * All functions are pure so window shifting can be tested without Android.
 */
object Windowing {
    fun isWindowed(len: Int) = len > Limits.FULL_DOC_CHARS

    fun full(buf: TextBuffer) = Win(0, buf.length)

    fun top(buf: TextBuffer): Win =
        if (!isWindowed(buf.length)) full(buf) else Win(0, alignEnd(buf, Limits.WINDOW_CHARS))

    fun bottom(buf: TextBuffer): Win {
        val len = buf.length
        return if (!isWindowed(len)) full(buf) else Win(alignStart(buf, len - Limits.WINDOW_CHARS), len)
    }

    /** Window containing [offset], roughly centred. */
    fun around(buf: TextBuffer, offset: Int): Win {
        val len = buf.length
        if (!isWindowed(len)) return full(buf)
        val half = Limits.WINDOW_CHARS / 2
        var s = (offset - half).coerceIn(0, len - Limits.WINDOW_CHARS)
        s = alignStart(buf, s)
        var e = alignEnd(buf, minOf(len, s + Limits.WINDOW_CHARS))
        if (offset > e) { e = alignEnd(buf, minOf(len, offset + half)); s = alignStart(buf, maxOf(0, e - Limits.WINDOW_CHARS)) }
        return Win(s, e)
    }

    /** Window moved by one step toward the end ([dir] > 0) or the start; null if already at that edge. */
    fun shifted(buf: TextBuffer, cur: Win, dir: Int): Win? {
        val len = buf.length
        if (!isWindowed(len)) return null
        return if (dir > 0) {
            if (cur.end >= len) null
            else {
                val e = alignEnd(buf, minOf(len, cur.end + Limits.WINDOW_STEP))
                Win(alignStart(buf, maxOf(cur.start + 1, e - Limits.WINDOW_CHARS)), e)
            }
        } else {
            if (cur.start <= 0) null
            else {
                val s = alignStart(buf, maxOf(0, cur.start - Limits.WINDOW_STEP))
                Win(s, alignEnd(buf, minOf(len, s + Limits.WINDOW_CHARS)))
            }
        }
    }

    /** Moves [raw] back to the start of its line (searching at most ALIGN_SCAN chars; else keeps it). */
    fun alignStart(buf: TextBuffer, raw: Int): Int {
        val r = raw.coerceIn(0, buf.length)
        if (r == 0) return 0
        val lim = maxOf(0, r - Limits.ALIGN_SCAN)
        var i = r
        while (i > lim) { if (buf[i - 1] == '\n') return i; i-- }
        return if (lim == 0) 0 else r
    }

    /** Moves [raw] forward to just after the next '\n' (at most ALIGN_SCAN chars; else keeps it). */
    fun alignEnd(buf: TextBuffer, raw: Int): Int {
        val len = buf.length
        val r = raw.coerceIn(0, len)
        if (r >= len) return len
        val lim = minOf(len, r + Limits.ALIGN_SCAN)
        var i = maxOf(r, 1)
        if (r > 0 && buf[r - 1] == '\n') return r
        while (i < lim) { if (buf[i - 1] == '\n') return i; i++ }
        return if (lim >= len) len else r
    }
}
