package app.plaintext.editor.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Per-document undo/redo history in DOCUMENT coordinates (not window coordinates).
 * Neighbouring keystrokes are merged so one undo step is roughly "one burst of typing". Memory is bounded by
 * [maxChars]. Entries are either a single replacement ([Edit]) or a "replace many" ([Bulk], used by Replace All
 * so that millions of matches do not become millions of entries).
 */
class UndoHistory(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxChars: Long = 4_000_000L,
    private val mergeWindowMs: Long = 1000L,
) {
    sealed interface Entry { val size: Long }

    /** At [start], [removed] was replaced by [inserted]. */
    class Edit(val start: Int, val removed: String, val inserted: String, val time: Long, val mergeable: Boolean = true) : Entry {
        override val size: Long get() = (removed.length + inserted.length).toLong()
    }

    /**
     * Every range starts[i] (ascending, in the text BEFORE the change) holding removed[i] was replaced by [replacement].
     * [removed] entries may share one String instance (case-sensitive Replace All).
     */
    class Bulk(val starts: IntArray, val removed: Array<String>, val replacement: String, val time: Long) : Entry {
        override val size: Long get() {
            var n = starts.size.toLong() * 3 + replacement.length
            var prev: String? = null
            for (r in removed) { if (r !== prev) n += r.length; prev = r }
            return n
        }

        /** Start offsets of the replacements in the text AFTER the change. */
        fun newStarts(): IntArray {
            val out = IntArray(starts.size)
            var shift = 0L
            for (i in starts.indices) {
                out[i] = (starts[i] + shift).toInt()
                shift += replacement.length - removed[i].length
            }
            return out
        }

        /** Maps an offset in the old text to the new text (offsets inside a replaced range map to its start). */
        fun mapForward(offset: Int): Int {
            var shift = 0L
            for (i in starts.indices) {
                val s = starts[i]
                if (s >= offset) break
                val end = s + removed[i].length
                if (end > offset) return (s + shift).toInt()
                shift += replacement.length - removed[i].length
            }
            return (offset + shift).toInt()
        }
    }

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    private var chars = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size

    fun clear() {
        undoStack.clear(); redoStack.clear(); chars = 0
    }

    /**
     * [mergeable] = false for deliberate actions (Tab key, indent, Replace): they are always their own undo step,
     * and typing right after them never merges into them.
     */
    fun record(start: Int, removed: String, inserted: String, mergeable: Boolean = true) {
        if (removed == inserted) return
        redoStack.clear()
        val now = clock()
        val last = undoStack.lastOrNull() as? Edit
        if (mergeable && last != null && last.mergeable && now - last.time <= mergeWindowMs) {
            val merged = tryMerge(last, Edit(start, removed, inserted, now))
            if (merged != null) {
                undoStack.removeLast()
                chars -= last.size
                if (merged.removed != merged.inserted) push(merged)
                return
            }
        }
        push(Edit(start, removed, inserted, now, mergeable))
    }

    /** Returns false (and clears the whole history) if the change is too large to keep. */
    fun recordBulk(b: Bulk): Boolean {
        redoStack.clear()
        if (b.size > maxChars) { clear(); return false }
        push(b)
        return true
    }

    private fun push(e: Entry) {
        undoStack.addLast(e)
        chars += e.size
        while (chars > maxChars && undoStack.size > 1) chars -= undoStack.removeFirst().size
    }

    private fun tryMerge(last: Edit, e: Edit): Edit? {
        if (e.inserted.contains('\n') || e.removed.contains('\n')) return null
        if (last.inserted.contains('\n') || last.removed.contains('\n')) return null
        if (last.inserted.length > MERGE_LIMIT || last.removed.length > MERGE_LIMIT) return null
        // Edit lies inside the text the previous edit inserted (typing, IME composition updates).
        if (e.start >= last.start && e.start + e.removed.length <= last.start + last.inserted.length) {
            val off = e.start - last.start
            val newInserted = last.inserted.substring(0, off) + e.inserted + last.inserted.substring(off + e.removed.length)
            return Edit(last.start, last.removed, newInserted, e.time)
        }
        // Chains of pure deletions (backspace / forward delete).
        if (e.inserted.isEmpty() && last.inserted.isEmpty()) {
            if (e.start + e.removed.length == last.start) return Edit(e.start, e.removed + last.removed, "", e.time)
            if (e.start == last.start) return Edit(last.start, last.removed + e.removed, "", e.time)
        }
        return null
    }

    /** Returns the entry to reverse (and moves it to the redo stack), or null. */
    fun popUndo(): Entry? {
        val e = undoStack.removeLastOrNull() ?: return null
        chars -= e.size
        redoStack.addLast(e)
        return e
    }

    /** Returns the entry to re-apply (and moves it back to the undo stack), or null. */
    fun popRedo(): Entry? {
        val e = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(e)
        chars += e.size
        return e
    }

    // ------------------------------------------------------------------ persistence (undo side only)

    /**
     * Serialises the newest undo entries (up to [budgetChars] characters). Redo entries are not kept.
     * The caller stores it together with the checksum of the text it applies to.
     */
    fun serialize(budgetChars: Long = PERSIST_BUDGET_CHARS): ByteArray {
        val picked = ArrayList<Entry>()
        var used = 0L
        for (i in undoStack.indices.reversed()) {
            val e = undoStack[i]
            if (used + e.size > budgetChars && picked.isNotEmpty()) break
            if (e.size > budgetChars) break
            picked.add(e); used += e.size
        }
        picked.reverse()
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(MAGIC); o.writeInt(picked.size)
            for (e in picked) {
                when (e) {
                    is Edit -> { o.writeByte(1); o.writeInt(e.start); str(o, e.removed); str(o, e.inserted) }
                    is Bulk -> {
                        o.writeByte(2); o.writeInt(e.starts.size); str(o, e.replacement)
                        for (s in e.starts) o.writeInt(s)
                        var prev: String? = null
                        for (r in e.removed) {
                            if (r === prev) { o.writeBoolean(true) } else { o.writeBoolean(false); str(o, r) }
                            prev = r
                        }
                    }
                }
            }
        }
        return bos.toByteArray()
    }

    private fun str(o: DataOutputStream, s: String) { o.writeInt(s.length); o.writeChars(s) }

    companion object {
        private const val MERGE_LIMIT = 200
        private const val MAGIC = 0x55444831 // "UDH1"
        const val PERSIST_BUDGET_CHARS = 1_000_000L

        /** Returns null if the data is corrupt or unreadable (the caller then simply starts with an empty history). */
        fun deserialize(bytes: ByteArray, clock: () -> Long = { System.currentTimeMillis() }): UndoHistory? {
            return try {
            val h = UndoHistory(clock)
            val inp = DataInputStream(ByteArrayInputStream(bytes))
            if (inp.readInt() != MAGIC) return null
            val n = inp.readInt()
            if (n < 0 || n > 100_000) return null
            val now = Long.MIN_VALUE / 4 // restored entries never merge with new typing: they are 'infinitely old'
            for (k in 0 until n) {
                when (inp.readByte().toInt()) {
                    1 -> {
                        val start = inp.readInt(); val removed = rstr(inp, bytes.size); val ins = rstr(inp, bytes.size)
                        if (start < 0) return null
                        h.push(Edit(start, removed, ins, now, mergeable = false))
                    }
                    2 -> {
                        val cnt = inp.readInt()
                        if (cnt < 0 || cnt > bytes.size) return null
                        val repl = rstr(inp, bytes.size)
                        val starts = IntArray(cnt) { inp.readInt() }
                        val removed = arrayOfNulls<String>(cnt)
                        var prev: String? = null
                        for (i in 0 until cnt) {
                            val same = inp.readBoolean()
                            val s = if (same) (prev ?: return null) else rstr(inp, bytes.size)
                            removed[i] = s; prev = s
                        }
                        @Suppress("UNCHECKED_CAST")
                        h.push(Bulk(starts, removed as Array<String>, repl, now))
                    }
                    else -> return null
                }
            }
            h
            } catch (e: Exception) {
                null
            }
        }

        private fun rstr(i: DataInputStream, limit: Int): String {
            val len = i.readInt()
            if (len < 0 || len > limit) throw java.io.IOException("bad length")
            val c = CharArray(len)
            for (k in 0 until len) c[k] = i.readChar()
            return String(c)
        }
    }
}
