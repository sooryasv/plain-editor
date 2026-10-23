package app.plaintext.editor.core

/** Tiny in-process timing log, shown in About so performance can be measured on the real device. Thread-safe. */
object PerfStats {
    private class Stat(var last: Long = 0, var max: Long = 0, var count: Long = 0, var total: Long = 0)
    private val stats = LinkedHashMap<String, Stat>()

    @Synchronized fun record(label: String, millis: Long) {
        val s = stats.getOrPut(label) { Stat() }
        s.last = millis; s.count++; s.total += millis; if (millis > s.max) s.max = millis
    }

    inline fun <T> time(label: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally { record(label, (System.nanoTime() - t0) / 1_000_000) }
    }

    @Synchronized fun summary(): String =
        if (stats.isEmpty()) "(nothing measured yet)"
        else stats.entries.joinToString("\n") { (k, s) -> "$k: last ${s.last} ms, max ${s.max} ms, avg ${s.total / s.count} ms (n=${s.count})" }

    @Synchronized fun reset() = stats.clear()
}
