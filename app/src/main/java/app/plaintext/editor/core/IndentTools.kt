package app.plaintext.editor.core

data class IndentSettings(val width: Int = 4, val useTabs: Boolean = false) {
    val unit: String get() = if (useTabs) "\t" else " ".repeat(width.coerceIn(1, 16))
}

/** Replace [start, end) by [replacement] and then select [selStart, selEnd] (all document offsets, post-edit for the selection). */
class EditPlan(val start: Int, val end: Int, val replacement: String, val selStart: Int, val selEnd: Int)

/**
 * Indent / outdent / Tab logic on LF-normalised text. Existing whitespace is never converted: indent only
 * prepends one unit to non-empty lines, outdent only removes one unit's worth of leading whitespace.
 */
object IndentTools {
    /** YAML forbids tab indentation, so spaces are forced for .yml/.yaml files. */
    fun effective(s: IndentSettings, fileName: String): IndentSettings {
        val ext = FileNaming.extensionOf(fileName)
        return if (s.useTabs && (ext == "yml" || ext == "yaml")) s.copy(useTabs = false) else s
    }

    /** Caret: insert one indent unit (spaces up to the next tab stop). Selection: indent the selected lines. */
    fun tab(text: CharSequence, selStart: Int, selEnd: Int, s: IndentSettings): EditPlan {
        if (selStart != selEnd) return indent(text, selStart, selEnd, s)
        val w = s.width.coerceIn(1, 16)
        val insert = if (s.useTabs) "\t" else {
            val col = selStart - lineStart(text, selStart)
            " ".repeat(w - (col % w))
        }
        return EditPlan(selStart, selStart, insert, selStart + insert.length, selStart + insert.length)
    }

    fun indent(text: CharSequence, selStart: Int, selEnd: Int, s: IndentSettings): EditPlan {
        val unit = s.unit
        val lines = lineRange(text, selStart, selEnd)
        val sb = StringBuilder()
        val starts = ArrayList<Int>()
        val indented = ArrayList<Boolean>()
        var pos = lines.first
        while (pos <= lines.second) {
            val e = lineEnd(text, pos).coerceAtMost(lines.second)
            starts.add(pos)
            val nonEmpty = e > pos
            indented.add(nonEmpty)
            if (nonEmpty) sb.append(unit)
            sb.append(text, pos, e)
            if (e < lines.second) sb.append('\n')
            pos = e + 1
        }
        // A selection that starts at column 0 keeps starting there (so the new indent stays inside it); a caret or a
        // selection end at column 0 moves behind the inserted indent, i.e. stays in front of the same character.
        fun map(p: Int, isSelectionStart: Boolean): Int {
            var shift = 0
            for (k in starts.indices) {
                val before = if (isSelectionStart) starts[k] < p else starts[k] <= p
                if (before && indented[k]) shift += unit.length
            }
            return p + shift
        }
        val hasSel = selEnd > selStart
        return EditPlan(lines.first, lines.second, sb.toString(), map(selStart, hasSel), map(selEnd, false))
    }

    /** @return null when no selected line has leading whitespace to remove. */
    fun outdent(text: CharSequence, selStart: Int, selEnd: Int, s: IndentSettings): EditPlan? {
        val w = s.width.coerceIn(1, 16)
        val lines = lineRange(text, selStart, selEnd)
        val sb = StringBuilder()
        val starts = ArrayList<Int>()
        val removedCounts = ArrayList<Int>()
        var any = false
        var pos = lines.first
        while (pos <= lines.second) {
            val e = lineEnd(text, pos).coerceAtMost(lines.second)
            var r = 0
            if (e > pos && text[pos] == '\t') r = 1
            else while (r < w && pos + r < e && text[pos + r] == ' ') r++
            if (r > 0) any = true
            starts.add(pos); removedCounts.add(r)
            sb.append(text, pos + r, e)
            if (e < lines.second) sb.append('\n')
            pos = e + 1
        }
        if (!any) return null
        fun map(p: Int): Int {
            var shift = 0
            for (k in starts.indices) if (starts[k] <= p) shift += minOf(removedCounts[k], p - starts[k])
            return p - shift
        }
        return EditPlan(lines.first, lines.second, sb.toString(), map(selStart), map(selEnd))
    }

    /** [first line start, end of last line (exclusive of its '\n')]. A selection ending right after a '\n' excludes that next line. */
    private fun lineRange(text: CharSequence, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val first = lineStart(text, selStart)
        val ref = if (selEnd > selStart && selEnd > 0 && text[selEnd - 1] == '\n') selEnd - 1 else selEnd
        return first to lineEnd(text, ref)
    }

    private fun lineStart(t: CharSequence, off: Int): Int {
        if (t is TextBuffer) return t.lineStart(off)
        var i = off
        while (i > 0 && t[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(t: CharSequence, off: Int): Int {
        if (t is TextBuffer) return t.lineEnd(off)
        var i = off
        while (i < t.length && t[i] != '\n') i++
        return i
    }
}
